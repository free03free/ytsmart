package com.example.l2sbuilder;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.*;

/** فاحص متكامل: يقرأ المشروع والـ SDK ويعطي تقريرًا (مشاكل/تحذيرات/نصائح) مع إصلاحات آمنة قابلة للتراجع. */
final class ProjectDoctor {
    private ProjectDoctor() {}

    static final int OK = 0, INFO = 1, WARN = 2, BAD = 3;

    static final class Finding {
        final int level; final String title, detail;
        String fixId, fixLabel, fixArg;
        Finding(int level, String title, String detail) { this.level = level; this.title = title; this.detail = detail; }
        Finding fix(String id, String label, String arg) { fixId = id; fixLabel = label; fixArg = arg; return this; }
    }

    static final class Report {
        File root; String agp, agpWhere, wrapper, effGradle; int jdk, maxCompile, maxInstalled;
        boolean effInstalled;
        final List<Finding> items = new ArrayList<>();
        int count(int lvl) { int n = 0; for (Finding f : items) if (f.level == lvl) n++; return n; }
        int verdict() { return count(BAD) > 0 ? BAD : count(WARN) > 0 ? WARN : OK; }
        String verdictText() {
            int v = verdict();
            return v == BAD ? "✘ غير متوافق: سيفشل البناء غالبًا" : v == WARN ? "⚠ متوافق مع تحذيرات" : "✔ متوافق: لا مشاكل ظاهرة";
        }
        String asText(String name) {
            StringBuilder sb = new StringBuilder("== تقرير فحص المشروع: ").append(name).append(" ==\n");
            sb.append(verdictText()).append("\n");
            sb.append("AGP: ").append(agp == null ? "؟" : agp).append(" | Gradle المعتمد: ").append(effGradle == null ? "؟" : effGradle)
              .append(" | JDK: ").append(jdk).append("\n\n");
            String[] names = {"✔", "ℹ", "⚠", "✘"};
            for (int lvl = BAD; lvl >= OK; lvl--)
                for (Finding f : items) if (f.level == lvl)
                    sb.append(names[lvl]).append(" ").append(f.title).append("\n   ").append(f.detail.replace("\n", "\n   ")).append("\n");
            sb.append("\nملاحظة: الفحص ثابت (يقرأ ملفات البناء)، ولا يغطي المكتبات والاعتماديات ولا يضمن نجاح البناء.\n");
            return sb.toString();
        }
    }

    // ---------------- ملفات ----------------
    private static final Set<String> SKIP = new HashSet<>(Arrays.asList("build", ".git", ".gradle", "node_modules", ".idea", ".cxx"));

    static File projectRoot(File dir) {
        Deque<File> q = new ArrayDeque<>(); q.add(dir);
        Map<File, Integer> depth = new HashMap<>(); depth.put(dir, 0);
        while (!q.isEmpty()) {
            File d = q.poll();
            if (new File(d, "settings.gradle").exists() || new File(d, "settings.gradle.kts").exists()) return d;
            int dd = depth.get(d);
            File[] ks = d.listFiles();
            if (ks == null || dd >= 3) continue;
            Arrays.sort(ks);
            for (File k : ks) if (k.isDirectory() && !SKIP.contains(k.getName())) { depth.put(k, dd + 1); q.add(k); }
        }
        return dir;
    }

    static List<File> files(File root, String... names) {
        List<File> out = new ArrayList<>();
        Set<String> want = new HashSet<>(Arrays.asList(names));
        walk(root, 0, want, out);
        return out;
    }

    private static void walk(File d, int depth, Set<String> want, List<File> out) {
        File[] ks = d.listFiles();
        if (ks == null || depth > 6) return;
        Arrays.sort(ks);
        for (File k : ks) {
            if (k.isDirectory()) { if (!SKIP.contains(k.getName())) walk(k, depth + 1, want, out); }
            else if (want.contains(k.getName())) out.add(k);
        }
    }

    static String read(File f) {
        try { if (f.length() > 2_000_000) return ""; return new String(Compat.readAllBytes(f), StandardCharsets.UTF_8); }
        catch (Exception e) { return ""; }
    }

    private static List<File> buildFiles(File root) { return files(root, "build.gradle", "build.gradle.kts"); }
    private static List<File> allGradle(File root) { return files(root, "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts", "libs.versions.toml"); }

    private static String rel(File root, File f) {
        String a = root.getAbsolutePath(), b = f.getAbsolutePath();
        return b.startsWith(a) ? b.substring(a.length()).replaceFirst("^/", "") : f.getName();
    }

    private static List<Integer> ints(String txt, String regex) {
        List<Integer> r = new ArrayList<>();
        Matcher m = Pattern.compile(regex).matcher(txt);
        while (m.find()) { try { r.add(Integer.parseInt(m.group(1))); } catch (Exception ignored) {} }
        return r;
    }

    static List<Integer> installedApis(File sdk) {
        List<Integer> r = new ArrayList<>();
        File[] ds = new File(sdk, "platforms").listFiles();
        if (ds != null) for (File d : ds) {
            Matcher m = Pattern.compile("^android-(\\d+)(?:\\.\\d+)?$").matcher(d.getName());
            if (m.find()) r.add(Integer.parseInt(m.group(1)));
        }
        Collections.sort(r);
        return r;
    }

    private static boolean hasPlatform(File sdk, int api) {
        return new File(sdk, "platforms/android-" + api + "/android.jar").exists()
                || new File(sdk, "platforms/android-" + api + ".0/android.jar").exists();
    }

    static String wrapperVersion(File root) {
        List<File> w = files(root, "gradle-wrapper.properties");
        if (w.isEmpty()) return null;
        Matcher m = Pattern.compile("gradle-([0-9.]+)-(?:bin|all)").matcher(read(w.get(0)));
        return m.find() ? m.group(1) : null;
    }

    // ---------------- الفحص ----------------
    static Report analyze(File proj, File sdk, File gh, int jdk, String effGradle, boolean effInstalled) {
        Report r = new Report();
        File root = projectRoot(proj);
        r.root = root; r.jdk = jdk; r.effGradle = effGradle; r.effInstalled = effInstalled;
        List<File> builds = buildFiles(root), gradles = allGradle(root);
        StringBuilder allTxt = new StringBuilder();
        for (File g : gradles) allTxt.append(read(g)).append("\n");
        String all = allTxt.toString();

        // ملفات الإعداد الخارجية: اكتشاف مبكر لملفات apply-from المفقودة، خصوصًا
        // عندما يكون المشروع مستخرجًا من ZIP ناقص أو مستودعًا لم تُجلب وحداته الفرعية.
        for (String missing : missingSettingsScripts(root)) {
            r.items.add(new Finding(BAD, "ملف إعداد مطلوب غير موجود: " + missing,
                    "يشير settings.gradle إلى هذا الملف. أعد استيراد نسخة كاملة من المشروع مع المجلدات الفرعية المطلوبة؛ لا تنشئ ملفًا فارغًا بدلًا منه لأنّه قد يحتوي إعدادات أساسية."));
        }

        // AGP
        r.agp = GradleAdvisor.detectAgp(root);
        r.wrapper = wrapperVersion(root);
        String agp = r.agp;
        if (agp == null) {
            r.items.add(new Finding(WARN, "لم أستطع قراءة إصدار AGP",
                    "لم أجد com.android.application بإصدار صريح في ملفات البناء. قد يكون معرَّفًا بطريقة غير معتادة، فلن تعمل التوصيات التلقائية."));
        } else {
            String need = GradleAdvisor.minGradleFor(agp);
            if (need == null) r.items.add(new Finding(INFO, "AGP " + agp + " خارج جدولي المعروف",
                    "لا أعرف متطلبات هذا الإصدار بدقة، فالتوصيات هنا تقريبية."));
            else r.items.add(new Finding(OK, "AGP " + agp, "يحتاج Gradle ≥ " + need + " وJDK ≥ " + GradleAdvisor.minJdkFor(agp) + "."));
        }
        String need = GradleAdvisor.minGradleFor(agp);

        // Gradle
        if (effGradle != null && need != null) {
            if (GradleAdvisor.cmp(effGradle, need) < 0) {
                r.items.add(new Finding(BAD, "Gradle المعتمد (" + effGradle + ") أقدم مما يطلبه AGP",
                        "AGP " + agp + " يحتاج Gradle ≥ " + need + ".").fix("SETGRADLE", "اعتمد Gradle " + need, need));
            } else if (num(effGradle) > num(need) + 0 && num(effGradle) >= 9 && num(agp) < 9) {
                r.items.add(new Finding(WARN, "Gradle " + effGradle + " أحدث بكثير من AGP " + agp,
                        "فروع Gradle 9.x تُحذف فيها واجهات قديمة يعتمد عليها AGP 8.x، وقد يفشل الإعداد. الأسلم Gradle " + need + ".")
                        .fix("SETGRADLE", "اعتمد Gradle " + need, need));
            } else {
                r.items.add(new Finding(OK, "Gradle " + effGradle + " مناسب", "يلبي أقل إصدار يطلبه AGP (" + need + ")."));
            }
            if (!effInstalled) r.items.add(new Finding(WARN, "Gradle " + effGradle + " غير منزَّل", "سيحتاج تنزيلًا (إنترنت) قبل البناء أو أثناءه. نزّله من «مدير Gradle»."));
        }
        if (r.wrapper != null && need != null && GradleAdvisor.cmp(r.wrapper, need) < 0) {
            r.items.add(new Finding(WARN, "wrapper المشروع (" + r.wrapper + ") أقدم من المطلوب",
                    "gradle-wrapper.properties يشير إلى " + r.wrapper + " بينما AGP يحتاج ≥ " + need + ".").fix("WRAPPER", "حدّث wrapper إلى " + need, need));
        }

        // JDK
        if (agp != null && jdk > 0) {
            int req = GradleAdvisor.minJdkFor(agp);
            if (jdk < req) r.items.add(new Finding(BAD, "JDK " + jdk + " أقل من المطلوب", "AGP " + agp + " يحتاج JDK ≥ " + req + "."));
            else if (num(agp) == 7 || (num(agp) == 8 && minor(agp) < 2)) {
                if (jdk >= 21) r.items.add(new Finding(WARN, "AGP قديم مع JDK " + jdk,
                        "AGP أقدم من 8.2 يفشل غالبًا مع JDK 21 (أخطاء jlink/jdk-image). الأفضل رفع AGP إلى 8.2 فأعلى."));
            }
        }

        // SDK
        List<Integer> apis = installedApis(sdk);
        r.maxInstalled = apis.isEmpty() ? 0 : apis.get(apis.size() - 1);
        List<Integer> comps = new ArrayList<>(), tgts = new ArrayList<>();
        Set<String> bts = new LinkedHashSet<>();
        for (File b : builds) {
            String t = read(b);
            comps.addAll(ints(t, "compileSdk(?:Version)?\\s*[=( ]\\s*(?:release\\()?(\\d+)"));
            tgts.addAll(ints(t, "targetSdk(?:Version)?\\s*[=( ]\\s*(\\d+)"));
            Matcher m = Pattern.compile("buildToolsVersion\\s*[=( ]?\\s*[\"']([0-9.]+)[\"']").matcher(t);
            while (m.find()) bts.add(m.group(1));
        }
        for (int c : new TreeSet<>(comps)) {
            r.maxCompile = Math.max(r.maxCompile, c);
            if (!hasPlatform(sdk, c)) {
                r.items.add(new Finding(BAD, "منصة compileSdk " + c + " غير مثبتة",
                        "الموجود في الـ SDK: " + (apis.isEmpty() ? "لا شيء" : apis.toString()) + ". نزّلها من «مدير SDK» أو غيّر compileSdk.")
                        .fix("SDKMGR", "فتح مدير SDK", null));
            } else if (agp != null && GradleAdvisor.maxApiFor(agp) > 0 && c > GradleAdvisor.maxApiFor(agp)) {
                r.items.add(new Finding(WARN, "compileSdk " + c + " أحدث مما يدعمه AGP " + agp,
                        "AGP " + agp + " مختبر حتى API " + GradleAdvisor.maxApiFor(agp) + ". يلزم AGP " + GradleAdvisor.minAgpForApi(c) + " فأعلى للأمان."));
            } else {
                r.items.add(new Finding(OK, "compileSdk " + c, "المنصة مثبتة ومدعومة."));
            }
        }
        if (comps.isEmpty()) r.items.add(new Finding(INFO, "لم أجد compileSdk", "قد يكون معرَّفًا عبر متغيّر أو ملف خارجي."));
        if (!tgts.isEmpty() && !comps.isEmpty()) {
            int mt = Collections.max(tgts), mc = Collections.max(comps);
            if (mt > mc) r.items.add(new Finding(BAD, "targetSdk " + mt + " أعلى من compileSdk " + mc, "ارفع compileSdk أو اخفض targetSdk."));
        }
        if (!tgts.isEmpty() && Collections.min(tgts) < 28)
            r.items.add(new Finding(INFO, "targetSdk " + Collections.min(tgts) + " قديم", "التطبيق سيعمل، لكن أندرويد الحديث يطبّق قيودًا توافقية عليه، ومتجر Google Play يرفضه."));
        if (agp != null && GradleAdvisor.maxApiFor(agp) > 0 && r.maxInstalled > GradleAdvisor.maxApiFor(agp)) {
            String up = GradleAdvisor.minAgpForApi(r.maxInstalled);
            String best = null;   // أحدث AGP مخزَّن يدعم هذه المنصة
            for (String c : GradleAdvisor.cachedAgps(gh)) if (GradleAdvisor.maxApiFor(c) >= r.maxInstalled) { best = c; break; }
            String base = "هذه هي حالة الخطأ NumberFormatException \"37.0\" في lintVital. التطبيق يستبعد lintVital تلقائيًا فلا يفشل البناء بسببها.\n";
            Finding f;
            if (best != null) {
                f = new Finding(WARN, "منصة API " + r.maxInstalled + " مثبتة وأحدث من AGP " + agp,
                        base + "حل جذري: غيّر AGP إلى " + best + " (مخزَّن على جهازك ويدعم هذه المنصة)، أو احذف المنصة من «مدير SDK».")
                        .fix("SETAGP", "غيّر إلى AGP " + best + " (مخزَّن)", best);
            } else {
                f = new Finding(WARN, "منصة API " + r.maxInstalled + " مثبتة وأحدث من AGP " + agp,
                        base + "حل جذري: احذف المنصة من «مدير SDK»، أو استخدم AGP " + up + " فأعلى (لا يوجد منه إصدار مخزَّن حاليًا؛ نزّل واحدًا من قائمة الإصدارات في الأسفل).")
                        .fix("AGPLIST", "اذهب إلى قائمة إصدارات AGP", null);
            }
            r.items.add(f);
        }
        for (String b : bts) if (!new File(sdk, "build-tools/" + b).isDirectory())
            r.items.add(new Finding(WARN, "build-tools " + b + " غير مثبتة", "المشروع يثبّتها صراحةً. احذف buildToolsVersion من ملف البناء أو ثبّتها."));

        // namespace (AGP 8+)
        if (agp != null && num(agp) >= 8) {
            for (File b : builds) {
                String t = read(b);
                if (Pattern.compile("com\\.android\\.(application|library)").matcher(t).find() && !t.contains("namespace")
                        && !Pattern.compile("(?m)^\\s*plugins\\s*\\{[^}]*apply\\s+false").matcher(t).find()) {
                    String pkg = manifestPackage(b.getParentFile());
                    Finding f = new Finding(BAD, "namespace مفقود في " + rel(root, b),
                            "AGP 8+ يرفض البناء بلا namespace في ملف build.gradle" + (pkg != null ? ". الحزمة في المانيفست: " + pkg : "."));
                    if (pkg != null) f.fix("NAMESPACE", "أضف namespace = " + pkg, rel(root, b));
                    r.items.add(f);
                }
            }
        }

        // مستودعات
        if (!all.contains("google()") && !all.contains("maven.google.com") && !all.contains("dl.google.com"))
            r.items.add(new Finding(BAD, "مستودع google() غير موجود", "بدونه لن يجد Gradle مكتبات AndroidX ولا AGP. أضف google() في settings.gradle (pluginManagement وdependencyResolutionManagement) أو في buildscript."));
        if (all.contains("jcenter()"))
            r.items.add(new Finding(WARN, "jcenter() مستخدم", "المستودع أُغلق ويبطئ البناء أو يفشله. يُستبدل بـ mavenCentral().").fix("JCENTER", "استبدل jcenter() بـ mavenCentral()", null));

        // gradle.properties
        List<File> gps = files(root, "gradle.properties");
        String gp = gps.isEmpty() ? "" : read(gps.get(0));
        boolean usesAndroidX = all.contains("androidx.") || all.contains("com.google.android.material");
        if (usesAndroidX && !Pattern.compile("(?m)^\\s*android\\.useAndroidX\\s*=\\s*true").matcher(gp).find() && agp != null && num(agp) < 9)
            r.items.add(new Finding(BAD, "android.useAndroidX غير مفعّل", "المشروع يستخدم مكتبات AndroidX ولا يفعّل الخيار في gradle.properties.").fix("ANDROIDX", "فعّل useAndroidX", null));
        if (Pattern.compile("(?m)^\\s*android\\.enableJetifier\\s*=\\s*true").matcher(gp).find())
            r.items.add(new Finding(INFO, "Jetifier مفعّل", "يبطئ البناء ويزيد الذاكرة. عطّله إن كانت كل مكتباتك تدعم AndroidX."));
        Matcher xm = Pattern.compile("-Xmx(\\d+)([mMgG])").matcher(gp);
        if (xm.find()) {
            long mb = Long.parseLong(xm.group(1)) * (xm.group(2).equalsIgnoreCase("g") ? 1024 : 1);
            if (mb < 768 && agp != null && num(agp) >= 8)
                r.items.add(new Finding(WARN, "ذاكرة Gradle صغيرة (" + mb + "MB)", "AGP 8+ يحتاج عادةً 1GB فأكثر، وإلا ظهر OutOfMemoryError في R8 والموارد."));
        }

        // Kotlin / AGP 9
        if (agp != null && num(agp) >= 9 && all.contains("org.jetbrains.kotlin.android"))
            r.items.add(new Finding(WARN, "AGP 9 مع مكوّن kotlin-android", "AGP 9 يدمج دعم Kotlin داخله، وقد يتعارض المكوّن القديم أو يحتاج إعدادًا إضافيًا. راجع ملاحظات الترحيل."));
        if (agp != null && num(agp) >= 9)
            r.items.add(new Finding(INFO, "AGP 9: تغييرات في DSL", "قد تحتاج سكربتات البناء القديمة تعديلات (خيارات محذوفة أو أسماء جديدة). التغيير إلى 9.x ليس مجرد رقم."));

        // مخزن AGP
        if (agp != null) {
            boolean cached = GradleAdvisor.agpCached(gh, agp);
            if (!cached) r.items.add(new Finding(INFO, "AGP " + agp + " غير مخزّن", "أول بناء سيحتاج إنترنتًا لتنزيله."));
        }

        long free = new File(gh.getParentFile() == null ? gh : gh.getParentFile(), ".").getUsableSpace();
        if (free > 0 && free < 1_000_000_000L) r.items.add(new Finding(WARN, "المساحة الحرة قليلة", "أقل من 1GB، وقد يفشل البناء أو تنزيل Gradle."));

        if (r.verdict() == OK) r.items.add(new Finding(INFO, "نصيحة", "احتفظ بنسخة احتياطية قبل أي ترقية كبيرة. كل تعديل من هذه الشاشة يُحفظ له نسخة ويمكن التراجع عنه."));
        return r;
    }

    /**
     * Finds literal apply-from script paths in settings.gradle(.kts) that do not exist.
     * Relative paths are resolved against the project root, as Gradle does for settings scripts.
     */
    static List<String> missingSettingsScripts(File projectDir) {
        List<String> missing = new ArrayList<>();
        File root = projectRoot(projectDir);
        for (String name : new String[]{"settings.gradle", "settings.gradle.kts"}) {
            File settings = new File(root, name);
            if (!settings.isFile()) continue;
            String source = read(settings);
            Pattern[] patterns = {
                Pattern.compile("(?m)\\bapply\\s+from\\s*:\\s*[\"']([^\"']+)[\"']"),
                Pattern.compile("(?m)\\bapply\\s*\\(\\s*from\\s*=\\s*[\"']([^\"']+)[\"']\\s*\\)")
            };
            for (Pattern pattern : patterns) {
                Matcher matcher = pattern.matcher(source);
                while (matcher.find()) {
                    String path = matcher.group(1).trim();
                    // لا نفترض مسارًا محليًا عند استخدام تعبيرات/متغيرات أو عنوان URL.
                    if (path.isEmpty() || path.contains("${") || path.contains("$rootDir") || path.contains("$settingsDir")
                            || path.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*")) continue;
                    File target = new File(root, path);
                    if (!target.isFile()) {
                        String rel = path.replace('\\', '/');
                        if (!missing.contains(rel)) missing.add(rel);
                    }
                }
            }
        }
        return missing;
    }

    private static int num(String s) { try { return Integer.parseInt(s.replaceAll("\\D.*$", "")); } catch (Exception e) { return 0; } }
    private static int minor(String agp) { String[] p = agp.split("\\."); return p.length > 1 ? num(p[1]) : 0; }

    private static String manifestPackage(File moduleDir) {
        File m = new File(moduleDir, "src/main/AndroidManifest.xml");
        Matcher mm = Pattern.compile("package\\s*=\\s*\"([A-Za-z0-9_.]+)\"").matcher(read(m));
        return mm.find() ? mm.group(1) : null;
    }

    // ---------------- نسخ احتياطي وتراجع ----------------
    static final class Backup {
        final File dir; final File root; final Set<String> saved = new HashSet<>();
        Backup(File base, File root) {
            this.root = root;
            this.dir = new File(base, new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()));
        }
        void save(File f) throws IOException {
            String rp = rel(root, f);
            if (!saved.add(rp)) return;
            File dst = new File(dir, "files/" + rp);
            dst.getParentFile().mkdirs();
            if (f.exists()) Compat.copy(f, dst);
            else Compat.write(new File(dir, "files/" + rp + ".l2s-new"), new byte[0]);
            Compat.write(new File(dir, "root.txt"), root.getAbsolutePath().getBytes(StandardCharsets.UTF_8));
        }
    }

    /** يعيد آخر نسخة احتياطية لهذا المشروع، ويحذفها بعد التراجع. */
    static String undoLast(File base) {
        File[] ds = base.listFiles(File::isDirectory);
        if (ds == null || ds.length == 0) return null;
        Arrays.sort(ds);
        File last = ds[ds.length - 1];
        String rootPath = read(new File(last, "root.txt")).trim();
        if (rootPath.isEmpty()) return null;
        File root = new File(rootPath);
        int n = 0;
        List<File> fs = new ArrayList<>();
        collect(new File(last, "files"), fs);
        for (File f : fs) {
            String rp = f.getAbsolutePath().substring(new File(last, "files").getAbsolutePath().length() + 1);
            try {
                if (rp.endsWith(".l2s-new")) { new File(root, rp.substring(0, rp.length() - 8)).delete(); n++; }
                else { File dst = new File(root, rp); dst.getParentFile().mkdirs(); Compat.copy(f, dst); n++; }
            } catch (IOException ignored) {}
        }
        rmTree(last);
        return "أُعيد " + n + " ملف إلى حالته قبل آخر تعديل (" + last.getName() + ").";
    }

    private static void collect(File d, List<File> out) {
        File[] ks = d.listFiles();
        if (ks != null) for (File k : ks) { if (k.isDirectory()) collect(k, out); else out.add(k); }
    }
    private static void rmTree(File f) {
        File[] ks = f.listFiles();
        if (ks != null) for (File k : ks) rmTree(k);
        f.delete();
    }

    private static void write(File f, String s) throws IOException { Compat.write(f, s.getBytes(StandardCharsets.UTF_8)); }

    // ---------------- الإصلاحات ----------------
    private static final Pattern[] AGP_PATTERNS = {
            Pattern.compile("(com\\.android\\.tools\\.build:gradle:)(\\d+\\.\\d+(?:\\.\\d+)?)"),
            Pattern.compile("(com\\.android\\.(?:application|library)[\"']\\)?\\s*version\\s*[\"'])(\\d+\\.\\d+(?:\\.\\d+)?)"),
            Pattern.compile("((?m)^\\s*(?:agp|androidGradlePlugin|android-gradle-plugin|androidGradle)\\s*=\\s*\")(\\d+\\.\\d+(?:\\.\\d+)?)"),
            Pattern.compile("(com\\.android\\.(?:application|library)\"\\s*,\\s*version\\s*=\\s*\")(\\d+\\.\\d+(?:\\.\\d+)?)")};

    /** يغيّر إصدار AGP في كل مكان معرَّف فيه + wrapper إن لزم. يعيد وصفًا لما تغيّر. */
    static String changeAgp(File root, String target, Backup bk) throws IOException {
        StringBuilder log = new StringBuilder();
        for (File f : allGradle(root)) {
            String t = read(f), n = t;
            for (Pattern p : AGP_PATTERNS) {
                Matcher m = p.matcher(n);
                StringBuffer sb = new StringBuffer();
                while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + target));
                m.appendTail(sb);
                n = sb.toString();
            }
            if (!n.equals(t)) { bk.save(f); write(f, n); log.append("• ").append(rel(root, f)).append(": AGP → ").append(target).append("\n"); }
        }
        String need = GradleAdvisor.minGradleFor(target), wv = wrapperVersion(root);
        if (need != null && wv != null && GradleAdvisor.cmp(wv, need) < 0) log.append(setWrapper(root, need, bk));
        if (log.length() == 0) log.append("لم أجد موضعًا لتغييره (AGP معرَّف بطريقة غير مدعومة).\n");
        return log.toString();
    }

    /** تغيير AGP مؤقت للبناء: يحفظ نسخة .l2s-orig بجانب كل ملف (إن لم توجد) لتستعيدها TargetApplier.restore. */
    static String changeAgpTemp(File root, String target) throws IOException {
        StringBuilder log = new StringBuilder();
        for (File f : allGradle(root)) {
            String t = read(f), n = t;
            for (Pattern p : AGP_PATTERNS) {
                Matcher m = p.matcher(n);
                StringBuffer sb = new StringBuffer();
                while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + target));
                m.appendTail(sb);
                n = sb.toString();
            }
            if (!n.equals(t)) {
                File bak = new File(f.getParentFile(), f.getName() + ".l2s-orig");
                if (!bak.exists()) write(bak, t);
                write(f, n);
                log.append("✔ AGP ").append(target).append(" ← ").append(rel(root, f)).append("\n");
            }
        }
        if (log.length() == 0) log.append("⚠ AGP المعتمد (").append(target).append("): لم أجد موضعًا لتغييره أو هو مطبَّق أصلًا.\n");
        return log.toString();
    }

    static String setWrapper(File root, String ver, Backup bk) throws IOException {
        List<File> w = files(root, "gradle-wrapper.properties");
        if (w.isEmpty()) return "";
        File f = w.get(0);
        String t = read(f);
        String line = "distributionUrl=" + GradleAdvisor.urlFor(ver).replace(":", "\\:");
        String n = Pattern.compile("(?m)^distributionUrl=.*$").matcher(t).replaceAll(Matcher.quoteReplacement(line));
        if (n.equals(t)) return "";
        bk.save(f); write(f, n);
        return "• " + rel(root, f) + ": Gradle → " + ver + "\n";
    }

    static String applyFix(File root, String id, String arg, Backup bk) throws IOException {
        switch (id) {
            case "JCENTER": {
                StringBuilder log = new StringBuilder();
                for (File f : allGradle(root)) {
                    String t = read(f);
                    if (t.contains("jcenter()")) { bk.save(f); write(f, t.replace("jcenter()", "mavenCentral()")); log.append("• ").append(rel(root, f)).append("\n"); }
                }
                return log.toString();
            }
            case "WRAPPER": return setWrapper(root, arg, bk);
            case "ANDROIDX": {
                List<File> gps = files(root, "gradle.properties");
                File f = gps.isEmpty() ? new File(root, "gradle.properties") : gps.get(0);
                String t = read(f);
                bk.save(f);
                String n = Pattern.compile("(?m)^\\s*android\\.useAndroidX\\s*=.*$").matcher(t).find()
                        ? t.replaceAll("(?m)^\\s*android\\.useAndroidX\\s*=.*$", "android.useAndroidX=true")
                        : t + (t.isEmpty() || t.endsWith("\n") ? "" : "\n") + "android.useAndroidX=true\n";
                write(f, n);
                return "• " + rel(root, f) + "\n";
            }
            case "NAMESPACE": {
                File f = new File(root, arg);
                String pkg = manifestPackage(f.getParentFile());
                if (pkg == null) return "تعذّر: لا package في المانيفست.\n";
                String t = read(f);
                boolean kts = f.getName().endsWith(".kts");
                Matcher m = Pattern.compile("(?m)^(\\s*)android\\s*\\{").matcher(t);
                if (!m.find()) return "تعذّر: لم أجد كتلة android { في الملف.\n";
                String ins = m.group(0) + "\n" + m.group(1) + "    namespace" + (kts ? " = \"" : " '") + pkg + (kts ? "\"" : "'");
                bk.save(f);
                write(f, t.substring(0, m.start()) + ins + t.substring(m.end()));
                return "• " + rel(root, f) + ": namespace " + pkg + "\n";
            }
            default: return "";
        }
    }
}
