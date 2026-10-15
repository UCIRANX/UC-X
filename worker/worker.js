// ربات صدور کد ۵ روزه UC-X
// مراحل: عضویت در کانال ← ارسال شناسه دستگاه ← فوروارد دو پست جدید از کانال ← دریافت کد امضاشده

const TOKEN_PREFIX = "MV3-";
const LICENSE_SECONDS = 5 * 86400;   // مدت اعتبار هر کد
const REQUIRED_POSTS = 2;            // تعداد پست‌های لازم برای هر کد جدید
const PENDING_TTL_SEC = 1800;        // مهلت ارسال پست‌ها (۳۰ دقیقه)
const DEVICE_ID_RE = /^[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{8}$/;

// کلید امضا فقط یک بار import می‌شود
let cachedSigningKey = null;

export default {
  async fetch(request, env) {
    if (request.method !== "POST") {
      return new Response("UC-X license bot is active", { status: 200 });
    }
    // فقط درخواست‌های واقعی تلگرام پذیرفته می‌شوند
    if (request.headers.get("X-Telegram-Bot-Api-Secret-Token") !== env.WEBHOOK_SECRET) {
      return new Response("forbidden", { status: 403 });
    }

    let update;
    try {
      update = await request.json();
    } catch {
      return new Response("bad request", { status: 400 });
    }

    try {
      if (update.message) await handleMessage(update.message, env);
    } catch (e) {
      console.log("handler error:", e && e.stack ? e.stack : String(e));
    }
    // همیشه 200 برمی‌گردانیم تا تلگرام پیام را دوباره نفرستد
    return new Response("OK", { status: 200 });
  },
};

function channelName(env) {
  return env.CHANNEL_USERNAME || "UCIRANIR";
}

function helpText(channel) {
  return (
    "👋 سلام!\n\n" +
    `۱. ابتدا در کانال @${channel} عضو شوید.\n` +
    "۲. شناسه ۸ حرفی دستگاه را از بخش «شناسه گوشی» برنامه کپی کرده و بفرستید.\n" +
    `۳. ${REQUIRED_POSTS} پست جدید از کانال را برای این ربات فوروارد کنید.\n\n` +
    "کد دریافتی را در برنامه، بخش «کد لایسنس» وارد کنید."
  );
}

async function handleMessage(msg, env) {
  if (!msg.from) return;
  const chatId = msg.chat.id;
  const userId = msg.from.id;
  const channel = channelName(env);
  const text = (msg.text || "").trim();

  // پست فورواردی از کانال (قبل از بقیهٔ بررسی‌ها)
  const postId = channelPostId(msg, channel);
  if (postId !== null) return handleForwardedPost(msg, env, channel, postId);

  if (msg.forward_origin || msg.forward_from_chat) {
    return send(env, chatId, `این پیام از کانال @${channel} نیست. لطفاً پست‌های همان کانال را فوروارد کنید.`);
  }

  if (text === "/start" || text === "/help") return send(env, chatId, helpText(channel));

  if (text === "/cancel") {
    await env.STATE.delete(`pend:${userId}`);
    return send(env, chatId, "درخواست فعلی لغو شد.");
  }

  if (text.startsWith("/unbind")) return handleUnbind(msg, env, text);

  if (text.startsWith("/")) return send(env, chatId, helpText(channel));

  return handleDeviceId(msg, env, channel, text);
}

// مرحلهٔ اول: دریافت شناسه دستگاه
async function handleDeviceId(msg, env, channel, text) {
  const chatId = msg.chat.id;
  const userId = String(msg.from.id);
  const deviceId = normalizeDeviceId(text);

  if (!deviceId) {
    return send(env, chatId, "شناسه معتبر نیست. شناسه ۸ حرفی را از بخش «شناسه گوشی» برنامه کپی و بفرستید.");
  }

  if (!(await isMember(env, msg.from.id, channel))) {
    return send(env, chatId, joinText(channel));
  }

  const dev = await getJSON(env.STATE, `dev:${deviceId}`);
  if (dev && dev.tg && dev.tg !== userId) {
    return send(env, chatId, "این شناسه قبلاً به حساب تلگرام دیگری وصل شده است. برای تغییر با پشتیبانی تماس بگیرید.");
  }

  // کد فعلی هنوز معتبر است: همان کد دوباره فرستاده می‌شود
  const now = Math.floor(Date.now() / 1000);
  if (dev && dev.tg && dev.expiry > now) {
    return send(env, chatId,
      `کد فعلی شما هنوز معتبر است تا <b>${fmtDate(dev.expiry)}</b>:\n<code>${dev.token}</code>`);
  }

  // کد منقضی شده: برای کد جدید باید پست‌های جدیدتر از آخرین صدور فوروارد شوند
  await env.STATE.put(
    `pend:${userId}`,
    JSON.stringify({ deviceId, posts: [], minPost: dev ? dev.lastPost || 0 : 0 }),
    { expirationTtl: PENDING_TTL_SEC }
  );

  return send(env, chatId,
    `شناسه <code>${formatDeviceId(deviceId)}</code> دریافت شد.\n` +
    `برای دریافت کد جدید، ${REQUIRED_POSTS} پست <b>جدید</b> از کانال @${channel} را برای همین ربات فوروارد کنید.`);
}

// مرحلهٔ دوم: فوروارد پست‌های کانال
async function handleForwardedPost(msg, env, channel, postId) {
  const chatId = msg.chat.id;
  const userId = msg.from.id;

  if (!(await isMember(env, userId, channel))) {
    return send(env, chatId, joinText(channel));
  }

  const key = `pend:${userId}`;
  const pend = await getJSON(env.STATE, key);
  if (!pend) {
    return send(env, chatId, "ابتدا شناسه دستگاه خود را بفرستید.");
  }

  if (pend.posts.includes(postId)) {
    return send(env, chatId, "این پست قبلاً ثبت شده. پست دیگری از کانال فوروارد کنید.");
  }

  if (postId <= pend.minPost) {
    return send(env, chatId, "این پست برای دریافت کد جدید معتبر نیست. لطفاً پست‌های جدیدتر کانال را فوروارد کنید.");
  }

  pend.posts.push(postId);

  if (pend.posts.length < REQUIRED_POSTS) {
    await env.STATE.put(key, JSON.stringify(pend), { expirationTtl: PENDING_TTL_SEC });
    const left = REQUIRED_POSTS - pend.posts.length;
    return send(env, chatId, `✅ ثبت شد. ${left} پست دیگر فوروارد کنید.`);
  }

  // همهٔ شرایط کامل است: صدور کد جدید
  const { token, expiry } = await issueToken(env, pend.deviceId);
  await env.STATE.put(
    `dev:${pend.deviceId}`,
    JSON.stringify({
      tg: String(userId),
      token,
      expiry,
      lastPost: Math.max(...pend.posts),
    })
  );
  await env.STATE.delete(key);

  return send(env, chatId,
    `✅ کد ۵ روزه شما:\n<code>${token}</code>\n` +
    `اعتبار تا: <b>${fmtDate(expiry)}</b>\n` +
    "این کد را در برنامه، بخش «کد لایسنس» وارد کنید.");
}

// دستور ادمین برای آزاد کردن یک شناسه
async function handleUnbind(msg, env, text) {
  const chatId = msg.chat.id;
  if (!env.ADMIN_ID || String(msg.from.id) !== String(env.ADMIN_ID)) {
    return send(env, chatId, "دسترسی ندارید.");
  }
  const deviceId = normalizeDeviceId(text.split(/\s+/)[1] || "");
  if (!deviceId) return send(env, chatId, "فرمت: /unbind XXXXXXXX");

  const dev = await getJSON(env.STATE, `dev:${deviceId}`);
  if (!dev) return send(env, chatId, "این شناسه ثبت نشده است.");

  dev.tg = null;
  dev.token = null;
  dev.expiry = 0;
  await env.STATE.put(`dev:${deviceId}`, JSON.stringify(dev));
  return send(env, chatId, `اتصال ${formatDeviceId(deviceId)} آزاد شد.`);
}

// ---------- کمک‌کننده‌ها ----------

function normalizeDeviceId(raw) {
  const id = raw.toUpperCase().replace(/[^A-Z0-9]/g, "");
  return DEVICE_ID_RE.test(id) ? id : null;
}

function formatDeviceId(id) {
  return `${id.slice(0, 4)}-${id.slice(4)}`;
}

// اگر پیام فورواردی از کانال باشد، شناسهٔ پست را برمی‌گرداند
function channelPostId(msg, channel) {
  const want = channel.toLowerCase();

  const origin = msg.forward_origin;
  if (origin && origin.type === "channel" &&
      String((origin.chat && origin.chat.username) || "").toLowerCase() === want) {
    return origin.message_id;
  }

  // سازگاری با فیلدهای قدیمی‌تر API تلگرام
  const fromChat = msg.forward_from_chat;
  if (fromChat && String(fromChat.username || "").toLowerCase() === want && msg.forward_from_message_id) {
    return msg.forward_from_message_id;
  }
  return null;
}

async function isMember(env, userId, channel) {
  const data = await tg(env, "getChatMember", { chat_id: `@${channel}`, user_id: userId });
  if (!data.ok) {
    // معمولاً یعنی ربات در کانال ادمین نیست
    console.log("getChatMember failed:", JSON.stringify(data));
    return false;
  }
  const s = data.result.status;
  return s === "creator" || s === "administrator" || s === "member" ||
         (s === "restricted" && data.result.is_member === true);
}

async function issueToken(env, deviceId) {
  const expiry = Math.floor(Date.now() / 1000) + LICENSE_SECONDS;

  // پِی‌لود ۱۶ بایتی: ۸ بایت شناسه (ASCII) + ۸ بایت زمان انقضا (Big-Endian)
  const payload = new Uint8Array(16);
  payload.set(new TextEncoder().encode(deviceId), 0);
  new DataView(payload.buffer).setBigInt64(8, BigInt(expiry), false);

  const key = await importSigningKey(env);
  // WebCrypto برای ECDSA امضای خام r||s به طول ۶۴ بایت برمی‌گرداند
  const sig = new Uint8Array(
    await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, key, payload)
  );

  const out = new Uint8Array(80);
  out.set(payload, 0);
  out.set(sig, 16);

  return { token: TOKEN_PREFIX + toBase64Url(out), expiry };
}

async function importSigningKey(env) {
  if (!cachedSigningKey) {
    const der = Uint8Array.from(atob(env.SIGNING_PRIVATE_KEY_PKCS8), (c) => c.charCodeAt(0));
    cachedSigningKey = await crypto.subtle.importKey(
      "pkcs8",
      der,
      { name: "ECDSA", namedCurve: "P-256" },
      false,
      ["sign"]
    );
  }
  return cachedSigningKey;
}

function toBase64Url(bytes) {
  let bin = "";
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function fmtDate(sec) {
  return new Date(sec * 1000).toLocaleString("fa-IR", { timeZone: "Asia/Tehran" });
}

function joinText(channel) {
  return `برای استفاده ابتدا در کانال عضو شوید:\nhttps://t.me/${channel}\nسپس دوباره شناسه دستگاه را بفرستید.`;
}

async function getJSON(kv, key) {
  const v = await kv.get(key);
  return v ? JSON.parse(v) : null;
}

async function tg(env, method, body) {
  const r = await fetch(`https://api.telegram.org/bot${env.BOT_TOKEN}/${method}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  return r.json().catch(() => ({ ok: false }));
}

function send(env, chatId, text) {
  return tg(env, "sendMessage", {
    chat_id: chatId,
    text,
    parse_mode: "HTML",
    disable_web_page_preview: true,
  });
}
