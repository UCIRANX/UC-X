import os
import sys
import time
import struct
import base64
import sqlite3
from datetime import datetime, timedelta
from cryptography.hazmat.primitives.asymmetric import ed25519

# کلید خصوصی اختصاصی پروژه شما (تولید شده توسط GitHub Actions)
PRIVATE_KEY_HEX = "b7327a4155ce10277bc0ff3888daa2e97bafae97ba381b56836c4986716343b9"
VALIDITY_DAYS = 5

def generate_5day_code(device_id_input: str, days: int = VALIDITY_DAYS) -> str:
    clean_id = device_id_input.strip().upper().replace("-", "").replace(" ", "")
    if len(clean_id) != 8 or not clean_id.isalnum():
        raise ValueError("شناسه دستگاه باید دقیقاً ۸ کاراکتر انگلیسی/عدد باشد (مثال: ABCD-1234)")

    expiry_epoch = int(time.time()) + (days * 86400)
    payload = clean_id.encode("ascii") + struct.pack(">Q", expiry_epoch)

    priv_key = ed25519.Ed25519PrivateKey.from_private_bytes(bytes.fromhex(PRIVATE_KEY_HEX))
    signature = priv_key.sign(payload)

    token_b64 = base64.urlsafe_b64encode(payload + signature).decode("ascii").rstrip("=")
    return f"MV1-{token_b64}"

if __name__ == "__main__":
    # حالت اول: تولید کد مستقیم از خط فرمان
    if len(sys.argv) >= 2 and sys.argv[1] == "--gen":
        dev_id = sys.argv[2]
        print(generate_5day_code(dev_id))
        sys.exit(0)

    # حالت دوم: اجرای ربات تلگرام
    import telebot

    BOT_TOKEN = os.getenv("BOT_TOKEN", "PUT_YOUR_TELEGRAM_BOT_TOKEN_HERE")
    if BOT_TOKEN == "8714396305:AAHJvEZdylAJw2xfqY97Xzab-1vCPioW3L8":
        print("لطفاً توکن ربات تلگرام خود را در متغیر محیطی BOT_TOKEN یا داخل فایل bot.py قرار دهید.")
        sys.exit(1)

    bot = telebot.TeleBot(BOT_TOKEN)

    # دیتابیس سبک برای ثبت زمان آخرین دریافت کد هر کاربر تلگرام
    conn = sqlite3.connect("bot_licenses.db", check_same_thread=False)
    conn.execute("CREATE TABLE IF NOT EXISTS issued_codes (user_id INTEGER, device_id TEXT, issued_at INTEGER)")
    conn.commit()

    @bot.message_handler(commands=["start", "help"])
    def send_welcome(message):
        bot.reply_to(
            message,
            "👋 سلام! برای دریافت کد فعال‌سازی ۵ روزه فیلترشکن، لطفاً **شناسه دستگاه ۸ حرفی** که داخل برنامه به شما نمایش داده می‌شود (مثلاً `ABCD-1234`) را همین‌جا ارسال کنید:",
            parse_mode="Markdown"
        )

    @bot.message_handler(func=lambda m: True)
    def handle_device_id(message):
        text = message.text.strip()
        try:
            code = generate_5day_code(text)
            exp_date = (datetime.now() + timedelta(days=VALIDITY_DAYS)).strftime("%Y-%m-%d %H:%M")
            conn.execute(
                "INSERT INTO issued_codes VALUES (?, ?, ?)",
                (message.from_user.id, text.upper(), int(time.time()))
            )
            conn.commit()
            bot.reply_to(
                message,
                f"✅ **کد ۵ روزه اختصاصی گوشی شما ساخته شد:**\n\n`{code}`\n\n⏳ اعتبار تا: `{exp_date}`\n📌 روی کد بالا بزنید تا کپی شود و آن را داخل برنامه وارد کنید.",
                parse_mode="Markdown"
            )
        except ValueError as e:
            bot.reply_to(message, f"❌ خطا: {e}")

    print("Telegram License Bot is running...")
    bot.infinity_polling()
    
