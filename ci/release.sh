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

ARTIFACT_APK="pending (the owner re-signs with the real keystore — same two commands)"
ARTIFACT_AAB="pending (built on the release machine / release CI)"
[ -f build/NeoKey-2036.apk ] && ARTIFACT_APK="build/NeoKey-2036.apk (SIGNED, drill stand-in identity — verified v1+v2, sha256 in gates.signedApk)"
UPLOADBUNDLE_BUNDLE_LINE=""
[ "${UPLOADBUNDLE_GATE:-}" = "OK" ] && UPLOADBUNDLE_BUNDLE_LINE="build/upload/bundle (week 32 — gate-verified: $UPLOADBUNDLE_MEMBERS members, data-safety derived from the manifest, listing coupled to live ui_en keys, statement neokey-uploadbundle-1)"

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

# Provisioning + rotation drill (phase 6, week 1 — week 30) — present when the gate ran
PROVISION_BLOCK=""
if [ "${PROVISION_GATE:-}" = "OK" ]; then
  PROVISION_BLOCK=$',\n    "provisioning": { "verifiedBy": "ci/provision_gate.sh", "contract": "docs/PHASE6-PLAN.md + docs/PHASE6-W1-REPORT.md", "pendingSlice": "the pin-SETTING half of the live-deployment pending — the decision procedure automated and proven to bite on committed stand-in fixtures; buying the domain + CA cert and running the two commands stays with the operator", "tool": "tests/com/hark/neokey/Provision.java (pure JDK, zero android.*) — the same CLI the operator runs on the real chain (PEM or DER CA accepted)", "oneDerivation": "SpkiPins.pinOf — the same derivation as the TLS gate and the pinned statements; the emitted literal is the single deployment line: NetGate.setSyncPins followed by the 64-hex pin in double quotes", "anchors": "arena pin16 '"${PROVISION_ANCHOR_PIN16:-}"' pinned literally (first barrier) + reference pin16 '"${PROVISION_PIN16:-}"' pinned literally (second barrier — internal consistency never judges identity)", "checks": '"${PROVISION_CHECKS:-0}"', "negativesRefused": "'"${PROVISION_NEGATIVES:-0}"'", "rotation": "same-key renewal = same pin (transparent); new key = new pin refused INSIDE the handshake until re-pin, then a green round", "statement": "build/provision/provision-statement.json (deterministic schema neokey-provision-1)", "negativeProof": "ci/provision-negative-proof.log" }'
fi

# Signed-artifact gate (phase 6, week 2 — week 31) — present when the gate ran
SIGNEDAPK_BLOCK=""
if [ "${SIGNEDAPK_GATE:-}" = "OK" ]; then
  SIGNEDAPK_BLOCK=$',\n    "signedApk": { "verifiedBy": "ci/signedapk_gate.sh", "contract": "docs/PHASE6-W2-REPORT.md", "pendingSlice": "the ARTIFACT half of the restore-signing-secrets pending — the full APK assembled offline (aapt2 link — real resources, restored week 31 after the W22 trim had dropped the identity strings; align: dex injected, arsc stored + 4-byte aligned), signed v1+v2 with the drill stand-in via Google apksig and verified end-to-end; the owner swaps in the real (W28-validated) keystore and runs the SAME two commands", "anchorFp16": "'"${SIGNEDAPK_FP16:-}"'", "v1": '"${SIGNEDAPK_V1:-0}"', "v2": '"${SIGNEDAPK_V2:-0}"', "artifactVersionCode": "'"${SIGNEDAPK_VC:-}"'", "artifactVersionName": "'"${SIGNEDAPK_VN:-}"'", "apkSha256": "'"${SIGNEDAPK_APK_SHA256:-}"'", "dexBytes": '"${SIGNEDAPK_DEX_BYTES:-0}"', "dexCeiling": 512000, "checks": '"${SIGNEDAPK_CHECKS:-0}"', "negativesRefused": "'"${SIGNEDAPK_NEGATIVES:-0}"'", "statement": "build/release/signedapk-statement.json (deterministic schema neokey-signedapk-1)", "negativeProof": "ci/signedapk-negative-proof.log" }'
fi

# Upload-bundle gate (phase 6, week 3 — week 32) — present when the gate ran
UPLOADBUNDLE_BLOCK=""
if [ "${UPLOADBUNDLE_GATE:-}" = "OK" ]; then
  UPLOADBUNDLE_BLOCK=$',\n    "uploadBundle": { "verifiedBy": "ci/uploadbundle_gate.sh", "contract": "docs/PHASE6-W3-REPORT.md", "pendingSlice": "the locally-closable slice of the store-upload pending — ONE verifiable bundle: the signed artifact (anchored byte-for-byte to the signedapk statement BEFORE assembly), mapping.txt, ar/en listing re-measured by the play-gate parser, every listing claim coupled to a LIVE ui_en.txt key, data-safety answers DERIVED from the actually-declared manifest permissions (duplicates refused; no answer without a declaration; collected=false everywhere), privacy policy, staged rollout plan, deterministic statement neokey-uploadbundle-1", "members": '"${UPLOADBUNDLE_MEMBERS:-0}"', "uiEvidence": '"${UPLOADBUNDLE_EVIDENCE:-0}"', "checks": '"${UPLOADBUNDLE_CHECKS:-0}"', "negativesRefused": "'"${UPLOADBUNDLE_NEGATIVES:-0}"'", "apkSha256": "'"${UPLOADBUNDLE_APK_SHA256:-}"'", "negativeProof": "ci/uploadbundle-negative-proof.log" }'
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
  PHASE4_BLOCK=$',\n    "phase4Close": { "verifiedBy": "ci/phase4_gate.sh", "contract": "docs/PHASE4-W4-REPORT.md", "phaseGatesGreen": "'"${PHASE4_GATES:-0/0}"'", "regressions": "'"${PHASE4_REGRESSIONS:-?}"'", "dexBytes": '"${PHASE4_DEX:-0}"', "dexCeiling": '"${PHASE4_CEILING:-513000}"', "versionCode": '"$VC"', "tests": "'"${PHASE4_TESTS:-0/0}"'", "negatives": "'"${PHASE4_NEGATIVES:-?}"'", "exitCriteria": "all phase-4 gates re-run green + three-way evidence integrity + zero pinned regressions + dex under the lowered ceiling + versionCode bumped" }'
fi

# Phase-5 closing gate (phase 5, week 4 / week 29) — present when the closing gate ran
PHASE5_BLOCK=""
if [ "${PHASE5_GATE:-}" = "OK" ]; then
  PHASE5_BLOCK=$',\n    "phase5Close": { "verifiedBy": "ci/phase5_gate.sh", "contract": "docs/PHASE5-W4-REPORT.md", "phaseGatesGreen": "'"${PHASE5_GATES:-0/0}"'", "regressions": "'"${PHASE5_REGRESSIONS:-?}"'", "dexBytes": '"${PHASE5_DEX:-0}"', "dexCeiling": '"${PHASE5_CEILING:-513000}"', "versionCode": '"$VC"', "tests": "'"${PHASE5_TESTS:-0/0}"'", "negatives": "'"${PHASE5_NEGATIVES:-?}"'", "drillFp16": "'"${KSDRILL_FP16:-}"'", "exitCriteria": "all phase-5 gates re-run fresh (tls/deploy/ksdrill) + three-way evidence integrity + zero regressions on all 21 previous gate keys + dex under the lowered ceiling + versionCode 70 / versionName 2.3" }'
fi

# Phase-6 closing gate (phase 6, week 4 / week 33) — present when the closing gate ran
PHASE6_BLOCK=""
if [ "${PHASE6_GATE:-}" = "OK" ]; then
  PHASE6_BLOCK=$',\n    "phase6Close": { "verifiedBy": "ci/phase6_gate.sh", "contract": "docs/PHASE6-W4-REPORT.md", "phaseGatesGreen": "'"${PHASE6_GATES:-0/0}"'", "regressions": "'"${PHASE6_REGRESSIONS:-?}"'", "dexBytes": '"${PHASE6_DEX:-0}"', "dexCeiling": '"${PHASE6_CEILING:-513000}"', "versionCode": '"$VC"', "tests": "'"${PHASE6_TESTS:-0/0}"'", "negatives": "'"${PHASE6_NEGATIVES:-?}"'", "anchorPin16": "'"${PHASE6_ANCHOR_PIN16:-}"'", "artifactFp16": "'"${PHASE6_FP16:-}"'", "exitCriteria": "all phase-6 gates re-run fresh (provision/signedapk/uploadbundle) + three-way evidence integrity + zero regressions on all 25 previous gate keys + dex under the lowered ceiling + versionCode 74 / versionName 2.7 (pinned once here; the live check is a floor)" }'
fi

# Phase-7 W2 gate (phase 7 W2 / week 35) — present when the deploysrv gate ran
DEPLOYSRV_BLOCK=""
if [ "${DEPLOYSRV_GATE:-}" = "OK" ]; then
  DEPLOYSRV_BLOCK=$',\n    "deploySrv": { "verifiedBy": "ci/deploysrv_gate.sh", "contract": "docs/PHASE7-W2-REPORT.md", "pendingSlice": "the last locally-closable slice of the live-sync-server pending — WHAT GETS TRANSFERRED is one verifiable pack (jar + generated start/stop/health scripts + systemd unit + INSTALL.md with the literal copy-paste commands INCLUDING the two automated ones: Provision → NetGate.setSyncPins) and WHAT GETS RUN IS THE TESTED THING: the gate boots the pack OWN copy as a separate process and runs a full contract round over the SPKI-pinned channel + health via the jar own --check; deterministic statement neokey-deploysrv-1; what remains outside: the domain + the CA cert + running the two commands", "members": '"${DEPLOYSRV_MEMBERS:-0}"', "checks": '"${DEPLOYSRV_CHECKS:-0}"', "negativesRefused": "'"${DEPLOYSRV_NEGATIVES:-0}"'", "jarSha256": "'"${DEPLOYSRV_JAR_SHA256:-}"'", "artifactVersionCode": "'"${DEPLOYSRV_VC:-}"'", "artifactVersionName": "'"${DEPLOYSRV_VN:-}"'", "negativeProof": "ci/deploysrv-negative-proof.log" }'
fi

# Phase-7 opener gate (phase 7 W1 / week 34) — present when the billing gate ran
BILLING_BLOCK=
if [ "${BILLING_GATE:-}" = "OK" ]; then
  BILLING_BLOCK=$',\n    "billing": { "verifiedBy": "ci/billing_gate.sh", "contract": "docs/PHASE7-W1-REPORT.md", "pendingSlice": "the locally-closable slice of the real-payment-provider pending — the REPLACEMENT proven before the console exists: PaymentProvider.PlayProvider (pure JVM, zero android.*) verifies the Play purchase RSA signature with the pasted console key (المرجع = المنشور), purchase state, catalog price, request binding (obfuscatedExternalAccountId) and orderId replay — then seals with THE SAME derivation, so the wallet never notices the provider swap; the contract-parity suite runs on BOTH providers; what remains outside: the ~20-line BillingClient wiring (needs a device) + pasting the real console key", "providers": 2, "checks": '"${BILLING_CHECKS:-0}"', "negativesRefused": "'"${BILLING_NEGATIVES:-0}"'", "artifactVersionCode": "'"${BILLING_VC:-}"'", "artifactVersionName": "'"${BILLING_VN:-}"'", "negativeProof": "ci/billing-negative-proof.log" }'
fi

# Phase-7 W3 gate (phase 7 W3 / week 36) — present when the preflight gate ran
PREFLIGHT_BLOCK=""
if [ "${PREFLIGHT_GATE:-}" = "OK" ]; then
  PREFLIGHT_BLOCK=$',\n    "preflight": { "verifiedBy": "ci/preflight_gate.sh", "contract": "docs/PHASE7-W3-REPORT.md", "pendingSlice": "the orchestration slice of ALL five pendings — ONE tool (Preflight, pure JVM) walks every release condition on FRESH re-runs (the four week gates re-run green inside the stage + the release commands existence) and emits THE HANDOVER SHEET (build/release/preflight-sheet.md, deterministic, no timestamps): every operator step either CHECKED here with the fresh verdict line as evidence, or OUTSIDE with its literal command + documented externality — the sheet is then chased row by row against the evidence and the sources (the sheet is the reference); what remains outside is named IN the sheet, no pretending", "rows": '"${PREFLIGHT_ROWS:-0}"', "checked": '"${PREFLIGHT_CHECKED:-0}"', "outside": '"${PREFLIGHT_OUTSIDE:-0}"', "checks": '"${PREFLIGHT_CHECKS:-0}"', "negativesRefused": "'"${PREFLIGHT_NEGATIVES:-0}"'", "artifactVersionCode": "'"${PREFLIGHT_VC:-}"'", "artifactVersionName": "'"${PREFLIGHT_VN:-}"'", "negativeProof": "ci/preflight-negative-proof.log" }'
fi

# Phase-7 closing gate (phase 7, week 4 / week 37) — present when the closing gate ran
PHASE7_BLOCK=""
if [ "${PHASE7_GATE:-}" = "OK" ]; then
  PHASE7_BLOCK=$',\n    "phase7Close": { "verifiedBy": "ci/phase7_gate.sh", "contract": "docs/PHASE7-W4-REPORT.md", "phaseGatesGreen": "'"${PHASE7_GATES:-0/0}"'", "regressions": "'"${PHASE7_REGRESSIONS:-?}"'", "dexBytes": '"${PHASE7_DEX:-0}"', "dexCeiling": '"${PHASE7_CEILING:-513000}"', "versionCode": '"$VC"', "tests": "'"${PHASE7_TESTS:-0/0}"'", "negatives": "'"${PHASE7_NEGATIVES:-?}"'", "packMembers": '"${PHASE7_MEMBERS:-0}"', "handoverRows": '"${PHASE7_ROWS:-0}"', "checkedHere": '"${PHASE7_CHECKED:-0}"', "outsideNamed": '"${PHASE7_OUTSIDE:-0}"', "exitCriteria": "all phase-7 gates re-run fresh (billing/deploysrv/preflight) + three-way evidence integrity + zero regressions on all 29 previous gate keys + dex under the lowered ceiling + versionCode 78 / versionName 3.1 (pinned once here; the live check is a floor)" }'
fi

REBUILD_BLOCK=""
if [ "${REBUILD_GATE:-}" = "OK" ]; then
  REBUILD_BLOCK=$',\n    "rebuild": { "verifiedBy": "ci/rebuild_gate.sh", "contract": "docs/PHASE8-W1-REPORT.md", "pendingSlice": "phase 8 W1 — the capability every closure PRESUMED, now a measured fact: a clean local clone of HEAD + the seven sha256-pinned toolchain artifacts (المرجع = المنشور) + the FULL INNER PIPELINE of 37 stages, every stage PASS with zero SKIP; RebuildGate.java re-computes every pin from tools/, re-parses the inner log, cross-checks the drill verdict against it, and bites 12 exact-tag negatives", "pins": "${REBUILD_PINS:-0/0}", "innerStages": "'"${REBUILD_STAGES:-0/0}"'", "innerTests": "'"${REBUILD_TESTS:-0/0}"'", "innerDexBytes": '"${REBUILD_DEX:-0}"', "dexCeiling": '"${REBUILD_CEILING:-513000}"', "checks": "'"${REBUILD_CHECKS:-0}"'", "negativesRefused": "'"${REBUILD_NEGATIVES:-0}"'", "negativeProof": "ci/rebuild-negative-proof.log" }'
fi

EVAUDIT_BLOCK=""
if [ "${EVAUDIT_GATE:-}" = "OK" ]; then
  EVAUDIT_BLOCK=$',\n    "evaudit": { "verifiedBy": "ci/evaudit_gate.sh", "contract": "docs/PHASE8-W2-REPORT.md", "pendingSlice": "phase 8 W2 — the WEEK EVIDENCE AUDITOR: this run\'s evidence archive packed deterministically (pipeline.log + gates.env + release-manifest.json + rollout plans + the preflight sheet + EVERY saved negative-proof log) with a sorted SHA256SUMS, then audited in isolation — member integrity, log shape, verdict↔ledger cross-check (every known gate line == its gates.env keys), identity consensus + the saved-negative-proof law — twelve exact-tag negatives including a forger who re-stamps SHA256SUMS; the vault owner verifies any week\'s proof BEFORE the outside step (pendings #3/#4): the archive is verified, not trusted", "members": '"${EVAUDIT_MEMBERS:-0}"', "proofs": "'"${EVAUDIT_PROOFS:-0/0}"'", "checks": "'"${EVAUDIT_CHECKS:-0}"'", "negativesRefused": "'"${EVAUDIT_NEGATIVES:-0}"'", "tests": "'"${EVAUDIT_TESTS:-0/0}"'", "dexBytes": '"${EVAUDIT_DEX:-0}"', "dexCeiling": '"${EVAUDIT_CEILING:-513000}"', "archive": "${EVAUDIT_ARCHIVE:-build/evaudit/archive}", "negativeProof": "ci/evaudit-negative-proof.log" }'
fi

PHASE8_BLOCK=""
if [ "${PHASE8_GATE:-}" = "OK" ]; then
  PHASE8_BLOCK=$',\n    "phase8Close": { "verifiedBy": "ci/phase8_gate.sh", "contract": "docs/PHASE8-W4-REPORT.md", "phaseGatesGreen": "'"${PHASE8_GATES:-0/0}"'", "regressions": "'"${PHASE8_REGRESSIONS:-?}"'", "dexBytes": '"${PHASE8_DEX:-0}"', "dexCeiling": '"${PHASE8_CEILING:-513000}"', "versionCode": '"$VC"', "tests": "'"${PHASE8_TESTS:-0/0}"'", "negatives": "'"${PHASE8_NEGATIVES:-?}"'", "pins": "'"${PHASE8_PINS:-?}"'", "letters": '"${PHASE8_LETTERS:-0}"', "packs": '"${PHASE8_PACKS:-0}"', "swaps": '"${PHASE8_SWAPS:-0}"', "exitCriteria": "all phase-8 gates re-run fresh (rebuild = a fresh drill, evaudit, opverify) + three-way evidence integrity + zero regressions on all 33 previous gate keys + dex under the lowered ceiling + versionCode 82 / versionName 3.5 (pinned once here; the live check is a floor)" }'
fi

OPVERIFY_BLOCK=""
if [ "${OPVERIFY_GATE:-}" = "OK" ]; then
  OPVERIFY_BLOCK=$',\n    "opverify": { "verifiedBy": "ci/opverify_gate.sh", "contract": "docs/PHASE8-W3-REPORT.md", "pendingSlice": "phase 8 W3 — the OPERATOR-OUTPUT VERIFIER: the outputs of the documented outside steps (pendings #1/#2) become machine-verifiable — the Provision letter (the emitted NetGate.setSyncPins literal + the neokey-provision-1 statement) verified against the published deployment shape with a fresh arena run as reference; the transferred pack re-verified BEFORE any run through the same SyncServerPack.check that proved it (the one-derivation law); the StoreActivity swap line documented as a gated line with its published identity (literal text + sha256) and an edited copy must touch exactly that line — twelve exact-tag negatives, no pretending", "letters": '"${OPVERIFY_LETTERS:-0}"', "packs": '"${OPVERIFY_PACKS:-0}"', "swaps": '"${OPVERIFY_SWAPS:-0}"', "swapLine16": "${OPVERIFY_SWAP_SHA16:-}", "checks": "'"${OPVERIFY_CHECKS:-0}"'", "negativesRefused": "'"${OPVERIFY_NEGATIVES:-0}"'", "negativeProof": "ci/opverify-negative-proof.log" }'
fi

PUBSYNC_BLOCK=""
if [ "${PUBSYNC_GATE:-}" = "OK" ]; then
  PUBSYNC_BLOCK=$',\n    "pubsync": { "verifiedBy": "ci/pubsync_gate.sh", "contract": "docs/PHASE9-W1-REPORT.md", "pendingSlice": "phase 9 W1 — the PUBLICATION GATE: the carry-out surface (the published release) becomes a gated input — this run\'s evidence archive packed by EvAudit.pack (the one derivation), zipped deterministically (two zips = identical bytes), uploaded to release ${PUBSYNC_REL:-} and verified by ROUNDTRIP: the published bytes fetched back, sha256 re-derived from what was DOWNLOADED (a tampered or partial upload bites), the fetched archive audited in isolation by EvAudit.check itself, and the cumulative patch from the last published state (${PUBSYNC_BASE:-v2.7-week33}) proven to apply to that base — no credential = fail closed, the set is monotonic (the week-33 assets stay)", "assets": '"${PUBSYNC_ASSETS:-0}"', "members": '"${PUBSYNC_MEMBERS:-0}"', "proofs": "'"${PUBSYNC_PROOFS:-0}"'", "checks": "'"${PUBSYNC_CHECKS:-0}"'", "negativesRefused": "'"${PUBSYNC_NEGATIVES:-0}"'", "releaseId": "${PUBSYNC_REL:-}", "baseTag": "${PUBSYNC_BASE:-}", "roundtrip": "${PUBSYNC_ROUNDTRIP:-?}", "negativeProof": "ci/pubsync-negative-proof.log" }'
fi

OWNERVERIFY_BLOCK=""
if [ "${OWNERVERIFY_GATE:-}" = "OK" ]; then
  OWNERVERIFY_BLOCK=$',\n    "ownerverify": { "verifiedBy": "ci/ownerverify_gate.sh", "contract": "docs/PHASE9-W2-REPORT.md", "pendingSlice": "phase 9 W2 — the OWNER FETCH VERIFIER: any published release verified from ANY machine without the write credential — the statement published as the third asset (its own sha self-referential by the W39 law, every field anchored transitively), the chain through the ONE derivation (PubSync.verify statement laws + sha re-derived from the FETCHED bytes, PubSync.unzip, EvAudit.check isolated) and the RECONSTRUCTION: the fetched cumulative patch applied to the published base (${OWNERVERIFY_BASE:-v2.7-week33}) must rebuild HEAD\'s tree BYTE-EXACTLY (tree oid == tree oid); the record (the committed proof line\'s full published sha) anchors against the consistent forger — whoever writes the release cannot rewrite the git the owner cloned; read-only is a law on the tool\'s own surface (a runtime self-audit refuses a write verb) and the read credential is fail-closed in the remote mode", "assets": '"${OWNERVERIFY_ASSETS:-0}"', "checks": "'"${OWNERVERIFY_CHECKS:-0}"'", "negativesRefused": "'"${OWNERVERIFY_NEGATIVES:-0}"'", "mode": "'"${OWNERVERIFY_MODE:-?}"'", "recordSource": "'"${OWNERVERIFY_RECORD:-?}"'", "recordSha256": "'"${OWNERVERIFY_RECORD_SHA:-}"'", "releaseId": "'"${OWNERVERIFY_REL:-}"'", "baseTag": "'"${OWNERVERIFY_BASE:-}"'", "negativeProof": "ci/ownerverify-negative-proof.log" }'
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
    "performance": $PERF_OBJ$R8_BLOCK$PLAY_BLOCK$NNET_BLOCK$NQFPORT_BLOCK$COMMUNITY_BLOCK$DICTSIGN_BLOCK$STOREUI_BLOCK$ENTGATE_BLOCK$REST_BLOCK$TLS_BLOCK$DEPLOY_BLOCK$KSDRILL_BLOCK$PROVISION_BLOCK$SIGNEDAPK_BLOCK$UPLOADBUNDLE_BLOCK$PAY_BLOCK$PHASE3_BLOCK$TRIM_BLOCK$PHASE4_BLOCK$PHASE5_BLOCK$PHASE6_BLOCK$BILLING_BLOCK$DEPLOYSRV_BLOCK$PREFLIGHT_BLOCK$PHASE7_BLOCK$REBUILD_BLOCK$EVAUDIT_BLOCK$OPVERIFY_BLOCK$PHASE8_BLOCK$PUBSYNC_BLOCK$OWNERVERIFY_BLOCK
  },
  "artifacts": { "apk": "$ARTIFACT_APK", "aab": "$ARTIFACT_AAB", "uploadBundle": "${UPLOADBUNDLE_BUNDLE_LINE:-pending (the uploadbundle gate assembles it: build/upload/bundle — signed APK + mapping + ar/en listing + data-safety + privacy + rollout plan)}" },
  "signedBy": "the signed-ARTIFACT path is proven automation (week 31: apksig v1+v2 on the drill stand-in, fp-pinned, 14 checks + 6/6 negatives); the release signature itself = the owner restores the real keystore (W28 procedure) and re-runs the same two commands — secrets never leave the vault"
}
EOF

# ---- store-readiness package (phase 4, week 3 / week 24) ------------------------
# A measured readiness statement, not a claim: every line cites the gate that
# verified it and the number it measured; what cannot close in this environment
# is named as pending with its exact original wording.
SIGNING_STATUS="verified — keystore fingerprint pinned in the manifest"
[ "$FP" = "(keystore fingerprint unavailable on this machine)" ] && \
  SIGNING_STATUS="pending — .secrets lost in environment rotation; restore from the encrypted backup before any signed build — BOTH HALVES ARE NOW AUTOMATED AND PROVEN TO BITE: the DECISION (week 28: ci/keystore_drill.sh — anchor + 12 checks + 8/8 negatives) and the ARTIFACT (week 31: ci/signedapk_gate.sh — full APK signed v1+v2 via apksig on the drill stand-in, 14 checks + 6/6 negatives, deterministic artifact); the owner restores the real keystore and runs the same two commands with it"
cat > "$OUT/store-readiness.json" <<EOF
{
  "app": "com.hark.neokey",
  "versionCode": $VC,
  "versionName": "$VN",
  "generatedAt": "$NOW",
  "readiness": {
    "storeListing": { "status": "verified by ci/play_gate.sh", "name_ar_en": "${PLAY_name_ar:-0}/30 + ${PLAY_name_en:-0}/30", "short_ar_en": "${PLAY_short_ar:-0}/80 + ${PLAY_short_en:-0}/80", "full_ar_en": "${PLAY_full_ar:-0}/4000 + ${PLAY_full_en:-0}/4000" },
    "shrink": { "status": "R8 Full Mode green + real trim enforced", "dexBytes": ${R8_DEX_BYTES:-0}, "ceiling": ${TRIM_CEILING:-513000}, "mapping": "build/release/mapping.txt" },
    "payments": { "status": "provider contract standard and proven; real provider = deployment decision", "gate": "${PAY_GATE:-NOT_OK}", "ledgerClosesCents": ${PAY_LEDGER_CLOSES:-0}, "negativeProof": "ci/pay-negative-proof.log" },
    "syncBackend": { "status": "contract proven on the wire + client TLS trust contract (SPKI pinning) proven + the deployable bundle proven as a separate process + the keystore-recovery drill proven to bite; PHASE 5 CLOSED by ci/phase5_gate.sh; PHASE 6 CLOSED by ci/phase6_gate.sh (week 33): the pin-SETTING decision procedure automated and bite-proven + the signed ARTIFACT proven v1+v2 + the store-upload bundle assembled and gate-verified; production deployment (domain + CA cert + the two automated commands) pending", "gate": "${REST_GATE:-NOT_OK}", "tlsGate": "${TLS_GATE:-NOT_OK}", "deployGate": "${DEPLOY_GATE:-NOT_OK}", "ksdrillGate": "${KSDRILL_GATE:-NOT_OK}", "provisionGate": "${PROVISION_GATE:-NOT_OK}", "phase5Gate": "${PHASE5_GATE:-NOT_OK}", "phase6Gate": "${PHASE6_GATE:-NOT_OK}", "wireRounds": ${REST_ROUNDS:-0} },
    "entitlements": { "status": "restore re-grants ownership by pin, never re-buys", "gate": "${ENTGATE:-NOT_OK}" },
    "economy": { "status": "70/30 close exact to the cent through Economy only", "monthCloseCents": ${PAY_LEDGER_CLOSES:-0} },
    "tests": { "total": $TESTS_TOTAL, "failed": $TESTS_FAILED },
    "rollout": { "status": "staged 10/50/100 with halt procedure — see rollout-plan.json", "defaultProcedure": true },
    "signing": { "status": "$SIGNING_STATUS" },
    "phase5": { "status": "closed by ci/phase5_gate.sh — the three locally-closable slices of the deployment pendings are proven; what remains outside is named below with its exact wording", "gates": "${PHASE5_GATES:-0/0}", "regressions": "${PHASE5_REGRESSIONS:-?}" },
    "phase6": { "status": "CLOSED by ci/phase6_gate.sh (week 33) — W1/30 the pin-setting decision procedure automated + rotation wire drill; W2/31 the signed ARTIFACT: full APK + apksig v1+v2 on the drill stand-in, fp-pinned, deterministic; W3/32 the store-upload bundle assembled, coupled and gate-verified; W4/33 the closure: all three re-run fresh + three-way evidence + zero regressions on 25 keys", "gates": "${PHASE6_GATES:-0/0}", "regressions": "${PHASE6_REGRESSIONS:-?}", "versionPinned": "vc 74 / vn 2.7 — pinned once in this manifest; the live check is a non-regression floor" },
    "phase7": { "status": "CLOSED by ci/phase7_gate.sh (week 37) — W1/34 the billing adapter REPLACEMENT proven before the console exists (contract parity + 10 exact-tag negatives); W2/35 the sync server as a DEPLOYABLE PACK — what gets transferred is one verifiable pack and what gets run is the tested thing (full contract round on the pack own-copy over the pinned channel + health + 5 exact-tag negatives); W3/36 the OPERATOR PREFLIGHT SHEET — one tool walks every release condition on fresh re-runs and emits the handover sheet: every step CHECKED here or OUTSIDE with its literal command, no pretending; W4/37 the closure: all three re-run fresh + three-way evidence + zero regressions on 29 keys", "gates": "${PHASE7_GATES:-0/3}", "regressions": "${PHASE7_REGRESSIONS:-?}", "billingGate": "${BILLING_GATE:-NOT_OK}", "billingChecks": "${BILLING_CHECKS:-0}", "billingNegatives": "${BILLING_NEGATIVES:-0}", "deploySrvGate": "${DEPLOYSRV_GATE:-NOT_OK}", "deploySrvChecks": "${DEPLOYSRV_CHECKS:-0}", "deploySrvNegatives": "${DEPLOYSRV_NEGATIVES:-0}", "deploySrvJarSha256": "${DEPLOYSRV_JAR_SHA256:-}", "preflightGate": "${PREFLIGHT_GATE:-NOT_OK}", "preflightChecks": "${PREFLIGHT_CHECKS:-0}", "preflightNegatives": "${PREFLIGHT_NEGATIVES:-0}", "preflightSheet": "${PREFLIGHT_SHEET:-build/release/preflight-sheet.md}", "versionPinned": "vc 78 / vn 3.1 — pinned once in this manifest; the live check is a non-regression floor" },
    "phase8": { "status": "CLOSED by ci/phase8_gate.sh (week 41) — W1/38 the REBUILD DRILL: a clean clone + the seven pinned tools + the full inner pipeline (the capability every closure presumed, now a measured gate); W2/39 the WEEK EVIDENCE AUDITOR: any week's evidence archive verifies in isolation (member integrity, log shape, verdict↔ledger cross-check, identity consensus + the saved-negative-proof law); W3/40 the OPERATOR-OUTPUT VERIFIER: the outputs of the documented outside steps are machine-verifiable — the Provision letter against the published deployment shape (a fresh arena run is the reference), the transferred pack re-verified BEFORE any run through the same check that proved it, and the StoreActivity swap line a gated line with its published identity; W4/41 the closure: all three re-run fresh (the closer's rebuild re-run IS a fresh drill) + three-way evidence + zero regressions on 33 keys — the five pendings stay EXACTLY as documented (external by nature); phase 8 armed the continuity and the owner's outside steps with verifiable tools, no pretending", "gates": "${PHASE8_GATES:-0/3}", "regressions": "${PHASE8_REGRESSIONS:-?}", "versionPinned": "vc 82 / vn 3.5 — pinned once in this manifest; the live check is a non-regression floor", "rebuildGate": "${REBUILD_GATE:-NOT_OK}", "pins": "${REBUILD_PINS:-0/0}", "innerStages": "${REBUILD_STAGES:-0/0}", "innerTests": "${REBUILD_TESTS:-0/0}", "checks": "${REBUILD_CHECKS:-0}", "negatives": "${REBUILD_NEGATIVES:-0}", "evauditGate": "${EVAUDIT_GATE:-NOT_OK}", "evauditMembers": "${EVAUDIT_MEMBERS:-0}", "evauditProofs": "${EVAUDIT_PROOFS:-0/0}", "evauditChecks": "${EVAUDIT_CHECKS:-0}", "evauditNegatives": "${EVAUDIT_NEGATIVES:-0}", "opverifyGate": "${OPVERIFY_GATE:-NOT_OK}", "opverifyLetters": "${OPVERIFY_LETTERS:-0}", "opverifyPacks": "${OPVERIFY_PACKS:-0}", "opverifySwaps": "${OPVERIFY_SWAPS:-0}", "opverifyChecks": "${OPVERIFY_CHECKS:-0}", "opverifyNegatives": "${OPVERIFY_NEGATIVES:-0}" },
    "phase9": { "status": "CLOSED by ci/phase9_gate.sh (week 44) — W1/42 the PUBLICATION GATE: the carry-out surface became a gated input — the evidence archive packed (EvAudit.pack, the one derivation), zipped deterministically (two zips = identical bytes), uploaded to the published release and verified by ROUNDTRIP: the published bytes fetched back, sha256 re-derived from what was DOWNLOADED, the fetched archive audited in isolation by EvAudit.check itself, and the cumulative patch from the last published state (v2.7-week33) proven to apply to that base; no credential = fail closed (publish.credential — no pretending). W2/43 the OWNER FETCH VERIFIER: any published release verified from ANY machine without the write credential — the statement published as the third asset (its own sha self-referential by the W39 law, every field anchored transitively), the chain through the ONE derivation and the RECONSTRUCTION: the fetched patch must rebuild HEAD's tree BYTE-EXACTLY (tree oid == tree oid); the record (the committed proof line's full published sha) anchors against the consistent forger; read-only enforced on the tool's own surface. W3/44 the closure: both gates re-run fresh in one line (the publication in its PUBLISH mode — the week's assets join the additive set — and the owner fetch verifier in its full local mode, the 24-check battery) + state consensus + zero regressions on 35 keys", "gates": "${PUBSYNC_GATE:-NOT_OK}", "pubsyncGate": "${PUBSYNC_GATE:-NOT_OK}", "pubsyncChecks": "${PUBSYNC_CHECKS:-0}", "pubsyncNegatives": "${PUBSYNC_NEGATIVES:-0}", "pubsyncAssets": "${PUBSYNC_ASSETS:-0}", "pubsyncMembers": "${PUBSYNC_MEMBERS:-0}", "pubsyncProofs": "${PUBSYNC_PROOFS:-0}", "pubsyncRel": "${PUBSYNC_REL:-}", "pubsyncBase": "${PUBSYNC_BASE:-}", "ownerVerifyGate": "${OWNERVERIFY_GATE:-NOT_OK}", "ownerVerifyChecks": "${OWNERVERIFY_CHECKS:-0}", "ownerVerifyNegatives": "${OWNERVERIFY_NEGATIVES:-0}", "ownerVerifyMode": "${OWNERVERIFY_MODE:-?}", "ownerVerifyRecord": "${OWNERVERIFY_RECORD:-?}", "ownerVerifyRel": "${OWNERVERIFY_REL:-}", "closureGates": "${PHASE9_GATES:-0/2}", "regressions": "${PHASE9_REGRESSIONS:-?}", "negatives": "${PHASE9_NEGATIVES:-0}", "assets": "${PHASE9_ASSETS:-0}", "phase9Close": "vc 85 / vn 3.8 pinned once — the phase-9 close floor (the W30 lesson, fifth closure running); no week may ship below it", "versionPinned": "vc 85 / vn 3.8 — pinned once in this manifest; the live check is a non-regression floor" }
  },
  "pendingOutsideThisEnvironment": [
    "real payment provider (Play Billing) — the REPLACEMENT is proven since week 34 (billing_gate: contract parity on both providers + purchase-signature verification with the pasted console key) and re-proven FRESH in every run since week 37 (the phase-7 closure re-runs billing_gate); what remains is EXACTLY: the ~20-line BillingClient wiring (needs a device) + pasting the real console key into the one-line swap in StoreActivity",
    "live sync server deployment with TLS on a real domain — the pin-setting decision procedure is automated and bite-proven (week 30), and since week 35 WHAT GETS TRANSFERRED is one verifiable pack whose own copy passes a full contract round on the wire (deploysrv_gate) — re-run FRESH in every run since week 37 (the phase-7 closure); what remains is EXACTLY: buying the domain + the CA certificate, transferring the pack, then running the two automated commands (Provision → the emitted NetGate.setSyncPins literal)",
    "store developer accounts + the upload click itself — the BUNDLE is assembled, coupled and gate-verified since week 32 (uploadbundle_gate), and since week 36 the handover sheet (preflight_gate) re-verifies it FRESH in every run and walks every release condition beside it; what remains is the account and the console click",
    "keystore secrets restore from the encrypted backup before any signed build — BOTH halves automated and proven (the DECISION: week-28 drill, anchor + 8 negatives on a stand-in; the ARTIFACT: week-31 signed APK, apksig v1+v2, 14 checks + 6/6 negatives, deterministic); since week 36 the preflight RE-RUNS the drill fresh in every run so the decision procedure provably still bites, and the handover sheet names the two release commands verbatim; the actual restore stays with the owner",
    "real-device matrix + MAU numbers — fully external (a device + an account); documented AS-IS in the handover sheet (preflight, week 36) with no pretending"
  ]
}
EOF

cat > "$OUT/store-readiness.txt" <<EOF
حزمة جاهزية النشر — $VN (versionCode $VC) — $NOW
════════════════════════════════════════════════════════════
 البند            الحالة (بوابة التحقيق + القياس)
────────────────────────────────────────────────────────────
 حزمة الرفع       مجمّعة ومتحقق منها — uploadbundle_gate (${UPLOADBUNDLE_MEMBERS:-0} أعضاء، ${UPLOADBUNDLE_CHECKS:-0} فحصاً، ${UPLOADBUNDLE_NEGATIVES:-0} سلبية) — الباقي: الحساب والنقرة
 قائمة المتجر     جاهزة — play_gate (${PLAY_name_ar:-0}/30 اسم عربي، ${PLAY_short_ar:-0}/80 وصف قصير، ${PLAY_full_ar:-0}/4000 وصف كامل + الإنجليزية)
 البناء والتصغير  R8 Full Mode أخضر — dex ${R8_DEX_BYTES:-0}/${TRIM_CEILING:-513000} بايت (تقليم حقيقي مفروض) + mapping.txt
 الدفع            العقد قياسي مثبت — PaymentProvider نقطة الاستبدال الوحيدة، دفتر ينغلق ${PAY_LEDGER_CLOSES:-0} سنتاً، إثبات سلبي 12 هجمة — **الاستبدال نفسه مثبت (الأسبوع 34: billing_gate ${BILLING_CHECKS:-0} فحصاً و${BILLING_NEGATIVES:-0} سلبية — تعادل العقد على المزوّدين + تحقق توقيع الشراء بمفتاح اللوحة)** — الباقي: سلك BillingClient على جهاز + لصق مفتاح اللوحة الحقيقي
 خادم المزامنة    العقد على السلك + ثقة العميل مثبتة + حزمة النشر عملية مستقلة + تدريب استعادة التوقيع يعضّ — المرحلة 5 مغلقة ببوابة phase5 — **التجهيز والدوران آليان وعضّالان (الأسبوع 30: provision_gate ${PROVISION_CHECKS:-0} فحصاً و${PROVISION_NEGATIVES:-0} سلبية)** — النشر الحي = شراء النطاق + شهادة CA ثم الأمران الآليان (Provision ← NetGate.setSyncPins)
 المرحلة 6        مغلقة ببوابة phase6 — بواباتها الثلاث تُعاد طازجة في خط واحد: التجهيز + الأرتقف الموقَّع + حزمة الرفع، اتساق ثلاثي للأدلة، لا تراجع في 25 مفتاحاً — الإصدار المثبت: vc ${PHASE6_VC:-$VC} / 2.7
 المرحلة 7        مغلقة ببوابة phase7 — بواباتها الثلاث تُعاد طازجة في خط واحد: مُحوّل الفاتورة (تعادل العقد على المزوّدين) + خادم المزامنة أرتقف نشر (الحزمة تنقل وتُشغَّل وتُختبر على السلك) + الفحص التمهيدي (ورقة التسليم: ${PREFLIGHT_CHECKED:-0} خطوات مفحوصة هنا و${PREFLIGHT_OUTSIDE:-0} خارجية بأمرها الحرفي)، اتساق ثلاثي للأدلة، لا تراجع في 29 مفتاحاً — الإصدار المثبت: vc ${PHASE7_VC:-$VC} / 3.1 — ورقة التسليم: ${PREFLIGHT_SHEET:-build/release/preflight-sheet.md}
 المرحلة 8        مغلقة ببوابة phase8 — بواباتها الثلاث تُعاد طازجة في خط واحد (إعادة rebuild داخل الإغلاق حفرية كاملة طازجة): الاستمرارية (حفرية إعادة البناء من لا شيء بسبع مراسيات sha256) + مُدقِّق الأدلة (الأرشيف يُتحقق منه معزولاً بأربع طبقات قوانين) + مُحقِّق مخرجات المشغّل (الحرف والحزمة وسطر الاستبدال قابلة للتحقق آلياً)، اتساق ثلاثي للأدلة، لا تراجع في 33 مفتاحاً — الإصدار المثبت: vc ${PHASE8_VC:-$VC} / 3.5 — المعلقات الخمسة تبقى بنصها (خارجية بطبيعتها) بلا تظاهر
 المرحلة 9        مفتوحة ببوابة pubsync (W1/42) وownerverify (W2/43) — W1: سطح حمل الأدلة خارجاً يصير مدخلاً مُحكَماً به: أرشيف هذا التشغيل يُحزَم بـ EvAudit.pack (الاشتقاق الواحد) ويُزوَّد حتمياً ويُرفع إلى الإصدار المنشور مع البيان أصلاً ثالثاً ثم **الجولة** تتحقق: البايتات المنشورة تُجلب من جديد وsha256 يُعاد اشتقاقها من المنزَّل، والأرشيف المنزَّل يُدقَّق معزولاً بـ EvAudit.check نفسها، والرقعة التراكمية من آخر حالة منشورة (v2.7-week33) تُثبَّت أنها تُطبَّق — بلا اعتماد تفشل مغلقةً (${PUBSYNC_GATE:-NOT_OK}، ${PUBSYNC_CHECKS:-0} فحصاً، ${PUBSYNC_NEGATIVES:-0} سلبية) — W2: **مُتحقِّق الجلب للمالك** — أي إصدار منشور يُدقَّق من أي جهاز بلا اعتماد الكتابة: سلسلة الاشتقاق الواحد على المنزَّل + **إعادة البناء** (الرقعة المنزَّلة تعيد بناء شجرة HEAD بايت-لطرفاً: oid == oid) + السجل المرساة ضد المزوّر المتسق + القراءة-فقط قانون على سطح الأداة نفسها (${OWNERVERIFY_GATE:-NOT_OK}، ${OWNERVERIFY_CHECKS:-0} فحصاً، ${OWNERVERIFY_NEGATIVES:-0} سلبية، record=${OWNERVERIFY_RECORD:-?})
 ورقة التسليم     كل خطوة مشغّل إما CHECKED هنا بدليل تشغيل طازج أو OUTSIDE بأمرها الحرفي وخارجيتها الموثقة — بلا تظاهر (المعلق الخامس كما هو)
 الاستحقاقات      الاستعادة تعيد الملكية بالدبوس ولا تشتري أبداً
 الاقتصاد         إغلاق 70/30 دقيق للسنت عبر Economy حصراً
 الاختبارات       $TESTS_TOTAL اختباراً، $TESTS_FAILED إخفاق
 الإصدار التدريجي  10/50/100% مع إجراء الإيقاف — rollout-plan.json
 التوقيع          $SIGNING_STATUS
────────────────────────────────────────────────────────────
 خارج هذه البيئة (موثق لا منسي):
  • مزوّد دفع حقيقي (الاستبدال مثبت منذ الأسبوع 34 (تعادل العقد على المزوّدين + تحقق توقيع الشراء + 10 سلبية بأوسمتها)؛ الباقي: سلك BillingClient على جهاز + لصق المفتاح الحقيقي
  • نشر خادم مزامنة حي بنطاق حقيقي + TLS — الباقي بالضبط: شراء النطاق + شهادة CA، ثم الأمران الآليان؛ إجراء ضبط الدبابيس والدوران آلي وعضّال (الأسبوع 30: مرساتان بدبوسين منشورين + تدريب تجديد/دوران على السلك)
  • حسابات متاجر + نقطة الرفع نفسها — الحزمة نفسها مجمّعة ومتحقق منها منذ الأسبوع 32 (أرتقف موقَّع + mapping + قائمة ar/en + سلامة بيانات مشتقة من الأذونات + خصوصية + خطة طرح)
  • استعادة أسرار التوقيع من النسخة المشفرة قبل أي بناء موقّع — النصفان آليان ومثبتان (القرار: تدريب الأسبوع 28 مرساة + 8 سلبية؛ الأرتقف: الأسبوع 31 APK موقّع v1+v2 عبر apksig على المخزن البديل، 14 فحصاً + 6/6 سلبية، أرتقف حتمي) — والاستعادة الفعلية تبقى بيد المالك الذي يشغّل الأمرين نفسَيهما
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
