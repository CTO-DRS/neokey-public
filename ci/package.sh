#!/bin/bash
# Packaging gate — builds the shareable deliverables without ever embedding the
# live signing secrets. Lesson learned from Week 2: the app zip embedded the
# .secrets/ vault (real keystore + real password next to each other) — one file
# share away from a repeat of NK-SEC-001. Now:
#   neokey-app.zip        — clean, shareable (no .secrets, no build/, no tool jars)
#   neokey-secrets.zip    — the vault alone, chmod 600, for the owner's encrypted backup
# Exit 1 if the clean zip fails the post-pack scan.
set -euo pipefail
cd "$(dirname "$0")/.."

APP_ZIP="${1:-build/neokey-app.zip}"
SEC_ZIP="${2:-build/neokey-secrets.zip}"

# ---- clean, shareable app zip -------------------------------------------------
rm -f "$APP_ZIP" "$SEC_ZIP"
zip -qr "$APP_ZIP" . \
  -x '.secrets/*' 'build/*' 'tools/*.jar' '.git/*' '*.apk' '*.aab' '*.env' 'play/promo/tmp/*' \
  'dictsrc/corpus/news_*' 'dictsrc/corpus/train_pairs.tsv' 'dictsrc/corpus/dev_pairs.tsv' 'dictsrc/corpus/w10-ckpt-*.npz' "dictsrc/corpus/news.2020.ar.gz"

# ---- post-pack scan: the two real secret files must not be inside -------------
if unzip -l "$APP_ZIP" | grep -Eq 'neokey\.keystore|\.secrets/neokey\.env$'; then
  echo "PACKAGE_FAILED — secrets leaked into the app zip:"; unzip -l "$APP_ZIP" | grep -E 'keystore|neokey\.env'; exit 1
fi

# ---- owner-only vault zip (keep OFF untrusted machines; store encrypted) ------
if [ -f .secrets/neokey.keystore ] && [ -f .secrets/neokey.env ]; then
  SEC_ABS=$(mkdir -p "$(dirname "$SEC_ZIP")" && readlink -f "$SEC_ZIP")
  rm -rf /tmp/nk-vault && mkdir -p /tmp/nk-vault/.secrets
  cp .secrets/neokey.keystore .secrets/neokey.env /tmp/nk-vault/.secrets/
  (cd /tmp/nk-vault && zip -qr /tmp/nk-vault.zip .secrets)
  mv /tmp/nk-vault.zip "$SEC_ABS" && chmod 600 "$SEC_ABS"
  rm -rf /tmp/nk-vault
fi

echo "PACKAGE_OK"
echo "  app zip:     $APP_ZIP ($(du -h "$APP_ZIP" | cut -f1), secrets-free — verified by scan)"
[ -f "$SEC_ZIP" ] && echo "  secrets zip: $SEC_ZIP (chmod 600 — encrypted off-machine backup only)"
exit 0
