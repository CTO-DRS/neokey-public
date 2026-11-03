#!/bin/bash
# secrets audit — blocks any hardcoded signing secret outside .secrets/
# Roadmap Phase 0 (Iron Foundation) — runs locally and in CI.
# The old compromised password is built by concatenation so this file never
# contains the literal string it scans for.
set -u
cd "$(dirname "$0")/.."
OLDPW='neokey''123'                                   # NK-SEC-001 compromised password
PATTERNS="(${OLDPW}|pass:[A-Za-z0-9+/_-]{6,}|-storepass[ :][A-Za-z0-9+/_-]{6,}|-keypass[ :][A-Za-z0-9+/_-]{6,})"
HITS=$(rg -n --hidden -g '!.secrets/' -g '!build/' -g '!tools/' -g '!.git/' -g '!tests/audit_secrets.sh' -e "$PATTERNS" . 2>/dev/null)
if [ -n "$HITS" ]; then
  echo "SECRETS_AUDIT_FAILED — hardcoded secret patterns found:"
  echo "$HITS"
  exit 1
fi
# keystore FILES (real binaries) outside the vault — text mentions of vault paths
# in build/release scripts are legitimate, so this is a find, not a text scan
KEYS=$(find . \( -path './.secrets' -o -path './build' -o -path './.git' \) -prune \
  -o \( -name '*.keystore' -o -name '*.jks' \) -type f -print 2>/dev/null || true)
if [ -n "$KEYS" ]; then
  echo "SECRETS_AUDIT_FAILED — keystore files outside .secrets/:"; echo "$KEYS"; exit 1
fi
echo "SECRETS_AUDIT_OK"
