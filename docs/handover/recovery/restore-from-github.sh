#!/usr/bin/env bash
# restore-from-github.sh — Recovery channel for project NeoKey (CTO-DRS/neokey-hark).
# Rebuilt 2026-11-08/09 after repeated sandbox wipes. Ships inside the handover package.
#
# Procedure (documented, fail-closed — nothing is announced without real output):
#   1. Read token from /tmp/gh.token (0600, never echoed, all logs masked)
#   2. Verify it grants access to the private repo CTO-DRS/neokey-hark
#   3. Fresh clone into /home/z/my-project/wk (or $NEOKEY_WORKDIR)
#   4. Verify anchor commit 23dbc0a (W41 / vc82 / v3.5 / Phase 8 CLOSED) is in history
#   5. Inventory what the repo provides vs what is missing (fetch_tools.sh, pipeline, keystore, secrets)
#   6. Run scripts/fetch_tools.sh (7 sha256-pinned tools) and ci/pipeline.sh when present.
#      The ONLY green light = PIPELINE_OK printed by the repo's own pipeline.
set -uo pipefail

REPO_URL="https://github.com/CTO-DRS/neokey-hark.git"
ANCHOR="23dbc0a"
WORKDIR="${NEOKEY_WORKDIR:-/home/z/my-project/wk}"
TOKEN_FILE="/tmp/gh.token"
LOG="${NEOKEY_RESTORE_LOG:-/home/z/my-project/scripts/restore.log}"

mask() { sed -E 's/gh[pousr]_[A-Za-z0-9_]+/gh****MASKED****/g; s#https://[^@/]+@#https://#g'; }
step() { echo "[$(date -u +%H:%M:%S)] $*"; }
mkdir -p "$(dirname "$LOG")"
exec > >(tee -a "$LOG" | mask) 2>&1

step "=== restore-from-github: start ==="

# --- 1. token ---
if [[ ! -s "$TOKEN_FILE" ]]; then
  step "FAIL-1: $TOKEN_FILE missing or empty. A fresh GitHub token is required."
  exit 1
fi
perms=$(stat -c '%a' "$TOKEN_FILE")
bytes=$(wc -c < "$TOKEN_FILE")
step "token file present: mode=$perms bytes=$bytes (content not shown)"
if [[ "$perms" != "600" ]]; then
  chmod 600 "$TOKEN_FILE" && step "tightened token file to 0600"
fi
TOKEN="$(cat "$TOKEN_FILE")"
if [[ ! "$TOKEN" =~ ^gh[pousr]_[A-Za-z0-9_]{20,}$ ]]; then
  step "FAIL-1b: token format unexpected — refusing to use it."
  exit 1
fi

# --- 2. remote access (token only inside URL; every log line passes through mask) ---
if ! git ls-remote "https://x-access-token:${TOKEN}@github.com/CTO-DRS/neokey-hark.git" HEAD >/dev/null 2>&1; then
  step "FAIL-2: token does not grant access to $REPO_URL (or repo unreachable)."
  exit 1
fi
step "OK-2: token grants access to private repo"

# --- 3. fresh clone ---
rm -rf "$WORKDIR"
if ! git clone --quiet "https://x-access-token:${TOKEN}@github.com/CTO-DRS/neokey-hark.git" "$WORKDIR" 2>/dev/null; then
  step "FAIL-3: clone failed"
  exit 1
fi
git -C "$WORKDIR" remote set-url origin "$REPO_URL"   # never keep the token in clone config
HEAD_SHA=$(git -C "$WORKDIR" rev-parse HEAD)
step "OK-3: cloned; HEAD=$HEAD_SHA; last commits:"
git -C "$WORKDIR" log --oneline -5

# --- 4. anchor verification ---
if git -C "$WORKDIR" merge-base --is-ancestor "$ANCHOR" HEAD 2>/dev/null; then
  step "OK-4: anchor $ANCHOR (W41 vc82 v3.5 Phase8-CLOSED) is ancestor of HEAD"
else
  step "FAIL-4: anchor $ANCHOR NOT found in history — stopping, do not trust this clone"
  exit 1
fi
step "version identity at HEAD (HISTORY.md tail):"
tail -n 12 "$WORKDIR/HISTORY.md" 2>/dev/null || step "(no HISTORY.md at repo root)"

# --- 5. inventory ---
step "--- inventory ---"
for f in ci/pipeline.sh scripts/fetch_tools.sh docs/PHASE8-W4-REPORT.md docs/PHASE9-PLAN.md; do
  if [[ -e "$WORKDIR/$f" ]]; then step "present: $f"; else step "MISSING: $f"; fi
done
[[ -e "$WORKDIR/neokey.keystore" ]] && step "present: neokey.keystore" || step "MISSING: neokey.keystore (signing stages need it — see handover package secrets/)"
[[ -d "$WORKDIR/.secrets" ]] && step "present: .secrets/" || step "MISSING: .secrets/ (gates may need it — see handover package secrets/)"
step "scripts/ in repo: $(ls "$WORKDIR/scripts" 2>/dev/null | tr '\n' ' ')"

# --- 6. tools + pipeline ---
if [[ -f "$WORKDIR/scripts/fetch_tools.sh" ]]; then
  step "running scripts/fetch_tools.sh (7 sha256-pinned tools)..."
  if (cd "$WORKDIR" && bash scripts/fetch_tools.sh); then
    step "OK-6a: tool pins verified"
  else
    step "FAIL-6a: fetch_tools.sh failed (pins mismatch or download failure)"
    exit 1
  fi
else
  step "SKIP-6a: scripts/fetch_tools.sh not in repo — tools must be restored separately"
fi

if [[ -f "$WORKDIR/ci/pipeline.sh" ]]; then
  step "running ci/pipeline.sh (full pipeline)..."
  if (cd "$WORKDIR" && bash ci/pipeline.sh); then
    step "OK-6b: PIPELINE_OK"
    step "=== RESTORE COMPLETE: anchor verified, pipeline green. W-state may now be assessed. ==="
  else
    step "FAIL-6b: pipeline did not reach PIPELINE_OK — see pipeline output above"
    exit 1
  fi
else
  step "SKIP-6b: ci/pipeline.sh not in repo — cannot run pipeline"
  step "=== RESTORE PARTIAL: clone + anchor verified; pipeline not runnable ==="
fi
