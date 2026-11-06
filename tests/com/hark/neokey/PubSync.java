package com.hark.neokey;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * PubSync — المرحلة 9 W1 (الأسبوع 42): بوابة النشر — سطح حمل الأدلة خارجاً.
 *
 * سطر إغلاق المرحلة 8 يقول إن المالك يخطو خطواته الموثقة «بناءً على
 * الأدلة»، وقانون W39 يقول إن الأدلة يجب أن تُتحقق **قبل** الخطوة —
 * لكن نسخة الأدلة الوحيدة المنشورة كانت حزمة الأسبوع 33، وأدلة
 * الأسابيع 34–41 تعيش في build/ (متجاهَل في git) في بيئة أُعيد
 * تدويرها إلى الصفر مرتين. W1 يجعل **النشر نفسه** مدخلاً مُحكَماً
 * به: الأرشيف يُحزَم بـEvAudit.pack (قانون الاشتقاق الواحد)، يُزوَّد
 * حتمياً، يُرفع إلى الإصدار المنشور، ثم **جولة الذهاب والعودة**:
 * البايتات المنشورة تُجلب من جديد وsha256 يعاد اشتقاقها من المنزَّل،
 * والأرشيف المنزَّل يُدقَّق معزولاً بـEvAudit.check نفسها، والرقعة
 * التراكمية من آخر حالة منشورة تُثبَّت أنها تُطبَّق على قاعدة المنشور.
 *
 * العقد (نقي JVM، صفر android.*، صفر تبعيات — يعمل بجوار المالك):
 *   • `zip <archiveDir> <outZip>` — تزويد حتمي: المداخل مرتبة بالاسم،
 *     توقيت المداخل مثبَّت، لا طوابع زمن — تزويدان لنفس الأرشيف
 *     = بايتات متطابقة (قانون الحتمية).
 *   • `unzip <zip> <outDir>` — فك حتمي يرفض traversal في أسماء
 *     المداخل (zip-slip) والعضو المقطوع (crc/حجم المركزي).
 *   • `verify <statement.json> <downloadDir>` — قوانين البيان +
 *     الجولة: شكل البيان حرفي، الهوية == SyncServerPack (المصدر
 *     الواحد)، أسماء الأصول تحمل الأسبوع، كل sha256 معلَن يُعاد
 *     اشتقاقه من الملف **المنزَّل** بايت-لطرفاً، لا عضو زائر، ورقم
 *     الإصدار وشكل وسم القاعدة مطابقان.
 *
 * الرفضات اثنا عشر بوسمها الحرفي. الحكم: `PUBSYNC_OK` /
 * `PUBSYNC_FAILED <tag>` (exit 1).
 */
public class PubSync {

    /* ---- الوسوم الحرفية للرفضات (اثنا عشر) ---- */
    static final String E_SCHEMA = "pubsync.statement_schema";
    static final String E_IDENTITY = "pubsync.statement_identity";
    static final String E_WEEKNAME = "pubsync.name_identity";
    static final String E_SHA_MISSING = "pubsync.sha_missing";
    static final String E_SHA_MISMATCH = "pubsync.sha_mismatch";
    static final String E_ASSET_MISSING = "pubsync.asset_missing";
    static final String E_EXTRA_ASSET = "pubsync.extra_asset";
    static final String E_TRAVERSAL = "unzip.path_traversal";
    static final String E_TRUNCATED = "unzip.member_truncated";
    static final String E_REL_SHAPE = "pubsync.rel_shape";
    static final String E_BASE_SHAPE = "pubsync.base_tag_shape";
    static final String E_FORMAT = "pubsync.zip_format";

    static final String SCHEMA = "neokey-pubsync-1";
    /** توقيت المداخل المثبَّت — 1981-01-01T00:00:02Z (أدنى قيمة معيارية
     *  آمنة لجميع الأدوات؛ dos epoch + هامش). */
    static final long FIXED_DOS_TIME = ((1981 - 1980) << 25) | (1 << 21);

    /** حقل ← قيمة من بيان JSON مسطّح (نصّ البيان الذي تكتبه البوابة —
     *  سطر لكل حقل بالشكل "key": "value"). */
    static Map<String, String> flat(String json) {
        Map<String, String> m = new LinkedHashMap<>();
        Matcher f = Pattern.compile("\"([a-zA-Z0-9_]+)\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        while (f.find()) m.put(f.group(1), f.group(2));
        return m;
    }

    static String sha256(Path p) throws IOException {
        try (InputStream in = Files.newInputStream(p)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IOException("sha256 " + p + ": " + e.getMessage(), e);
        }
    }

    /* ---- التزويد الحتمي ---- */

    /** تزويد حتمي: المداخل مرتبة بالاسم، التوقيت مثبَّت، DEFLATED بمستوى
     *  ثابت. نفس الأرشيف = نفس البايتات في كل تشغيل. */
    public static void zip(Path archiveDir, Path outZip) throws IOException {
        List<Path> files = new ArrayList<>();
        try (java.util.stream.Stream<Path> s = Files.walk(archiveDir)) {
            s.filter(Files::isRegularFile).map(archiveDir::relativize).sorted(Comparator.comparing(Path::toString)).forEach(files::add);
        }
        Files.createDirectories(outZip.getParent() == null ? Paths.get(".") : outZip.getParent());
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(outZip))) {
            z.setLevel(9);
            z.setMethod(ZipOutputStream.DEFLATED);
            for (Path rel : files) {
                ZipEntry e = new ZipEntry(rel.toString().replace('\\', '/'));
                e.setTime(631180800000L + 2000L);  // 1990-01-01T00:00:02Z ثابت
                z.putNextEntry(e);
                z.write(Files.readAllBytes(archiveDir.resolve(rel)));
                z.closeEntry();
            }
        }
    }

    /** فك حتمي مع رفض zip-slip والعضو المقطوع. */
    public static void unzip(Path zipPath, Path outDir) throws IOException {
        if (!Files.isRegularFile(zipPath)) fail(E_FORMAT, zipPath + " missing");
        try (ZipFile zf = new ZipFile(zipPath.toFile())) {
            java.util.Enumeration<? extends ZipEntry> entries = zf.entries();
            List<ZipEntry> list = new ArrayList<>();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String name = e.getName();
                if (name.startsWith("/") || name.contains("../") || name.contains("..\\") || name.contains(":"))
                    fail(E_TRAVERSAL, name);
                list.add(e);
            }
            Files.createDirectories(outDir);
            for (ZipEntry e : list) {
                Path out = outDir.resolve(e.getName()).normalize();
                if (!out.startsWith(outDir.normalize())) fail(E_TRAVERSAL, e.getName());
                if (e.isDirectory()) { Files.createDirectories(out); continue; }
                Files.createDirectories(out.getParent() == null ? outDir : out.getParent());
                try (InputStream in = zf.getInputStream(e)) {
                    long declared = e.getSize();
                    long n = (declared >= 0)
                            ? copyExact(in, out, declared)
                            : Files.copy(in, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    if (declared >= 0 && n != declared) fail(E_TRUNCATED, e.getName());
                    long crc = crc32(out);
                    if (e.getCrc() >= 0 && crc != e.getCrc()) fail(E_TRUNCATED, e.getName() + " crc");
                }
            }
        } catch (java.util.zip.ZipException | java.io.EOFException ze) {
            fail(E_TRUNCATED, String.valueOf(ze.getMessage()));
        } catch (IOException io) {
            if (io.getCause() instanceof java.util.zip.ZipException) fail(E_TRUNCATED, String.valueOf(io.getCause().getMessage()));
            throw io;
        } catch (Exception e) {
            fail(E_FORMAT, String.valueOf(e.getMessage()));
        }
    }

    private static long copyExact(InputStream in, Path out, long declared) throws IOException {
        byte[] buf = new byte[8192];
        long total = 0;
        try (OutputStream o = Files.newOutputStream(out)) {
            int n;
            while (total < declared && (n = in.read(buf, 0, (int) Math.min(buf.length, declared - total))) > 0) {
                o.write(buf, 0, n);
                total += n;
            }
        }
        return total;
    }

    private static long crc32(Path p) throws IOException {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        byte[] buf = new byte[8192];
        try (InputStream in = Files.newInputStream(p)) {
            int n;
            while ((n = in.read(buf)) > 0) crc.update(buf, 0, n);
        }
        return crc.getValue();
    }

    /* ---- قوانين البيان والجولة ---- */

    /** `verify <statement.json> <downloadDir>` — البيان + الجولة. */
    public static void verify(Path statement, Path downloadDir) throws IOException {
        if (!Files.isRegularFile(statement)) fail(E_SCHEMA, statement + " missing");
        Map<String, String> m = flat(Files.readString(statement, StandardCharsets.UTF_8));
        if (!SCHEMA.equals(m.get("schema"))) fail(E_SCHEMA, String.valueOf(m.get("schema")));
        String vc = m.get("vc"), vn = m.get("vn");
        if (!SyncServerPack.VC.equals(vc) || !SyncServerPack.VN.equals(vn))
            fail(E_IDENTITY, "statement says vc=" + vc + " vn=" + vn + " — the week is " + SyncServerPack.VC + " / " + SyncServerPack.VN);
        String week = m.get("week");
        if (week == null || !week.matches("[0-9]+")) fail(E_WEEKNAME, String.valueOf(week));
        String rel = m.get("rel");
        if (rel == null || !rel.matches("[0-9]+")) fail(E_REL_SHAPE, String.valueOf(rel));
        String base = m.get("base_tag");
        if (base == null || !base.matches("v[0-9]+\\.[0-9]+-week[0-9]+")) fail(E_BASE_SHAPE, String.valueOf(base));

        // أسماء الأصول تحمل الأسبوع (المرجع = المنشور على سطح النشر)
        List<String> names = new ArrayList<>();
        Matcher an = Pattern.compile("\"asset_name([0-9]*)\"\\s*:\\s*\"([^\"]+)\"").matcher(Files.readString(statement, StandardCharsets.UTF_8));
        while (an.find()) names.add(an.group(2));
        if (names.isEmpty()) fail(E_SCHEMA, "no asset_name fields");
        boolean evName = false, patchName = false;
        for (String n : names) {
            if (n.startsWith("neokey-evidence-week" + week + "-") || n.equals("neokey-evidence-week" + week + ".zip")) evName = true;
            if (n.matches("neokey-patch-week[0-9]+-" + week + "\\.patch")) patchName = true;
        }
        if (!evName || !patchName) fail(E_WEEKNAME, names.toString());

        // كل أصل معلَن: حاضر في المنزَّل + sha256 يعاد اشتقاقه بايت-لطرفاً
        List<String> declared = new ArrayList<>(names);
        List<String> declaredSha = new ArrayList<>();
        Matcher sn = Pattern.compile("\"asset_sha([0-9]*)\"\\s*:\\s*\"([0-9a-f]{64})\"").matcher(Files.readString(statement, StandardCharsets.UTF_8));
        while (sn.find()) declaredSha.add(sn.group(2));
        if (declaredSha.size() != declared.size()) fail(E_SHA_MISSING, declared.size() + " names vs " + declaredSha.size() + " shas");
        for (int i = 0; i < declared.size(); i++) {
            Path f = downloadDir.resolve(declared.get(i));
            if (!Files.isRegularFile(f)) fail(E_ASSET_MISSING, declared.get(i));
            String got = sha256(f);
            if (!got.equals(declaredSha.get(i))) fail(E_SHA_MISMATCH, declared.get(i) + " published " + got + " != declared " + declaredSha.get(i));
        }
        // لا عضو زائر في مجلد الجولة
        try (java.util.stream.Stream<Path> s = Files.list(downloadDir)) {
            for (Path p : s.toList()) {
                if (!declared.contains(p.getFileName().toString())) fail(E_EXTRA_ASSET, p.getFileName().toString());
            }
        }
    }

    static void fail(String tag, String detail) {
        System.out.println("PUBSYNC_FAILED " + tag + " (" + detail + ")");
        System.exit(1);
    }

    public static void main(String[] args) throws Exception {
        if (args.length >= 3 && "zip".equals(args[0])) { zip(Paths.get(args[1]), Paths.get(args[2])); return; }
        if (args.length >= 3 && "unzip".equals(args[0])) { unzip(Paths.get(args[1]), Paths.get(args[2])); return; }
        if (args.length >= 3 && "verify".equals(args[0])) { verify(Paths.get(args[1]), Paths.get(args[2])); return; }
        System.out.println("PUBSYNC_FAILED usage (zip|unzip|verify)");
        System.exit(1);
    }
}
