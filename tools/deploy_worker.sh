#!/usr/bin/env bash
# استقرار ربات لایسنس: ساخت KV (فقط یک‌بار)، تنظیم Secrets، deploy و ثبت وب‌هوک تلگرام
set -euo pipefail

: "${CLOUDFLARE_API_TOKEN:?Secret به نام CF_API_TOKEN تنظیم نشده است}"
: "${CLOUDFLARE_ACCOUNT_ID:?Secret به نام CF_ACCOUNT_ID تنظیم نشده است}"
: "${BOT_TOKEN:?Secret به نام BOT_TOKEN تنظیم نشده است}"
export CLOUDFLARE_API_TOKEN CLOUDFLARE_ACCOUNT_ID

WORKER_NAME="uc-license-bot"
KV_TITLE="uc-license-state"
API="https://api.cloudflare.com/client/v4/accounts/${CLOUDFLARE_ACCOUNT_ID}"
H=(-H "Authorization: Bearer ${CLOUDFLARE_API_TOKEN}" -H "Content-Type: application/json")

KV_ID="$(curl -fsS "${H[@]}" "${API}/storage/kv/namespaces?per_page=100" \
  | jq -r --arg t "$KV_TITLE" '.result[] | select(.title == $t) | .id' | head -n 1)"
if [ -z "$KV_ID" ]; then
  KV_ID="$(curl -fsS -X POST "${H[@]}" "${API}/storage/kv/namespaces" \
    --data "{\"title\":\"${KV_TITLE}\"}" | jq -r '.result.id')"
fi
if [ -z "$KV_ID" ] || [ "$KV_ID" = "null" ]; then
  echo "::error::ساخت KV ناموفق بود. دسترسی توکن Cloudflare را بررسی کنید."
  exit 1
fi

cat > worker/wrangler.toml <<TOML
name = "${WORKER_NAME}"
main = "worker.js"
compatibility_date = "2025-01-01"

[vars]
CHANNEL_USERNAME = "UCIRANIR"

[[kv_namespaces]]
binding = "STATE"
id = "${KV_ID}"
TOML

cd worker
WR=(npx --yes wrangler@3)
"${WR[@]}" deploy --config wrangler.toml

printf '%s' "$BOT_TOKEN" | "${WR[@]}" secret put BOT_TOKEN --config wrangler.toml
if [ -n "${ADMIN_ID:-}" ]; then
  printf '%s' "$ADMIN_ID" | "${WR[@]}" secret put ADMIN_ID --config wrangler.toml
fi
WEBHOOK_SECRET="$(openssl rand -hex 32)"
printf '%s' "$WEBHOOK_SECRET" | "${WR[@]}" secret put WEBHOOK_SECRET --config wrangler.toml
base64 -w0 ../signing/license_priv.pkcs8.der | "${WR[@]}" secret put SIGNING_PRIVATE_KEY_PKCS8 --config wrangler.toml
cd ..

SUB="$(curl -fsS "${H[@]}" "${API}/workers/subdomain" | jq -r '.result.subdomain')"
URL="https://${WORKER_NAME}.${SUB}.workers.dev"
echo "Worker URL: ${URL}"

curl -fsS "https://api.telegram.org/bot${BOT_TOKEN}/setWebhook" \
  --data-urlencode "url=${URL}" \
  --data-urlencode "secret_token=${WEBHOOK_SECRET}" \
  --data-urlencode 'allowed_updates=["message"]' | jq .
echo "Webhook registered."
