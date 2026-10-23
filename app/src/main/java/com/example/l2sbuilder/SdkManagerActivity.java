package com.example.l2sbuilder;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * مدير SDK + اختيار الإصدارات المستهدفة + فحص التوافق.
 * - ينزّل منصات android.jar الرسمية من dl.google.com مع التحقق من SHA-1 المنشور في ملف المستودع.
 * - لا يحذف شيئًا إلا بطلب صريح (زر حذف لمنصة واحدة).
 * - لا يلمس build-tools: ملفاتها تنفيذية لمعمارية x86_64 ولا تعمل مباشرة على ARM.
 */
public class SdkManagerActivity extends Activity {

    private static final String REPO_BASE = "https://dl.google.com/android/repository/";
    private static final String REPO_XML = REPO_BASE + "repository2-3.xml";
    private static final int BG = 0xFF11121A, CARD = 0xFF1C1E2A, ACCENT = 0xFF7C5CFF,
            OK = 0xFF22D3B8, WARN = 0xFFFFB84D, DANGER = 0xFFFF5C7A, TXT = 0xFFF2F2F7, SUB = 0xFFA8ABBE;

    // Android 7..15 => API
    private static final String[] NAMES = {"Android 7.0", "Android 7.1", "Android 8.0", "Android 8.1",
            "Android 9", "Android 10", "Android 11", "Android 12", "Android 12L", "Android 13", "Android 14", "Android 15", "Android 16", "Android 17"};
    private static final int[] APIS = {24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37};

    private static class Pkg { int api; String dir; String url; String sha1; long size; String rev; }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<Integer, Pkg> remote = new TreeMap<>();
    private File sdkDir;
    private SharedPreferences prefs;
    private LinearLayout sdkList, root;
    private TextView status, targetInfo, compatOut, apkOut, gradleLine;
    private final CheckBox[] boxes = new CheckBox[APIS.length];
    private LinearLayout boxList;
    private RadioButton rbSingle;
    private Spinner projSpin;
    private volatile boolean busy = false;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        sdkDir = new File(getFilesDir(), "sdk");
        prefs = getSharedPreferences("l2s_targets", MODE_PRIVATE);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(14);
        root.setPadding(p, p, p, p);
        sv.addView(root);
        setContentView(sv);

        root.addView(title("مدير SDK والإصدارات المستهدفة"));
        status = text("", SUB, 13);
        root.addView(status);

        // ---------- 1) المنصات ----------
        root.addView(section("1) منصات Android (android.jar)"));
        Button refresh = btn("تحديث القائمة من الإنترنت", ACCENT);
        refresh.setOnClickListener(v -> fetchRepo());
        root.addView(refresh);
        Button stop = btn("إيقاف التنزيل (يمكن استئنافه)", WARN);
        stop.setOnClickListener(v -> { cancel = true; });
        root.addView(stop);
        sdkList = new LinearLayout(this);
        sdkList.setOrientation(LinearLayout.VERTICAL);
        root.addView(sdkList);
        renderSdkList();

        // ---------- 2) الإصدارات المستهدفة ----------
        root.addView(section("2) الإصدارات المستهدفة"));
        root.addView(sub("اختر الإصدارات"));
        CheckBox only = new CheckBox(this);
        only.setText("إظهار المنصات المثبتة فقط");
        styleBox(only);
        only.setChecked(prefs.getBoolean("installed_only", false));
        only.setOnCheckedChangeListener((x, c) -> { prefs.edit().putBoolean("installed_only", c).apply(); renderTargets(); });
        root.addView(only);
        boxList = new LinearLayout(this);
        boxList.setOrientation(LinearLayout.VERTICAL);
        root.addView(boxList);
        renderTargets();
        root.addView(sub("طريقة البناء"));
        RadioGroup rg = new RadioGroup(this);
        rbSingle = new RadioButton(this);
        rbSingle.setText("APK واحد يغطي النطاق (minSdk = أقل إصدار، target = أعلى إصدار)");
        rbSingle.setTextColor(TXT);
        RadioButton rbMulti = new RadioButton(this);
        rbMulti.setText("عدة APK، واحد لكل إصدار محدد");
        rbMulti.setTextColor(TXT);
        styleBox(rbSingle); styleBox(rbMulti);
        rg.addView(rbSingle);
        rg.addView(rbMulti);
        if (prefs.getBoolean("single", true)) rbSingle.setChecked(true); else rbMulti.setChecked(true);
        rg.setOnCheckedChangeListener((g, id) -> saveTargets());
        root.addView(rg);
        root.addView(sub("خيارات إضافية"));
        CheckBox auto = new CheckBox(this);
        auto.setText("طبّق minSdk/targetSdk تلقائيًا عند بناء أي مشروع (وضع APK واحد؛ يحفظ نسخة .l2s-orig)");
        auto.setTextColor(TXT);
        styleBox(auto);
        auto.setChecked(prefs.getBoolean("apply_on_build", true));
        auto.setOnCheckedChangeListener((x, c) -> prefs.edit().putBoolean("apply_on_build", c).apply());
        root.addView(auto);
        root.addView(sub("ملخص ما سيُطبَّق"));
        targetInfo = text("", TXT, 13);
        root.addView(targetInfo);
        saveTargets();

        // ---------- 3) فحص التوافق ----------
        root.addView(section("3) فحص توافق مشروع"));
        root.addView(sub("اختر المشروع"));
        projSpin = new Spinner(this);
        root.addView(projSpin);
        fillProjects();
        Button scan = btn("افحص المشروع", OK);
        scan.setOnClickListener(v -> runCompat());
        root.addView(scan);
        Button fix = btn("طبّق minSdk/targetSdk المختارة على هذا المشروع", WARN);
        fix.setOnClickListener(v -> applyToProject(false));
        root.addView(fix);
        Button undo = btn("استعادة ملفات gradle الأصلية", DANGER);
        undo.setOnClickListener(v -> applyToProject(true));
        root.addView(undo);
        compatOut = text("", TXT, 13);
        compatOut.setTextIsSelectable(true);
        root.addView(compatOut);

        // ---------- 4) تقرير APK ----------
        root.addView(section("4) تقرير APK الناتج"));
        Button pick = btn("اختر ملف APK لفحصه", OK);
        pick.setOnClickListener(v -> {
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, 77);
        });
        root.addView(pick);
        apkOut = text("", TXT, 13);
        apkOut.setTextIsSelectable(true);
        root.addView(apkOut);

        root.addView(section("Gradle"));
        gradleLine = text("", TXT, 13);
        root.addView(gradleLine);
        Button openG = btn("افتح مدير Gradle", ACCENT);
        openG.setOnClickListener(v -> startActivity(new android.content.Intent(this, GradleManagerActivity.class)));
        root.addView(openG);
        refreshGradleLine();
        projSpin.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> a, View v, int pos, long id) { refreshGradleLine(); }
            @Override public void onNothingSelected(AdapterView<?> a) {}
        });

        fetchRepo();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshGradleLine();
    }

    private void refreshGradleLine() {
        if (gradleLine == null) return;
        SharedPreferences ui = getSharedPreferences("l2s_ui", MODE_PRIVATE);
        boolean auto = ui.getBoolean("gradle_auto", false);
        Matcher m = Pattern.compile("gradle-([0-9.]+)-(?:bin|all)").matcher(ui.getString("gradle_url", ""));
        String ver = m.find() ? m.group(1) : "8.11.1";
        String note = auto ? "تلقائي" : "بسيط";
        Object it = projSpin == null ? null : projSpin.getSelectedItem();
        if (auto && it != null && !it.toString().startsWith("(")) {
            String rec = GradleAdvisor.minGradleFor(GradleAdvisor.detectAgp(new File(getFilesDir(), "projects/" + it)));
            if (rec != null) ver = rec;
        }
        gradleLine.setText("Gradle المستخدم الآن: " + ver + "  (الوضع: " + note + ")");
    }

    // =====================================================================
    // 1) SDK
    // =====================================================================
    private void fetchRepo() {
        if (busy) return;
        busy = true;
        setStatus("يجلب قائمة الحزم من Google...");
        new Thread(() -> {
            try {
                HttpURLConnection c = open(REPO_XML);
                XmlPullParser xp = XmlPullParserFactory.newInstance().newPullParser();
                xp.setInput(c.getInputStream(), "UTF-8");
                Map<Integer, Pkg> found = new TreeMap<>();
                String path = null; int major = 0, minor = 0;
                Pkg cur = null; boolean inArchive = false, hostOs = false;
                String tag = ""; int ev = xp.getEventType();
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG) {
                        tag = xp.getName();
                        if (tag.equals("remotePackage")) {
                            path = xp.getAttributeValue(null, "path");
                            cur = null; major = 0; minor = 0; inArchive = false; hostOs = false;
                            if (path != null) {
                                Matcher m = Pattern.compile("^platforms;android-(\\d+)(\\.0)?$").matcher(path);
                                if (m.matches()) { cur = new Pkg(); cur.api = Integer.parseInt(m.group(1)); cur.dir = "android-" + cur.api + (m.group(2) != null ? ".0" : ""); cur.rev = ""; }
                            }
                        } else if (cur != null) {
                            if (tag.equals("major")) major = parseIntSafe(xp.nextText());
                            else if (tag.equals("minor")) minor = parseIntSafe(xp.nextText());
                            else if (tag.equals("archive")) { inArchive = true; hostOs = false; }
                            else if (tag.equals("host-os") && inArchive) { hostOs = true; xp.nextText(); }
                            else if (tag.equals("size") && inArchive && cur.size == 0) cur.size = parseLongSafe(xp.nextText());
                            else if (tag.equals("checksum") && inArchive && cur.sha1 == null) cur.sha1 = xp.nextText().trim();
                            else if (tag.equals("url") && inArchive && cur.url == null) cur.url = xp.nextText().trim();
                        }
                    } else if (ev == XmlPullParser.END_TAG) {
                        if (xp.getName().equals("remotePackage") && cur != null) {
                            cur.rev = major + "." + minor;
                            // نقبل فقط الأرشيف غير المرتبط بنظام تشغيل (android.jar عام)
                            if (cur.url != null && cur.sha1 != null && !hostOs && (!found.containsKey(cur.api) || !cur.dir.endsWith(".0"))) found.put(cur.api, cur);
                            cur = null;
                        }
                    }
                    ev = xp.next();
                }
                ui.post(() -> {
                    remote.clear(); remote.putAll(found);
                    busy = false;
                    setStatus(found.isEmpty() ? "لم يُعثر على منصات." : "تم: " + found.size() + " منصة متاحة.");
                    renderSdkList();
                });
            } catch (Exception e) {
                ui.post(() -> { busy = false; setStatus("تعذّر جلب القائمة: " + e); });
            }
        }).start();
    }

    private File platDir(int api) {
        File a = new File(sdkDir, "platforms/android-" + api);
        File b = new File(sdkDir, "platforms/android-" + api + ".0");
        if (new File(a, "android.jar").exists()) return a;
        if (new File(b, "android.jar").exists()) return b;
        return null;
    }

    private void renderSdkList() {
        sdkList.removeAllViews();
        for (int i = 0; i < APIS.length; i++) {
            final int api = APIS[i];
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setBackgroundColor(CARD);
            row.setPadding(dp(10), dp(8), dp(10), dp(8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, dp(4), 0, dp(4));
            row.setLayoutParams(lp);
            boolean inst = platDir(api) != null;
            Pkg pk = remote.get(api);
            String info = NAMES[i] + " — API " + api + "\n"
                    + (inst ? "✔ مثبتة" : "✘ غير مثبتة");
            if (pk != null) info += "\nالحجم: \u200E" + (pk.size / 1048576) + " MB\u200E   |   المراجعة: \u200E" + pk.rev;
            else if (!remote.isEmpty()) info += "\nغير متاحة للتنزيل";
            row.addView(text(info, inst ? OK : TXT, 14));
            LinearLayout btns = new LinearLayout(this);
            btns.setOrientation(LinearLayout.HORIZONTAL);
            if (!inst && pk != null) {
                Button d = btn("تنزيل  (\u200E" + (pk.size / 1048576) + " MB\u200E)", ACCENT);
                d.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                        .setMessage("تنزيل API " + api + "\nالحجم: " + (pk.size / 1048576) + " MB\nالمساحة المتاحة: " + (getFilesDir().getUsableSpace() / 1048576) + " MB\nيلزم نحو " + (pk.size * 3 / 1048576) + " MB أثناء التثبيت")
                        .setPositiveButton("تنزيل", (a, w) -> download(pk))
                        .setNegativeButton("إلغاء", null).show());
                btns.addView(d, new LinearLayout.LayoutParams(0, -2, 1f));
            }
            if (inst) {
                Button del = btn("حذف", DANGER);
                del.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                        .setMessage("حذف منصة API " + api + " فقط؟")
                        .setPositiveButton("حذف", (a, w) -> { rm(platDir(api)); renderSdkList(); })
                        .setNegativeButton("إلغاء", null).show());
                btns.addView(del, new LinearLayout.LayoutParams(0, -2, 1f));
            }
            row.addView(btns);
            sdkList.addView(row);
        }
        renderTargets();
    }

    private volatile boolean cancel = false;

    private void download(Pkg pk) {
        if (busy) { toast("عملية جارية"); return; }
        busy = true;
        cancel = false;
        final String url = pk.url.startsWith("http") ? pk.url : REPO_BASE + pk.url;
        new Thread(() -> {
            File part = new File(getCacheDir(), "platform-" + pk.api + ".part");
            File tmp = new File(getFilesDir(), "sdk.dl-" + pk.api);
            try {
                long free = getFilesDir().getUsableSpace();
                if (free < pk.size * 3) throw new IOException("المساحة غير كافية (يلزم نحو " + (pk.size * 3 / 1048576) + " MB)");
                for (int attempt = 1; ; attempt++) {
                    try {
                        long have = part.exists() ? part.length() : 0;
                        if (have > pk.size) { part.delete(); have = 0; }
                        if (have == pk.size) break;
                        setStatus((have > 0 ? "يستأنف" : "ينزّل") + " API " + pk.api + " ...");
                        HttpURLConnection c = openRange(url, have);
                        boolean append = c.getResponseCode() == 206 && have > 0;
                        if (!append) have = 0;
                        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part, append)) {
                            byte[] buf = new byte[65536]; int n; long tot = have, last = 0;
                            while ((n = in.read(buf)) > 0) {
                                if (cancel) throw new CancelledIOException();
                                out.write(buf, 0, n); tot += n;
                                if (tot - last > 1048576) { last = tot; final long t = tot; setStatus("ينزّل API " + pk.api + ": " + (t / 1048576) + "/" + (pk.size / 1048576) + " MB"); }
                            }
                        }
                        if (part.length() >= pk.size) break;
                        throw new IOException("انقطع التنزيل مبكرًا");
                    } catch (CancelledIOException ce) {
                        throw new IOException("أُوقف التنزيل. يمكنك استئنافه لاحقًا من نقطة التوقف.");
                    } catch (IOException e) {
                        if (attempt >= 4) throw new IOException(e.getMessage() + " (حُفظ الجزء المنزَّل، اضغط تنزيل للاستئناف)");
                        setStatus("انقطع الاتصال، إعادة المحاولة " + attempt + "/3 ...");
                        try { Thread.sleep(2000L * attempt); } catch (InterruptedException ie) { throw e; }
                    }
                }
                setStatus("يتحقق من SHA-1...");
                MessageDigest md = MessageDigest.getInstance("SHA-1");
                try (InputStream in = new FileInputStream(part)) {
                    byte[] buf = new byte[65536]; int n;
                    while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
                }
                if (!hex(md.digest()).equalsIgnoreCase(pk.sha1)) { part.delete(); throw new IOException("فشل التحقق من SHA-1 — الملف تالف وحُذف، أعد التنزيل"); }
                setStatus("يفك الحزمة...");
                rm(tmp); tmp.mkdirs();
                unzipSafe(part, tmp);
                File[] top = tmp.listFiles();
                File src = (top != null && top.length == 1 && top[0].isDirectory()) ? top[0] : tmp;
                if (!new File(src, "android.jar").exists()) throw new IOException("android.jar غير موجود داخل الحزمة");
                File dst = new File(sdkDir, "platforms/" + pk.dir);
                dst.getParentFile().mkdirs();
                rm(dst);
                if (!src.renameTo(dst)) throw new IOException("تعذّر نقل المنصة");
                rm(tmp); part.delete();
                ui.post(() -> { busy = false; setStatus("✔ ثُبّتت API " + pk.api + " (تم التحقق من SHA-1)"); renderSdkList(); });
            } catch (Exception e) {
                rm(tmp);
                ui.post(() -> { busy = false; setStatus("✘ " + e.getMessage()); });
            }
        }).start();
    }

    private static class CancelledIOException extends IOException {}

    private HttpURLConnection openRange(String u, long from) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "L2SBuilder");
        if (from > 0) c.setRequestProperty("Range", "bytes=" + from + "-");
        c.setInstanceFollowRedirects(true);
        int rc = c.getResponseCode();
        if (rc != 200 && rc != 206) throw new IOException("HTTP " + rc);
        return c;
    }

    private void unzipSafe(File zip, File dest) throws IOException {
        String base = dest.getCanonicalPath() + File.separator;
        try (ZipInputStream z = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
            ZipEntry e;
            byte[] buf = new byte[65536];
            while ((e = z.getNextEntry()) != null) {
                File f = new File(dest, e.getName());
                if (!f.getCanonicalPath().startsWith(base)) throw new IOException("مسار مشبوه في الحزمة");
                if (e.isDirectory()) { f.mkdirs(); continue; }
                f.getParentFile().mkdirs();
                try (OutputStream o = new FileOutputStream(f)) { int n; while ((n = z.read(buf)) > 0) o.write(buf, 0, n); }
            }
        }
    }

    // =====================================================================
    // 2) الإصدارات المستهدفة
    // =====================================================================
    private void renderTargets() {
        if (boxList == null) return;
        Set<String> saved = prefs.getStringSet("apis", new HashSet<>(Arrays.asList("24", "28", "36")));
        boxList.removeAllViews();
        int shown = 0;
        for (int i = 0; i < APIS.length; i++) {
            boxes[i] = null;
            boolean has = platDir(APIS[i]) != null;
            if (!has && prefs.getBoolean("installed_only", false)) continue;
            CheckBox cb = new CheckBox(this);
            cb.setText(NAMES[i] + "  (API " + APIS[i] + ")  " + (has ? "✔ منصة مثبتة" : "○ بدون منصة"));
            cb.setChecked(saved.contains(String.valueOf(APIS[i])));
            cb.setOnCheckedChangeListener((x, c) -> saveTargets());
            styleBox(cb);
            boxes[i] = cb;
            boxList.addView(cb);
            shown++;
        }
        if (shown == 0) boxList.addView(text("لا توجد منصات مثبتة. ألغِ خيار «المثبتة فقط» أو نزّل منصة من القسم 1.", WARN, 13));
        saveTargets();
    }

    private List<Integer> selected() {
        List<Integer> l = new ArrayList<>();
        for (int i = 0; i < APIS.length; i++) if (boxes[i] != null && boxes[i].isChecked()) l.add(APIS[i]);
        return l;
    }

    private void saveTargets() {
        if (targetInfo == null) return;
        List<Integer> sel = selected();
        Set<String> s = new HashSet<>(prefs.getStringSet("apis", new HashSet<String>()));
        for (int i = 0; i < APIS.length; i++) if (boxes[i] != null) s.remove(String.valueOf(APIS[i]));
        for (int a : sel) s.add(String.valueOf(a));
        boolean single = rbSingle == null || rbSingle.isChecked();
        Set<String> ms = new HashSet<>();
        for (int a : sel) ms.add(String.valueOf(a));
        prefs.edit().putStringSet("apis", s).putStringSet("multi_apis", ms).putBoolean("single", single).apply();
        if (sel.isEmpty()) { targetInfo.setText("علّم إصدارًا واحدًا على الأقل من المنصات المثبتة."); return; }
        int min = Collections.min(sel), max = Collections.max(sel);
        StringBuilder sb = new StringBuilder();
        if (single) {
            sb.append("سيُبنى APK واحد:\nminSdk ").append(min).append("  /  targetSdk ").append(max)
              .append("\n");
            sb.append("تنبيه: هذا APK سيُثبَّت أيضًا على الإصدارات بين ").append(min).append(" و").append(max)
              .append(" حتى لو لم تحددها، فأندرويد لا يسمح بتحديد إصدارات متقطعة داخل APK واحد.\n");
        } else {
            sb.append("ستُبنى ").append(sel.size()).append(" نسخ APK:\n");
            for (int a : sel) sb.append("• API ").append(a).append(": minSdk ").append(a).append(" / targetSdk ").append(a).append("\n");
            sb.append("اجعل لكل نسخة versionCode مختلفًا حتى يختار المتجر المناسب.\n");
        }
        int need = 0;
        for (int a : APIS) if (a >= max && platDir(a) != null) { need = a; break; }
        if (need == 0) {
            sb.append("⚠ للترجمة تحتاج منصة مثبتة بمستوى API ").append(max).append(" أو أعلى (نزّلها من القسم 1). اختيار minSdk لا يحتاج منصة.");
            need = max;
        } else {
            sb.append("compileSdk المتاح عندك: ").append(need).append(" (من المنصات المثبتة)");
        }
        targetInfo.setText(sb.toString());
        prefs.edit().putInt("minSdk", min).putInt("targetSdk", max).putInt("compileSdk", need).apply();
    }

    // =====================================================================
    // 3) فحص التوافق (فحص ثابت للمصدر — ليس اختبارًا فعليًا)
    // =====================================================================
    private void fillProjects() {
        File pd = new File(getFilesDir(), "projects");
        List<String> names = new ArrayList<>();
        File[] fs = pd.listFiles();
        if (fs != null) { Arrays.sort(fs); for (File f : fs) if (f.isDirectory()) names.add(f.getName()); }
        if (names.isEmpty()) names.add("(لا توجد مشاريع)");
        projSpin.setBackgroundColor(CARD);
        projSpin.setPadding(dp(8), dp(10), dp(8), dp(10));
        projSpin.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, names) {
            private View style(View v, boolean drop) {
                if (v instanceof TextView) {
                    TextView t = (TextView) v;
                    t.setTextColor(TXT);
                    t.setTextSize(15);
                    if (drop) t.setBackgroundColor(CARD);
                }
                return v;
            }
            @Override public View getView(int pos, View cv, android.view.ViewGroup par) { return style(super.getView(pos, cv, par), false); }
            @Override public View getDropDownView(int pos, View cv, android.view.ViewGroup par) { return style(super.getDropDownView(pos, cv, par), true); }
        });
    }

    private void runCompat() {
        Object it = projSpin.getSelectedItem();
        if (it == null || it.toString().startsWith("(")) { toast("لا يوجد مشروع"); return; }
        final File proj = new File(getFilesDir(), "projects/" + it);
        final List<Integer> sel = selected();
        if (sel.isEmpty()) { toast("اختر إصدارات مستهدفة أولًا"); return; }
        compatOut.setText("يفحص...");
        new Thread(() -> {
            String r;
            try { r = analyze(proj, Collections.min(sel), Collections.max(sel)); }
            catch (Exception e) { r = "فشل الفحص: " + e; }
            final String out = r;
            ui.post(() -> compatOut.setText(out));
        }).start();
    }

    private String analyze(File proj, int selMin, int selMax) throws IOException {
        StringBuilder sb = new StringBuilder();
        List<File> gradles = new ArrayList<>(), sources = new ArrayList<>(), manifests = new ArrayList<>(), sos = new ArrayList<>();
        walk(proj, 0, gradles, sources, manifests, sos);
        int projMin = -1, projTarget = -1, projCompile = -1;
        Pattern pm = Pattern.compile("minSdk(?:Version)?\\s*(?:=\\s*|\\()?\\s*(\\d+)");
        Pattern pt = Pattern.compile("targetSdk(?:Version)?\\s*(?:=\\s*|\\()?\\s*(\\d+)");
        Pattern pc = Pattern.compile("compileSdk(?:Version)?\\s*(?:=\\s*|\\()?\\s*(\\d+)");
        for (File g : gradles) {
            String s = read(g);
            Matcher m = pm.matcher(s); if (m.find()) projMin = Math.max(projMin, Integer.parseInt(m.group(1)));
            m = pt.matcher(s); if (m.find()) projTarget = Math.max(projTarget, Integer.parseInt(m.group(1)));
            m = pc.matcher(s); if (m.find()) projCompile = Math.max(projCompile, Integer.parseInt(m.group(1)));
        }
        sb.append("المشروع: ").append(proj.getName()).append("\n");
        sb.append("إعدادات Gradle الحالية: minSdk ").append(projMin < 0 ? "؟" : projMin)
          .append(" / target ").append(projTarget < 0 ? "؟" : projTarget)
          .append(" / compile ").append(projCompile < 0 ? "؟" : projCompile).append("\n");
        sb.append("المطلوب منك: minSdk ").append(selMin).append(" / target ").append(selMax).append("\n\n");
        if (projMin > selMin) sb.append("✘ minSdk في المشروع (").append(projMin).append(") أعلى من أقل إصدار مطلوب (").append(selMin).append("): لن يُثبَّت على الإصدارات الأقدم.\n");
        else if (projMin >= 0) sb.append("✔ minSdk في المشروع يغطي المطلوب.\n");

        // مكتبات أصلية
        Set<String> abis = new TreeSet<>();
        for (File so : sos) { File par = so.getParentFile(); if (par != null) abis.add(par.getName()); }
        if (!abis.isEmpty()) {
            sb.append("\nمكتبات أصلية (.so) بمعماريات: ").append(abis).append("\n");
            if (!abis.contains("arm64-v8a")) sb.append("✘ لا توجد arm64-v8a: لن تعمل على معظم الأجهزة الحديثة.\n");
            if (selMin <= 29 && !abis.contains("armeabi-v7a")) sb.append("⚠ لا توجد armeabi-v7a: الأجهزة 32-بت القديمة (Android 7-9 غالبًا) لن تعمل.\n");
        }

        // واجهات (فحص دقيق: يتجاهل التعليقات والنصوص ويفرّق المحمي عن غير المحمي)
        CompatScanner.Result cr = CompatScanner.scan(proj, selMin);
        sb.append("\n").append(CompatScanner.report(cr, selMin));
        if (projMin > selMin) {
            sb.append("\nالحل: اضغط «طبّق minSdk/targetSdk المختارة على هذا المشروع»")
              .append(cr.unguarded.isEmpty() ? " — الفحص نظيف فسيُخفَّض minSdk إلى " + selMin + ".\n"
                      : "؛ سيُخفَّض minSdk فعلًا فقط إن اختفت الاستخدامات غير المحمية أعلاه (في مشروع L2S نفسه)، أما في المشاريع الأخرى فيُطبَّق مع تحذير.\n");
        } else if (!cr.clean()) {
            sb.append("\nالقيمة المسجلة minSdk ").append(projMin < 0 ? "؟" : projMin).append(" لكن المصدر يستخدم واجهات أحدث دون حماية: غلّفها بـ Build.VERSION.SDK_INT >= N.\n");
        }
        sb.append("\nهذا فحص ثابت للمصدر (تقريبي في كشف الحماية)؛ لا يغني عن تجربة التطبيق على جهاز فعلي.");
        return sb.toString();
    }

    private void walk(File d, int depth, List<File> g, List<File> src, List<File> man, List<File> so) {
        if (depth > 12) return;
        File[] ks = d.listFiles();
        if (ks == null) return;
        for (File k : ks) {
            String n = k.getName();
            if (k.isDirectory()) {
                if (n.equals("build") || n.equals(".gradle") || n.equals(".git") || n.equals(".cxx")) continue;
                walk(k, depth + 1, g, src, man, so);
            } else if (n.endsWith(".gradle") || n.endsWith(".gradle.kts")) g.add(k);
            else if (n.equals("AndroidManifest.xml")) man.add(k);
            else if (n.endsWith(".java") || n.endsWith(".kt")) src.add(k);
            else if (n.endsWith(".so")) so.add(k);
        }
    }


    private void applyToProject(boolean restore) {
        Object it = projSpin.getSelectedItem();
        if (it == null || it.toString().startsWith("(")) { toast("لا يوجد مشروع"); return; }
        File proj = new File(getFilesDir(), "projects/" + it);
        File base = proj;
        File[] ks = proj.listFiles();
        if (ks != null && ks.length == 1 && ks[0].isDirectory()) base = ks[0];
        List<Integer> sel = selected();
        if (!restore && sel.isEmpty()) { toast("اختر إصدارات أولًا"); return; }
        if (!restore && !prefs.getBoolean("single", true)) {
            compatOut.setText("وضع «عدة APK»: يُطبَّق كل إصدار تلقائيًا أثناء البناء (فعّل «طبّق تلقائيًا عند البناء»)، ثم تُستعاد ملفات المشروع الأصلية. لا حاجة للتطبيق اليدوي.\nالإصدارات: " + sel + "\n");
            return;
        }
        compatOut.setText(restore ? TargetApplier.restore(base)
                : TargetApplier.apply(base, Collections.min(sel), Collections.max(sel), sdkDir));
    }

    @Override protected void onActivityResult(int req, int res, android.content.Intent data) {
        super.onActivityResult(req, res, data);
        if (req != 77 || res != RESULT_OK || data == null || data.getData() == null) return;
        final android.net.Uri u = data.getData();
        apkOut.setText("يفحص...");
        new Thread(() -> {
            String r;
            try { r = inspectApk(u); } catch (Exception e) { r = "فشل الفحص: " + e; }
            final String out = r;
            ui.post(() -> apkOut.setText(out));
        }).start();
    }

    private String inspectApk(android.net.Uri u) throws IOException {
        File f = new File(getCacheDir(), "inspect.apk");
        try (InputStream in = getContentResolver().openInputStream(u); OutputStream o = new FileOutputStream(f)) {
            byte[] b = new byte[65536]; int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
        }
        android.content.pm.PackageInfo pi = getPackageManager().getPackageArchiveInfo(f.getAbsolutePath(), 0);
        if (pi == null) { f.delete(); return "الملف ليس APK صالحًا."; }
        android.content.pm.ApplicationInfo ai = pi.applicationInfo;
        Set<String> abis = new TreeSet<>();
        try (ZipInputStream z = new ZipInputStream(new BufferedInputStream(new FileInputStream(f)))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                String n = e.getName();
                if (n.startsWith("lib/") && n.endsWith(".so")) { String[] p = n.split("/"); if (p.length >= 3) abis.add(p[1]); }
            }
        }
        int min = ai.minSdkVersion, tgt = ai.targetSdkVersion;
        StringBuilder sb = new StringBuilder();
        sb.append("الحزمة: ").append(pi.packageName).append("\nالإصدار: ").append(pi.versionName)
          .append("\nالحجم: ").append(f.length() / 1048576).append(" MB")
          .append("\nminSdk: ").append(min).append("  |  targetSdk: ").append(tgt).append("\n");
        sb.append("المعماريات: ").append(abis.isEmpty() ? "لا مكتبات أصلية (Java/Kotlin فقط → كل المعماريات)" : abis.toString()).append("\n\n");
        sb.append("الإصدارات المتوقع أن يُقبل تثبيته عليها:\n");
        for (int i = 0; i < APIS.length; i++) {
            if (APIS[i] < min) { sb.append("✘ ").append(NAMES[i]).append(" — أقل من minSdk\n"); continue; }
            String note = "";
            if (APIS[i] >= 35 && tgt < 24) { sb.append("✘ ").append(NAMES[i]).append(" — يمنع تثبيت targetSdk أقل من 24\n"); continue; }
            if (APIS[i] >= 34 && tgt < 23) { sb.append("✘ ").append(NAMES[i]).append(" — يمنع تثبيت targetSdk أقل من 23\n"); continue; }
            if (APIS[i] >= 30 && tgt < 30) note = " (قد تعمل بوضع توافق للتخزين)";
            if (APIS[i] >= 34 && tgt < 28) note = " (قد تظهر تحذيرات)";
            sb.append("✔ ").append(NAMES[i]).append(note).append("\n");
        }
        if (!abis.isEmpty()) {
            if (!abis.contains("arm64-v8a")) sb.append("\n⚠ لا arm64-v8a: لن يعمل على الأجهزة 64-بت فقط.");
            if (!abis.contains("armeabi-v7a")) sb.append("\n⚠ لا armeabi-v7a: لن يعمل على الأجهزة 32-بت.");
        }
        sb.append("\n\nهذا تقدير من بيانات الـAPK، وليس اختبار تشغيل فعلي.");
        f.delete();
        return sb.toString();
    }


    // =====================================================================
    // أدوات
    // =====================================================================
    private HttpURLConnection open(String u) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "L2SBuilder");
        c.setInstanceFollowRedirects(true);
        if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
        return c;
    }

    private static String read(File f) {
        try {
            if (f.length() > 2_000_000) return "";
            byte[] b = new byte[(int) f.length()];
            try (DataInputStream in = new DataInputStream(new FileInputStream(f))) { in.readFully(b); }
            return new String(b, "UTF-8");
        } catch (IOException e) { return ""; }
    }

    private static void rm(File f) {
        if (f == null || !f.exists()) return;
        File[] ks = f.listFiles();
        if (ks != null) for (File k : ks) rm(k);
        f.delete();
    }

    private static String hex(byte[] b) { StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format("%02x", x)); return s.toString(); }
    private static int parseIntSafe(String s) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; } }
    private static long parseLongSafe(String s) { try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0; } }
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private void setStatus(String s) { ui.post(() -> status.setText(s)); }

    private TextView text(String s, int color, int sp) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextColor(color); t.setTextSize(sp);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }
    private TextView title(String s) { TextView t = text(s, TXT, 20); t.setTypeface(null, android.graphics.Typeface.BOLD); return t; }
    private TextView section(String s) { TextView t = text(s, ACCENT, 16); t.setTypeface(null, android.graphics.Typeface.BOLD); t.setPadding(0, dp(18), 0, dp(6)); return t; }
    private <T extends CompoundButton> T styleBox(T cb) {
        int[][] st = {{android.R.attr.state_checked}, {}};
        cb.setButtonTintList(new android.content.res.ColorStateList(st, new int[]{OK, 0xFFD0D3E6}));
        cb.setTextColor(TXT);
        cb.setTextSize(15);
        cb.setPadding(dp(8), dp(10), dp(8), dp(10));
        return cb;
    }

    private TextView sub(String s) {
        TextView t = text(s, SUB, 13);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setPadding(0, dp(14), 0, dp(4));
        return t;
    }
    private Button btn(String s, int color) {
        Button b = new Button(this);
        b.setText(s); b.setTextColor(Color.WHITE); b.setBackgroundColor(color); b.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(dp(2), dp(4), dp(2), dp(4));
        b.setLayoutParams(lp);
        return b;
    }
}
