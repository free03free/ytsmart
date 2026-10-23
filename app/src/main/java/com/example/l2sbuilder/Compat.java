package com.example.l2sbuilder;

import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import java.io.*;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * طبقة توافق تجعل التطبيق يعمل من API 24 (Android 7.0).
 * على API 26+ تُستخدم نفس دوال java.nio.file السابقة تمامًا (السلوك لا يتغير)،
 * وعلى API 24/25 تُستخدم بدائل java.io و android.system.Os.
 */
final class Compat {
    private Compat() {}

    private static boolean nio() { return Build.VERSION.SDK_INT >= 26; }

    // ---------------------------------------------------------------- ملفات
    static byte[] readAllBytes(File f) throws IOException {
        if (nio()) return Nio.readAllBytes(f);
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) != -1) o.write(b, 0, n);
            return o.toByteArray();
        }
    }

    static List<String> readAllLines(File f, Charset cs) throws IOException {
        if (nio()) return Nio.readAllLines(f, cs);
        List<String> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), cs))) {
            String l;
            while ((l = r.readLine()) != null) out.add(l);
        }
        return out;
    }

    static void write(File f, byte[] data) throws IOException {
        if (nio()) { Nio.write(f, data, false); return; }
        try (OutputStream o = new FileOutputStream(f, false)) { o.write(data); }
    }

    static void writeAppend(File f, byte[] data) throws IOException {
        if (nio()) { Nio.write(f, data, true); return; }
        try (OutputStream o = new FileOutputStream(f, true)) { o.write(data); }
    }

    /** مثل Files.copy(src, dst, REPLACE_EXISTING). */
    static void copy(File src, File dst) throws IOException {
        if (nio()) { Nio.copy(src, dst, true); return; }
        copyFallback(src, dst);
    }

    /** مثل Files.copy(src, dst) بلا استبدال: يفشل إن كان الهدف موجودًا. */
    static void copyNoReplace(File src, File dst) throws IOException {
        if (nio()) { Nio.copy(src, dst, false); return; }
        if (dst.exists() || isSymbolicLink(dst)) throw new IOException("الهدف موجود: " + dst);
        copyFallback(src, dst);
    }

    private static void copyFallback(File src, File dst) throws IOException {
        if (src.isDirectory()) { dst.mkdirs(); return; }
        if (isSymbolicLink(dst)) dst.delete();
        try (InputStream in = new FileInputStream(src); OutputStream o = new FileOutputStream(dst)) {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) != -1) o.write(b, 0, n);
        }
        if (src.canExecute()) dst.setExecutable(true, false);
    }

    /** مثل Files.copy(in, dst, REPLACE_EXISTING). */
    static long copy(InputStream in, File dst) throws IOException {
        if (nio()) return Nio.copy(in, dst);
        if (isSymbolicLink(dst)) dst.delete();
        long total = 0;
        try (OutputStream o = new FileOutputStream(dst)) {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) != -1) { o.write(b, 0, n); total += n; }
        }
        return total;
    }

    static boolean deleteIfExists(File f) throws IOException {
        if (nio()) return Nio.deleteIfExists(f);
        if (!f.exists() && !isSymbolicLink(f)) return false;
        if (f.delete()) return true;
        throw new IOException("تعذّر الحذف: " + f);
    }

    /** مثل Files.move(a, b, REPLACE_EXISTING). */
    static void move(File a, File b) throws IOException {
        if (nio()) { Nio.move(a, b); return; }
        if (b.exists() && !b.isDirectory()) b.delete();
        if (!a.renameTo(b)) {
            copyFallback(a, b);
            if (!a.delete()) throw new IOException("تعذّر حذف المصدر بعد النقل: " + a);
        }
    }

    // ---------------------------------------------------------------- روابط
    static boolean isSymbolicLink(File f) {
        if (nio()) return Nio.isSymbolicLink(f);
        try {
            StructStat st = Os.lstat(f.getPath());
            return OsConstants.S_ISLNK(st.st_mode);
        } catch (ErrnoException | RuntimeException e) {
            return false;
        }
    }

    static void createSymbolicLink(File link, String target) throws IOException {
        if (nio()) { Nio.createSymbolicLink(link, target); return; }
        try { Os.symlink(target, link.getPath()); }
        catch (ErrnoException e) { throw new IOException(e.getMessage(), e); }
    }

    static void createLink(File link, File existing) throws IOException {
        if (nio()) { Nio.createLink(link, existing); return; }
        try { Os.link(existing.getPath(), link.getPath()); }
        catch (ErrnoException e) { throw new IOException(e.getMessage(), e); }
    }

    static String readSymbolicLink(File f) throws IOException {
        if (nio()) return Nio.readSymbolicLink(f);
        try { return Os.readlink(f.getPath()); }
        catch (ErrnoException e) { throw new IOException(e.getMessage(), e); }
    }

    /**
     * مجموع أحجام الملفات العادية تحت d دون تتبع الروابط.
     * يعيد -1 إن أُلغي (cancel != null && cancel.get()).
     */
    static long dirSize(File d, AtomicBoolean cancel) {
        if (nio()) return Nio.dirSize(d, cancel);
        long total = 0;
        ArrayDeque<File> stack = new ArrayDeque<>();
        stack.push(d);
        while (!stack.isEmpty()) {
            if (cancel != null && cancel.get()) return -1;
            File cur = stack.pop();
            if (isSymbolicLink(cur)) continue;
            if (cur.isDirectory()) {
                File[] ks = cur.listFiles();
                if (ks != null) for (File k : ks) stack.push(k);
            } else if (cur.isFile()) {
                total += cur.length();
            }
        }
        return (cancel != null && cancel.get()) ? -1 : total;
    }

    // ---------------------------------------------------------------- نصوص
    static String join(CharSequence delim, Iterable<? extends CharSequence> items) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (CharSequence c : items) {
            if (!first) sb.append(delim);
            sb.append(c);
            first = false;
        }
        return sb.toString();
    }

    static String join(CharSequence delim, CharSequence... items) {
        return join(delim, Arrays.asList(items));
    }

    // ---------------------------------------------------------------- عمليات
    static boolean waitFor(Process p, long timeout, TimeUnit unit) throws InterruptedException {
        if (Build.VERSION.SDK_INT >= 26) return p.waitFor(timeout, unit);
        long end = System.nanoTime() + unit.toNanos(timeout);
        while (true) {
            try { p.exitValue(); return true; } catch (IllegalThreadStateException running) { /* ما زالت تعمل */ }
            if (System.nanoTime() >= end) return false;
            Thread.sleep(50);
        }
    }

    static boolean isAlive(Process p) {
        if (p == null) return false;
        if (Build.VERSION.SDK_INT >= 26) return p.isAlive();
        try { p.exitValue(); return false; } catch (IllegalThreadStateException running) { return true; }
    }

    static void destroyForcibly(Process p) {
        if (p == null) return;
        if (Build.VERSION.SDK_INT >= 26) p.destroyForcibly(); else p.destroy();
    }

    static void redirectInput(ProcessBuilder pb, File f) {
        if (Build.VERSION.SDK_INT >= 26) pb.redirectInput(f);
    }

    // ---------------------------------------------------------------- وقت
    /** حقول وقت zip (DOS) من آخر تعديل بالمنطقة المحلية، مع حدّ أدنى 1980-01-01. */
    static int[] dosTime(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        int year = c.get(Calendar.YEAR), mon = c.get(Calendar.MONTH) + 1, day = c.get(Calendar.DAY_OF_MONTH);
        int h = c.get(Calendar.HOUR_OF_DAY), mi = c.get(Calendar.MINUTE), s = c.get(Calendar.SECOND);
        if (year < 1980) { year = 1980; mon = 1; day = 1; h = 0; mi = 0; s = 0; }
        int time = (h << 11) | (mi << 5) | (s / 2);
        int date = ((year - 1980) << 9) | (mon << 5) | day;
        return new int[]{time, date};
    }

    // ---------------------------------------------------------------- java.nio.file (API 26+ فقط)
    @android.annotation.TargetApi(26)
    private static final class Nio {
        static byte[] readAllBytes(File f) throws IOException { return java.nio.file.Files.readAllBytes(f.toPath()); }
        static List<String> readAllLines(File f, Charset cs) throws IOException { return java.nio.file.Files.readAllLines(f.toPath(), cs); }
        static void write(File f, byte[] d, boolean append) throws IOException {
            if (append) java.nio.file.Files.write(f.toPath(), d, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            else java.nio.file.Files.write(f.toPath(), d);
        }
        static void copy(File a, File b, boolean replace) throws IOException {
            if (replace) java.nio.file.Files.copy(a.toPath(), b.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            else java.nio.file.Files.copy(a.toPath(), b.toPath());
        }
        static long copy(InputStream in, File b) throws IOException {
            return java.nio.file.Files.copy(in, b.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        static boolean deleteIfExists(File f) throws IOException { return java.nio.file.Files.deleteIfExists(f.toPath()); }
        static void move(File a, File b) throws IOException {
            java.nio.file.Files.move(a.toPath(), b.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        static boolean isSymbolicLink(File f) { return java.nio.file.Files.isSymbolicLink(f.toPath()); }
        static void createSymbolicLink(File l, String t) throws IOException {
            java.nio.file.Files.createSymbolicLink(l.toPath(), java.nio.file.Paths.get(t));
        }
        static void createLink(File l, File e) throws IOException { java.nio.file.Files.createLink(l.toPath(), e.toPath()); }
        static String readSymbolicLink(File f) throws IOException { return java.nio.file.Files.readSymbolicLink(f.toPath()).toString(); }
        static long dirSize(File d, final AtomicBoolean cancel) {
            final long[] total = {0};
            try {
                java.nio.file.Files.walkFileTree(d.toPath(), new java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                    @Override public java.nio.file.FileVisitResult visitFile(java.nio.file.Path p, java.nio.file.attribute.BasicFileAttributes a) {
                        if (cancel != null && cancel.get()) return java.nio.file.FileVisitResult.TERMINATE;
                        if (a.isRegularFile()) total[0] += a.size();
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                    @Override public java.nio.file.FileVisitResult visitFileFailed(java.nio.file.Path p, IOException e) {
                        return (cancel != null && cancel.get()) ? java.nio.file.FileVisitResult.TERMINATE : java.nio.file.FileVisitResult.CONTINUE;
                    }
                    @Override public java.nio.file.FileVisitResult preVisitDirectory(java.nio.file.Path p, java.nio.file.attribute.BasicFileAttributes a) {
                        return (cancel != null && cancel.get()) ? java.nio.file.FileVisitResult.TERMINATE : java.nio.file.FileVisitResult.CONTINUE;
                    }
                });
            } catch (Throwable ignored) { }
            return (cancel != null && cancel.get()) ? -1 : total[0];
        }
    }
}
