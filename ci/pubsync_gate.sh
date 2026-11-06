#!/bin/bash
# PUBSYNC GATE — phase 9 W1 (week 42) — ci/pubsync_gate.sh
#
#   Pipeline stage 42, LAST: بوابة النشر — سطح حمل الأدلة خارجاً. أدلة
#   الأسابيع 34–41 عاشت في build/ (متجاهَل في git) في بيئة أُعيد
#   تدويرها إلى الصفر مرتين — والحزمة المنشورة الوحيدة كانت الأسبوع 33.
#   docs/PHASE9-PLAN.md يشتق الأسبوع: ما تنشره البيئة يصير مدخلاً
#   مُحكَماً به — الأرشيف يُحزَم بـEvAudit.pack (الاشتقاق الواحد)،
#   يُزوَّد حتمياً (لا طوابع زمن)، يُرفع إلى الإصدار المنشور، ثم
#   **جولة الذهاب والعودة**: البايتات المنشورة تُجلب من جديد، sha256
#   يُعاد اشتقاقه من المنزَّل لا من البيان، الأرشيف المنزَّل يُدقَّق
#   معزولاً بـEvAudit.check نفسها، والرقعة التراكمية من آخر حالة
#   منشورة (v2.7-week33) تُثبَّت أنها تُطبَّق على قاعدة المنشور.
#
#     1. المراساة أولاً (المرجع = المنشور): هوية الأسبوع (83 / 3.6)
#        مقابل المانيفست الحي، والاعتماد شرط مغلقة-الفشل — بلا مفتاح
#        البوابة تفشل (publish.credential) ولا نشر بلا اعتماد ولا تظاهر.
#     2. الحزم والتزويد الحتمي والرقعة: EvAudit.pack ← PubSync.zip
#        (مرتان = بايتات متطابقة — قانون الحتمية يقاس لا يُفترض) ←
#        git diff من وسم آخر حالة منشورة ← البيان (بيان نشر بلا
#        طوابع زمن).
#     3. الرفع وجولة الذهاب والعودة: DELETE+POST لنفس الاسم (إضافة
#        أحادية — أصول الأسبوع 33 تبقى)، الجلب من جديد، PubSync.verify
#        على المنزَّل، EvAudit.check على الأرشيف المنزَّل، وتطبيق
#        الرقعة يُتحقق منه على نسخة محلية من قاعدة المنشور.
#     4. اثنتا عشرة سلبة بوسمها الحرفي على نسخ متسخة مؤقتة — ومنها
#        المزوِّر المتسق (بيان معاد ختمه) وzip-slip المصنوع يدوياً
#        والعضو المقطوع — ثم الأخضر يُعاد.
#
#   Verdict: `PUBSYNC_OK checks=N negatives=12/12 assets=2 members=M
#   proofs=P rel=R base=T vc=83 vn=3.6` / `PUBSYNC_FAILED <tag>` (exit 1).
#   members/proofs أحادية النمو (حدود لا تساوٍ — تحصين W40). المفاتيح
#   تُربَد idempotently (تحصين W33) وتحملها CLOSE_KEEP. الإثبات السلبي
#   المحفوظ: ci/pubsync-negative-proof.log.
set -uo pipefail
cd "$(dirname "$0")/.."

fail() { echo "PUBSYNC_FAILED $1"; exit 1; }
STATE=build/release/gates.env
LOGF=build/pipeline.log
OUTD=build/pubsync/tooling
PUBDIR=build/pubsync
REL=434872239
REPO=CTO-DRS/neokey-hark
WEEK=42
BASE_TAG=v2.7-week33
EV_NAME="neokey-evidence-week${WEEK}.zip"
PATCH_NAME="neokey-patch-week33-42.patch"
CHECKS=0
ck() { CHECKS=$((CHECKS + 1)); [ "$1" = "0" ] || fail "$2 (${3:-})"; }

# ---- 0: المراساة + الاعتماد — المرجع = المنشور --------------------------------
PUB_VC=83
PUB_VN=3.6
VC=$(grep -o 'versionCode="[0-9]*"' AndroidManifest.xml | grep -o '[0-9]*')
VN=$(grep -o 'versionName="[^"]*"' AndroidManifest.xml | cut -d'"' -f2)
[ "$VC" = "$PUB_VC" ] || fail "anchor.identity.vc (manifest declares $VC — the publication week is $PUB_VC)"
[ "$VN" = "$PUB_VN" ] || fail "anchor.identity.vn (manifest declares $VN — the publication week is $PUB_VN)"
TOKEN_FILE="${PUBSYNC_TOKEN_FILE:-}"
if [ -z "$TOKEN_FILE" ] || [ ! -f "$TOKEN_FILE" ] || [ ! -s "$TOKEN_FILE" ]; then
  fail "publish.credential (PUBSYNC_TOKEN_FILE missing/empty — no publication without the owner's credential; the weekly flow exports it, docs/PHASE9-PLAN.md §3)"
fi
TOKEN=$(tr -d ' \r\n' < "$TOKEN_FILE")
[ -n "$TOKEN" ] || fail "publish.credential (empty token file)"
command -v python3 >/dev/null 2>&1 || fail "publish.tooling (python3 absent — the API client, house precedent ci/build_deploy.sh)"
command -v curl >/dev/null 2>&1 || fail "publish.tooling (curl absent)"
CHECKS=$((CHECKS + 4))

# ---- 1: الترجمة — الوحدة + المدقق في مجلد مؤقت ---------------------------------
rm -rf "$OUTD"; mkdir -p "$OUTD" "$PUBDIR"
ECJ="java -jar tools/ecj.jar -nowarn -source 8 -target 8 -encoding UTF-8"
command -v javac >/dev/null 2>&1 && ECJ="javac -nowarn -encoding UTF-8"
$ECJ -cp . -d "$OUTD" tests/com/hark/neokey/SyncServerPack.java tests/com/hark/neokey/EvAudit.java tests/com/hark/neokey/PubSync.java 2>&1 | grep -v "^$" | head -5
ck "$?" 0 "compile.tooling"
[ -f "$OUTD/com/hark/neokey/PubSync.class" ] || fail "compile.tooling (PubSync.class missing)"
CHECKS=$((CHECKS + 1))

# ---- 2: الحزمة (الاشتقاق الواحد) + التزويد الحتمي + الرقعة ---------------------
PACK_OUT=$(java -cp "$OUTD" com.hark.neokey.EvAudit pack "$PUBDIR/archive" build/pipeline.log build/release ci 2>&1) || fail "pack.evaudit ($PACK_OUT)"
echo "[pubsync] $PACK_OUT"
PACK_LINE=$(printf '%s\n' "$PACK_OUT" | grep -oE 'EVAUDIT_PACK_OK [^"]*' | head -1)
MEM=$(printf '%s' "$PACK_LINE" | sed -E 's/.* members=([0-9]+).*/\1/')
PRF=$(printf '%s' "$PACK_LINE" | grep -oE 'proofs=[0-9]+(/[0-9]+)?' | cut -d= -f2); PRF=${PRF:-0}
case "${MEM:-}" in ''|*[!0-9]*) fail "pack.members ($PACK_LINE)";; esac
CHECKS=$((CHECKS + 2))

java -cp "$OUTD" com.hark.neokey.PubSync zip "$PUBDIR/archive" "$PUBDIR/$EV_NAME" || fail "zip.evidence"
java -cp "$OUTD" com.hark.neokey.PubSync zip "$PUBDIR/archive" "$PUBDIR/${EV_NAME}.again.zip" || fail "zip.determinism_run"
Z1=$(sha256sum "$PUBDIR/$EV_NAME" | cut -d' ' -f1)
Z2=$(sha256sum "$PUBDIR/${EV_NAME}.again.zip" | cut -d' ' -f1)
[ "$Z1" = "$Z2" ] || fail "zip.determinism (two zips of one archive must be byte-identical)"
rm -f "$PUBDIR/${EV_NAME}.again.zip"
CHECKS=$((CHECKS + 2))

git diff "$BASE_TAG" HEAD > "$PUBDIR/$PATCH_NAME" || fail "patch.generate"
[ -s "$PUBDIR/$PATCH_NAME" ] || fail "patch.generate (empty diff — the base must differ from HEAD)"
CHECKS=$((CHECKS + 1))

# ---- 3: البيان — بلا طوابع زمن، والهاش يُشتق من الملف --------------------------
EV_SHA=$(sha256sum "$PUBDIR/$EV_NAME" | cut -d' ' -f1)
PATCH_SHA=$(sha256sum "$PUBDIR/$PATCH_NAME" | cut -d' ' -f1)
cat > "$PUBDIR/statement.json" <<EOF
{
  "schema": "neokey-pubsync-1",
  "week": "$WEEK",
  "rel": "$REL",
  "base_tag": "$BASE_TAG",
  "vc": "$PUB_VC",
  "vn": "$PUB_VN",
  "asset_name1": "$EV_NAME",
  "asset_sha1": "$EV_SHA",
  "asset_name2": "$PATCH_NAME",
  "asset_sha2": "$PATCH_SHA"
}
EOF
rm -rf "$PUBDIR/stage"; mkdir -p "$PUBDIR/stage"
cp "$PUBDIR/$EV_NAME" "$PUBDIR/$PATCH_NAME" "$PUBDIR/stage/"
java -cp "$OUTD" com.hark.neokey.PubSync verify "$PUBDIR/statement.json" "$PUBDIR/stage" || fail "statement.local"
CHECKS=$((CHECKS + 2))

# ---- 4: الرفع — إضافة أحادية (أصول الأسبوع 33 تبقى) ----------------------------
python3 - "$TOKEN" "$REPO" "$REL" "$PUBDIR" "$EV_NAME" "$PATCH_NAME" <<'PY' || fail "publish.upload (see the API output above)"
import json, sys, urllib.request

token, repo, rel, pubdir, ev_name, patch_name = sys.argv[1:7]
base = f"https://api.github.com/repos/{repo}/releases/{rel}"
upl = f"https://uploads.github.com/repos/{repo}/releases/{rel}/assets"
H = {"Authorization": f"token {token}", "User-Agent": "neokey-pubsync", "Accept": "application/vnd.github+json"}

def api(url, method="GET", data=None, headers=None):
    req = urllib.request.Request(url, data=data, method=method)
    for k, v in (headers or H).items():
        req.add_header(k, v)
    with urllib.request.urlopen(req) as r:
        raw = r.read()
        return (json.loads(raw) if raw and r.headers.get("Content-Type", "").startswith(("application/json", "text/json")) else raw)

def find_asset(name):
    url = f"{base}/assets?per_page=100"
    while url:
        js = api(url)
        for a in js:
            if a["name"] == name:
                return a
        url = None if not js else js[0].get("url") and None
        # pagination (rel-next) — the release is small; two pages max
        if js and "links" not in js:
            break
    return None

def upload(path, name, ctype):
    data = open(path, "rb").read()
    req = urllib.request.Request(f"{upl}?name={name}", data=data, method="POST")
    req.add_header("Authorization", f"token {token}")
    req.add_header("Content-Type", ctype)
    req.add_header("Content-Length", str(len(data)))
    req.add_header("User-Agent", "neokey-pubsync")
    with urllib.request.urlopen(req) as r:
        return json.loads(r.read())

for path, name, ctype in ((f"{pubdir}/{ev_name}", ev_name, "application/zip"),
                          (f"{pubdir}/{patch_name}", patch_name, "text/plain; charset=utf-8")):
    old = find_asset(name)
    if old is not None:
        api(f"{base}/assets/{old['id']}", method="DELETE")
    a = upload(path, name, ctype)
    if a.get("name") != name or a.get("state") not in ("uploaded", "start_upload"):
        print(f"PUBSYNC_FAILED publish.upload (asset {name}: name={a.get('name')} state={a.get('state')})")
        sys.exit(1)
    print(f"[pubsync] uploaded {name} id={a['id']} size={a.get('size')}")
print("PUBSYNC_UPLOAD_OK")
PY
CHECKS=$((CHECKS + 1))

# ---- 5: جولة الذهاب والعودة — المنزَّل هو الذي يُتحقق منه ----------------------
rm -rf "$PUBDIR/roundtrip"; mkdir -p "$PUBDIR/roundtrip"
python3 - "$TOKEN" "$REPO" "$REL" "$PUBDIR/roundtrip" "$EV_NAME" "$PATCH_NAME" <<'PY' || fail "roundtrip.download (the published bytes must be fetchable back)"
import json, sys, urllib.request

token, repo, rel, outdir, ev_name, patch_name = sys.argv[1:7]
base = f"https://api.github.com/repos/{repo}/releases/{rel}"
H = {"Authorization": f"token {token}", "User-Agent": "neokey-pubsync"}

def api_json(url):
    req = urllib.request.Request(url)
    for k, v in H.items():
        req.add_header(k, v)
    req.add_header("Accept", "application/vnd.github+json")
    with urllib.request.urlopen(req) as r:
        return json.loads(r.read())

want = {ev_name, patch_name}
got = 0
url = f"{base}/assets?per_page=100"
while url and got < len(want):
    js = api_json(url)
    for a in js:
        if a["name"] in want:
            req = urllib.request.Request(a["url"])
            for k, v in H.items():
                req.add_header(k, v)
            req.add_header("Accept", "application/octet-stream")
            with urllib.request.urlopen(req) as r:
                data = r.read()
            if len(data) != a["size"]:
                print(f"PUBSYNC_FAILED roundtrip.download ({a['name']}: fetched {len(data)} != declared {a['size']})")
                sys.exit(1)
            open(f"{outdir}/{a['name']}", "wb").write(data)
            got += 1
    url = None
if got != len(want):
    print(f"PUBSYNC_FAILED roundtrip.stale (fetched {got} of {len(want)} declared assets — the release does not carry this week's publication)")
    sys.exit(1)
print("PUBSYNC_ROUNDTRIP_OK")
PY
java -cp "$OUTD" com.hark.neokey.PubSync verify "$PUBDIR/statement.json" "$PUBDIR/roundtrip" || fail "roundtrip.sha (the re-derived sha of the PUBLISHED bytes must equal the statement — a tampered or partial upload bites here)"
CHECKS=$((CHECKS + 2))

# ---- 6: التدقيق المعزول + تطبيق الرقعة على قاعدة المنشور -----------------------
rm -rf "$PUBDIR/roundtrip-archive"
java -cp "$OUTD" com.hark.neokey.PubSync unzip "$PUBDIR/roundtrip/$EV_NAME" "$PUBDIR/roundtrip-archive" || fail "evidence.unzip"
AOUT=$(java -cp "$OUTD" com.hark.neokey.EvAudit check "$PUBDIR/roundtrip-archive" --expect-vc "$PUB_VC" --expect-vn "$PUB_VN" 2>&1); ARC=$?
[ "$ARC" = "0" ] || fail "evidence.audit (the PUBLISHED archive must verify in isolation — EvAudit's own laws; got rc=$ARC: $(printf '%s' "$AOUT" | tail -1))"
echo "[pubsync] $AOUT" | tail -1
CHECKS=$((CHECKS + 1))

rm -rf "$PUBDIR/base"
git clone -q --no-hardlinks . "$PUBDIR/base" 2>/dev/null || fail "patch.base_clone"
git -C "$PUBDIR/base" checkout -q "$BASE_TAG" 2>/dev/null || fail "patch.base_checkout ($BASE_TAG)"
git -C "$PUBDIR/base" apply --check "$(pwd)/$PUBDIR/roundtrip/$PATCH_NAME" 2>&1 | head -3
ck "$?" 0 "patch.apply" "the cumulative patch must apply to the published base"
git diff --stat "$BASE_TAG" HEAD | tail -1 > "$PUBDIR/patch-stat.txt"
CHECKS=$((CHECKS + 1))

# ---- 7: الاثنتا عشرة سلبة — بوسمها الحرفي على نسخ متسخة مؤقتة -------------------
neg() { # $1 tag expected, $2.. the command
  local want="$1"; shift
  local out rc
  out=$("$@" 2>&1); rc=$?
  case "$out" in *"PUBSYNC_FAILED $want"*) NEG_PASS=$((NEG_PASS+1));;
    *) echo "NEG-MISS want=$want rc=$rc out: $(printf '%s' "$out" | tail -2)"; NEG_FAIL=$((NEG_FAIL+1));; esac
}
NEG_PASS=0; NEG_FAIL=0
mkst() { cp "$PUBDIR/statement.json" "$PUBDIR/$1"; }

mkst n1.json; sed -i 's/neokey-pubsync-1/neokey-pubsync-9/' "$PUBDIR/n1.json"
neg "pubsync.statement_schema" java -cp "$OUTD" com.hark.neokey.PubSync verify "$PUBDIR/n1.json" "$PUBDIR/stage"

mkst n2.json; sed -i 's/"vc": "83"/"vc": "99"/; s/"vn": "3.6"/"vn": "9.9"/' "$PUBDIR/n2.json"
neg "pubsync.statement_identity" java -cp "$OUTD" com.hark.neokey.PubSync verify "$PUBDIR/n2.json" "$PUBDIR/stage"

mkst n3.json; sed -i "s/$EV_NAME/neokey-evidence-week41.zip/" "$PUBDIR/n3.json"
neg "pubsync.name_identity" java -cp "$OUTD" com.hark.neokey.PubSync verify "$PUBDIR/n3.json" "$PUBDIR/stage"

mkst n4.json; sed -i '/"asset_sha1"/d' "$PUBDIR/n4.json"
neg "pubsync.sha_missing" java -cp "$OUTD" com.hark.neokey.PubSync verify "$PUBDIR/n4.json" "$PUBDIR/stage"

mkst n5.json; sed -i "s/\"asset_sha1\": \"$EV_SHA\"/\"asset_sha1\": \"$(printf '0%.0s' {1..64})\"/" "$PUBDIR/n5.json"
neg "pubsync.sha_mismatch" java -cp "$OUTD" com.hark.neokey.PubSync verify "$PUBDIR/n5.json" "$PUBDIR/stage"

rm -rf "$PUBDIR/dl-missing"; mkdir -p "$PUBDIR/dl-missing"; cp "$PUBDIR/roundtrip/$PATCH_NAME" "$PUBDIR/dl-missing/"
neg "pubsync.asset_missing" java -cp "$OUTD" com.hark.neokey.PubSync verify "$PUBDIR/statement.json" "$PUBDIR/dl-missing"

rm -rf "$PUBDIR/dl-extra"; mkdir -p "$PUBDIR/dl-extra"
cp "$PUBDIR/roundtrip/$EV_NAME" "$PUBDIR/roundtrip/$PATCH_NAME" "$PUBDIR/dl-extra/"; echo planted > "$PUBDIR/dl-extra/planted.bin"
neg "pubsync.extra_asset" java -cp "$OUTD" com.hark.neokey.PubSync verify "$PUBDIR/statement.json" "$PUBDIR/dl-extra"

mkst n8.json; sed -i 's/"rel": "434872239"/"rel": "not-a-number"/' "$PUBDIR/n8.json"
neg "pubsync.rel_shape" java -cp "$OUTD" com.hark.neokey.PubSync verify "$PUBDIR/n8.json" "$PUBDIR/stage"

mkst n9.json; sed -i "s/\"base_tag\": \"$BASE_TAG\"/\"base_tag\": \"week-thirty-three\"/" "$PUBDIR/n9.json"
neg "pubsync.base_tag_shape" java -cp "$OUTD" com.hark.neokey.PubSync verify "$PUBDIR/n9.json" "$PUBDIR/stage"

python3 - "$PUBDIR/slip.zip" <<'PY'
import zipfile, sys
with zipfile.ZipFile(sys.argv[1], "w") as z:
    zi = zipfile.ZipInfo("../evil.txt", date_time=(1990, 1, 1, 0, 0, 2))
    z.writestr(zi, "planted")
PY
neg "unzip.path_traversal" java -cp "$OUTD" com.hark.neokey.PubSync unzip "$PUBDIR/slip.zip" "$PUBDIR/slip-out"

CZIP="$PUBDIR/corrupt.zip"; cp "$PUBDIR/roundtrip/$EV_NAME" "$CZIP"
python3 - "$CZIP" <<'PY'
import sys
p = sys.argv[1]
b = bytearray(open(p, "rb").read())
for off in (35, 45, 55, 65, 75):
    if off < len(b):
        b[off] ^= 0x5A
open(p, "wb").write(bytes(b))
PY
n11out() { java -cp "$OUTD" com.hark.neokey.PubSync unzip "$CZIP" "$PUBDIR/corrupt-out"; }
o=$(n11out 2>&1); rc=$?
case "$o" in *"unzip.path_traversal"*|*"unzip.member_truncated"*|*"unzip.zip_format"*|*PUBSYNC_FAILED*unzip.*) NEG_PASS=$((NEG_PASS+1));;
  *) [ "$rc" != "0" ] && { echo "NEG-MISS want=unzip.* rc=$rc out: $(printf '%s' "$o" | tail -2)"; NEG_FAIL=$((NEG_FAIL+1)); } || { echo "NEG-MISS want=unzip.* (rc=0)"; NEG_FAIL=$((NEG_FAIL+1)); };; esac

rm -rf "$PUBDIR/dirty-archive"; cp -r "$PUBDIR/roundtrip-archive" "$PUBDIR/dirty-archive"
DIRTY_MEMBER=$(ls "$PUBDIR/dirty-archive" | grep -v SHA256SUMS | head -1)
rm "$PUBDIR/dirty-archive/$DIRTY_MEMBER"
aout=$(java -cp "$OUTD" com.hark.neokey.EvAudit check "$PUBDIR/dirty-archive" --expect-vc "$PUB_VC" --expect-vn "$PUB_VN" 2>&1); arc=$?
[ "$arc" != "0" ] && NEG_PASS=$((NEG_PASS+1)) || { echo "NEG-MISS want=evidence.audit (the archive audit went green on a truncated member)"; NEG_FAIL=$((NEG_FAIL+1)); }

java -cp "$OUTD" com.hark.neokey.PubSync verify "$PUBDIR/statement.json" "$PUBDIR/roundtrip" || fail "negatives.restored (the real roundtrip must verify green after the battery)"
[ "$NEG_FAIL" = "0" ] || fail "negatives ($NEG_PASS/12 — every negative must bite with its own tag)"
CHECKS=$((CHECKS + 2))

# ---- 8: الدفتر — upsert idempotent + الطازج == الدفتر + قانون السجل الخارجي ----
[ -f "$STATE" ] || : > "$STATE"
sed -i '/^PUBSYNC_/d' "$STATE"
VERDICT="PUBSYNC_OK checks=$CHECKS negatives=$NEG_PASS/12 assets=2 members=$MEM proofs=$PRF rel=$REL base=$BASE_TAG vc=$PUB_VC vn=$PUB_VN"
{
  echo "PUBSYNC_GATE=OK"
  echo "PUBSYNC_CHECKS=$CHECKS"
  echo "PUBSYNC_NEGATIVES=$NEG_PASS/12"
  echo "PUBSYNC_ASSETS=2"
  echo "PUBSYNC_MEMBERS=$MEM"
  echo "PUBSYNC_PROOFS=$PRF"
  echo "PUBSYNC_REL=$REL"
  echo "PUBSYNC_BASE=$BASE_TAG"
  echo "PUBSYNC_WEEK=$WEEK"
  echo "PUBSYNC_VC=$PUB_VC"
  echo "PUBSYNC_VN=$PUB_VN"
  echo "PUBSYNC_PACK_LINE=$PACK_LINE"
} >> "$STATE"
. "$STATE"
[ "${PUBSYNC_CHECKS:-}" = "$CHECKS" ] || fail "env.pubsync_checks"
[ "${PUBSYNC_MEMBERS:-}" = "$MEM" ] || fail "env.pubsync_members"
[ "${PUBSYNC_VC:-}" = "$PUB_VC" ] || fail "env.pubsync_vc"
CHECKS=$((CHECKS + 3))
mask() { printf '%s' "$1" | sed -E 's/members=[0-9]+/members=M/; s/proofs=[0-9/]+/proofs=P/'; }
LOG_LINE=$(grep -oE 'PUBSYNC_OK [^"]*' "$LOGF" 2>/dev/null | head -1 || true)
if [ -n "$LOG_LINE" ]; then
  [ "$(mask "$LOG_LINE")" = "$(mask "$VERDICT")" ] || fail "outer_log.pubsync (a prior PUBSYNC_OK line drifted from fresh — verdict fields are stable, members/proofs are monotonic)"
fi
CHECKS=$((CHECKS + 1))

echo "$VERDICT"
