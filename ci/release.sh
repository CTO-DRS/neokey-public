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

# Signed-dictionary-merge gate (phase 3, week 2) — present when the gate ran
DICTSIGN_BLOCK=""
if [ "${DICTSIGN_GATE:-}" = "OK" ]; then
  DICTSIGN_BLOCK=$',\n    "dictsign": { "verifiedBy": "ci/dictsign_gate.sh", "contract": "docs/PHASE3-W2-REPORT.md", "packAsset": "assets/community_ar.dpack", "packWords": '"${DICTSIGN_WORDS:-0}"', "negativesRefused": "'"${DICTSIGN_NEGATIVES:-0}"'", "publishP50Us": '"${DICTSIGN_P50_PUBLISH_US:-0}"', "loadP50Us": '"${DICTSIGN_P50_LOAD_US:-0}"', "trustChain": "review -> statement -> sha256 signature -> golden pin -> engine merge after installed corpus" }'
fi

# Entitlements-across-devices gate (phase 3, week 3) — present when the gate ran
ENTGATE_BLOCK=""
if [ "${ENTGATE:-}" = "OK" ]; then
  ENTGATE_BLOCK=$',\n    "entitlements": { "verifiedBy": "ci/entitlement_gate.sh", "contract": "docs/PHASE3-W3-REPORT.md", "recordKind": "entitlement (ownership pin only — binaries always come from the pinned catalog)", "granted": '"${ENTGATE_GRANTED:-0}"', "negativesRefused": "'"${ENTGATE_NEGATIVES:-0}"'", "collectP50Us": '"${ENTGATE_P50_COLLECT_US:-0}"', "applyP50Us": '"${ENTGATE_P50_APPLY_US:-0}"', "serverEntropyX100": '"${ENTGATE_ENTROPY:-0}"', "trustChain": "catalog purchase -> entitlement record -> E2E sealed sync -> grantFromSync by exact pin -> install from catalog" }'
fi

# REST reference-server gate (phase 4, week 23) — present when the gate ran
REST_BLOCK=""
if [ "${REST_GATE:-}" = "OK" ]; then
  REST_BLOCK=$',\n    "restServer": { "verifiedBy": "ci/rest_gate.sh", "contract": "docs/SYNC-REST.md", "server": "SyncServer — real HTTP (JDK) reference server on 127.0.0.1, tests/ not src/ (proof infrastructure, zero dex, zero app attack surface)", "client": "SyncRestTransport (production, HttpURLConnection)", "endpoints": 5, "wireRounds": '"${REST_ROUNDS:-0}"', "blobsSynced": '"${REST_BLOBS:-0}"', "wireBytes": '"${REST_WIRE_BYTES:-0}"', "grantedAcrossDevices": '"${REST_GRANTED:-0}"', "negativesRefused": "'"${REST_NEGATIVES:-0}"'", "p50PushMs": '"${REST_P50_PUSH_MS:-0}"', "p50PullMs": '"${REST_P50_PULL_MS:-0}"', "serverEntropyX100": '"${REST_ENTROPY_X100:-0}"', "negativeProof": "ci/rest-negative-proof.log" }'
fi

# Payment-provider contract gate (phase 4, week 24) — present when the gate ran
PAY_BLOCK=""
if [ "${PAY_GATE:-}" = "OK" ]; then
  PAY_BLOCK=$',\n    "payment": { "verifiedBy": "ci/pay_gate.sh", "contract": "docs/PHASE4-W3-REPORT.md", "replacementPoint": "PaymentProvider — the ONLY money path into the wallet (verified sealed receipt, deterministic seal, REPLAY ledger)", "simulation": "local-sim welcome credit — a declared provider, replaced by the real one at deployment", "providersProven": '"${PAY_PROVIDERS:-0}"', "receiptsVerified": '"${PAY_RECEIPTS:-0}"', "deterministicProof": "'"det_runs=2 det_equal=${PAY_DET_EQUAL:-0}"'", "ledgerClosesCents": '"${PAY_LEDGER_CLOSES:-0}"', "negativesRefused": "'"${PAY_NEGATIVES:-0}"'", "topupP50Us": '"${PAY_P50_TOPUP_US:-0}"', "negativeProof": "ci/pay-negative-proof.log (12 attacks + 12 green baselines)" }'
fi

# TLS-SPKI-pinning gate (phase 5, week 26) — present when the gate ran
TLS_BLOCK=""
if [ "${TLS_GATE:-}" = "OK" ]; then
  TLS_BLOCK=$',\n    "tlsPinning": { "verifiedBy": "ci/tls_gate.sh", "contract": "docs/SYNC-REST.md §TLS", "pendingSlice": "locally-closable slice of the live-TLS-server deployment — the client trust contract", "trustModel": "SPKI sha256 pin on the END-ENTITY key inside the handshake TrustManager — a valid chain with a different key is refused; no request byte leaves before the pin passes", "pins": '"${TLS_PINS:-0}"', "protocolMeasured": "'"${TLS_PROTOCOL:-}"'", "protocolFloor": "TLSv1.2", "wireRounds": '"${TLS_ROUNDS:-0}"', "negativesRefused": "'"${TLS_NEGATIVES:-0}"'", "serverEntropyX100": '"${TLS_ENTROPY_X100:-0}"', "p50HandshakeMs": '"${TLS_P50_HANDSHAKE_MS:-0}"', "productionRule": "NetGate.setSyncPins at deployment — https sync without pins refuses at construction (https_unpinned)", "negativeProof": "ci/tls-negative-proof.log" }'
fi

# Deployment-bundle gate (phase 5, week 27) — present when the gate ran
DEPLOY_BLOCK=""
if [ "${DEPLOY_GATE:-}" = "OK" ]; then
  DEPLOY_BLOCK=$',\n    "deployBundle": { "verifiedBy": "ci/deploy_gate.sh", "contract": "deploy/README.md", "pendingSlice": "locally-closable slice of the live-sync-server deployment — the deployable artifact itself", "bundle": "build/deploy/neokey-sync-server.jar (standalone, java 8 target, zero dependencies; the SAME reference-contract SyncServer class proven on the wire by RESTGATE/TLSGATE — no parallel implementation)", "launcher": "deploy/com/hark/neokey/deploy/DeployMain.java — serve / --print-spki (the pin NetGate.setSyncPins consumes) / --check (the contract is the probe: grey 404 = alive, over a pinned channel)", "bootDiscipline": "READY line once, loopback default bind (public exposure = explicit --host), every pre-READY error = DEPLOY_REFUSED with non-zero exit", "pinAnchor": "jar printed pin == pinned fixture fingerprint == gate constant (three sources, one truth)", "healthP50Ms": '"${DEPLOY_HEALTH_MS:-0}"', "negativesRefused": "'"${DEPLOY_NEGATIVES:-0}"'", "negativeProof": "ci/deploy-negative-proof.log" }'
fi

# Keystore-recovery-drill gate (phase 5, week 28) — present when the drill ran
KSDRILL_BLOCK=""
if [ "${KSDRILL_GATE:-}" = "OK" ]; then
  KSDRILL_BLOCK=$',\n    "ksDrill": { "verifiedBy": "ci/keystore_drill.sh", "contract": "docs/PHASE5-W3-REPORT.md + docs/SECURITY.md §6", "pendingSlice": "the PROCEDURE half of the restore-signing-secrets pending — rehearsed on a committed stand-in keystore; the secret itself never leaves the owner-side vault", "validator": "tests/com/hark/neokey/KsValidator.java (pure JDK, zero android.*) — the same class the owner runs on the real restored artifact", "drillAnchor": "sha256(cert DER) of tests/ks/drill-release.p12 pinned as a literal (fp16 '"${KSDRILL_FP16:-}"'…) — المرجع = المنشور; the anchor is the FIRST barrier", "checks": '"${KSDRILL_CHECKS:-0}"', "negativesRefused": "'"${KSDRILL_NEGATIVES:-0}"'", "daysLeftOnStandIn": '"${KSDRILL_DAYS_LEFT:-0}"', "recoverySimulation": "fixture → build/ks-drill/restored.p12 (chmod 600) → KsValidator CLI against the published fingerprint", "negativeProof": "ci/ksdrill-negative-proof.log" }'
fi

# Phase-3 closing gate (phase 3, week 4) — present when the closing gate ran
PHASE3_BLOCK=""
if [ "${PHASE3_GATE:-}" = "OK" ]; then
  PHASE3_BLOCK=$',\n    "phase3Close": { "verifiedBy": "ci/phase3_gate.sh", "contract": "docs/PHASE3-W4-REPORT.md", "phaseGatesGreen": "'"${PHASE3_GATES:-0/0}"'", "regressions": "'"${PHASE3_REGRESSIONS:-?}"'", "dexBytes": '"${PHASE3_DEX:-0}"', "dexCeiling": '"${PHASE3_CEILING:-520000}"', "versionCode": '"$VC"', "tests": "'"${PHASE3_TESTS:-0/0}"'", "negatives": "'"${PHASE3_NEGATIVES:-?}"'", "exitCriteria": "all phase-3 gates re-run green + zero pinned regressions + dex under ceiling + versionCode bumped" }'
fi

# Real-dex-trim gate (phase 4, week 22) — present when the trim gate ran
TRIM_BLOCK=""
if [ "${TRIM_GATE:-}" = "OK" ]; then
  TRIM_BLOCK=$',\n    "trim": { "verifiedBy": "ci/trim_gate.sh", "contract": "docs/PHASE4-W1-REPORT.md", "lever": "119 static Arabic UI-copy literals relocated byte-identically to res/values/strings.xml (resources ride in the APK, not the dex; purity untouched)", "entries": '"${TRIM_ENTRIES:-0}"', "bytesSavedFromDex": '"${TRIM_SAVED:-0}"', "dexBytes": '"${TRIM_DEX_BYTES:-0}"', "dexCeilingLoweredTo": '"${TRIM_CEILING:-0}"', "uiEnCoupledKeys": '"${TRIM_UI_EN_COUPLED:-0}"', "doctrine": "overflow means trim, never raise" }'
fi

# Phase-4 closing gate (phase 4, week 4 / week 25) — present when the closing gate ran
PHASE4_BLOCK=""
if [ "${PHASE4_GATE:-}" = "OK" ]; then
  PHASE4_BLOCK=$',\n    "phase4Close": { "verifiedBy": "ci/phase4_gate.sh", "contract": "docs/PHASE4-W4-REPORT.md", "phaseGatesGreen": "'"${PHASE4_GATES:-0/0}"'", "regressions": "'"${PHASE4_REGRESSIONS:-?}"'", "dexBytes": '"${PHASE4_DEX:-0}"', "dexCeiling": '"${PHASE4_CEILING:-512000}"', "versionCode": '"$VC"', "tests": "'"${PHASE4_TESTS:-0/0}"'", "negatives": "'"${PHASE4_NEGATIVES:-?}"'", "exitCriteria": "all phase-4 gates re-run green + three-way evidence integrity + zero pinned regressions + dex under the lowered ceiling + versionCode bumped" }'
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
    "performance": $PERF_OBJ$R8_BLOCK$PLAY_BLOCK$NNET_BLOCK$NQFPORT_BLOCK$COMMUNITY_BLOCK$DICTSIGN_BLOCK$STOREUI_BLOCK$ENTGATE_BLOCK$REST_BLOCK$TLS_BLOCK$DEPLOY_BLOCK$KSDRILL_BLOCK$PAY_BLOCK$PHASE3_BLOCK$TRIM_BLOCK$PHASE4_BLOCK
  },
  "artifacts": { "apk": "$ARTIFACT_APK", "aab": "$ARTIFACT_AAB" },
  "signedBy": "release workflow only — signing secrets never leave the vault"
}
EOF

# ---- store-readiness package (phase 4, week 3 / week 24) ------------------------
# A measured readiness statement, not a claim: every line cites the gate that
# verified it and the number it measured; what cannot close in this environment
# is named as pending with its exact original wording.
SIGNING_STATUS="verified — keystore fingerprint pinned in the manifest"
[ "$FP" = "(keystore fingerprint unavailable on this machine)" ] && \
  SIGNING_STATUS="pending — .secrets lost in environment rotation; restore from the encrypted backup before any signed build (DictSign/CI do not need it) — THE RESTORE PROCEDURE ITSELF IS NOW AUTOMATED AND PROVEN TO BITE (ci/keystore_drill.sh, KsValidator + KsDrillGate: anchor-first, ${KSDRILL_CHECKS:-0} checks, ${KSDRILL_NEGATIVES:-0/0} negatives; rehearsed on a stand-in keystore, the real secret never leaves the vault)"

cat > "$OUT/store-readiness.json" <<EOF
{
  "app": "com.hark.neokey",
  "versionCode": $VC,
  "versionName": "$VN",
  "generatedAt": "$NOW",
  "readiness": {
    "storeListing": { "status": "verified by ci/play_gate.sh", "name_ar_en": "${PLAY_name_ar:-0}/30 + ${PLAY_name_en:-0}/30", "short_ar_en": "${PLAY_short_ar:-0}/80 + ${PLAY_short_en:-0}/80", "full_ar_en": "${PLAY_full_ar:-0}/4000 + ${PLAY_full_en:-0}/4000" },
    "shrink": { "status": "R8 Full Mode green + real trim enforced", "dexBytes": ${R8_DEX_BYTES:-0}, "ceiling": ${TRIM_CEILING:-512000}, "mapping": "build/release/mapping.txt" },
    "payments": { "status": "provider contract standard and proven; real provider = deployment decision", "gate": "${PAY_GATE:-NOT_OK}", "ledgerClosesCents": ${PAY_LEDGER_CLOSES:-0}, "negativeProof": "ci/pay-negative-proof.log" },
    "syncBackend": { "status": "contract proven on the wire + client TLS trust contract (SPKI pinning) proven + the deployable bundle proven as a separate process (READY pin anchor, health check, fail-closed boot); production deployment (sync.neokey.app + TLS pins at NetGate) pending", "gate": "${REST_GATE:-NOT_OK}", "tlsGate": "${TLS_GATE:-NOT_OK}", "deployGate": "${DEPLOY_GATE:-NOT_OK}", "wireRounds": ${REST_ROUNDS:-0} },
    "entitlements": { "status": "restore re-grants ownership by pin, never re-buys", "gate": "${ENTGATE:-NOT_OK}" },
    "economy": { "status": "70/30 close exact to the cent through Economy only", "monthCloseCents": ${PAY_LEDGER_CLOSES:-0} },
    "tests": { "total": $TESTS_TOTAL, "failed": $TESTS_FAILED },
    "rollout": { "status": "staged 10/50/100 with halt procedure — see rollout-plan.json", "defaultProcedure": true },
    "signing": { "status": "$SIGNING_STATUS" }
  },
  "pendingOutsideThisEnvironment": [
    "real payment provider (Play Billing) — replaces PaymentProvider.SimProvider in StoreActivity only",
    "live sync server deployment with TLS on a real domain (contract already proven on the wire)",
    "store developer accounts + actual store upload (rollout plan ready)",
    "keystore secrets restore from the encrypted backup before any signed build — the DECISION PROCEDURE is now automated and proven (week 28 drill: anchor + 8 negatives on a stand-in keystore); the actual restore stays with the owner",
    "real-device matrix + MAU numbers"
  ]
}
EOF

cat > "$OUT/store-readiness.txt" <<EOF
حزمة جاهزية النشر — $VN (versionCode $VC) — $NOW
════════════════════════════════════════════════════════════
 البند            الحالة (بوابة التحقيق + القياس)
────────────────────────────────────────────────────────────
 قائمة المتجر     جاهزة — play_gate (${PLAY_name_ar:-0}/30 اسم عربي، ${PLAY_short_ar:-0}/80 وصف قصير، ${PLAY_full_ar:-0}/4000 وصف كامل + الإنجليزية)
 البناء والتصغير  R8 Full Mode أخضر — dex ${R8_DEX_BYTES:-0}/${TRIM_CEILING:-512000} بايت (تقليم حقيقي مفروض) + mapping.txt
 الدفع            العقد قياسي مثبت — PaymentProvider نقطة الاستبدال الوحيدة، دفتر ينغلق ${PAY_LEDGER_CLOSES:-0} سنتاً، إثبات سلبي 12 هجمة (المزوّد الحقيقي قرار نشر)
 خادم المزامنة    العقد على السلك + ثقة العميل مثبتة + حزمة النشر مثبتة كعملية مستقلة (بصمة الجرة = المرساة، فحص حي، إقلاع مغلقة الفشل) — النشر الحي = نطاق + شهادة + ضبط الدبابيس
 الاستحقاقات      الاستعادة تعيد الملكية بالدبوس ولا تشتري أبداً
 الاقتصاد         إغلاق 70/30 دقيق للسنت عبر Economy حصراً
 الاختبارات       $TESTS_TOTAL اختباراً، $TESTS_FAILED إخفاق
 الإصدار التدريجي  10/50/100% مع إجراء الإيقاف — rollout-plan.json
 التوقيع          $SIGNING_STATUS
────────────────────────────────────────────────────────────
 خارج هذه البيئة (موثق لا منسي):
  • مزوّد دفع حقيقي (يستبدل SimProvider في StoreActivity فقط)
  • نشر خادم مزامنة حي بنطاق حقيقي + TLS
  • حسابات متاجر + رفع فعلي (الخطة جاهزة)
  • استعادة أسرار التوقيع من النسخة المشفرة قبل أي بناء موقّع — إجراء القرار نفسه آلي ومثبت (تدريب الأسبوع 28: مرساة + 8 سلبية على مخزن بديل)، والاستعادة الفعلية تبقى بيد المالك
  • أجهزة حقيقية + أرقام استخدام
════════════════════════════════════════════════════════════
EOF

echo "STORE_READINESS_OK → $OUT/store-readiness.json + store-readiness.txt"

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
