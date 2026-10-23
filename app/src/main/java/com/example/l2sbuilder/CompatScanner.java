package com.example.l2sbuilder;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/**
 * فاحص توافق minSdk دقيق: يتجاهل التعليقات والنصوص الحرفية، ويفرّق بين الاستخدام المحمي بـ SDK_INT
 * (آمن) وغير المحمي (يتعطل على الأجهزة الأقدم)، ويعطي ملفًا وسطرًا لكل حالة.
 */
final class CompatScanner {
    private CompatScanner() {}

    static final class Hit {
        final String desc, file;
        final int api, line;
        Hit(String desc, int api, String file, int line) { this.desc = desc; this.api = api; this.file = file; this.line = line; }
    }

    static final class Result {
        final List<Hit> unguarded = new ArrayList<>();
        final List<Hit> guarded = new ArrayList<>();
        final List<String> resIssues = new ArrayList<>();
        int filesScanned;
        /** أعلى مستوى API بين الاستخدامات غير المحمية (0 إن لم توجد). */
        int maxUnguardedApi() { int m = 0; for (Hit h : unguarded) m = Math.max(m, h.api); return m; }
        boolean clean() { return unguarded.isEmpty() && resIssues.isEmpty(); }
    }

    // {regex, api, وصف}
    private static final String[][] RULES = {
            {"\\bjava\\.time\\.|\\bLocalDate(?:Time)?\\b|\\bInstant\\b|\\bZoneId\\b|\\bDuration\\.of", "26", "java.time (يلزم desugaring)"},
            {"\\bjava\\.nio\\.file\\.|\\.toPath\\(\\)|\\bPaths\\.get\\(|\\bFiles\\.[a-z]\\w*\\(", "26", "java.nio.file / File.toPath"},
            {"\\.startForegroundService\\(", "26", "startForegroundService"},
            {"\\bNotificationChannel\\b|\\.createNotificationChannel\\(|\\.getNotificationChannel\\(", "26", "قنوات الإشعارات"},
            {"\\.destroyForcibly\\(\\)|\\bproc(?:ess)?\\.isAlive\\(\\)|(?<!Compat)\\.waitFor\\([^()]*,[^()]*\\)", "26", "Process.destroyForcibly/isAlive/waitFor(مهلة)"},
            {"(?<!Compat)\\.redirect(?:Input|Output|Error)\\(", "26", "ProcessBuilder.redirect*"},
            {"\\.canRequestPackageInstalls\\(\\)", "26", "canRequestPackageInstalls"},
            {"\\bAdaptiveIconDrawable\\b", "26", "AdaptiveIconDrawable"},
            {"\\.(?:setTooltipText|setAutofillHints|setImportantForAutofill|setFocusedByDefault|setKeyboardNavigationCluster)\\(", "26", "واجهات View الحديثة (API 26)"},
            {"\\.(?:group|start|end)\\(\\s*\"\\s*\"\\s*\\)", "26", "مجموعات regex المسماة"},
            {"\\.getLongVersionCode\\(\\)", "28", "getLongVersionCode"},
            {"\\.getMainExecutor\\(", "28", "getMainExecutor"},
            {"\\bMediaStore\\.Downloads\\b", "29", "MediaStore.Downloads"},
            {"\\.isExternalStorageManager\\(\\)", "30", "isExternalStorageManager"},
            {"\\.setForegroundServiceBehavior\\(", "31", "setForegroundServiceBehavior"},
    };
    private static final Pattern[] RX = new Pattern[RULES.length];
    static { for (int i = 0; i < RULES.length; i++) RX[i] = Pattern.compile(RULES[i][0]); }

    private static final Pattern GE = Pattern.compile("SDK_INT\\s*(>=|>)\\s*(\\d+)");
    private static final Pattern LT = Pattern.compile("SDK_INT\\s*(<=|<)\\s*(\\d+)");
    private static final Pattern VC = Pattern.compile("SDK_INT\\s*(>=|>|<=|<)\\s*(?:Build\\.)?VERSION_CODES\\.([A-Z_0-9]+)");
    private static final Pattern ANN = Pattern.compile("@(?:[\\w.]*\\.)?(?:RequiresApi|TargetApi)\\s*\\(\\s*(?:api\\s*=\\s*|value\\s*=\\s*)?(?:(\\d+)|(?:Build\\.)?VERSION_CODES\\.([A-Z_0-9]+))");

    private static int codeLevel(String n) {
        switch (n) {
            case "O": return 26; case "O_MR1": return 27; case "P": return 28; case "Q": return 29;
            case "R": return 30; case "S": return 31; case "S_V2": return 32; case "TIRAMISU": return 33;
            case "UPSIDE_DOWN_CAKE": return 34; case "VANILLA_ICE_CREAM": return 35; case "BAKLAVA": return 36;
            case "N": return 24; case "N_MR1": return 25; case "M": return 23;
            default: return 0;
        }
    }

    static Result scan(File root, int minSdk) {
        Result r = new Result();
        walk(root, 0, root, minSdk, r);
        return r;
    }

    private static void walk(File d, int depth, File root, int min, Result r) {
        if (depth > 14) return;
        File[] ks = d.listFiles();
        if (ks == null) return;
        Arrays.sort(ks);
        for (File k : ks) {
            String n = k.getName();
            if (k.isDirectory()) {
                if (n.equals("build") || n.equals(".gradle") || n.equals(".git") || n.equals(".cxx") || n.equals("node_modules")) continue;
                if (Compat.isSymbolicLink(k)) continue;
                walk(k, depth + 1, root, min, r);
            } else if (n.endsWith(".java") || n.endsWith(".kt")) {
                if (k.length() > 3_000_000) continue;
                scanSource(k, min, r);
            } else if (n.endsWith(".xml") && min < 26 && isResDir(d)) {
                checkAdaptive(k, d, root, r);
            }
        }
    }

    private static boolean isResDir(File d) {
        File p = d.getParentFile();
        return p != null && p.getName().equals("res");
    }

    private static void checkAdaptive(File f, File dir, File root, Result r) {
        String q = dir.getName();
        if (!(q.startsWith("mipmap") || q.startsWith("drawable"))) return;
        Matcher v = Pattern.compile("-v(\\d+)").matcher(q);
        boolean safe = v.find() && Integer.parseInt(v.group(1)) >= 26;
        if (safe) return;
        String s = readSafe(f);
        if (s.contains("<adaptive-icon")) {
            r.resIssues.add(rel(root, f) + ": <adaptive-icon> خارج مجلد -v26 (يفشل AAPT عند minSdk < 26؛ انقله إلى " + q + "-v26)");
        }
    }

    private static void scanSource(File f, int min, Result r) {
        String src = readSafe(f);
        if (src.isEmpty()) return;
        r.filesScanned++;
        String[] lines = strip(src).split("\n", -1);
        boolean annotated = false;
        for (int i = 0; i < lines.length; i++) {
            String ln = lines[i];
            String t = ln.trim();
            if (t.isEmpty() || t.startsWith("import ") || t.startsWith("package ")) continue;
            for (int k = 0; k < RULES.length; k++) {
                int api = Integer.parseInt(RULES[k][1]);
                if (api <= min) continue;
                if (!RX[k].matcher(ln).find()) continue;
                Hit h = new Hit(RULES[k][2], api, f.getName(), i + 1);
                if (isGuarded(lines, i, api)) r.guarded.add(h); else r.unguarded.add(h);
            }
        }
    }

    /** الحماية: SDK_INT >= N (N >= api) في السطر أو قبله بنحو 40 سطرًا، أو شرط مبكر SDK_INT < N، أو @RequiresApi/@TargetApi. */
    private static boolean isGuarded(String[] lines, int idx, int api) {
        int from = Math.max(0, idx - 40);
        for (int i = idx; i >= from; i--) {
            String s = lines[i];
            if (s.indexOf("SDK_INT") >= 0) {
                Matcher m = GE.matcher(s);
                while (m.find()) {
                    int n = Integer.parseInt(m.group(2));
                    if (m.group(1).equals(">")) n++;
                    if (n >= api) return true;
                }
                m = LT.matcher(s);
                while (m.find()) {
                    int n = Integer.parseInt(m.group(2));
                    if (m.group(1).equals("<=")) n++;
                    if (n >= api) return true;
                }
                m = VC.matcher(s);
                while (m.find()) {
                    int n = codeLevel(m.group(2));
                    String op = m.group(1);
                    if (op.equals(">")) n++;
                    if (op.equals("<=")) n++;
                    if (n >= api) return true;
                }
            }
        }
        int annFrom = Math.max(0, idx - 150);
        for (int i = idx; i >= annFrom; i--) {
            if (lines[i].indexOf('@') < 0) continue;
            Matcher m = ANN.matcher(lines[i]);
            while (m.find()) {
                int n = m.group(1) != null ? Integer.parseInt(m.group(1)) : codeLevel(m.group(2));
                if (n >= api) return true;
            }
        }
        return false;
    }

    /** يحذف التعليقات ومحتوى النصوص الحرفية مع إبقاء الأسطر في أماكنها. */
    static String strip(String s) {
        StringBuilder o = new StringBuilder(s.length());
        int n = s.length(), i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                while (i < n && s.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                i += 2;
                while (i < n && !(s.charAt(i) == '*' && i + 1 < n && s.charAt(i + 1) == '/')) { if (s.charAt(i) == '\n') o.append('\n'); i++; }
                i += 2;
                o.append(' ');
            } else if (c == '"' && s.startsWith("\"\"\"", i)) {
                o.append("\"\"");
                i += 3;
                while (i < n && !s.startsWith("\"\"\"", i)) { if (s.charAt(i) == '\n') o.append('\n'); i++; }
                i += 3;
                o.append("\"\"");
            } else if (c == '"') {
                o.append('"');
                i++;
                while (i < n && s.charAt(i) != '"' && s.charAt(i) != '\n') { if (s.charAt(i) == '\\') i++; i++; }
                o.append('"');
                if (i < n && s.charAt(i) == '"') i++;
            } else if (c == '\'') {
                o.append('\'');
                i++;
                while (i < n && s.charAt(i) != '\'' && s.charAt(i) != '\n') { if (s.charAt(i) == '\\') i++; i++; }
                o.append('\'');
                if (i < n && s.charAt(i) == '\'') i++;
            } else { o.append(c); i++; }
        }
        return o.toString();
    }

    /** تقرير عربي مختصر وواضح. */
    static String report(Result r, int min) {
        StringBuilder sb = new StringBuilder();
        sb.append("فحص الواجهات لأقل إصدار API ").append(min).append(" (").append(r.filesScanned).append(" ملف مصدر، تجاهل التعليقات والنصوص):\n");
        if (r.unguarded.isEmpty()) {
            sb.append("✔ لا توجد استخدامات غير محمية لواجهات أحدث من API ").append(min).append(".\n");
        } else {
            sb.append("✘ ").append(r.unguarded.size()).append(" استخدام غير محمي (يتعطل على أجهزة أقدم من API ")
              .append(r.maxUnguardedApi()).append("):\n");
            Map<String, List<Hit>> g = new LinkedHashMap<>();
            for (Hit h : r.unguarded) { List<Hit> l = g.get(h.desc + "|" + h.api); if (l == null) { l = new ArrayList<>(); g.put(h.desc + "|" + h.api, l); } l.add(h); }
            for (Map.Entry<String, List<Hit>> e : g.entrySet()) {
                List<Hit> l = e.getValue();
                sb.append("  • ").append(l.get(0).desc).append(" — API ").append(l.get(0).api).append("+ — ").append(l.size()).append(" موضع: ");
                for (int i = 0; i < Math.min(3, l.size()); i++) sb.append(i > 0 ? "، " : "").append(l.get(i).file).append(':').append(l.get(i).line);
                if (l.size() > 3) sb.append(" …");
                sb.append('\n');
            }
        }
        if (!r.guarded.isEmpty()) sb.append("✔ ").append(r.guarded.size()).append(" استخدام محمي بشرط SDK_INT أو @RequiresApi (آمن).\n");
        for (String s : r.resIssues) sb.append("✘ ").append(s).append('\n');
        return sb.toString();
    }

    private static String rel(File root, File f) { return root.toURI().relativize(f.toURI()).getPath(); }

    private static String readSafe(File f) {
        try (InputStream in = new FileInputStream(f); ByteArrayOutputStream o = new ByteArrayOutputStream()) {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) != -1) o.write(b, 0, n);
            return new String(o.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) { return ""; }
    }
}
