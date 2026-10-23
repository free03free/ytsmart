package com.example.l2sbuilder;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.regex.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** يكتشف نسخة AGP في مشروع ويقترح أقل Gradle متوافق معها، ويجلب قائمة إصدارات Gradle الرسمية. */
final class GradleAdvisor {
    private GradleAdvisor() {}

    // {AGP major.minor, أقل Gradle} — مأخوذ من جدول التوافق الرسمي كما أعرفه؛ ما ليس فيه يُعامل «غير معروف».
    // المصدر: صفحات إصدارات AGP الرسمية في developer.android.com (9.x حُدِّثت في أكتوبر 2026).
    private static final String[][] MIN_GRADLE = {
            {"9.4", "9.6.0"}, {"9.3", "9.5.0"}, {"9.2", "9.4.1"}, {"9.1", "9.2.1"}, {"9.0", "9.1.0"},
            {"8.13", "8.13"}, {"8.12", "8.13"}, {"8.11", "8.13"}, {"8.10", "8.11.1"}, {"8.9", "8.11.1"},
            {"8.8", "8.10.2"}, {"8.7", "8.9"}, {"8.6", "8.7"}, {"8.5", "8.7"}, {"8.4", "8.6"},
            {"8.3", "8.4"}, {"8.2", "8.2"}, {"8.1", "8.0"}, {"8.0", "8.0"},
            {"7.4", "7.5"}, {"7.3", "7.4"}, {"7.2", "7.3.3"}, {"7.1", "7.2"}, {"7.0", "7.0"}};

    // أعلى API تدعمه كل نسخة AGP حسب الصفحات الرسمية (9.x) وما أعرفه لـ 8.x؛ 7.x وما قبلها غير معروف (0).
    // ملاحظة: API 37 يتطلب AGP 9.1.1 فأعلى (يُعالج 9.1 بالتصحيح في maxApiFor).
    private static final String[][] MAX_API = {
            {"9.4", "37"}, {"9.3", "37"}, {"9.2", "37"}, {"9.1", "36"}, {"9.0", "36"},
            {"8.13", "36"}, {"8.12", "36"}, {"8.11", "36"}, {"8.10", "36"}, {"8.9", "36"}, {"8.8", "35"},
            {"8.7", "35"}, {"8.6", "35"}, {"8.5", "34"}, {"8.4", "34"}, {"8.3", "34"}, {"8.2", "34"},
            {"8.1", "33"}, {"8.0", "33"}};

    private static File agpCacheDir(File gh) { return new File(gh, "caches/modules-2/files-2.1/com.android.tools.build/gradle"); }

    /** مخزّن فعلًا = يوجد ملف gradle-X.jar (وجود المجلد وحده لا يكفي: قد يحوي pom فقط بعد تنزيل ناقص). */
    static boolean agpCached(File gh, String v) {
        File[] hs = new File(agpCacheDir(gh), v).listFiles();
        if (hs != null) for (File h : hs) if (new File(h, "gradle-" + v + ".jar").isFile()) return true;
        return false;
    }

    /** كل إصدارات AGP المخزّنة فعلًا، الأحدث أولًا. */
    static List<String> cachedAgps(File gh) {
        List<String> out = new ArrayList<>();
        String[] ds = agpCacheDir(gh).list();
        if (ds != null) for (String d : ds) if (agpCached(gh, d)) out.add(d);
        Collections.sort(out, (a, b) -> cmp(b, a));
        return out;
    }

    /** أعلى API (compileSdk/targetSdk) تدعمه نسخة AGP، أو 0 إن لم تُعرف. */
    static int maxApiFor(String agp) {
        if (agp == null) return 0;
        Matcher m = Pattern.compile("^9\\.1\\.(\\d+)").matcher(agp);
        if (m.find() && Integer.parseInt(m.group(1)) >= 1) return 37; // 9.1.1+
        for (String[] r : MAX_API) if (agp.equals(r[0]) || agp.startsWith(r[0] + ".")) return Integer.parseInt(r[1]);
        return 0;
    }

    /** أقل نسخة AGP (major.minor) تدعم API المعطى، أو null إن لم يُعرف. */
    static String minAgpForApi(int api) {
        if (api >= 37) return "9.1.1";
        for (int i = MAX_API.length - 1; i >= 0; i--) if (Integer.parseInt(MAX_API[i][1]) >= api) return MAX_API[i][0] + ".x";
        return null;
    }

    /** أقل JDK يحتاجه AGP. */
    static int minJdkFor(String agp) {
        if (agp == null) return 0;
        int mj = num(agp.split("\\.")[0]);
        return mj >= 8 ? 17 : 11;
    }

    static String minGradleFor(String agp) {
        if (agp == null) return null;
        for (String[] r : MIN_GRADLE) if (agp.equals(r[0]) || agp.startsWith(r[0] + ".")) return r[1];
        return null;
    }

    /** أقل Gradle يدعم تشغيل JVM المعطى. Gradle 8.5 هو أول إصدار يدعم التشغيل على Java 21. */
    static String minGradleForJdk(int jdkMajor) {
        if (jdkMajor >= 21) return "8.5";
        if (jdkMajor >= 20) return "8.3";
        if (jdkMajor >= 19) return "7.6";
        if (jdkMajor >= 18) return "7.5";
        return null;
    }

    /** الحد الأدنى الذي يجمع متطلبات JDK وAGP دون تغيير إصدار AGP داخل المشروع. */
    static String minimumGradleFor(File projectDir, String agpOverride, int jdkMajor) {
        String need = null;
        try {
            String agp = agpOverride != null ? agpOverride : detectAgp(projectDir);
            need = minGradleFor(agp);
        } catch (Throwable ignored) {}
        String jdkNeed = minGradleForJdk(jdkMajor);
        if (need == null) return jdkNeed;
        if (jdkNeed == null) return need;
        return cmp(need, jdkNeed) >= 0 ? need : jdkNeed;
    }

    static String versionFromUrl(String url) {
        if (url == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("gradle-([0-9][0-9.]*)-(?:bin|all)").matcher(url);
        return m.find() ? m.group(1) : null;
    }

    static String urlFor(String version) {
        return "https://services.gradle.org/distributions/gradle-" + version + "-bin.zip";
    }

    /** أعلى AGP موجود في المشروع (build.gradle / settings / libs.versions.toml) أو null. */
    static String detectAgp(File dir) {
        List<File> fs = new ArrayList<>();
        walk(dir, 0, fs);
        String best = null;
        Pattern[] ps = {
                Pattern.compile("com\\.android\\.tools\\.build:gradle:(\\d+\\.\\d+(?:\\.\\d+)?)"),
                Pattern.compile("com\\.android\\.(?:application|library)[\"']\\)?\\s*version\\s*[\"'](\\d+\\.\\d+(?:\\.\\d+)?)"),
                Pattern.compile("(?m)^\\s*(?:agp|androidGradlePlugin|android-gradle-plugin|androidGradle)\\s*=\\s*\"(\\d+\\.\\d+(?:\\.\\d+)?)"),
                Pattern.compile("com\\.android\\.(?:application|library)\"\\s*,\\s*version\\s*=\\s*\"(\\d+\\.\\d+(?:\\.\\d+)?)")};
        for (File f : fs) {
            String s = read(f);
            for (Pattern p : ps) {
                Matcher m = p.matcher(s);
                while (m.find()) if (best == null || cmp(m.group(1), best) > 0) best = m.group(1);
            }
        }
        return best;
    }

    /** رابط Gradle المناسب للمشروع، أو null إن لم يُعرف. */
    static String autoUrl(File projectDir) {
        if (projectDir == null || !projectDir.isDirectory()) return null;
        String need = minimumGradleFor(projectDir, null, 21);
        return need == null ? null : urlFor(need);
    }

    /** إصدارات Gradle المستقرة {version, url} من الأحدث للأقدم. */
    static List<String[]> fetchStable() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("https://services.gradle.org/versions/all").openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "L2SBuilder");
        if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
            String l; while ((l = r.readLine()) != null) sb.append(l);
        }
        JSONArray a = new JSONArray(sb.toString());
        List<String[]> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            if (o.optBoolean("snapshot") || o.optBoolean("nightly") || o.optBoolean("releaseNightly")
                    || o.optBoolean("activeRc") || o.optBoolean("broken")) continue;
            if (!o.optString("rcFor", "").isEmpty() || !o.optString("milestoneFor", "").isEmpty()) continue;
            String v = o.optString("version");
            if (!v.matches("\\d+\\.\\d+(\\.\\d+)?")) continue;
            out.add(new String[]{v, urlFor(v)});
        }
        return out;
    }

    /**
     * يختار أنسب توزيعة Gradle منزَّلة فعليًا (دون شبكة) لمشروع معيّن، أو null إن لم توجد أي توزيعة.
     * الترتيب: الإصدار المفضّل (إن كان منزَّلًا) ← أحدث إصدار من نفس الجيل الرئيسي الذي يحقق الحد الأدنى لـ AGP
     * ← أقل إصدار أعلى من الحد الأدنى ← (لا قيد معروف) أحدث إصدار منزَّل ← (لا شيء يكفي) أحدث المتاح.
     */
    static String pickInstalled(File gh, File projectDir, String preferred) {
        List<String> have = cached(gh); // من الأحدث للأقدم
        if (have.isEmpty()) return null;
        if (preferred != null && have.contains(preferred)) return preferred;
        String need = minimumGradleFor(projectDir, null, 21);
        if (need == null) return have.get(0);
        String needMajor = need.split("\\.")[0];
        for (String v : have) if (cmp(v, need) >= 0 && v.split("\\.")[0].equals(needMajor)) return v;
        String lowest = null;
        for (String v : have) if (cmp(v, need) >= 0) lowest = v;
        return lowest != null ? lowest : have.get(0);
    }

    /**
     * رابط Gradle الذكي عند عدم اختيار المستخدم شيئًا: توزيعة منزَّلة تناسب المشروع إن وُجدت،
     * وإلا أقل إصدار يطلبه AGP، وإلا الرابط الاحتياطي. لا يثبّت أي إصدار بعينه.
     */
    static String smartUrl(File gh, File projectDir, String fallbackUrl) {
        String need = minimumGradleFor(projectDir, null, 21);
        String have = pickInstalled(gh, projectDir, null);
        if (have != null && (need == null || cmp(have, need) >= 0)) return urlFor(have);
        if (need != null) return urlFor(need);
        return fallbackUrl;
    }

    /** يعطي صلاحية التنفيذ لكل ملفات bin/ في كل توزيعات Gradle المنزَّلة (قد تضيع بعد التنزيل داخل proot أو بعد الاستيراد). */
    static int fixExecBits(File gh) {
        int n = 0;
        File[] ds = new File(gh, "wrapper/dists").listFiles();
        if (ds == null) return 0;
        for (File d : ds) {
            File[] hs = d.listFiles(File::isDirectory);
            if (hs == null) continue;
            for (File h : hs) {
                File[] gs = h.listFiles(File::isDirectory);
                if (gs == null) continue;
                for (File g : gs) {
                    File[] bins = new File(g, "bin").listFiles();
                    if (bins == null) continue;
                    for (File b : bins) if (b.isFile() && !b.canExecute() && b.setExecutable(true, false)) n++;
                }
            }
        }
        return n;
    }

    /** اسم مجلد التوزيعة كما يسميه Gradle wrapper. */
    static String distName(String ver) { return "gradle-" + ver + "-bin"; }

    /** نفس تجزئة PathAssembler في Gradle: base36(md5(distributionUrl)). */
    static String hashOf(String url) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            return new java.math.BigInteger(1, md.digest(url.getBytes("UTF-8"))).toString(36);
        } catch (Exception e) { return "0"; }
    }

    /** مجلد التوزيعة الجاهزة (الذي يحوي lib) للإصدار، أو null إن لم تكن منزَّلة كاملة. */
    static File installedDir(File gradleHome, String ver) {
        File[] hs = new File(gradleHome, "wrapper/dists/" + distName(ver)).listFiles();
        if (hs != null) for (File h : hs) {
            File g = new File(h, "gradle-" + ver);
            if (new File(g, "lib").isDirectory()) return g;
        }
        return null;
    }

    /** إصدارات Gradle المنزَّلة فعليًا في gradle-home (مجلد مستخرج كامل). */
    static List<String> cached(File gradleHome) {
        List<String> r = new ArrayList<>();
        File[] ds = new File(gradleHome, "wrapper/dists").listFiles();
        if (ds != null) for (File d : ds) {
            Matcher m = Pattern.compile("^gradle-(\\d+\\.\\d+(?:\\.\\d+)?)-(?:bin|all)$").matcher(d.getName());
            if (m.find() && installedDirAny(d, m.group(1))) r.add(m.group(1));
        }
        Collections.sort(r, (x, y) -> cmp(y, x));
        return r;
    }

    private static boolean installedDirAny(File dist, String ver) {
        File[] hs = dist.listFiles();
        if (hs == null) return false;
        for (File h : hs) if (new File(h, "gradle-" + ver + "/lib").isDirectory()) return true;
        return false;
    }

    static int cmp(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return p < q ? -1 : 1;
        }
        return 0;
    }

    private static int num(String s) { try { return Integer.parseInt(s.replaceAll("\\D.*$", "")); } catch (Exception e) { return 0; } }

    private static void walk(File d, int depth, List<File> out) {
        if (depth > 5) return;
        File[] ks = d.listFiles();
        if (ks == null) return;
        for (File k : ks) {
            String n = k.getName();
            if (k.isDirectory()) { if (!n.equals("build") && !n.equals(".gradle") && !n.equals(".git") && !n.equals("src")) walk(k, depth + 1, out); }
            else if (n.endsWith(".gradle") || n.endsWith(".gradle.kts") || n.equals("libs.versions.toml")) out.add(k);
        }
    }

    private static String read(File f) {
        try {
            if (f.length() > 1_000_000) return "";
            byte[] b = new byte[(int) f.length()];
            try (DataInputStream in = new DataInputStream(new FileInputStream(f))) { in.readFully(b); }
            return new String(b, "UTF-8");
        } catch (IOException e) { return ""; }
    }
}
