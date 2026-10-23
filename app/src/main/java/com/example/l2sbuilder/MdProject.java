package com.example.l2sbuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** يحلّل ملف md المُصدَّر (أقسام "## FILE: المسار" + كتلة كود) إلى قائمة ملفات. */
public final class MdProject {

    public static final class Item {
        public String path;
        public String content;
        public int status; // 0 = بلا تغيير، 1 = معدَّل، 2 = جديد
    }

    public static final class Result {
        public final List<Item> items = new ArrayList<>();
        public int truncated;   // أقسام بلا إغلاق للكتلة (ردّ مقطوع)
        public int badPaths;    // مسارات مرفوضة (.. أو مطلقة)
    }

    private static final Pattern HEADER = Pattern.compile("^#{1,6}\\s*FILE\\s*:\\s*(.+?)\\s*$", Pattern.CASE_INSENSITIVE);

    private MdProject() {}

    /** ينظّف المسار؛ يعيد null إن كان غير آمن أو فارغًا. */
    public static String cleanPath(String p) {
        if (p == null) return null;
        p = p.trim();
        while (p.length() > 1 && ("`*\"'".indexOf(p.charAt(0)) >= 0)) p = p.substring(1).trim();
        while (p.length() > 1 && ("`*\"'".indexOf(p.charAt(p.length() - 1)) >= 0)) p = p.substring(0, p.length() - 1).trim();
        p = p.replace('\\', '/');
        while (p.startsWith("./")) p = p.substring(2);
        if (p.isEmpty() || p.startsWith("/") || p.endsWith("/") || p.matches("^[A-Za-z]:.*")) return null;
        for (String seg : p.split("/", -1)) {
            if (seg.isEmpty() || seg.equals("..") || seg.equals(".")) return null;
        }
        return p;
    }

    private static int fenceLen(String t, char ch) {
        int n = 0;
        while (n < t.length() && t.charAt(n) == ch) n++;
        return n;
    }

    public static Result parse(String text) {
        Result res = new Result();
        String[] lines = text.replace("\uFEFF", "").split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            Matcher m = HEADER.matcher(lines[i].trim());
            if (!m.matches()) { i++; continue; }
            String path = cleanPath(m.group(1));
            i++;
            while (i < lines.length && lines[i].trim().isEmpty()) i++;
            if (i >= lines.length) break;
            String open = lines[i].trim();
            char ch = open.startsWith("```") ? '`' : (open.startsWith("~~~") ? '~' : 0);
            if (ch == 0) continue; // لا كتلة كود بعد العنوان
            int flen = fenceLen(open, ch);
            i++;
            StringBuilder body = new StringBuilder();
            boolean closed = false;
            while (i < lines.length) {
                String t = lines[i].trim();
                if (t.length() >= flen && fenceLen(t, ch) == t.length()) { closed = true; i++; break; }
                body.append(lines[i]).append('\n');
                i++;
            }
            if (!closed) { res.truncated++; break; }
            if (path == null) { res.badPaths++; continue; }
            Item it = new Item();
            it.path = path;
            it.content = body.toString();
            res.items.add(it);
        }
        return res;
    }
}
