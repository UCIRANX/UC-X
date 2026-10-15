#!/usr/bin/env python3
"""
UC Tor bridge collector (runs hourly in GitHub Actions).

Sources:
  * Tor Project Moat API (official built-in bridges: snowflake, meek, obfs4, webtunnel)
  * Community GitHub collectors (OnionHop, Delta-Kronecker, scriptzteam v1/v2, igareck)
  * Public Telegram channels (@tor_bridges, @vanilla_bridges, @rutorbridgesobfs4webtunnel)

Output (in --out): bridges.json + one .txt per transport.
NOTE: reachability is tested from GitHub's servers (not from Iran). It only removes dead
bridges; the app still has to try them from the user's own network.
"""
import argparse
import asyncio
import html
import json
import re
import ssl
import sys
import time
import urllib.request
from datetime import datetime, timezone
from urllib.parse import urlparse

UA = "Mozilla/5.0 (X11; Linux x86_64) UCTorCollector/1.0"
MOAT = "https://bridges.torproject.org/moat/circumvention/builtin"

GH = "https://raw.githubusercontent.com"
TEXT_SOURCES = [
    ("onionhop", f"{GH}/center2055/OnionHop-Bridges-Collector/main/bridge/webtunnel_tested.txt"),
    ("onionhop", f"{GH}/center2055/OnionHop-Bridges-Collector/main/bridge/obfs4_tested.txt"),
    ("onionhop", f"{GH}/center2055/OnionHop-Bridges-Collector/main/bridge/vanilla_tested.txt"),
    ("onionhop", f"{GH}/center2055/OnionHop-Bridges-Collector/main/bridge/obfs4_72h.txt"),
    ("onionhop", f"{GH}/center2055/OnionHop-Bridges-Collector/main/bridge/webtunnel_72h.txt"),
    ("delta-kronecker", f"{GH}/Delta-Kronecker/Tor-Bridges-Collector/main/bridge/webtunnel_tested.txt"),
    ("delta-kronecker", f"{GH}/Delta-Kronecker/Tor-Bridges-Collector/main/bridge/obfs4_tested.txt"),
    ("delta-kronecker", f"{GH}/Delta-Kronecker/Tor-Bridges-Collector/main/bridge/vanilla_tested.txt"),
    ("delta-kronecker", f"{GH}/Delta-Kronecker/Tor-Bridges-Collector/main/bridges/webtunnel_tested.txt"),
    ("delta-kronecker", f"{GH}/Delta-Kronecker/Tor-Bridges-Collector/main/bridges/obfs4_tested.txt"),
    ("scriptzteam-v2", f"{GH}/scriptzteam/Tor-Bridges-Collector-v2/main/bridges/webtunnel_tested.txt"),
    ("scriptzteam-v2", f"{GH}/scriptzteam/Tor-Bridges-Collector-v2/main/bridges/obfs4_tested.txt"),
    ("scriptzteam-v2", f"{GH}/scriptzteam/Tor-Bridges-Collector-v2/main/bridges/vanilla_tested.txt"),
    ("scriptzteam", f"{GH}/scriptzteam/Tor-Bridges-Collector/main/bridges-webtunnel"),
    ("scriptzteam", f"{GH}/scriptzteam/Tor-Bridges-Collector/main/bridges-obfs4"),
    ("scriptzteam", f"{GH}/scriptzteam/Tor-Bridges-Collector/main/bridges-vanilla"),
    ("igareck", f"{GH}/igareck/vpn-configs-for-russia/main/TOR-BRIDGES/TOR_BRIDGES_WEBTUNNEL.txt"),
    ("igareck", f"{GH}/igareck/vpn-configs-for-russia/main/TOR-BRIDGES/TOR_BRIDGES_OBFS4.txt"),
    ("igareck", f"{GH}/igareck/vpn-configs-for-russia/main/TOR-BRIDGES/TOR_BRIDGES_TOP100.txt"),
]
TELEGRAM_CHANNELS = ["tor_bridges", "vanilla_bridges", "rutorbridgesobfs4webtunnel"]
TELEGRAM_PAGES = 3

LIMITS = {"webtunnel": 150, "obfs4": 300, "vanilla": 150}

FP = r"[0-9A-Fa-f]{40}"
ADDR = r"(?:\[[0-9A-Fa-f:]+\]|\d{1,3}(?:\.\d{1,3}){3}):\d{1,5}"
EXTRA = r"((?:[ \t]+[A-Za-z0-9_\-]+=[^\s<>\"']+)*)"
RE_OBFS4 = re.compile(rf"obfs4\s+({ADDR})\s+({FP})\s+cert=([A-Za-z0-9+/]+=*)\s+iat-mode=([0-2])")
RE_WT = re.compile(rf"webtunnel\s+({ADDR})\s+({FP})\s+url=(https?://[^\s<>\"']+?)(?=\s|ver=|webtunnel|obfs4|$){EXTRA}")
RE_VAN = re.compile(rf"({ADDR})\s+({FP})")
STOP_WORDS = ("webtunnel", "obfs4", "snowflake", "meek")


def log(*a):
    print(*a, file=sys.stderr, flush=True)


def fetch(url, data=None, headers=None, timeout=30):
    h = {"User-Agent": UA}
    h.update(headers or {})
    req = urllib.request.Request(url, data=data, headers=h, method="POST" if data is not None else "GET")
    for i in range(3):
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return r.read().decode("utf-8", "ignore")
        except Exception as e:  # noqa: BLE001
            if i == 2:
                log(f"  ! {url}: {e}")
            time.sleep(1.5 * (i + 1))
    return None


def html_to_text(page):
    page = re.sub(r"(?i)<br\s*/?>", "\n", page)
    page = re.sub(r"(?i)</(div|p|pre|code|li)>", "\n", page)
    page = re.sub(r"<[^>]+>", "", page)
    return html.unescape(page)


def clean_value(v):
    for w in STOP_WORDS:  # telegram sometimes glues the next line on
        idx = v.find(w)
        if idx > 0:
            v = v[:idx]
    return v


def parse(text):
    """Returns list of (transport, key, line)."""
    # Telegram pages sometimes glue entries together ("iat-mode=0obfs4 ...", "ver=0.0.3webtunnel ...")
    text = re.sub(r"(?<=[0-9A-Za-z=])(obfs4|webtunnel|snowflake|meek_lite)(?=[ \t])", r"\n\1", text)
    out = []
    for m in RE_OBFS4.finditer(text):
        addr, fp, cert, iat = m.groups()
        out.append(("obfs4", fp.upper(), f"obfs4 {addr} {fp.upper()} cert={cert} iat-mode={iat}"))
    for m in RE_WT.finditer(text):
        addr, fp, url, extra = m.groups()
        parts = []
        for tok in (extra or "").split():
            k, _, v = tok.partition("=")
            v = clean_value(v)
            if k == "ver":
                mv = re.match(r"\d+(?:\.\d+)*", v)
                if not mv:
                    continue
                v = mv.group(0)
            if v:
                parts.append(f"{k}={v}")
        line = f"webtunnel {addr} {fp.upper()} url={clean_value(url)}" + ("" if not parts else " " + " ".join(parts))
        out.append(("webtunnel", fp.upper(), line))
    stripped = RE_WT.sub(" ", RE_OBFS4.sub(" ", text))
    stripped = re.sub(r"(snowflake|meek_lite|meek)\s+\S+", " ", stripped)
    for m in RE_VAN.finditer(stripped):
        addr, fp = m.groups()
        out.append(("vanilla", fp.upper(), f"{addr} {fp.upper()}"))
    return out


def moat_builtin():
    raw = fetch(MOAT, data=b"{}", headers={"Content-Type": "application/vnd.api+json"})
    if not raw:
        raw = fetch(MOAT)
    if not raw:
        return {}
    try:
        data = json.loads(raw)
    except Exception:  # noqa: BLE001
        return {}
    res = {}
    for k, v in data.items():
        if not isinstance(v, list):
            continue
        key = {"meek-azure": "meek_lite", "meek": "meek_lite"}.get(k, k)
        for line in v:
            line = re.sub(r"\s+", " ", str(line).strip())
            if line.startswith("meek-azure ") or line.startswith("meek "):
                line = "meek_lite " + line.split(" ", 1)[1]
            res.setdefault(key, [])
            if line not in res[key]:
                res[key].append(line)
    return res


def telegram(channel):
    texts = []
    before = None
    for _ in range(TELEGRAM_PAGES):
        url = f"https://t.me/s/{channel}" + (f"?before={before}" if before else "")
        page = fetch(url)
        if not page:
            break
        texts.append(html_to_text(page))
        ids = [int(x) for x in re.findall(r'data-post="[^"/]+/(\d+)"', page)]
        if not ids:
            break
        nb = min(ids)
        if before is not None and nb >= before:
            break
        before = nb
    return "\n".join(texts)


def host_port(transport, line):
    if transport == "webtunnel":
        u = re.search(r"url=(\S+)", line).group(1)
        p = urlparse(u)
        return p.hostname, p.port or (443 if p.scheme == "https" else 80), p.scheme == "https"
    addr = line.split()[1] if transport == "obfs4" else line.split()[0]
    host, _, port = addr.rpartition(":")
    return host.strip("[]"), int(port), False


async def probe(transport, line, sem, timeout=7.0):
    try:
        host, port, tls = host_port(transport, line)
    except Exception:  # noqa: BLE001
        return None
    async with sem:
        t0 = time.monotonic()
        try:
            ctx = None
            if tls:
                ctx = ssl.create_default_context()
                ctx.check_hostname = False
                ctx.verify_mode = ssl.CERT_NONE
            fut = asyncio.open_connection(host, port, ssl=ctx, server_hostname=host if tls else None)
            _, w = await asyncio.wait_for(fut, timeout=timeout)
            w.close()
            try:
                await asyncio.wait_for(w.wait_closed(), 2)
            except Exception:  # noqa: BLE001
                pass
            return (time.monotonic() - t0) * 1000
        except Exception:  # noqa: BLE001
            return None


async def test_all(items):
    sem = asyncio.Semaphore(250)
    return await asyncio.gather(*(probe(t, l, sem) for t, l in items))


def is_ipv6(transport, line):
    if transport == "webtunnel":
        return False  # webtunnel connects through its URL (domain), the IP is a placeholder
    addr = line.split()[1] if transport == "obfs4" else line.split()[0]
    return addr.startswith("[")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="out")
    ap.add_argument("--no-test", action="store_true")
    args = ap.parse_args()

    found = {}          # (transport, key) -> {"line":..., "sources": set()}
    source_counts = {}

    def add(src, items):
        n = 0
        for t, k, line in items:
            rec = found.setdefault((t, k), {"line": line, "sources": set()})
            rec["sources"].add(src)
            n += 1
        source_counts[src] = source_counts.get(src, 0) + n

    log("* Moat (official)")
    moat = moat_builtin()
    for t in ("obfs4", "webtunnel"):
        add("moat", parse("\n".join(moat.get(t, []))))
    for src, url in TEXT_SOURCES:
        log(f"* {src}: {url}")
        body = fetch(url)
        if body:
            add(src, parse(body))
    for ch in TELEGRAM_CHANNELS:
        log(f"* telegram @{ch}")
        add(f"telegram:{ch}", parse(telegram(ch)))

    per_type = {"obfs4": [], "webtunnel": [], "vanilla": []}
    for (t, _), rec in found.items():
        per_type[t].append((rec["line"], len(rec["sources"])))
    log("* unique:", {k: len(v) for k, v in per_type.items()})

    result = {}
    for t, entries in per_type.items():
        v4 = [(l, s) for l, s in entries if not is_ipv6(t, l)]
        v6 = [(l, s) for l, s in entries if is_ipv6(t, l)]
        if args.no_test:
            alive = [(l, s, 0.0) for l, s in v4]
        else:
            lat = asyncio.run(test_all([(t, l) for l, _ in v4]))
            alive = [(l, s, ms) for (l, s), ms in zip(v4, lat) if ms is not None]
        log(f"  {t}: {len(alive)}/{len(v4)} reachable (ipv4/domain), {len(v6)} ipv6 untested")

        def port_rank(line):
            try:
                return 0 if host_port(t, line)[1] in (443, 80) else 1
            except Exception:  # noqa: BLE001
                return 1

        alive.sort(key=lambda x: (port_rank(x[0]), -min(x[1], 3), x[2]))
        lines = [l for l, _, _ in alive][: LIMITS[t]]
        lines += [l for l, _ in sorted(v6, key=lambda x: -x[1])][: max(0, LIMITS[t] // 5)]
        result[t] = lines

    result["snowflake"] = moat.get("snowflake", [])
    result["meek_lite"] = moat.get("meek_lite", [])
    total = sum(len(v) for v in result.values())
    if total == 0:
        log("No bridges collected at all, refusing to publish an empty list.")
        sys.exit(1)

    import os
    os.makedirs(args.out, exist_ok=True)
    now = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    doc = {
        "updated": now,
        "generator": "UC Tor bridge collector",
        "counts": {k: len(v) for k, v in result.items()},
        "sources": source_counts,
        "bridges": result,
    }
    with open(os.path.join(args.out, "bridges.json"), "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, indent=1)
    for t, lines in result.items():
        with open(os.path.join(args.out, f"{t}.txt"), "w", encoding="utf-8") as f:
            f.write("\n".join(lines) + ("\n" if lines else ""))
    with open(os.path.join(args.out, "README.md"), "w", encoding="utf-8") as f:
        f.write(f"# UC Tor bridges\n\nUpdated: {now}\n\n")
        f.write("| transport | count |\n|---|---|\n")
        for k, v in result.items():
            f.write(f"| {k} | {len(v)} |\n")
        f.write("\nGenerated automatically. Do not edit this branch by hand.\n")
    log("* done:", doc["counts"])


if __name__ == "__main__":
    main()
