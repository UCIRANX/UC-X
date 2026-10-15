#!/usr/bin/env bash
# کلیدهای امضا را فقط یک‌بار می‌سازد و به‌صورت رمزگذاری‌شده در شاخه signing-keys نگه می‌دارد.
# در اجراهای بعدی همان کلیدها بازیابی می‌شوند تا APKها روی نسخه قبلی نصب شوند.
set -euo pipefail

: "${SIGNING_PASSWORD:?Secret به نام SIGNING_PASSWORD تنظیم نشده است}"
: "${GH_TOKEN:?GH_TOKEN لازم است}"

BR="signing-keys"
REPO_URL="https://x-access-token:${GH_TOKEN}@github.com/${GITHUB_REPOSITORY}.git"
OUT="$PWD/signing"
mkdir -p "$OUT"
work="$(mktemp -d)"

if git ls-remote --exit-code --heads "$REPO_URL" "$BR" >/dev/null 2>&1; then
  echo "Restoring existing signing keys..."
  git clone -q --depth 1 --branch "$BR" "$REPO_URL" "$work/repo"
  openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 \
    -in "$work/repo/keys.tar.enc" -out "$work/keys.tar" -pass env:SIGNING_PASSWORD \
    || { echo "::error::SIGNING_PASSWORD با رمز قبلی یکی نیست. رمز اصلی را در Secrets قرار دهید."; exit 1; }
  tar -xf "$work/keys.tar" -C "$OUT"
else
  echo "Creating NEW signing keys (this happens only once)..."
  ANDROID_PASS="$(openssl rand -hex 24)"
  keytool -genkeypair -noprompt -keystore "$OUT/release.jks" -storetype PKCS12 \
    -alias ucx -keyalg RSA -keysize 4096 -validity 36500 \
    -storepass "$ANDROID_PASS" -keypass "$ANDROID_PASS" -dname "CN=UC-X, O=UCIRAN, C=IR"
  printf '%s\n' "$ANDROID_PASS" > "$OUT/android_pass.txt"

  openssl ecparam -name prime256v1 -genkey -noout -out "$work/license_priv.pem"
  openssl pkcs8 -topk8 -nocrypt -in "$work/license_priv.pem" -outform DER -out "$OUT/license_priv.pkcs8.der"
  openssl ec -in "$work/license_priv.pem" -pubout -outform DER -out "$OUT/license_pub.der"

  tar -cf "$work/keys.tar" -C "$OUT" .
  openssl enc -aes-256-cbc -pbkdf2 -iter 200000 -salt \
    -in "$work/keys.tar" -out "$work/keys.tar.enc" -pass env:SIGNING_PASSWORD

  mkdir -p "$work/push"
  cp "$work/keys.tar.enc" "$work/push/"
  cat > "$work/push/README.md" <<'TXT'
# کلیدهای امضای UC-X
این شاخه کلیدها را به‌صورت رمزگذاری‌شده نگه می‌دارد. هرگز حذفش نکنید.
اگر SIGNING_PASSWORD گم شود، کلیدها قابل بازیابی نیستند و کاربران باید دوباره نصب کنند.
TXT
  git -C "$work/push" init -q
  git -C "$work/push" checkout -q -b "$BR"
  git -C "$work/push" add -A
  git -C "$work/push" -c user.name=ucx-bot -c user.email=ucx-bot@users.noreply.github.com \
    commit -q -m "Create signing keys [skip ci]"
  git -C "$work/push" push -q "$REPO_URL" "$BR"
  echo "Keys created and stored on branch $BR."
fi

mkdir -p keystore
cp "$OUT/release.jks" keystore/release.jks

ANDROID_PASS_VAL="$(cat "$OUT/android_pass.txt")"
PUB_B64="$(base64 -w0 "$OUT/license_pub.der")"

if [ -n "${GITHUB_ENV:-}" ]; then
  echo "::add-mask::$ANDROID_PASS_VAL"
  echo "::add-mask::$PUB_B64"
  {
    echo "ANDROID_KEYSTORE_PASSWORD=$ANDROID_PASS_VAL"
    echo "ANDROID_KEY_ALIAS=ucx"
    echo "ANDROID_KEY_PASSWORD=$ANDROID_PASS_VAL"
    echo "LICENSE_PUBLIC_KEY_B64=$PUB_B64"
  } >> "$GITHUB_ENV"
fi
echo "Signing keys are ready."
