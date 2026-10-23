package com.example.l2sbuilder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** محلل مساحة ذكي: يحسب الأحجام ويشرح دور كل مكوّن ودرجة أهميته، دون واجهة (منطق فقط). */
final class StorageScanner {
    static final int ESSENTIAL = 0, IMPORTANT = 1, OPTIONAL = 2, SAFE = 3;

    static final class Item {
        String title = "", role = "", note = "", consequence = "";
        long bytes;
        int level = OPTIONAL;
        List<File> deleteTargets = new ArrayList<>();
        boolean contentsOnly;          // احذف محتويات المجلد وأبقِ المجلد نفسه
        String debPkg;                 // اسم حزمة Debian (يُحذف عبر apt)
        long debKb;
        boolean debEssential;
        String debPriority = "";
        int debDependents;
    }

    static final class Section {
        String icon = "", title = "", intro = "";
        long bytes;
        List<Item> items = new ArrayList<>();
    }

    // ---------------------------------------------------------------- أدوات
    static long size(File f) {
        try {
            if (f == null || !f.exists()) return 0;
            return Math.max(0, Compat.dirSize(f, null));
        } catch (Throwable ignored) { return 0; }
    }

    private static String read(File f) {
        try { return new String(Compat.readAllBytes(f), StandardCharsets.UTF_8); } catch (Throwable e) { return ""; }
    }

    private static File[] dirs(File d) {
        File[] r = d.listFiles(File::isDirectory);
        if (r == null) return new File[0];
        java.util.Arrays.sort(r, (a, b) -> a.getName().compareTo(b.getName()));
        return r;
    }

    private static Item item(String title, long bytes, int level, String role, String note) {
        Item i = new Item();
        i.title = title; i.bytes = bytes; i.level = level; i.role = role; i.note = note;
        return i;
    }

    private static Item deletable(Item i, String consequence, boolean contentsOnly, File... targets) {
        i.consequence = consequence;
        i.contentsOnly = contentsOnly;
        Collections.addAll(i.deleteTargets, targets);
        return i;
    }

    private static Section section(String icon, String title, String intro, List<Item> items) {
        Section s = new Section();
        s.icon = icon; s.title = title; s.intro = intro;
        Collections.sort(items, (a, b) -> Long.compare(b.bytes, a.bytes));
        s.items = items;
        for (Item i : items) s.bytes += i.bytes;
        return s;
    }

    // ------------------------------------------------- استخدام المشاريع الحالية
    static final class Usage {
        Map<String, List<String>> compileSdk = new HashMap<>();
        Map<String, List<String>> buildTools = new HashMap<>();
        Map<String, List<String>> agp = new HashMap<>();
        Map<String, List<String>> gradle = new HashMap<>();
        int projects;
    }

    private static void put(Map<String, List<String>> m, String k, String proj) {
        List<String> l = m.get(k);
        if (l == null) { l = new ArrayList<>(); m.put(k, l); }
        if (!l.contains(proj)) l.add(proj);
    }

    private static final Pattern P_COMPILE = Pattern.compile("compileSdk(?:Version)?\\s*[=( ]\\s*(?:release\\()?(\\d+)");
    private static final Pattern P_BT = Pattern.compile("buildToolsVersion\\s*[=( ]?\\s*[\"']([0-9.]+)[\"']");
    private static final Pattern P_AGP1 = Pattern.compile("com\\.android\\.(?:application|library)['\"]\\s*version\\s*['\"]([0-9][0-9.]*)['\"]");
    private static final Pattern P_AGP2 = Pattern.compile("com\\.android\\.tools\\.build:gradle:([0-9][0-9.]*)");
    private static final Pattern P_AGP3 = Pattern.compile("(?m)^\\s*agp\\s*=\\s*\"([0-9][0-9.]*)\"");
    private static final Pattern P_GRADLE = Pattern.compile("gradle-([0-9][0-9.]*)-(?:bin|all)\\.zip");

    static Usage detectUsage(File filesDir) {
        Usage u = new Usage();
        File[] ps = new File(filesDir, "projects").listFiles(File::isDirectory);
        if (ps == null) return u;
        for (File p : ps) {
            u.projects++;
            String n = p.getName();
            List<File> files = new ArrayList<>();
            for (String nm : new String[]{"build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
                    "gradle/wrapper/gradle-wrapper.properties", "gradle/libs.versions.toml"})
                files.add(new File(p, nm));
            File[] sub = p.listFiles(File::isDirectory);
            if (sub != null) for (File s : sub) { files.add(new File(s, "build.gradle")); files.add(new File(s, "build.gradle.kts")); }
            for (File f : files) {
                if (!f.isFile() || f.length() > 400000) continue;
                String t = read(f);
                Matcher m = P_COMPILE.matcher(t); while (m.find()) put(u.compileSdk, m.group(1), n);
                m = P_BT.matcher(t); while (m.find()) put(u.buildTools, m.group(1), n);
                m = P_AGP1.matcher(t); while (m.find()) put(u.agp, m.group(1), n);
                m = P_AGP2.matcher(t); while (m.find()) put(u.agp, m.group(1), n);
                m = P_AGP3.matcher(t); while (m.find()) put(u.agp, m.group(1), n);
                m = P_GRADLE.matcher(t); while (m.find()) put(u.gradle, m.group(1), n);
            }
        }
        return u;
    }

    /** إصدار build-tools الافتراضي لكل AGP (تقدير من الوثائق الرسمية). */
    static String defaultBuildTools(String agp) {
        if (agp.startsWith("8.0") || agp.startsWith("8.1")) return "33.0.1";
        if (agp.startsWith("7.")) return "30.0.3";
        if (agp.startsWith("8.2") || agp.startsWith("8.3") || agp.startsWith("8.4") || agp.startsWith("8.5") || agp.startsWith("8.6")) return "34.0.0";
        if (agp.startsWith("8.7") || agp.startsWith("8.8") || agp.startsWith("8.9") || agp.startsWith("8.10") || agp.startsWith("8.11") || agp.startsWith("8.12")) return "35.0.0";
        return null;
    }

    private static String names(List<String> l) {
        if (l == null || l.isEmpty()) return "";
        List<String> s = l.size() > 3 ? l.subList(0, 3) : l;
        return android_join(s) + (l.size() > 3 ? " و" + (l.size() - 3) + " آخرين" : "");
    }

    private static String android_join(List<String> l) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < l.size(); i++) { if (i > 0) b.append("، "); b.append(l.get(i)); }
        return b.toString();
    }

    // ----------------------------------------------------------------- المسح
    static List<Section> scan(File filesDir, File cacheDir, File extDir, File rootfs) {
        List<Section> out = new ArrayList<>();
        Usage us = detectUsage(filesDir);
        out.add(overview(filesDir, cacheDir, extDir, us));
        out.add(sdkSection(new File(filesDir, "sdk"), us));
        out.add(gradleSection(new File(filesDir, "gradle-home"), us));
        if (rootfs != null) {
            out.add(rootfsSection(rootfs));
            Section d = debianSection(rootfs);
            if (d != null) out.add(d);
        }
        return out;
    }

    // ----- نظرة عامة على المجلدات الرئيسية
    private static Section overview(File fd, File cache, File ext, Usage us) {
        List<Item> l = new ArrayList<>();
        Map<String, Object[]> known = new LinkedHashMap<>();
        known.put("rootfs", new Object[]{"نظام Debian", ESSENTIAL, "نظام لينكس كامل يعمل داخل التطبيق ويشغّل Java وGradle.", "بدونه لا يعمل أي بناء."});
        known.put("sdk", new Object[]{"Android SDK", ESSENTIAL, "ملفات android.jar وأدوات البناء التي يترجم بها Gradle تطبيقك.", "بدونه لا يعمل أي بناء."});
        known.put("gradle-home", new Object[]{"Gradle (توزيعات ومكتبات)", IMPORTANT, "برنامج Gradle نفسه والمكتبات التي نزّلها (AGP وغيرها).", "يمكن إعادة تنزيله لكن يلزم إنترنت."});
        known.put("tools", new Object[]{"أدوات L2S (proot وaapt2)", ESSENTIAL, "برامج صغيرة يشغّل بها التطبيق Debian ويبني الموارد.", "بدونها لا يعمل التطبيق."});
        known.put("android-home", new Object[]{"مفتاح التوقيع (.android)", ESSENTIAL, "مفتاح debug.keystore الذي توقَّع به تطبيقاتك.", "حذفه يغيّر توقيع تطبيقاتك فلا تُحدَّث فوق القديمة."});
        known.put("projects", new Object[]{"مشاريعك", ESSENTIAL, "الكود المصدري لمشاريعك. هذا عملك الأصلي.", "حذفه يفقدك مشاريعك."});
        known.put("signing", new Object[]{"خزنة مفاتيح التوقيع", ESSENTIAL, "مفاتيحك المحفوظة في مركز التوقيع وملفات APK الموقَّعة منها.", "حذفها يضيّع مفاتيحك نهائيًا فلا تستطيع تحديث تطبيقاتك. صدّر نسخة مشفّرة قبل أي حذف."});
        known.put("backup", new Object[]{"النسخ الاحتياطية الداخلية", IMPORTANT, "لقطات من مشاريعك (آخر 8 لكل مشروع) ونسخ قديمة من المكوّنات.", "يمكن حذف الأقدم من شاشة النسخ الاحتياطية."});
        known.put("local-ai", new Object[]{"Local AI (نماذج llama.cpp)", OPTIONAL, "نماذج GGUF وبرنامج llama-cli وسجلات المحادثة.", "تفقد النماذج المحمّلة (تعيد تنزيلها لاحقًا)."});
        known.put("staging", new Object[]{"مجلد استيراد مؤقت", SAFE, "بقايا عملية استيراد.", "لا شيء."});
        File[] tops = fd.listFiles();
        Set<String> seen = new HashSet<>();
        if (tops != null) for (File t : tops) {
            String n = t.getName();
            seen.add(n);
            long b = size(t);
            if (b < 512 * 1024L) continue;
            Object[] k = known.get(n);
            Item i;
            if (k != null) {
                i = item((String) k[0], b, (Integer) k[1], (String) k[2], (String) k[3]);
                if (n.equals("projects") && us.projects > 0) i.note = us.projects + " مشروع. " + i.note;
                if (n.equals("local-ai")) {
                    long m = size(new File(t, "models"));
                    i.note = "النماذج: " + fmt(m) + ". " + i.note;
                    i.consequence = "سيُحذف كل ما في Local AI (النماذج والمحادثات).";
                    i.deleteTargets.add(t);
                }
                if (n.equals("staging")) { i.consequence = "حذف المجلد المؤقت."; i.deleteTargets.add(t); }
            } else if (n.startsWith("trash-")) {
                i = item("بقايا استرجاع (" + n + ")", b, SAFE, "نسخة قديمة بقيت بعد استرجاع نسخة احتياطية.", "لا تؤثر على العمل.");
                i.consequence = "حذف البقايا."; i.deleteTargets.add(t);
            } else {
                i = item(n, b, OPTIONAL, "مجلد غير معروف للمحلل.", "لا أستطيع الجزم بدوره.");
            }
            l.add(i);
        }
        long cb = size(cache);
        if (cb > 512 * 1024L) l.add(deletable(item("الكاش المؤقت (cache)", cb, SAFE, "ملفات مؤقتة وأيقونات ونسخ APK للتثبيت.", "تُعاد عند الحاجة."), "يُفرَّغ الكاش.", true, cache));
        if (ext != null) {
            long eb = size(ext);
            if (eb > 512 * 1024L) l.add(deletable(item("ملفات خارجية (APK وغيرها)", eb, SAFE, "نسخ APK قديمة وحزم تصدير في مجلد التطبيق الخارجي.", "النسخ المهمة محفوظة في Downloads."), "تُحذف ملفات هذا المجلد.", true, ext));
        }
        return section("\uD83D\uDDC2", "نظرة عامة: أين ذهبت المساحة؟", "المجلدات الرئيسية في بيانات التطبيق، مرتبة بالحجم.", l);
    }

    // ----- SDK
    private static Section sdkSection(File sdk, Usage us) {
        List<Item> l = new ArrayList<>();
        if (!sdk.isDirectory()) return section("\uD83E\uDDF0", "Android SDK", "غير مثبّت.", l);
        for (File p : dirs(new File(sdk, "platforms"))) {
            String n = p.getName();
            String api = n.replace("android-", "");
            long b = size(p);
            List<String> by = us.compileSdk.get(api);
            Item i;
            if (api.equals("36")) {
                i = item("platform " + n, b, ESSENTIAL, "مكتبة الأندرويد (android.jar) لهذا الإصدار، يترجم بها المشروع.",
                        "يشترطها التطبيق نفسه قبل أي بناء (الخطوة 4)." + (by != null ? " مستخدم في: " + names(by) : ""));
            } else if (by != null) {
                i = item("platform " + n, b, IMPORTANT, "مكتبة الأندرويد لهذا الإصدار.", "مستخدم في مشاريعك: " + names(by));
            } else {
                i = deletable(item("platform " + n, b, OPTIONAL, "مكتبة الأندرويد لهذا الإصدار.",
                        us.projects > 0 ? "لا يستخدمه أي مشروع من مشاريعك الحالية." : "لا توجد مشاريع لمقارنتها."),
                        "أي مشروع يضبط compileSdk=" + api + " سيفشل بناؤه حتى تعيد تثبيت هذا platform.", false, p);
            }
            l.add(i);
        }
        Set<String> btUsed = new HashSet<>(us.buildTools.keySet());
        Map<String, List<String>> byDefault = new HashMap<>();
        for (Map.Entry<String, List<String>> e : us.agp.entrySet()) {
            String d = defaultBuildTools(e.getKey());
            if (d != null) { btUsed.add(d); byDefault.put(d, e.getValue()); }
        }
        for (File p : dirs(new File(sdk, "build-tools"))) {
            String n = p.getName();
            long b = size(p);
            boolean used = btUsed.contains(n);
            List<String> by = us.buildTools.containsKey(n) ? us.buildTools.get(n) : byDefault.get(n);
            Item i;
            if (used) {
                i = item("build-tools " + n, b, IMPORTANT, "أدوات التغليف (aapt وzipalign وd8) التي تصنع ملف APK.",
                        "يُرجَّح أنه مستخدم في: " + names(by) + " (تقدير من إصدار AGP).");
            } else {
                i = deletable(item("build-tools " + n, b, OPTIONAL, "أدوات التغليف (aapt وzipalign وd8) التي تصنع ملف APK.",
                        us.projects > 0 ? "لا يبدو أن مشاريعك تحتاجه (تقدير)." : "لا توجد مشاريع لمقارنتها."),
                        "إن احتاجه مشروع ما سيحاول Gradle تنزيله (يلزم إنترنت).", false, p);
            }
            l.add(i);
        }
        String[][] others = {
                {"platform-tools", "أدوات adb وغيرها", "لا يحتاجها البناء داخل التطبيق عادة."},
                {"cmdline-tools", "sdkmanager لتنزيل مكوّنات SDK", "تحتاجه إن أردت تنزيل منصّات أو أدوات جديدة."},
                {"ndk", "مترجم C/C++ لتطبيقات الأندرويد", "غير لازم إلا لمشاريع تحوي كود native."},
                {"emulator", "المحاكي", "لا يعمل على الهاتف عمليًا."},
                {"extras", "مكتبات إضافية قديمة", "نادرًا ما يلزم."},
                {".temp", "ملفات تنزيل مؤقتة", "تُعاد عند الحاجة."},
                {".downloadIntermediates", "ملفات تنزيل وسيطة", "تُعاد عند الحاجة."},
                {"licenses", "ملفات قبول التراخيص", "صغيرة جدًا وضرورية كي لا يطلب Gradle القبول من جديد."}};
        for (String[] o : others) {
            File f = new File(sdk, o[0]);
            if (!f.exists()) continue;
            long b = size(f);
            if (b < 256 * 1024L && !o[0].equals("licenses")) continue;
            boolean temp = o[0].startsWith(".");
            boolean lic = o[0].equals("licenses");
            boolean cmd = o[0].equals("cmdline-tools");
            Item i = item(o[0], b, lic ? ESSENTIAL : temp ? SAFE : OPTIONAL, o[1], o[2]);
            if (!lic && !cmd) deletable(i, temp ? "تُحذف الملفات المؤقتة." : "حذفها لا يؤثر على البناء العادي.", false, f);
            l.add(i);
        }
        return section("\uD83E\uDDF0", "Android SDK", "مكوّنات SDK مع تحديد المستخدم منها فعليًا في مشاريعك.", l);
    }

    // ----- Gradle
    private static Section gradleSection(File gh, Usage us) {
        List<Item> l = new ArrayList<>();
        if (!gh.isDirectory()) return section("\uD83D\uDC18", "Gradle", "غير مثبّت.", l);
        File[] ds = dirs(new File(gh, "wrapper/dists"));
        String newest = null;
        for (File d : ds) {
            Matcher m = Pattern.compile("^gradle-([0-9][0-9.]*)-").matcher(d.getName());
            if (m.find() && (newest == null || cmpVer(m.group(1), newest) > 0)) newest = m.group(1);
        }
        for (File d : ds) {
            String n = d.getName();
            Matcher m = Pattern.compile("^gradle-([0-9][0-9.]*)-").matcher(n);
            String v = m.find() ? m.group(1) : n;
            long b = size(d);
            List<String> by = us.gradle.get(v);
            Item i;
            if (by != null) {
                i = item("Gradle " + v, b, IMPORTANT, "برنامج البناء نفسه بهذا الإصدار.", "مستخدم في: " + names(by));
            } else if (v.equals(newest)) {
                i = item("Gradle " + v, b, IMPORTANT, "برنامج البناء نفسه بهذا الإصدار.", "أحدث إصدار مخزّن، يستعمله التطبيق لتوليد wrapper للمشاريع الناقصة.");
            } else {
                i = deletable(item("Gradle " + v, b, OPTIONAL, "برنامج البناء نفسه بهذا الإصدار.",
                        us.projects > 0 ? "لا يطلبه أي مشروع حالي." : "لا توجد مشاريع لمقارنتها."),
                        "إن طلبه مشروع لاحقًا سيُنزَّل من جديد (يلزم إنترنت).", false, d);
            }
            l.add(i);
            // ملفات zip الأصلية داخل التوزيعة (تُعاد من المجلد المستخرج)
            List<File> zips = new ArrayList<>();
            long zb = 0;
            File[] hs = d.listFiles(File::isDirectory);
            if (hs != null) for (File h : hs) {
                File[] zs = h.listFiles((x, nm) -> nm.endsWith(".zip"));
                if (zs != null) for (File z : zs) { zips.add(z); zb += z.length(); }
            }
            if (zb > 1024 * 1024L) {
                Item z = item("ملف zip الأصلي لـ Gradle " + v, zb, SAFE, "نسخة مضغوطة من Gradle بقيت بعد الاستخراج.", "المجلد المستخرج يكفي للبناء.");
                deletable(z, "تُحذف النسخة المضغوطة فقط. يعيد التطبيق بناءها عند الحاجة.", false, zips.toArray(new File[0]));
                l.add(z);
                i.bytes -= zb;
            }
        }
        File ldc = new File(gh, "local-dist-cache");
        if (ldc.isDirectory()) {
            long b = size(ldc);
            if (b > 1024 * 1024L) l.add(deletable(item("نسخة Gradle المحلية (local-dist-cache)", b, SAFE,
                    "نسخة غير مضغوطة من Gradle يبنيها التطبيق لتوليد wrapper بلا إنترنت.", "تتكرر لكل إصدار وتعادل حجمه تقريبًا."),
                    "يعيد التطبيق بناءها عند أول حاجة (يمكن ضبط الوضع إلى «مضغوط» لتصغيرها).", true, ldc));
        }
        File caches = new File(gh, "caches");
        File m2 = new File(caches, "modules-2");
        if (m2.isDirectory()) {
            File files21 = new File(m2, "files-2.1");
            long total = size(m2);
            StringBuilder top = new StringBuilder();
            File[] groups = files21.listFiles(File::isDirectory);
            if (groups != null) {
                List<Object[]> gs = new ArrayList<>();
                for (File g : groups) gs.add(new Object[]{g.getName(), size(g)});
                Collections.sort(gs, (a, b2) -> Long.compare((Long) b2[1], (Long) a[1]));
                for (int k = 0; k < Math.min(4, gs.size()); k++) top.append(k > 0 ? "، " : "").append(gs.get(k)[0]).append(" ").append(fmt((Long) gs.get(k)[1]));
            }
            List<String> agps = new ArrayList<>();
            for (File a : dirs(new File(files21, "com.android.tools.build/gradle"))) {
                String v = a.getName();
                agps.add(us.agp.containsKey(v) ? v + " (مستخدم)" : v);
            }
            Item i = deletable(item("مكتبات Gradle المحمّلة (modules-2)", total, IMPORTANT,
                    "كل المكتبات التي نزّلها Gradle (AGP وandroidx وغيرها). تجعل البناء ممكنًا دون إنترنت.",
                    (top.length() > 0 ? "الأكبر: " + top + ". " : "") + (agps.isEmpty() ? "" : "إصدارات AGP: " + android_join(agps) + ".")),
                    "لن يعمل البناء حتى يُعاد تنزيل المكتبات بالإنترنت (قد تكون جيجابايت).", false, m2);
            l.add(i);
        }
        if (caches.isDirectory()) {
            File[] cs = caches.listFiles();
            if (cs != null) for (File c : cs) {
                String n = c.getName();
                if (n.equals("modules-2")) continue;
                long b = size(c);
                if (b < 1024 * 1024L) continue;
                Item i;
                if (n.startsWith("build-cache")) i = deletable(item("كاش البناء (" + n + ")", b, SAFE, "نتائج مهام سابقة لتسريع البناء.", "يُعاد بناؤه تلقائيًا."), "يصبح أول بناء أبطأ قليلًا.", false, c);
                else if (n.startsWith("transforms") || n.startsWith("jars-")) i = deletable(item("كاش تحويلات (" + n + ")", b, SAFE, "ملفات وسيطة يولّدها Gradle من المكتبات.", "تُعاد تلقائيًا."), "يصبح أول بناء أبطأ قليلًا.", false, c);
                else if (c.isDirectory()) {
                    i = deletable(item("كاش Gradle " + n, b, OPTIONAL, "ملفات داخلية خاصة بإصدار Gradle هذا (تحويلات وسكربتات مترجمة).", "تُعاد تلقائيًا، لكن لا أضمن كل محتوياتها."), "أول بناء بعدها أبطأ.", false, c);
                } else continue;
                l.add(i);
            }
        }
        for (String n : new String[]{"daemon", "native", "kotlin-profile", "notifications", "workers"}) {
            File f = new File(gh, n);
            long b = size(f);
            if (b < 1024 * 1024L) continue;
            l.add(deletable(item(n.equals("daemon") ? "سجلات Gradle (daemon)" : "ملفات Gradle (" + n + ")", b, SAFE,
                    n.equals("daemon") ? "سجلات تشغيل Gradle القديمة." : "ملفات داخلية مؤقتة.", "لا تؤثر على البناء."), "تُحذف الملفات.", false, f));
        }
        return section("\uD83D\uDC18", "Gradle", "برنامج البناء ومكتباته، مع تحديد المستخدم منها في مشاريعك.", l);
    }

    private static int cmpVer(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = 0, q = 0;
            try { p = i < x.length ? Integer.parseInt(x[i]) : 0; } catch (Exception ignored) { }
            try { q = i < y.length ? Integer.parseInt(y[i]) : 0; } catch (Exception ignored) { }
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    // ----- ملفات نظام Debian غير الحزم
    private static Section rootfsSection(File rf) {
        List<Item> l = new ArrayList<>();
        Object[][] k = {
                {"var/cache/apt", "كاش apt: ملفات .deb المحمّلة", SAFE, "حزم نزّلتها apt وبقيت بعد التثبيت.", "لا حاجة لها بعد التثبيت."},
                {"var/lib/apt/lists", "فهارس apt", SAFE, "قوائم الحزم المتاحة للتنزيل.", "تُعاد بأمر apt update عند تثبيت حزمة جديدة (يلزم إنترنت)."},
                {"var/log", "سجلات النظام", SAFE, "سجلات قديمة.", "لا تؤثر على العمل."},
                {"tmp", "ملفات مؤقتة (tmp)", SAFE, "ملفات مؤقتة تركتها عمليات سابقة.", "لا تؤثر على العمل."},
                {"root/.cache", "كاش المستخدم (root/.cache)", SAFE, "كاش برامج مثل pip.", "يُعاد عند الحاجة."},
                {"usr/share/doc", "وثائق الحزم", SAFE, "ملفات توثيق وحقوق للحزم.", "لا يحتاجها البناء."},
                {"usr/share/man", "صفحات man", SAFE, "كتيّبات الأوامر.", "لا يحتاجها البناء."},
                {"usr/share/info", "ملفات info", SAFE, "وثائق إضافية.", "لا يحتاجها البناء."},
                {"usr/share/locale", "ترجمات اللغات", OPTIONAL, "ترجمات رسائل البرامج.", "ستظهر رسائل Debian بالإنجليزية فقط."},
                {"usr/lib/jvm", "Java (JDK)", IMPORTANT, "بيئة تشغيل Java التي يعمل بها Gradle.", "بدونها لا يعمل أي بناء."},
                {"usr/lib/llvm-19", "مترجم LLVM 19", OPTIONAL, "مترجم وأدوات للكود native (C/C++/Rust).", "غير لازم لبناء تطبيقات Java/Kotlin العادية."},
                {"usr/lib/gcc", "مترجم GCC", OPTIONAL, "مترجم C/C++.", "غير لازم لبناء تطبيقات Java/Kotlin العادية."},
                {"usr/lib/python3", "مكتبات بايثون", OPTIONAL, "بايثون ومكتباته.", "تحتاجها إن استعملت python3 في الطرفية."}};
        long accounted = 0;
        for (Object[] r : k) {
            File f = new File(rf, (String) r[0]);
            if (!f.exists()) continue;
            long b = size(f);
            accounted += b;
            if (b < 2 * 1024 * 1024L) continue;
            Item i = item((String) r[1], b, (Integer) r[2], (String) r[3], (String) r[4]);
            int lv = (Integer) r[2];
            if (r[0].equals("var/cache/apt")) {
                List<File> debs = new ArrayList<>();
                File[] as = new File(f, "archives").listFiles((x, nm) -> nm.endsWith(".deb"));
                if (as != null) Collections.addAll(debs, as);
                for (String pc : new String[]{"pkgcache.bin", "srcpkgcache.bin"}) { File q = new File(f, pc); if (q.isFile()) debs.add(q); }
                deletable(i, "تُحذف ملفات .deb المحمّلة فقط.", false, debs.toArray(new File[0]));
            } else if (lv == SAFE || lv == OPTIONAL && !((String) r[0]).startsWith("usr/lib/")) {
                deletable(i, "تُحذف محتويات هذا المجلد وتبقى بنية النظام سليمة.", true, f);
            }
            l.add(i);
        }
        long total = size(rf);
        long other = total - accounted;
        if (other > 0) l.add(item("بقية ملفات النظام والحزم", other, IMPORTANT, "ملفات الحزم المثبّتة (برامج ومكتبات). التفاصيل في قسم الحزم.", "تقلّصها بحذف حزم غير لازمة."));
        return section("\uD83D\uDC27", "نظام Debian (الملفات)", "ما يشغله النظام: " + fmt(total) + ". الجزء الأكبر عادة حزم، والباقي كاش وتوثيق يمكن حذفه.", l);
    }

    // ----- حزم Debian
    private static final class Pkg {
        String name, desc = "", priority = "", section = "", depends = "", provides = "", status = "";
        long kb;
        boolean essential, manual = true;
    }

    private static Section debianSection(File rf) {
        File status = new File(rf, "var/lib/dpkg/status");
        if (!status.isFile()) return null;
        String txt = read(status);
        Map<String, Pkg> pk = new LinkedHashMap<>();
        for (String blk : txt.split("\n\n")) {
            Pkg p = new Pkg();
            String last = "";
            for (String line : blk.split("\n")) {
                if (line.startsWith(" ") || line.startsWith("\t")) continue;
                int c = line.indexOf(':');
                if (c < 0) continue;
                String key = line.substring(0, c), val = line.substring(c + 1).trim();
                switch (key) {
                    case "Package": p.name = val; break;
                    case "Installed-Size": try { p.kb = Long.parseLong(val); } catch (Exception ignored) { } break;
                    case "Priority": p.priority = val; break;
                    case "Essential": p.essential = val.equalsIgnoreCase("yes"); break;
                    case "Section": p.section = val; break;
                    case "Depends": p.depends += (p.depends.isEmpty() ? "" : ",") + val; break;
                    case "Pre-Depends": p.depends += (p.depends.isEmpty() ? "" : ",") + val; break;
                    case "Provides": p.provides = val; break;
                    case "Description": p.desc = val; break;
                    case "Status": p.status = val; break;
                    default: break;
                }
            }
            if (p.name != null && p.status.endsWith("installed") && !p.status.contains("not-installed")) pk.put(p.name, p);
        }
        if (pk.isEmpty()) return null;
        // auto-installed
        String ext = read(new File(rf, "var/lib/apt/extended_states"));
        for (String blk : ext.split("\n\n")) {
            String nm = null; boolean auto = false;
            for (String line : blk.split("\n")) {
                if (line.startsWith("Package:")) nm = line.substring(8).trim();
                if (line.startsWith("Auto-Installed:") && line.contains("1")) auto = true;
            }
            if (nm != null && auto && pk.containsKey(nm)) pk.get(nm).manual = false;
        }
        Map<String, String> virt = new HashMap<>();
        for (Pkg p : pk.values()) for (String v : p.provides.split(",")) {
            String vn = v.trim().replaceAll("\\s*\\(.*\\)", "").replaceAll(":any$", "");
            if (!vn.isEmpty() && !pk.containsKey(vn)) virt.put(vn, p.name);
        }
        Map<String, Set<String>> deps = new HashMap<>();
        Map<String, Set<String>> rdeps = new HashMap<>();
        for (Pkg p : pk.values()) {
            Set<String> d = new HashSet<>();
            for (String part : p.depends.split(",")) for (String alt : part.split("\\|")) {
                String n = alt.trim().replaceAll("\\s*\\(.*\\)", "").replaceAll(":any$", "").replaceAll(":[a-z0-9]+$", "").trim();
                if (n.isEmpty()) continue;
                if (pk.containsKey(n)) d.add(n);
                else if (virt.containsKey(n)) d.add(virt.get(n));
            }
            d.remove(p.name);
            deps.put(p.name, d);
            for (String x : d) { Set<String> r = rdeps.get(x); if (r == null) { r = new HashSet<>(); rdeps.put(x, r); } r.add(p.name); }
        }
        Set<String> sysRoots = new HashSet<>(), javaRoots = new HashSet<>();
        for (Pkg p : pk.values()) {
            if (p.essential || p.priority.equals("required") || p.priority.equals("important")) sysRoots.add(p.name);
            if (p.name.startsWith("openjdk-") || p.name.equals("java-common") || p.name.startsWith("ca-certificates")
                    || p.name.startsWith("aapt") || p.name.equals("zip") || p.name.equals("unzip") || p.name.equals("apt")) javaRoots.add(p.name);
        }
        Set<String> sys = closure(sysRoots, deps);
        Set<String> java = closure(javaRoots, deps);
        List<Item> l = new ArrayList<>();
        long sumAll = 0, sumSafe = 0, sumOpt = 0;
        for (Pkg p : pk.values()) {
            long bytes = p.kb * 1024L;
            sumAll += bytes;
            int lv;
            String note;
            Set<String> rd = rdeps.get(p.name);
            int nrd = rd == null ? 0 : rd.size();
            if (sys.contains(p.name)) { lv = ESSENTIAL; note = "جزء من النظام الأساسي أو تعتمد عليه حزم أساسية."; }
            else if (java.contains(p.name)) { lv = IMPORTANT; note = "تلزم Java أو أدوات البناء."; }
            else if (!p.manual && nrd == 0) { lv = SAFE; note = "حزمة يتيمة: لا تعتمد عليها أي حزمة، ويزيلها autoremove."; }
            else if (p.manual) { lv = OPTIONAL; note = "مثبّتة يدويًا (من الإعداد أو من قِبلك)" + (nrd > 0 ? " وتعتمد عليها " + nrd + " حزمة." : "."); }
            else { lv = OPTIONAL; note = "تُثبّت كاعتماد لحزمة غير أساسية" + (nrd > 0 ? " (" + nrd + " حزمة تعتمد عليها)." : "."); }
            if (lv == SAFE) sumSafe += bytes;
            if (lv == OPTIONAL) sumOpt += bytes;
            if (bytes < 8 * 1024 * 1024L) continue;
            Item i = item(p.name, bytes, lv, roleOf(p), note + knowHint(p.name));
            i.debDependents = nrd;
            i.debKb = p.kb; i.debEssential = p.essential; i.debPriority = p.priority;
            if (lv >= OPTIONAL) {
                i.debPkg = p.name;
                i.consequence = "تُحذف الحزمة" + (nrd > 0 ? " مع الحزم التي تعتمد عليها (" + nrd + ")" : "") + " عبر apt purge.";
            }
            l.add(i);
        }
        if (l.size() > 45) l = new ArrayList<>(l.subList(0, 45));
        Section s = section("\uD83D\uDCE6", "حزم Debian", pk.size() + " حزمة بحجم " + fmt(sumAll)
                + ". غير الأساسية: " + fmt(sumOpt + sumSafe) + " (منها " + fmt(sumSafe) + " حزم يتيمة آمنة). تظهر الحزم الأكبر من 8 م.ب فقط.", l);
        s.bytes = sumAll;
        return s;
    }

    private static Set<String> closure(Set<String> roots, Map<String, Set<String>> deps) {
        Set<String> seen = new HashSet<>(roots);
        java.util.ArrayDeque<String> q = new java.util.ArrayDeque<>(roots);
        while (!q.isEmpty()) {
            String n = q.poll();
            Set<String> d = deps.get(n);
            if (d == null) continue;
            for (String x : d) if (seen.add(x)) q.add(x);
        }
        return seen;
    }

    private static String roleOf(Pkg p) {
        String n = p.name;
        if (n.startsWith("openjdk-")) return "بيئة Java (JDK/JRE) التي يعمل بها Gradle.";
        if (n.startsWith("qemu")) return "محاكي لتشغيل برامج بمعمارية مختلفة (مثل x86 على معالج ARM).";
        if (n.startsWith("llvm") || n.startsWith("libllvm")) return "مكتبات مترجم LLVM لبناء كود C/C++/Rust.";
        if (n.startsWith("clang") || n.startsWith("libclang")) return "مترجم Clang لـC/C++.";
        if (n.startsWith("gcc") || n.startsWith("g++") || n.startsWith("cpp")) return "مترجم GCC لـC/C++.";
        if (n.startsWith("libc6-dev") || n.startsWith("linux-libc-dev")) return "ملفات تطوير مكتبة C لبناء كود native.";
        if (n.startsWith("python3") || n.startsWith("libpython3")) return "لغة بايثون ومكتباتها.";
        if (n.startsWith("perl")) return "لغة Perl (يحتاجها dpkg وأدوات النظام).";
        if (n.equals("git") || n.startsWith("git-")) return "نظام التحكم بالإصدارات Git.";
        if (n.equals("curl") || n.startsWith("libcurl")) return "أداة تنزيل الملفات curl.";
        if (n.startsWith("binutils")) return "أدوات الربط والتجميع (ld وas).";
        if (n.equals("make") || n.startsWith("cmake") || n.equals("ninja-build")) return "أدوات بناء الكود native.";
        if (n.startsWith("nodejs") || n.startsWith("npm")) return "بيئة Node.js.";
        if (n.startsWith("aapt")) return "أداة تغليف موارد الأندرويد (aapt).";
        if (n.startsWith("libicu")) return "مكتبة دعم Unicode تحتاجها برامج كثيرة.";
        if (n.equals("ca-certificates")) return "شهادات الأمان للاتصال بمواقع HTTPS.";
        if (!p.desc.isEmpty()) return p.desc;
        return p.section.startsWith("libs") ? "مكتبة مشتركة تحتاجها حزم أخرى." : "حزمة بلا وصف.";
    }

    private static String knowHint(String n) {
        if (n.startsWith("qemu")) return " تنبيه: قد يُستعمل لتشغيل أدوات SDK المبنية لـx86. احذفه ثم جرّب بناءً؛ إن فشل أعد تثبيته.";
        if (n.startsWith("llvm") || n.startsWith("libllvm") || n.startsWith("clang") || n.startsWith("libclang"))
            return " غير لازم لمشاريع Java/Kotlin العادية، ويلزم فقط لو تبني كود native.";
        if (n.startsWith("gcc") || n.startsWith("g++") || n.startsWith("libc6-dev")) return " غير لازم لمشاريع Java/Kotlin العادية.";
        if (n.equals("git") || n.equals("curl") || n.startsWith("python3")) return " أضفتها من قائمة التطبيق؛ احذفها إن لم تستخدمها في الطرفية.";
        return "";
    }

    static String fmt(long b) {
        if (b >= 1073741824L) return String.format(java.util.Locale.US, "%.2f GB", b / 1073741824.0);
        if (b >= 1048576L) return String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0);
        if (b >= 1024L) return String.format(java.util.Locale.US, "%.0f KB", b / 1024.0);
        return b + " B";
    }
}
