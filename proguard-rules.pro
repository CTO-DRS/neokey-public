# NeoKey — R8 FULL MODE rules (roadmap Phase 0, item C: الحماية ضد الهندسة العكسية — الطبقة الأولى)
# Used by build.sh (USE_R8=1) and release.yml. Full mode is the R8 default here:
# shrink unused code + rename everything not pinned below + archive mapping.txt.
#
# What must survive obfuscation, and why:
#
# 1) Manifest components — the SYSTEM instantiates these by class name
#    (AndroidManifest.xml keeps textual names; renames would crash on launch):
-keep public class com.hark.neokey.SetupActivity { public <init>(); }
-keep public class com.hark.neokey.ClipEditActivity { public <init>(); }
-keep public class com.hark.neokey.WriterActivity { public <init>(); }
-keep public class com.hark.neokey.DictActivity { public <init>(); }
-keep public class com.hark.neokey.ClipIOActivity { public <init>(); }
-keep public class com.hark.neokey.BackupActivity { public <init>(); }
-keep public class com.hark.neokey.SyncActivity { public <init>(); }
-keep public class com.hark.neokey.PerfActivity { public <init>(); }
-keep public class com.hark.neokey.StoreActivity { public <init>(); }
-keep public class com.hark.neokey.NeoKeyService { public <init>(); }
-keep public class com.hark.neokey.BankWatch { public <init>(); }
# Lifecycle callbacks (onCreate/onStartInputView/...) override library virtuals —
# R8 keeps those automatically; only the reflected <init> needed the explicit rule.

# 2) Crash diagnostics (Perf writes filesDir/last_crash.txt): keep line numbers so a
#    reported stack stays symbolizable against the archived mapping.txt, while real
#    file names are masked behind a constant:
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 3) android.jar hides java.lang.invoke (framework-internal); R8 desugars lambdas
#    itself, so the missing reference is expected noise:
-dontwarn java.lang.invoke.**

# Not needed and deliberately absent:
# - no -keep for data classes: ClipIO/Backup write JSON by hand (no reflection on fields)
# - no -dontobfuscate: obfuscation is the point
# - no resource rules: resources live in the APK (aapt2), not in the dex

# 3) Phase 3 store week: dex budget bite — the store pulled ThemePack/Economy
#    into the reachable set (+13.4KB). Shrink access bridges and flatten package
#    names for everything not pinned by a -keep rule (manifest components keep
#    their names; behaviour unchanged, documented in docs/R8.md).
-allowaccessmodification
-repackageclasses ''
