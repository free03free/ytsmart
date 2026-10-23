package com.example.l2sbuilder;

import android.content.Context;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** ينزّل AGP واعتمادياته مسبقًا إلى مخزن Gradle: يشغّل Gradle داخل proot على مشروع صغير مؤقت يعتمد على AGP فقط. */
final class AgpPrefetcher {
    private AgpPrefetcher() {}

    interface Cb { void onProgress(String msg); }

    private static volatile Process cur;
    private static volatile boolean cancelled;

    // ===== حالة المهمة (تبقى حيّة حتى لو أُغلقت الشاشة) =====
    interface Done { void onDone(String agp, String err, long mb); }
    private static final Object JOB = new Object();
    private static volatile boolean running;
    private static volatile String jobAgp = "", lastMsg = "";
    private static volatile Cb liveCb;
    private static volatile Done liveDone;

    /** أخطاء الشبكة والانقطاع فقط تستحق إعادة المحاولة؛ أخطاء الإعداد لا. */
    private static boolean retryable(String err) {
        if (err.startsWith("فشل (رمز ")) {
            String e = err.toLowerCase(java.util.Locale.ROOT);
            for (String k : new String[]{"could not get", "could not head", "timed out", "timeout", "connection", "unknownhost", "unable to resolve host",
                    "ssl", "premature end", "unexpected end", "network is unreachable", "reset", "broken pipe", "checksum", "download", "(رمز 137", "(رمز 143"})
                if (e.contains(k)) return true;
            return false;
        }
        return false;
    }

    private static boolean hasNet(Context ctx) {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Throwable t) { return true; }
    }

    static boolean isRunning() { return running; }
    static String runningAgp() { return jobAgp; }
    static String lastMessage() { return lastMsg; }

    /** تربط الواجهة بالمهمة الجارية (أو تفصلها بـ null) دون إيقاف المهمة. */
    static void attach(Cb cb, Done done) { liveCb = cb; liveDone = done; }

    /** تبدأ التنزيل في خيط مستقل وخدمة أمامية، فيستمر مع إغلاق الشاشة أو الخروج من الصفحة. */
    static boolean startBackground(final Context ctx0, final String agp, final String gv) {
        final Context ctx = ctx0.getApplicationContext();
        synchronized (JOB) {
            if (running) return false;
            running = true; jobAgp = agp; lastMsg = "يبدأ...";
        }
        BuildService.start(ctx);
        BuildService.noteTask(ctx, "agp", agp, "تنزيل AGP " + agp);
        Thread t = new Thread(() -> {
            long[] mb = new long[1];
            String err = null;
            final int MAX_TRIES = 12;
            for (int attempt = 1; attempt <= MAX_TRIES; attempt++) {
                final int at = attempt;
                try {
                    err = run(ctx, agp, gv, m -> {
                        String mm = at > 1 ? "🔁 محاولة " + at + "/" + MAX_TRIES + " (يُستأنف ما نُزِّل)\n" + m : m;
                        lastMsg = mm;
                        Cb c = liveCb; if (c != null) c.onProgress(mm);
                        BuildService.setStatus(ctx, mm.replace("\n", " — "));
                    }, mb);
                } catch (Throwable e) { err = "خطأ غير متوقع: " + e; }
                if (err == null || cancelled || attempt == MAX_TRIES || !retryable(err)) break;
                // انقطاع: ننتظر عودة الشبكة (حتى 10 دقائق) ثم نعيد المحاولة؛ الملفات المكتملة محفوظة فلا تُنزَّل ثانية.
                long wait = Math.min(30, 5L * attempt);
                String why = "انقطع التنزيل، إعادة المحاولة بعد " + wait + " ث...";
                lastMsg = why; Cb c = liveCb; if (c != null) c.onProgress(why);
                BuildService.setStatus(ctx, why);
                long until = System.currentTimeMillis() + wait * 1000;
                while (!cancelled && System.currentTimeMillis() < until) { try { Thread.sleep(500); } catch (InterruptedException ie) { cancelled = true; } }
                long netUntil = System.currentTimeMillis() + 10 * 60000L;
                while (!cancelled && !hasNet(ctx) && System.currentTimeMillis() < netUntil) {
                    String w = "لا يوجد اتصال بالإنترنت، بانتظار عودته...";
                    lastMsg = w; Cb c2 = liveCb; if (c2 != null) c2.onProgress(w);
                    BuildService.setStatus(ctx, w);
                    try { Thread.sleep(3000); } catch (InterruptedException ie) { cancelled = true; }
                }
                if (cancelled) { err = "أُوقف التنزيل. يمكنك إعادة المحاولة وسيُستأنف ما نُزِّل."; break; }
            }
            if (err == null) ctx.getSharedPreferences("l2s_ui", Context.MODE_PRIVATE).edit().putLong("agp_dl_mb_" + agp, mb[0]).apply();
            running = false;
            BuildService.setStatus(ctx, null);
            BuildService.stop(ctx);
            Done d = liveDone; if (d != null) d.onDone(agp, err, mb[0]);
        }, "agp-prefetch");
        t.start();
        return true;
    }

    static void cancel() { cancelled = true; Process p = cur; if (p != null) Compat.destroyForcibly(p); }

    static long cacheSize(File gh) { return dirSize(new File(gh, "caches/modules-2")); }

    /** كل ما يكتبه Gradle أثناء العمل (المخزن + الملفات المؤقتة)، لأن الملف لا يظهر في المخزن إلا بعد اكتماله. */
    private static long liveSize(File gh, File rootfs) {
        return dirSize(new File(gh, "caches")) + dirSize(new File(gh, ".tmp")) + dirSize(new File(rootfs, "tmp"));
    }

    private static long dirSize(File f) {
        if (f.isFile()) return f.length();
        long t = 0; File[] ks = f.listFiles();
        if (ks != null) for (File k : ks) t += dirSize(k);
        return t;
    }

    private static File findRoot(File d, int depth) {
        if (new File(d, "usr/bin/env").exists()) return d;
        if (depth == 0) return null;
        File[] ks = d.listFiles();
        if (ks != null) for (File k : ks)
            if (k.isDirectory() && !Compat.isSymbolicLink(k)) { File r = findRoot(k, depth - 1); if (r != null) return r; }
        return null;
    }

    /** يعيد null عند النجاح، أو نص الخطأ. mbOut[0] يحمل الحجم المنزَّل (MB). */
    static String run(Context ctx, String agp, String gv, Cb cb, long[] mbOut) {
        cancelled = false;
        File files = ctx.getFilesDir();
        File tools = new File(files, "tools"), tt = new File(tools, "tooltest");
        File base = new File(tt, "bin").exists() ? tt : tools;
        File rootfs = findRoot(new File(files, "rootfs"), 2);
        File sdk = new File(files, "sdk"), gh = new File(files, "gradle-home");
        if (!new File(base, "bin/proot").exists() || rootfs == null)
            return "أدوات البناء (proot وDebian) غير جاهزة: أكمل الخطوتين 1 و2 من القائمة الرئيسية أولًا.";
        File dist = GradleAdvisor.installedDir(gh, gv);
        if (dist == null) return "Gradle " + gv + " غير منزَّل. نزّله أولًا من «مدير Gradle».";
        gh.mkdirs();
        File tp = new File(files, "agp-prefetch/" + agp);
        tp.mkdirs();
        try {
            w(new File(tp, "settings.gradle"), "rootProject.name = 'agp-prefetch'\n");
            w(new File(tp, "build.gradle"),
                    "buildscript {\n  repositories { google(); mavenCentral() }\n  dependencies { classpath 'com.android.tools.build:gradle:" + agp + "' }\n}\n"
                            + "tasks.register('prefetch') { doLast { println 'AGP_RESOLVED' } }\n");
            w(new File(tp, "gradle.properties"), "org.gradle.jvmargs=-Xmx1024m -Dfile.encoding=UTF-8\n");
            File rc = new File(rootfs, "etc/resolv.conf");
            Compat.deleteIfExists(rc);
            w(rc, "nameserver 8.8.8.8\nnameserver 1.1.1.1\n");
        } catch (IOException e) { return "تعذّر تجهيز المشروع المؤقت: " + e; }

        File ah = new File(files, "android-home"); ah.mkdirs();
        new File(rootfs, "root/.android").mkdirs();
        List<String> c = new ArrayList<>();
        c.add(new File(base, "bin/proot").getAbsolutePath());
        c.add("--link2symlink"); c.add("--kill-on-exit"); c.add("-0");
        c.add("-r"); c.add(rootfs.getAbsolutePath());
        for (String b : new String[]{"/dev", "/proc", "/sys", "/system"}) { c.add("-b"); c.add(b); }
        if (new File("/apex").exists()) { c.add("-b"); c.add("/apex"); }
        c.add("-b"); c.add(base.getAbsolutePath() + ":/opt/l2s");
        c.add("-b"); c.add(sdk.getAbsolutePath() + ":/opt/sdk");
        c.add("-b"); c.add(gh.getAbsolutePath() + ":/root/.gradle");
        c.add("-b"); c.add(ah.getAbsolutePath() + ":/root/.android");
        c.add("-b"); c.add(tp.getAbsolutePath() + ":/prefetch");
        c.add("-w"); c.add("/prefetch");
        c.add("/usr/bin/env"); c.add("-i");
        c.add("HOME=/root");
        c.add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        c.add("LANG=C.UTF-8"); c.add("TMPDIR=/tmp");
        c.add("ANDROID_HOME=/opt/sdk");
        c.add("JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64");
        String relDist = dist.getAbsolutePath().substring(gh.getAbsolutePath().length());
        c.add("/bin/sh"); c.add("/root/.gradle" + relDist + "/bin/gradle");
        c.add("--no-daemon"); c.add("--console=plain"); c.add("--info"); c.add("prefetch");

        final long before = liveSize(gh, rootfs), beforeFinal = cacheSize(gh);
        final ArrayDeque<String> tail = new ArrayDeque<>();
        try {
            ProcessBuilder pb = new ProcessBuilder(c);
            pb.redirectErrorStream(true);
            Map<String, String> env = pb.environment();
            env.put("LD_LIBRARY_PATH", new File(base, "lib").getAbsolutePath());
            env.put("PROOT_LOADER", new File(base, "libexec/proot/loader").getAbsolutePath());
            env.put("PROOT_TMP_DIR", ctx.getCacheDir().getAbsolutePath());
            final Process p = pb.start();
            cur = p;
            Thread rd = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String l;
                    while ((l = br.readLine()) != null) synchronized (tail) { tail.add(l); if (tail.size() > 25) tail.poll(); }
                } catch (IOException ignored) {}
            });
            rd.start();
            long t0 = System.currentTimeMillis();
            while (true) {
                try { if (Compat.waitFor(p, 2, java.util.concurrent.TimeUnit.SECONDS)) break; } catch (InterruptedException e) { Compat.destroyForcibly(p); break; }
                long sec = (System.currentTimeMillis() - t0) / 1000;
                double mb = Math.max(0, liveSize(gh, rootfs) - before) / 1048576.0;
                String last; synchronized (tail) { last = tail.isEmpty() ? "" : tail.peekLast(); }
                if (last.length() > 90) last = last.substring(0, 90) + "…";
                String phase = mb < 0.5 ? (sec < 90 ? "① تشغيل Gradle داخل proot (قد يستغرق دقيقة أو أكثر)..." : "② الاتصال بالمستودعات (Google / Maven)...")
                        : "③ تنزيل الاعتماديات";
                cb.onProgress("⬇ AGP " + agp + "\n" + phase + "\nنُزِّل ≈ " + String.format(java.util.Locale.US, "%.1f", mb) + " MB   •   " + (sec / 60) + ":" + String.format(java.util.Locale.US, "%02d", sec % 60)
                        + (last.isEmpty() ? "" : "\n" + last));
                if (System.currentTimeMillis() - t0 > 30L * 60000) { Compat.destroyForcibly(p); return "انتهت المهلة (30 دقيقة). أعد المحاولة، يُستأنف ما نُزِّل سابقًا."; }
            }
            try { rd.join(2000); } catch (InterruptedException ignored) {}
            int code = p.exitValue();
            long mb = Math.max(0, cacheSize(gh) - beforeFinal) / 1048576;
            if (mbOut != null && mbOut.length > 0) mbOut[0] = mb;
            if (cancelled) return "أُوقف التنزيل. يمكنك إعادة المحاولة وسيُستأنف ما نُزِّل.";
            if (code != 0) { synchronized (tail) { return "فشل (رمز " + code + "):\n" + Compat.join("\n", tail); } }
            return null;
        } catch (Exception e) {
            return "تعذّر التشغيل: " + e;
        } finally { cur = null; }
    }

    private static void w(File f, String s) throws IOException { Compat.write(f, s.getBytes(StandardCharsets.UTF_8)); }
}
