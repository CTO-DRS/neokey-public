#!/bin/bash
# Release manifest + staged-rollout plan generator (roadmap Phase 0 item B:
# "الإصدار التدريجي (10% ← 50% ← 100%) يصبح إجراءً افتراضياً لا استثناءً").
# Reads the gates state written by ci/pipeline.sh (build/release/gates.env) and
# emits build/release/{release-manifest.json, rollout-plan.json, rollout-plan.txt}.
set -euo pipefail
cd "$(dirname "$0")/.."

OUT=build/release; mkdir -p "$OUT"
VC=$(grep -o 'versionCode="[0-9]*"' AndroidManifest.xml | grep -o '[0-9]*')
VN=$(grep -o 'versionName="[^"]*"' AndroidManifest.xml | cut -d'"' -f2)
NOW=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
FP="(keystore fingerprint unavailable on this machine)"
[ -f .secrets/KEY_FINGERPRINT.txt ] && FP=$(grep -oE '([0-9A-F]{2}:){31}[0-9A-F]{2}' .secrets/KEY_FINGERPRINT.txt | head -1)

# ---- gates state (defaults keep the script usable standalone) -----------------
AUDIT=SKIPPED; TESTS_TOTAL=0; TESTS_FAILED=0; PERF="{}"
if [ -f "$OUT/gates.env" ]; then . "$OUT/gates.env"; fi

# fold PERF_* pairs from gates.env into a JSON object (|| guards the empty-grep case)
PERF_JSON=$(grep -E '^PERF_' "$OUT/gates.env" 2>/dev/null \
  | sed 's/^PERF_//' | awk -F= '{printf "%s\"%s\":%s", sep, $1, $2; sep=","}') || PERF_JSON=""
PERF_OBJ="{${PERF_JSON:-}}"

ARTIFACT_APK="pending (built on the release machine / release CI)"
ARTIFACT_AAB="pending (built on the release machine / release CI)"
[ -f build/NeoKey-2036.apk ] && ARTIFACT_APK="build/NeoKey-2036.apk"
[ -f build/NeoKey.aab ] && ARTIFACT_AAB="build/NeoKey.aab"

# R8 Full Mode (item C) — present when the shrink gate ran
R8_BLOCK=""
if [ -n "${R8_DEX_BYTES:-}" ]; then
  R8_BLOCK=$',\n    "r8FullMode": { "dexBytes": '"$R8_DEX_BYTES"', "manifestComponentsKept": "'"${R8_COMPONENTS_KEPT:-0/8}"'", "mapping": "build/release/mapping.txt (upload to Play Console as deobfuscation file)" }'
fi

# Store-listing gate (item D) — present when the play gate ran
PLAY_BLOCK=""
if [ "${PLAY_GATE:-}" = "OK" ]; then
  PLAY_BLOCK=$',\n    "storeListing": { "verifiedBy": "ci/play_gate.sh", "name": "'"ar ${PLAY_name_ar:-0}/30, en ${PLAY_name_en:-0}/30"'", "shortDescription": "'"ar ${PLAY_short_ar:-0}/80, en ${PLAY_short_en:-0}/80"'", "fullDescription": "'"ar ${PLAY_full_ar:-0}/4000, en ${PLAY_full_en:-0}/4000"'" }'
fi

# Neural-inference gate (phase 1, item A1) — present when the nnet gate ran
NNET_BLOCK=""
if [ "${NNET_GATE:-}" = "OK" ]; then
  NNET_BLOCK=$',\n    "nnet": { "verifiedBy": "ci/nnet_gate.sh", "params": '"${NNET_PARAMS:-0}"', "modelBytes": '"${NNET_MODEL_BYTES:-0}"', "p50Ms": '"${NNET_P50_MS:-0}"', "p95Ms": '"${NNET_P95_MS:-0}"', "bitsPerParam": '"${NNET_BPP:-0}"', "format": "NQF v1 int8-symmetric-per-tensor" }'
fi

# Platform-neutrality gate (phase 2, item C, week 4) — present when the port gate ran
NQFPORT_BLOCK=""
if [ "${NQFPORT_GATE:-}" = "OK" ]; then
  NQFPORT_BLOCK=$',\n    "nqfPort": { "verifiedBy": "ci/nqfport_gate.sh", "contract": "docs/NQF-PORT.md", "goldenVectors": "docs/NQF-GOLDEN.json", "swiftSpec": "docs/NQF-SWIFT.md", "assetsPinned": '"${NQFPORT_ASSETS:-0}"', "tensorsAgreeBitExact": '"${NQFPORT_TENSORS:-0}"', "q8Params": '"${NQFPORT_PARAMS:-0}"', "negativesRefused": '"${NQFPORT_NEGATIVES:-0}"', "p50Ms": '"${NQFPORT_P50_MS:-0}"' }'
fi

# Community-enrichment gate (phase 2, item D, week 5) — present when the gate ran
COMMUNITY_BLOCK=""
if [ "${COMMUNITY_GATE:-}" = "OK" ]; then
  COMMUNITY_BLOCK=$',\n    "community": { "verifiedBy": "ci/community_gate.sh", "contract": "docs/COMMUNITY-DICT.md", "batchesReviewed": '"${COMMUNITY_BATCHES:-0}"', "rowsAccepted": '"${COMMUNITY_ACCEPTED:-0}"', "rowsRejected": '"${COMMUNITY_REJECTED:-0}"', "negativesRefused": '"${COMMUNITY_NEGATIVES:-0}"', "p50CheckUs": '"${COMMUNITY_P50_US:-0}"', "thanksPage": "CONTRIBUTORS.md (generated into build/release/)" }'
fi

# In-app store gate (phase 3, week 1) — present when the store gate ran
STOREUI_BLOCK=""
if [ "${STOREUI_GATE:-}" = "OK" ]; then
  STOREUI_BLOCK=$',\n    "store": { "verifiedBy": "ci/store_ui_gate.sh", "contract": "docs/PHASE3-W1-REPORT.md", "catalogPacks": 25, "catalogFounders": 10, "catalogCents": '"${STOREUI_CATALOG_CENTS:-0}"', "ledgerClosesCents": '"${STOREUI_LEDGER_CENTS:-0}"', "negativesRefused": "'"${STOREUI_NEGATIVES:-0}"'", "installP50Us": '"${STOREUI_P50_US:-0}"', "wallet": "local cents ledger — real payment provider is a deployment decision" }'
fi


# Signed community-pack gate (phase 3, week 2) — present when the gate ran
SIGNEDICT_BLOCK=""
if [ "${SIGNEDICT_GATE:-}" = "OK" ]; then
  SIGNEDICT_BLOCK=$',\n    "signedCommunityPack": { "verifiedBy": "ci/signeddict_gate.sh", "contract": "docs/SIGNED-DICT.md", "packWords": '"${SIGNEDICT_PACK_WORDS:-0}"', "mergeAdd": '"${SIGNEDICT_MERGE_ADD:-0}"', "mergeSkip": '"${SIGNEDICT_MERGE_SKIP:-0}"', "negativesRefused": "'"${SIGNEDICT_NEGATIVES:-0}"'", "verifyP50Us": '"${SIGNEDICT_P50_US:-0}"', "consumedBy": "Dictionary.load after the installed corpus" }'
fi

cat > "$OUT/release-manifest.json" <<EOF
{
  "app": "com.hark.neokey",
  "versionCode": $VC,
  "versionName": "$VN",
  "generatedAt": "$NOW",
  "keystoreSha256": "$FP",
  "gates": {
    "secretsAudit": "$AUDIT",
    "tests": { "total": $TESTS_TOTAL, "failed": $TESTS_FAILED },
    "performance": $PERF_OBJ$R8_BLOCK$PLAY_BLOCK$NNET_BLOCK$NQFPORT_BLOCK$COMMUNITY_BLOCK$STOREUI_BLOCK$SIGNEDICT_BLOCK
  },
  "artifacts": { "apk": "$ARTIFACT_APK", "aab": "$ARTIFACT_AAB" },
  "signedBy": "release workflow only — signing secrets never leave the vault"
}
EOF

# Thanks page (community item D): CONTRIBUTORS.md ships with the release when generated
[ -f build/community/CONTRIBUTORS.md ] && cp build/community/CONTRIBUTORS.md "$OUT/CONTRIBUTORS.md"

# ---- staged rollout plan (the default procedure) ------------------------------
cat > "$OUT/rollout-plan.json" <<EOF
{
  "release": "$VN (versionCode $VC)",
  "generatedAt": "$NOW",
  "defaultProcedure": true,
  "stages": [
    { "fraction": 0.10, "trigger": "release CI uploads the AAB to the production track",
      "criteria": ["all gates green on the tag", "Play App Signing active"] },
    { "fraction": 0.50, "minHoursAtPreviousStage": 48,
      "criteria": ["crash rate < 1%", "ANR rate < 0.47%", "no new top crash cluster vs previous release"] },
    { "fraction": 1.00, "minHoursAtPreviousStage": 48,
      "criteria": ["crash rate < 1%", "ANR rate < 0.47%", "stability metrics at or better than previous release"] }
  ],
  "haltProcedure": "Play Console → Production → Releases → Halt rollout (takes effect within hours; already-served users keep the version)",
  "promotionMechanism": "release.yml promote job — workflow_dispatch + environment approval (the approval IS the halt-gate review)"
}
EOF

cat > "$OUT/rollout-plan.txt" <<EOF
خطة الإصدار التدريجي — $VN (versionCode $VC) — $NOW
════════════════════════════════════════════════════════════
 المرحلة   النسبة   شرط الانتقال
────────────────────────────────────────────────────────────
 1         10%      تلقائي بعد رفع AAB إلى مسار الإنتاج (البوابات خضراء)
 2         50%      ≥ 48 ساعة + معدل أعطال < 1% + ANR < 0.47% + بلا عنقود أعطال جديد
 3         100%     ≥ 48 ساعة على 50% + نفس العتبات + استقرار ≥ الإصدار السابق
────────────────────────────────────────────────────────────
 الإيقاف:  Play Console ← الإنتاج ← Halt rollout
 الترقية:  release.yml (workflow_dispatch) مع اعتماد بيئة النشر
════════════════════════════════════════════════════════════
EOF

echo "RELEASE_MANIFEST_OK → $OUT/release-manifest.json + rollout-plan.json + rollout-plan.txt"
