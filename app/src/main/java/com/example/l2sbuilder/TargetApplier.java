package com.example.l2sbuilder;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.*;
import java.util.*;
import java.util.regex.*;

/** يطبّق minSdk/targetSdk المختارة على وحدات التطبيق (com.android.application) مع نسخة احتياطية .l2s-orig. */
final class TargetApplier {
    private TargetApplier() {}

    static String applyIfEnabled(Context c, File root) {
        SharedPreferences p = c.getSharedPreferences("l2s_targets", Context.MODE_PRIVATE);
        if (!p.getBoolean("apply_on_build", true)) return "";
        if (!p.getBoolean("single", true)) {
            List<Integer> l = multiApis(c);
            return l.isEmpty() ? "عدة APK: لم تُختر إصدارات.\n"
                    : "عدة APK: سيُبنى APK لكل إصدار من " + l + " (يُعدَّل المشروع مؤقتًا لكل بناء ثم تُستعاد ملفاته).\n";
        }
        int min = p.getInt("minSdk", 24), tgt = p.getInt("targetSdk", 36);
        if (min <= 0 || tgt <= 0) return "";
        return apply(root, min, tgt, new File(c.getFilesDir(), "sdk"));
    }


    /** الإصدارات المختارة لوضع «عدة APK» (مرتبة تصاعديًا) إن كان الوضع مفعّلًا ومربوطًا بالبناء، وإلا قائمة فارغة. */
    static List<Integer> multiApis(Context c) {
        List<Integer> out = new ArrayList<>();
        SharedPreferences p = c.getSharedPreferences("l2s_targets", Context.MODE_PRIVATE);
        if (!p.getBoolean("apply_on_build", true) || p.getBoolean("single", true)) return out;
        Set<String> st = p.getStringSet("multi_apis", null);
        if (st == null) return out;
        for (String x : st) { try { out.add(Integer.parseInt(x)); } catch (NumberFormatException ignored) {} }
        Collections.sort(out);
        return out;
    }

    /** يضبط المشروع لإصدار واحد (min=target=api) مع versionCode فريد؛ يستعيد الأصل أولًا ليكون التطبيق متكررًا بلا تراكم. */
    static String applyMulti(File root, int api, File sdk) {
        restore(root);
        String log = apply(root, api, api, sdk);
        List<File> gs = new ArrayList<>();
        find(root, 0, gs);
        for (File g : gs) {
            String s = read(g);
            if (!s.contains("android.application") || s.contains("com.example.l2sbuilder")) continue;
            if (Pattern.compile("apply\\s*(?:=\\s*)?false").matcher(s).find()) continue;
            Matcher m = Pattern.compile("(versionCode\\s*(?:=\\s*|\\()?\\s*)(\\d+)").matcher(s);
            if (!m.find()) continue;
            long base = Long.parseLong(m.group(2));
            long nv = base * 100 + api;
            if (nv > 2100000000L) nv = 2100000000L;
            StringBuffer sb = new StringBuffer();
            m.reset();
            while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + nv));
            m.appendTail(sb);
            try {
                File bak = new File(g.getParentFile(), g.getName() + ".l2s-orig");
                if (!bak.exists()) write(bak, s);
                write(g, sb.toString());
                log += "✔ " + g.getName() + ": versionCode " + base + " → " + nv + "\n";
            } catch (IOException e) { log += "✘ تعذّر تعديل versionCode: " + e.getMessage() + "\n"; }
        }
        return log;
    }

    private static boolean hasPlatform(File sdk, int api) {
        if (sdk == null) return true; // غير معروف: لا نمنع
        return new File(sdk, "platforms/android-" + api + "/android.jar").exists()
                || new File(sdk, "platforms/android-" + api + ".0/android.jar").exists();
    }

    /** منصة بمجلد عادي android-N فقط (AGP 8.x لا يتعرف على مجلد android-N.0). */
    private static boolean hasPlain(File sdk, int api) {
        if (sdk == null) return true;
        return new File(sdk, "platforms/android-" + api + "/android.jar").exists();
    }

    private static boolean usable(File sdk, int api, boolean plainOnly) {
        return plainOnly ? hasPlain(sdk, api) : hasPlatform(sdk, api);
    }

    private static int highestPlain(File sdk) {
        int best = 0;
        for (int a = 1; a <= 40; a++) if (hasPlain(sdk, a)) best = a;
        return best;
    }

    /** أصغر منصة مثبتة (قابلة للاستخدام مع AGP الحالي) بمستوى >= from، أو 0. */
    private static int smallestInstalledAtLeast(File sdk, int from, boolean plainOnly) {
        for (int a = Math.max(from, 1); a <= 40; a++) if (usable(sdk, a, plainOnly)) return a;
        return 0;
    }

    private static int highestInstalled(File sdk) {
        int best = 0;
        for (int a = 1; a <= 40; a++) if (hasPlatform(sdk, a)) best = a;
        return best;
    }

    static String apply(File root, int min, int target) { return apply(root, min, target, null); }

    private static final String MIN_RE = "(minSdk(?:Version)?\\s*(?:=\\s*|\\()?\\s*)(\\d+)";
    private static final String TGT_RE = "(targetSdk(?:Version)?\\s*(?:=\\s*|\\()?\\s*)(\\d+)";
    private static final String COMPILE_RE = "(compileSdk(?:Version)?\\s*(?:=\\s*|\\()?\\s*)(\\d+)";
    private static final String COMPILE_REL_RE = "(compileSdk\\s*\\{[^}]*?release\\()(\\d+)";

    private static int readCompile(String s) {
        Matcher m = Pattern.compile(COMPILE_RE).matcher(s);
        if (m.find()) return Integer.parseInt(m.group(2));
        Matcher r = Pattern.compile(COMPILE_REL_RE).matcher(s);
        return r.find() ? Integer.parseInt(r.group(2)) : 0;
    }

    /** يغيّر compileSdk بالصيغتين (القديمة والجديدة release(N))؛ يعيد النص نفسه إن لم تُطابق أي صيغة. */
    private static String setCompile(String s, int val) {
        String r = sub(s, COMPILE_RE, val);
        return r.equals(s) ? sub(s, COMPILE_REL_RE, val) : r;
    }

    static String apply(File root, int min, int targetReq, File sdk) {
        List<File> gs = new ArrayList<>();
        find(root, 0, gs);
        StringBuilder log = new StringBuilder();
        int changed = 0;
        String agp = GradleAdvisor.detectAgp(root);
        int maxApi = GradleAdvisor.maxApiFor(agp);
        // مجلد المنصة android-N.0 (مثل android-37.0) لا يفهمه AGP أقدم من 9.2؛ لذلك لا نختاره قبل ذلك
        final boolean plainOnly = maxApi < 37;
        // لا نرفع target فوق ما يدعمه AGP، ولا فوق أعلى منصة قابلة للاستخدام (وإلا يفشل البناء قبل الترجمة)
        int target = targetReq;
        if (sdk != null) {
            int cap = maxApi > 0 ? maxApi : highestPlain(sdk);
            if (cap > 0 && target > cap) {
                log.append("ℹ targetSdk ").append(target).append(" → ").append(cap)
                   .append(maxApi > 0 ? " (أعلى API يدعمه AGP " + agp + ")" : " (أعلى منصة مثبتة قابلة للاستخدام)").append("\n");
                target = cap;
            }
            if (min > target) min = target;
        }
        for (File g : gs) {
            String s = read(g);
            if (!s.contains("android.application")) continue;
            if (Pattern.compile("apply\\s*(?:=\\s*)?false").matcher(s).find()) continue; // build.gradle الجذر وليس وحدة التطبيق
            if (s.contains("com.example.l2sbuilder")) {
                // مشروع L2S Builder نفسه: يحتاج targetSdk 28 ليشغّل أدواته (proot) على أندرويد 10 فأحدث،
                // لذلك لا نغيّر target/compile أبدًا، ونخفّض minSdk فقط إن كان المصدر آمنًا فعلًا على الإصدار الأقدم.
                CompatScanner.Result cr = CompatScanner.scan(root, min);
                if (!cr.clean()) {
                    log.append("⛔ ").append(rel(root, g)).append(": لم يُخفَّض minSdk إلى ").append(min)
                       .append(" لأن المصدر فيه استخدامات غير محمية لواجهات أحدث (كانت ستتعطل على الأجهزة الأقدم):\n")
                       .append(CompatScanner.report(cr, min));
                    continue;
                }
                String sm = sub(s, MIN_RE, min);
                if (sm.equals(s)) {
                    log.append("✔ ").append(rel(root, g)).append(": minSdk مطابق (").append(min).append(") والمصدر آمن (target يبقى 28).\n");
                    continue;
                }
                File bak0 = new File(g.getParentFile(), g.getName() + ".l2s-orig");
                try {
                    if (!bak0.exists()) write(bak0, s);
                    write(g, sm);
                    changed++;
                    log.append("✔ عُدّل ").append(rel(root, g)).append(": minSdk ").append(min)
                       .append(" (الفحص نظيف؛ target/compile بلا تغيير لأن هذا مشروع L2S نفسه)\n");
                } catch (IOException e) { log.append("✘ تعذّر تعديل ").append(g.getName()).append(": ").append(e.getMessage()).append("\n"); }
                continue;
            }
            String o = s;
            int compile = readCompile(s);
            boolean compileChanged = false, minChanged = false, tgtChanged = false;
            // compileSdk: لا نغيّره إلا إذا منصته غير مثبتة أو أقل من target
            if (compile > 0 && sdk != null && (!usable(sdk, compile, plainOnly) || compile < target)) {
                int cand = smallestInstalledAtLeast(sdk, Math.max(compile, target), plainOnly);
                if (cand > 0 && cand != compile) {
                    String ns = setCompile(s, cand);
                    if (!ns.equals(s)) {
                        s = ns;
                        compileChanged = true;
                        log.append("ℹ ").append(g.getName()).append(": compileSdk ").append(compile).append(" → ").append(cand).append(" (أصغر منصة مثبتة مناسبة)\n");
                        compile = cand;
                    } else {
                        log.append("⚠ ").append(g.getName()).append(": تعذّر تعديل compileSdk (صيغته غير مدعومة)؛ غيّره يدويًا إلى ").append(cand).append(".\n");
                    }
                } else if (cand == 0) {
                    log.append("⚠ ").append(g.getName()).append(": لا توجد منصة مثبتة بمستوى ").append(Math.max(compile, target)).append(" أو أعلى؛ نزّل منصة من مدير SDK.\n");
                }
            }
            String s1 = sub(s, MIN_RE, min);
            minChanged = !s1.equals(s);
            s = s1;
            if (compile == 0 && sdk != null && target > highestInstalled(sdk)) {
                log.append("⚠ ").append(g.getName()).append(": لم يُعرف compileSdk ولا توجد منصة مثبتة بمستوى ").append(target).append("؛ لم يُغيَّر target.\n");
            } else if (compile == 0 || target <= compile) {
                String s2 = sub(s, TGT_RE, target);
                tgtChanged = !s2.equals(s);
                s = s2;
            } else {
                log.append("⚠ ").append(g.getName()).append(": target ").append(target).append(" أعلى من compileSdk ").append(compile).append(" فلم يُغيَّر target (نزّل المنصة وارفع compileSdk).\n");
            }
            // تحذير: AGP أقدم مما يدعمه API المطلوب
            if (maxApi > 0 && ((tgtChanged && target > maxApi) || compile > maxApi)) {
                log.append("⚠ AGP ").append(agp).append(" يدعم حتى API ").append(maxApi).append(" فقط؛ قد يفشل البناء أو يحذّر. ارفع AGP")
                   .append(Math.max(compile, target) >= 37 ? " (API 37 يلزمه AGP 9.1.1 فأعلى)" : "").append(".\n");
            }
            if (minChanged || min < 26) {
                CompatScanner.Result cr2 = CompatScanner.scan(root, min);
                if (!cr2.clean()) log.append("⚠ ").append(g.getName()).append(": minSdk ").append(min)
                   .append(" طُبّق، لكن المصدر فيه استخدامات غير محمية قد تتعطل على الأجهزة الأقدم:\n").append(CompatScanner.report(cr2, min));
            }
            if (!s.equals(o)) {
                File bak = new File(g.getParentFile(), g.getName() + ".l2s-orig");
                try {
                    if (!bak.exists()) write(bak, o);
                    write(g, s);
                    changed++;
                    log.append("✔ عُدّل ").append(rel(root, g)).append(" (")
                       .append(minChanged ? "minSdk " + min : "minSdk بلا تغيير").append("، ")
                       .append(tgtChanged ? "target " + target : "target بلا تغيير")
                       .append(compileChanged ? "، compileSdk " + compile : "").append(")\n");
                } catch (IOException e) { log.append("✘ تعذّر تعديل ").append(g.getName()).append(": ").append(e.getMessage()).append("\n"); }
            }
        }
        if (changed == 0 && log.length() == 0) log.append("لا تغيير: القيم مطابقة أو معرّفة بمتغيرات (libs.versions) لا يعدّلها التطبيق.\n");
        return log.toString();
    }

    private static final String MARKER = ".l2s-multi-running";

    /** علامة تدل على أن حلقة «عدة APK» تعدّل المشروع مؤقتًا (تُحذف بعد الاستعادة). */
    static void setMarker(File root, boolean on) {
        File m = new File(root, MARKER);
        try { if (on) { new FileOutputStream(m).close(); } else m.delete(); } catch (IOException ignored) {}
    }

    /** إن بقيت علامة (انقطع التطبيق أثناء الحلقة) تُستعاد ملفات gradle الأصلية. يعيد true إن استُعيد شيء. */
    static boolean recoverIfStale(File root) {
        File m = new File(root, MARKER);
        if (!m.exists()) return false;
        restore(root);
        m.delete();
        return true;
    }

    /** يفحص مجلدات المشاريع (عمق حتى 3) ويستعيد ما تُرك معدّلًا. يعيد أسماء المشاريع المستعادة. */
    static List<String> recoverStaleAll(File projectsDir) {
        List<String> out = new ArrayList<>();
        File[] ps = projectsDir == null ? null : projectsDir.listFiles();
        if (ps == null) return out;
        for (File p : ps) if (p.isDirectory() && recoverTree(p, 0)) out.add(p.getName());
        return out;
    }

    private static boolean recoverTree(File d, int depth) {
        if (depth > 3) return false;
        boolean any = recoverIfStale(d);
        File[] ks = d.listFiles();
        if (ks == null) return any;
        for (File k : ks) {
            String n = k.getName();
            if (k.isDirectory() && !n.equals("build") && !n.equals(".gradle") && !n.equals(".git") && !n.equals("src") && !n.equals("node_modules"))
                any |= recoverTree(k, depth + 1);
        }
        return any;
    }

    /** يحذف النسخ .l2s-orig دون استعادتها (تُعدّ الملفات الحالية هي الأصل). */
    static void forgetBackups(File root) {
        List<File> all = new ArrayList<>();
        findBak(root, 0, all);
        for (File b : all) b.delete();
    }

    static String restore(File root) {
        List<File> all = new ArrayList<>();
        findBak(root, 0, all);
        int n = 0;
        for (File b : all) {
            File orig = new File(b.getParentFile(), b.getName().substring(0, b.getName().length() - ".l2s-orig".length()));
            try { write(orig, read(b)); b.delete(); n++; } catch (IOException ignored) {}
        }
        return n == 0 ? "لا توجد نسخ أصلية للاستعادة.\n" : "✔ استُعيدت " + n + " ملفات gradle الأصلية.\n";
    }

    private static String sub(String s, String re, int val) {
        Matcher m = Pattern.compile(re).matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + val));
        m.appendTail(sb);
        return sb.toString();
    }

    private static void find(File d, int depth, List<File> out) {
        if (depth > 5) return;
        File[] ks = d.listFiles();
        if (ks == null) return;
        for (File k : ks) {
            String n = k.getName();
            if (k.isDirectory()) { if (!n.equals("build") && !n.equals(".gradle") && !n.equals(".git") && !n.equals("src")) find(k, depth + 1, out); }
            else if (n.equals("build.gradle") || n.equals("build.gradle.kts")) out.add(k);
        }
    }

    private static void findBak(File d, int depth, List<File> out) {
        if (depth > 5) return;
        File[] ks = d.listFiles();
        if (ks == null) return;
        for (File k : ks) {
            if (k.isDirectory()) { String n = k.getName(); if (!n.equals("build") && !n.equals(".gradle") && !n.equals(".git") && !n.equals("src")) findBak(k, depth + 1, out); }
            else if (k.getName().endsWith(".l2s-orig")) out.add(k);
        }
    }

    private static String rel(File root, File f) { return root.toURI().relativize(f.toURI()).getPath(); }

    private static String read(File f) {
        try (InputStream in = new FileInputStream(f); ByteArrayOutputStream o = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            return o.toString("UTF-8");
        } catch (IOException e) { return ""; }
    }

    private static void write(File f, String s) throws IOException {
        try (OutputStream o = new FileOutputStream(f)) { o.write(s.getBytes("UTF-8")); }
    }
}
