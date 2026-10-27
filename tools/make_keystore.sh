#!/usr/bin/env bash
set -euo pipefail

OUT="${1:-release.jks}"
ALIAS="${2:-flipperbridge}"
read -r -s -p "Choose a keystore password (min 6 characters): " PASS
echo
keytool -genkeypair -v -keystore "$OUT" -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 36500 \
  -storepass "$PASS" -keypass "$PASS" -dname "CN=Flipper Bridge"

echo
echo "Add these four repository secrets (Settings > Secrets and variables > Actions):"
echo "ANDROID_KEYSTORE_PASSWORD = the password you typed"
echo "ANDROID_KEY_PASSWORD      = the same password"
echo "ANDROID_KEY_ALIAS         = $ALIAS"
echo "ANDROID_KEYSTORE_BASE64   = the content of $OUT.b64 (written now)"
base64 -w0 "$OUT" > "$OUT.b64" 2>/dev/null || base64 "$OUT" | tr -d '\n' > "$OUT.b64"
echo
echo "Keep $OUT somewhere safe and never commit it. Without the same key, phones cannot update the app."
