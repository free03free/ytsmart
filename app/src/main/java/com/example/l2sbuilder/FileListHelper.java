package com.example.l2sbuilder;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.CornerPathEffect;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** ترتيب/فلترة/أيقونات لمتصفح الملفات (PathBrowser). يعمل على اسم الملف وبياناته فقط. */
public class FileListHelper {

    public enum SortBy { NAME, TYPE, DATE, SIZE }

    public enum Cat {
        FOLDER(0xFFFFB300, "مجلدات"),
        APK(0xFF3DDC84, "تطبيقات"),
        ARCHIVE(0xFF8E24AA, "مضغوطات"),
        IMAGE(0xFF039BE5, "صور"),
        VIDEO(0xFFE53935, "فيديو"),
        AUDIO(0xFFFB8C00, "صوت"),
        DOC(0xFF546E7A, "مستندات"),
        CODE(0xFF00897B, "أكواد"),
        WEB(0xFF5C6BC0, "ويب"),
        OTHER(0xFF78909C, "أخرى");

        public final int color; public final String label;
        Cat(int c, String l) { color = c; label = l; }
    }

    public static class Prefs {
        public SortBy sortBy = SortBy.NAME;
        public boolean ascending = true;
        public boolean foldersFirst = true;
        public boolean showIcons = true;
        public boolean showDetails = true;
        public boolean showHidden = true;
        public Cat filter = null;   // null = الكل
    }

    /** عنصر واحد في القائمة */
    public static class Entry {
        public String id, name;
        public boolean dir;
        public long size = -1, mtime = 0;
        public Uri uri;   // رابط الملف لقراءة أيقونة التطبيق / مصغّرة الصورة
        public Entry(String id, String name, boolean dir, long size, long mtime) {
            this.id = id; this.name = name; this.dir = dir; this.size = size; this.mtime = mtime;
        }
    }

    // ---------------------------------------------------------------- حفظ/تحميل
    private static final String SP = "file_list_prefs";

    public static Prefs load(Context c) {
        SharedPreferences sp = c.getSharedPreferences(SP, Context.MODE_PRIVATE);
        Prefs p = new Prefs();
        try { p.sortBy = SortBy.valueOf(sp.getString("sortBy", "NAME")); } catch (Exception ignored) {}
        p.ascending = sp.getBoolean("asc", true);
        p.foldersFirst = sp.getBoolean("foldersFirst", true);
        p.showIcons = sp.getBoolean("icons", true);
        p.showDetails = sp.getBoolean("details", true);
        p.showHidden = sp.getBoolean("hidden", true);
        String f = sp.getString("filter", "");
        if (!f.isEmpty()) try { p.filter = Cat.valueOf(f); } catch (Exception ignored) {}
        return p;
    }

    public static void save(Context c, Prefs p) {
        c.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
                .putString("sortBy", p.sortBy.name()).putBoolean("asc", p.ascending)
                .putBoolean("foldersFirst", p.foldersFirst).putBoolean("icons", p.showIcons)
                .putBoolean("details", p.showDetails).putBoolean("hidden", p.showHidden)
                .putString("filter", p.filter == null ? "" : p.filter.name()).apply();
    }

    // ---------------------------------------------------------------- التصنيف
    public static String ext(String n) {
        int i = n.lastIndexOf('.');
        return (i < 0 || i == n.length() - 1) ? "" : n.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    public static Cat category(String name, boolean dir) {
        if (dir) return Cat.FOLDER;
        switch (ext(name)) {
            case "apk": case "apks": case "xapk": case "aab": return Cat.APK;
            case "zip": case "rar": case "7z": case "tar": case "gz": case "tgz":
            case "xz": case "bz2": case "jar": case "aar": return Cat.ARCHIVE;
            case "jpg": case "jpeg": case "png": case "gif": case "webp": case "bmp":
            case "svg": case "heic": return Cat.IMAGE;
            case "mp4": case "mkv": case "avi": case "mov": case "3gp": case "webm": return Cat.VIDEO;
            case "mp3": case "wav": case "ogg": case "m4a": case "flac": case "aac": return Cat.AUDIO;
            case "txt": case "pdf": case "doc": case "docx": case "xls": case "xlsx":
            case "ppt": case "pptx": case "md": case "csv": case "log": return Cat.DOC;
            case "java": case "kt": case "py": case "js": case "ts": case "c": case "cpp":
            case "h": case "sh": case "gradle": case "xml": case "json": case "yml":
            case "yaml": case "properties": case "sql": case "php": case "go": case "rs": return Cat.CODE;
            case "htm": case "html": case "css": return Cat.WEB;
            default: return Cat.OTHER;
        }
    }

    // ---------------------------------------------------------------- ترتيب + فلترة
    public static ArrayList<Entry> apply(List<Entry> in, Prefs p) {
        ArrayList<Entry> out = new ArrayList<>();
        for (Entry e : in) {
            if (!p.showHidden && e.name.startsWith(".")) continue;
            if (p.filter != null && !e.dir && category(e.name, false) != p.filter) continue; // المجلدات تبقى للتنقل
            out.add(e);
        }
        Collections.sort(out, comparator(p));
        return out;
    }

    public static Comparator<Entry> comparator(final Prefs p) {
        return (a, b) -> {
            if (p.foldersFirst && a.dir != b.dir) return a.dir ? -1 : 1;
            int r;
            switch (p.sortBy) {
                case TYPE:
                    r = category(a.name, a.dir).compareTo(category(b.name, b.dir));
                    if (r == 0) r = ext(a.name).compareTo(ext(b.name));
                    if (r == 0) r = natural(a.name, b.name);
                    break;
                case DATE: r = Long.compare(a.mtime, b.mtime); break;
                case SIZE: r = Long.compare(a.dir ? 0 : a.size, b.dir ? 0 : b.size); break;
                default: r = natural(a.name, b.name);
            }
            return p.ascending ? r : -r;
        };
    }

    /** ترتيب طبيعي: file2 قبل file10 */
    static int natural(String a, String b) {
        a = a.toLowerCase(Locale.ROOT); b = b.toLowerCase(Locale.ROOT);
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char x = a.charAt(i), y = b.charAt(j);
            if (Character.isDigit(x) && Character.isDigit(y)) {
                int si = i, sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                String na = a.substring(si, i).replaceFirst("^0+(?=.)", "");
                String nb = b.substring(sj, j).replaceFirst("^0+(?=.)", "");
                if (na.length() != nb.length()) return na.length() - nb.length();
                int c = na.compareTo(nb);
                if (c != 0) return c;
            } else {
                if (x != y) return x - y;
                i++; j++;
            }
        }
        return (a.length() - i) - (b.length() - j);
    }

    // ---------------------------------------------------------------- تنسيق
    public static String humanSize(long b) {
        if (b < 0) return "";
        if (b < 1024) return b + " B";
        double v = b / 1024.0; String[] u = {"KB", "MB", "GB", "TB"}; int i = 0;
        while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
        return String.format(Locale.US, "%.1f %s", v, u[i]);
    }

    public static String humanDate(long t) {
        return t <= 0 ? "" : new SimpleDateFormat("yyyy-MM-dd  HH:mm", Locale.US).format(new Date(t));
    }

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    // ---------------------------------------------------------------- صف العنصر
    /** selected: null للمجلدات (بلا مربع اختيار)، وإلا حالة التحديد */
    public static LinearLayout makeRow(Context c, Entry e, Prefs p, Boolean selected) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(c, 8), dp(c, 7), dp(c, 8), dp(c, 7));
        if (selected != null && selected) row.setBackgroundColor(0x221E88E5);

        final Cat cat = category(e.name, e.dir);
        if (p.showIcons) {
            final int sz = dp(c, 42);
            final ImageView iv = new ImageView(c);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            final int rad = dp(c, 9);
            iv.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), rad);
                }
            });
            final TypeIcon fallback = new TypeIcon(e.name, cat);
            boolean real = !e.dir && e.uri != null && (cat == Cat.APK || cat == Cat.IMAGE);
            if (real) {
                // أثناء التحميل: مربع رمادي محايد (لا نُظهر أيقونة افتراضية للتطبيقات)
                GradientDrawable ph = new GradientDrawable();
                ph.setCornerRadius(rad);
                ph.setColor(0x22888888);
                iv.setImageDrawable(ph);
                loadIconAsync(c, e, cat, new Done() {
                    @Override public void got(Bitmap b) {
                        if (b != null) {
                            iv.setClipToOutline(true);
                            iv.setImageBitmap(b);
                        } else {
                            iv.setClipToOutline(false);
                            iv.setImageDrawable(fallback);   // تطبيق بلا أيقونة أو صورة تعذّر قراءتها
                        }
                    }
                });
            } else {
                iv.setImageDrawable(fallback);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sz, sz);
            lp.setMarginEnd(dp(c, 10));
            row.addView(iv, lp);
        }

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView name = new TextView(c);
        name.setText(e.name);
        name.setTextSize(15);
        name.setMaxLines(2);
        if (e.dir) name.setTypeface(null, Typeface.BOLD);
        col.addView(name);

        if (p.showDetails) {
            String info;
            if (e.dir) info = humanDate(e.mtime);
            else {
                info = humanSize(e.size);
                String d = humanDate(e.mtime);
                if (!d.isEmpty()) info += (info.isEmpty() ? "" : "  •  ") + d;
            }
            if (!info.isEmpty()) {
                TextView sub = new TextView(c);
                sub.setText(info);
                sub.setTextSize(12);
                sub.setTextColor(Color.GRAY);
                col.addView(sub);
            }
        }

        if (selected != null) {
            TextView ck = new TextView(c);
            ck.setText(selected ? "\u2611" : "\u2610");
            ck.setTextSize(22);
            ck.setPadding(dp(c, 8), 0, dp(c, 4), 0);
            row.addView(ck);
        }
        return row;
    }

    // ---------------------------------------------------------------- نافذة الخيارات
    public static void showOptionsDialog(final Context c, final Prefs p, final Runnable onChanged) {
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(c, 20), dp(c, 8), dp(c, 20), dp(c, 4));

        root.addView(label(c, "ترتيب حسب"));
        final RadioGroup sortGroup = new RadioGroup(c);
        final SortBy[] keys = {SortBy.NAME, SortBy.TYPE, SortBy.DATE, SortBy.SIZE};
        String[] names = {"الاسم", "النوع", "تاريخ التعديل", "الحجم"};
        for (int i = 0; i < keys.length; i++) {
            RadioButton rb = new RadioButton(c);
            rb.setText(names[i]); rb.setId(1000 + i);
            sortGroup.addView(rb);
            if (keys[i] == p.sortBy) rb.setChecked(true);
        }
        root.addView(sortGroup);
        final Switch asc = check(c, "تصاعدي (أ→ي ، الأقدم→الأحدث ، الأصغر→الأكبر)", p.ascending);
        final Switch folders = check(c, "المجلدات أولًا", p.foldersFirst);
        root.addView(asc); root.addView(folders);

        root.addView(label(c, "عرض نوع محدد"));
        final RadioGroup filterGroup = new RadioGroup(c);
        RadioButton all = new RadioButton(c);
        all.setText("الكل"); all.setId(2000);
        filterGroup.addView(all);
        if (p.filter == null) all.setChecked(true);
        final Cat[] cats = Cat.values();
        for (int i = 1; i < cats.length; i++) {          // FOLDER يظهر دائمًا
            RadioButton rb = new RadioButton(c);
            SpannableString ss = new SpannableString("\u25CF  " + cats[i].label);
            ss.setSpan(new ForegroundColorSpan(cats[i].color), 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            rb.setText(ss); rb.setId(2000 + i);
            filterGroup.addView(rb);
            if (p.filter == cats[i]) rb.setChecked(true);
        }
        root.addView(filterGroup);

        root.addView(label(c, "المظهر"));
        final Switch icons = check(c, "إظهار الأيقونات", p.showIcons);
        final Switch details = check(c, "إظهار الحجم والتاريخ", p.showDetails);
        final Switch hidden = check(c, "إظهار الملفات المخفية (.)", p.showHidden);
        root.addView(icons); root.addView(details); root.addView(hidden);

        ScrollView sv = new ScrollView(c);
        sv.addView(root);
        new AlertDialog.Builder(c)
                .setTitle("ترتيب وعرض الملفات")
                .setView(sv)
                .setPositiveButton("تطبيق", (d, w) -> {
                    int sid = sortGroup.getCheckedRadioButtonId();
                    if (sid >= 1000 && sid < 2000) p.sortBy = keys[sid - 1000];
                    p.ascending = asc.isChecked();
                    p.foldersFirst = folders.isChecked();
                    int fid = filterGroup.getCheckedRadioButtonId();
                    p.filter = (fid <= 2000) ? null : cats[fid - 2000];
                    p.showIcons = icons.isChecked();
                    p.showDetails = details.isChecked();
                    p.showHidden = hidden.isChecked();
                    save(c, p);
                    if (onChanged != null) onChanged.run();
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private static TextView label(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s); t.setTypeface(null, Typeface.BOLD); t.setTextSize(14);
        t.setPadding(0, dp(c, 14), 0, dp(c, 4));
        return t;
    }

    // سويتش: أحمر = متوقف ، أخضر = مشغّل
    private static Switch check(Context c, String s, boolean v) {
        Switch sw = new Switch(c);
        sw.setText(s);
        sw.setTextSize(14);
        sw.setPadding(0, dp(c, 8), 0, dp(c, 8));
        android.graphics.drawable.GradientDrawable off = new android.graphics.drawable.GradientDrawable();
        off.setColor(0xFFE53935); off.setCornerRadius(dp(c, 14)); off.setSize(dp(c, 52), dp(c, 28));
        android.graphics.drawable.GradientDrawable on = new android.graphics.drawable.GradientDrawable();
        on.setColor(0xFF43A047); on.setCornerRadius(dp(c, 14)); on.setSize(dp(c, 52), dp(c, 28));
        android.graphics.drawable.StateListDrawable track = new android.graphics.drawable.StateListDrawable();
        track.addState(new int[]{android.R.attr.state_checked}, on);
        track.addState(new int[]{}, off);
        sw.setTrackDrawable(track);
        android.graphics.drawable.GradientDrawable thumb = new android.graphics.drawable.GradientDrawable();
        thumb.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        thumb.setColor(0xFFFFFFFF); thumb.setStroke(dp(c, 1), 0x33000000); thumb.setSize(dp(c, 24), dp(c, 24));
        sw.setThumbDrawable(thumb);
        sw.setShowText(false);
        sw.setChecked(v);
        return sw;
    }

    // =========================================================== الأيقونات
    /** أيقونة ملف مرسومة: ورقة بزاوية مطويّة ولون النوع وامتداد الملف بخط واضح، أو مجلد. */
    static class TypeIcon extends Drawable {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path path = new Path();
        final RectF r = new RectF();
        final String label; final int color; final boolean folder;

        TypeIcon(String name, Cat cat) {
            folder = cat == Cat.FOLDER;
            String e = ext(name).toUpperCase(Locale.ROOT);
            if (cat == Cat.APK) e = "APK";
            label = e.length() > 4 ? e.substring(0, 4) : e;
            color = colorFor(name, cat);
        }

        @Override public void draw(Canvas cv) {
            Rect b = getBounds();
            if (folder) drawFolder(cv, b); else drawDoc(cv, b);
        }

        void drawFolder(Canvas cv, Rect b) {
            float w = b.width(), h = b.height();
            float l = b.left + w * 0.05f, rr = b.right - w * 0.05f;
            float t = b.top, bt = b.bottom - h * 0.14f;
            p.setStyle(Paint.Style.FILL);
            p.setPathEffect(null);
            float rad = w * 0.09f;
            p.setColor(0xFFF9A825);                       // خلف المجلد + اللسان
            r.set(l, t + h * 0.17f, l + w * 0.44f, t + h * 0.42f);
            cv.drawRoundRect(r, rad, rad, p);
            r.set(l, t + h * 0.27f, rr, bt);
            cv.drawRoundRect(r, rad, rad, p);
            p.setColor(0xFFFFCA28);                       // الواجهة الأمامية
            r.set(l, t + h * 0.38f, rr, bt);
            cv.drawRoundRect(r, rad, rad, p);
        }

        void drawDoc(Canvas cv, Rect b) {
            float w = b.width(), h = b.height();
            float dw = w * 0.70f;
            float l = b.left + (w - dw) / 2f, rr = l + dw;
            float t = b.top + h * 0.05f, bt = b.bottom - h * 0.05f;
            float fold = dw * 0.32f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(color);
            p.setPathEffect(new CornerPathEffect(w * 0.06f));
            path.reset();
            path.moveTo(l, t);
            path.lineTo(rr - fold, t);
            path.lineTo(rr, t + fold);
            path.lineTo(rr, bt);
            path.lineTo(l, bt);
            path.close();
            cv.drawPath(path, p);
            // الزاوية المطويّة
            p.setPathEffect(null);
            p.setColor(0x66FFFFFF);
            path.reset();
            path.moveTo(rr - fold, t);
            path.lineTo(rr - fold, t + fold);
            path.lineTo(rr, t + fold);
            path.close();
            cv.drawPath(path, p);

            p.setColor(Color.WHITE);
            if (label.isEmpty()) {                        // بلا امتداد: ثلاثة أسطر
                float lh = (bt - t) * 0.06f, x1 = l + dw * 0.2f, x2 = rr - dw * 0.2f;
                for (int i = 0; i < 3; i++) {
                    float y = t + (bt - t) * (0.50f + i * 0.15f);
                    r.set(x1, y, x2, y + lh);
                    cv.drawRoundRect(r, lh, lh, p);
                }
                return;
            }
            p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setTextAlign(Paint.Align.CENTER);
            float ts = dw * (label.length() <= 3 ? 0.38f : 0.31f);
            p.setTextSize(ts);
            while (p.measureText(label) > dw * 0.88f && ts > 6) { ts -= 1; p.setTextSize(ts); }
            cv.drawText(label, l + dw / 2f, t + (bt - t) * 0.72f, p);
        }

        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { p.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    static int colorFor(String name, Cat cat) {
        switch (ext(name)) {
            case "pdf": return 0xFFD32F2F;
            case "doc": case "docx": return 0xFF1976D2;
            case "xls": case "xlsx": case "csv": return 0xFF2E7D32;
            case "ppt": case "pptx": return 0xFFE64A19;
            case "txt": case "md": case "log": return 0xFF607D8B;
            default: return cat.color;
        }
    }

    // ---- تحميل أيقونة التطبيق الحقيقية / مصغّرة الصورة في الخلفية
    interface Done { void got(Bitmap b); }   // null = لا توجد أيقونة

    private static final LruCache<String, Bitmap> MEM = new LruCache<>(120);
    private static final Bitmap NONE = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
    private static final ConcurrentHashMap<String, Object> LOCKS = new ConcurrentHashMap<>();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final int ICON_PX = 128;

    static void loadIconAsync(Context ctx, final Entry e, final Cat cat, final Done cb) {
        final Context c = ctx.getApplicationContext();
        final String key = md5(e.uri + "|" + e.size + "|" + e.mtime + "|" + cat.name());
        Bitmap m = MEM.get(key);
        if (m != null) { cb.got(m == NONE ? null : m); return; }
        POOL.execute(new Runnable() {
            @Override public void run() {
                Object lock = LOCKS.computeIfAbsent(key, k -> new Object());
                Bitmap out;
                synchronized (lock) {
                    Bitmap m2 = MEM.get(key);
                    if (m2 != null) out = m2;
                    else {
                        Bitmap r = null;
                        try { r = diskOrCompute(c, e, cat, key); } catch (Throwable ignored) {}
                        out = r == null ? NONE : r;
                        MEM.put(key, out);
                    }
                }
                final Bitmap fo = out == NONE ? null : out;
                UI.post(new Runnable() { @Override public void run() { cb.got(fo); } });
            }
        });
    }

    private static Bitmap diskOrCompute(Context c, Entry e, Cat cat, String key) throws Exception {
        File dir = new File(c.getCacheDir(), "l2s_icons");
        dir.mkdirs();
        File png = new File(dir, key + ".png"), none = new File(dir, key + ".none");
        if (png.exists()) {
            Bitmap b = BitmapFactory.decodeFile(png.getAbsolutePath());
            if (b != null) return b;
        }
        if (none.exists()) return null;
        Bitmap b = cat == Cat.APK ? apkIcon(c, e) : imageThumb(c, e);
        if (b != null) {
            try (OutputStream os = new FileOutputStream(png)) { b.compress(Bitmap.CompressFormat.PNG, 100, os); }
        } else {
            none.createNewFile();
        }
        return b;
    }

    private static String md5(String s) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception ex) { return String.valueOf(s.hashCode()); }
    }

    private static Bitmap toBitmap(Drawable d) {
        if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null) {
            Bitmap src = ((BitmapDrawable) d).getBitmap();
            return Bitmap.createScaledBitmap(src, ICON_PX, ICON_PX, true);
        }
        Bitmap b = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        d.setBounds(0, 0, ICON_PX, ICON_PX);
        d.draw(cv);
        return b;
    }

    /** نتيجة قراءة APK: parsed=false يعني تعذّر فتح الملف كحزمة */
    private static class ApkRes { boolean parsed; Bitmap bmp; }

    private static ApkRes iconFromPath(PackageManager pm, String path) {
        ApkRes r = new ApkRes();
        try {
            PackageInfo pi = pm.getPackageArchiveInfo(path, 0);
            if (pi == null || pi.applicationInfo == null) return r;
            ApplicationInfo ai = pi.applicationInfo;
            ai.sourceDir = path;
            ai.publicSourceDir = path;
            r.parsed = true;
            if (ai.icon == 0) return r;                 // التطبيق نفسه بلا أيقونة
            Drawable d = ai.loadIcon(pm);
            if (d != null) r.bmp = toBitmap(d);
        } catch (Throwable ignored) {}
        return r;
    }

    private static Bitmap apkIcon(Context c, Entry e) throws Exception {
        PackageManager pm = c.getPackageManager();
        // المحاولة 1: قراءة مباشرة عبر واصف الملف (بلا نسخ)
        ParcelFileDescriptor pfd = null;
        try {
            pfd = c.getContentResolver().openFileDescriptor(e.uri, "r");
            if (pfd != null) {
                ApkRes r = iconFromPath(pm, "/proc/self/fd/" + pfd.getFd());
                if (r.parsed) return r.bmp;
            }
        } catch (Throwable ignored) {
        } finally {
            if (pfd != null) try { pfd.close(); } catch (Throwable ignored) {}
        }
        // المحاولة 2: نسخ مؤقت إلى الكاش ثم حذفه
        if (e.size > 300L * 1024 * 1024) return null;
        File tmp = new File(c.getCacheDir(), "l2s_apk_" + System.nanoTime() + ".apk");
        try (InputStream in = c.getContentResolver().openInputStream(e.uri);
             OutputStream os = new FileOutputStream(tmp)) {
            if (in == null) return null;
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
        try {
            return iconFromPath(pm, tmp.getAbsolutePath()).bmp;
        } finally {
            tmp.delete();
        }
    }

    private static Bitmap imageThumb(Context c, Entry e) {
        try {
            Bitmap t = DocumentsContract.getDocumentThumbnail(c.getContentResolver(), e.uri,
                    new Point(ICON_PX, ICON_PX), null);
            if (t != null) return t;
        } catch (Throwable ignored) {}
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = c.getContentResolver().openInputStream(e.uri)) {
                BitmapFactory.decodeStream(in, null, o);
            }
            int ss = 1;
            while (o.outWidth / (ss * 2) >= ICON_PX && o.outHeight / (ss * 2) >= ICON_PX) ss *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = ss;
            try (InputStream in = c.getContentResolver().openInputStream(e.uri)) {
                return BitmapFactory.decodeStream(in, null, o);
            }
        } catch (Throwable ignored) {
            return null;
        }
    }
}
