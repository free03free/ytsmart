package com.example.l2sbuilder;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.*;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** مدير Gradle: تنزيل/حذف/اعتماد كل إصدار على حدة، ووضع بسيط أو تلقائي. */
public class GradleManagerActivity extends Activity {
    private static final int BG = 0xFF11121A, CARD = 0xFF1C1E2A, ACCENT = 0xFF7C5CFF,
            OK = 0xFF22D3B8, WARN = 0xFFFFB84D, DANGER = 0xFFFF5C7A, TXT = 0xFFF2F2F7, SUB = 0xFFA8ABBE;
    private static final String DEFAULT_VER = "8.11.1";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<String[]> remote = new ArrayList<>();   // {version,url} الأحدث أولًا
    private TextView status, info;
    private Spinner projSpin;
    private LinearLayout listBox;
    private int shown = 12;
    private volatile boolean busy = false, cancel = false;
    private File gh;
    private final Map<String, Long> remoteSize = new HashMap<>();   // حجم التنزيل
    private final Map<String, Long> diskSize = new HashMap<>();     // مساحة المنزَّل على الجهاز
    private final Map<String, TextView> sizeViews = new HashMap<>();
    private final Map<String, Button> dlButtons = new HashMap<>();
    private final Set<String> sizePending = new HashSet<>();
    private final java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();

    private SharedPreferences uiPrefs() { return getSharedPreferences("l2s_ui", MODE_PRIVATE); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        gh = new File(getFilesDir(), "gradle-home");
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(14);
        root.setPadding(p, p, p, p);
        sv.addView(root);
        setContentView(sv);

        TextView t = text("مدير Gradle", TXT, 20);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(t);
        status = text("", SUB, 13);
        root.addView(status);

        root.addView(section("المشروع"));
        projSpin = new Spinner(this);
        root.addView(projSpin);
        fillProjects();

        root.addView(section("الحالة"));
        info = text("", TXT, 13);
        root.addView(info);

        root.addView(section("وضع الاختيار"));
        RadioGroup mode = new RadioGroup(this);
        RadioButton simple = new RadioButton(this);
        simple.setText("بسيط: أستخدم الإصدار المعتمد فقط. أنزّله وأعتمده بنفسي من القائمة أدناه");
        RadioButton auto = new RadioButton(this);
        auto.setText("تلقائي (متقدم): يختار التطبيق Gradle المناسب للمشروع، وإن لم يكن منزَّلًا يُنزَّل أثناء البناء");
        styleBox(simple); styleBox(auto);
        mode.addView(simple); mode.addView(auto);
        if (uiPrefs().getBoolean("gradle_auto", false)) auto.setChecked(true); else simple.setChecked(true);
        mode.setOnCheckedChangeListener((g, id) -> {
            uiPrefs().edit().putBoolean("gradle_auto", id == auto.getId()).apply();
            refresh(); renderList();
        });
        root.addView(mode);

        root.addView(section("الإصدارات"));
        Button fetch = btn("تحديث القائمة من الإنترنت", ACCENT);
        fetch.setOnClickListener(v -> fetchGradle());
        root.addView(fetch);
        Button stop = btn("إيقاف التنزيل (يمكن استئنافه)", WARN);
        stop.setOnClickListener(v -> cancel = true);
        root.addView(stop);
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(listBox);
        Button more = btn("عرض المزيد", CARD);
        more.setOnClickListener(v -> { shown += 12; renderList(); });
        root.addView(more);
        Button reset = btn("إعادة الاعتماد إلى الافتراضي (" + DEFAULT_VER + ")", WARN);
        reset.setOnClickListener(v -> { uiPrefs().edit().remove("gradle_url").apply(); refresh(); renderList(); });
        root.addView(reset);

        projSpin.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> a, View v, int pos, long id) { refresh(); renderList(); }
            @Override public void onNothingSelected(AdapterView<?> a) {}
        });
        refresh();
        renderList();
        fetchGradle();
        final String dv = getIntent() == null ? null : getIntent().getStringExtra("download_ver");
        if (dv != null && !dv.isEmpty() && !isInstalled(dv)) ui.postDelayed(() -> confirmDownload(dv, GradleAdvisor.urlFor(dv)), 400);
    }

    // ================= حالة =================
    private File selectedProject() {
        Object it = projSpin.getSelectedItem();
        if (it == null || it.toString().startsWith("(")) return null;
        return new File(getFilesDir(), "projects/" + it);
    }

    private String manualVersion() {
        String cur = uiPrefs().getString("gradle_url", "");
        Matcher m = Pattern.compile("gradle-([0-9.]+)-(?:bin|all)").matcher(cur == null ? "" : cur);
        return m.find() ? m.group(1) : DEFAULT_VER;
    }

    private String agpOf(File proj) { return proj == null ? null : GradleAdvisor.detectAgp(proj); }
    private String recommended() { return GradleAdvisor.minGradleFor(agpOf(selectedProject())); }
    private boolean isInstalled(String v) { return GradleAdvisor.installedDir(gh, v) != null; }

    private void refresh() {
        if (info == null) return;
        boolean auto = uiPrefs().getBoolean("gradle_auto", false);
        String manual = manualVersion(), rec = recommended();
        File proj = selectedProject();
        StringBuilder sb = new StringBuilder();
        sb.append("الوضع الحالي: ").append(auto ? "تلقائي" : "بسيط").append("\n");
        String eff = (auto && rec != null) ? rec : manual;
        sb.append("▶ Gradle الذي سيُستخدم في البناء: ").append(eff).append("  ")
          .append(isInstalled(eff) ? "(✔ منزَّل)" : auto ? "(⬇ غير منزَّل — سيُنزَّل أثناء البناء)" : "(⚠ غير منزَّل — نزّله من القائمة قبل البناء)").append("\n");
        if (auto && rec == null) sb.append("لم يُعرف AGP للمشروع، فاستُخدم المعتمد يدويًا.\n");
        if (proj != null) {
            String agp = agpOf(proj);
            sb.append("المشروع «").append(proj.getName()).append("»: AGP ").append(agp == null ? "غير مكتشف" : agp);
            if (rec != null) sb.append(" → يوصى بـ Gradle ").append(rec);
            sb.append("\n");
        }
        if (!remote.isEmpty()) sb.append("أحدث إصدار متاح: ").append(remote.get(0)[0]).append("\n");
        List<String> cached = GradleAdvisor.cached(gh);
        sb.append("المنزَّل على جهازك: ").append(cached.isEmpty() ? "لا شيء" : cached.toString());
        if (!cached.isEmpty()) {
            long tot = 0; boolean all = true;
            for (String c : cached) { Long d = diskSize.get(c); if (d == null) all = false; else tot += d; }
            sb.append("\nإجمالي المساحة المستخدمة: ").append(all ? "\u200E" + mb(tot) + "\u200E" : "يحسب...");
        }
        info.setText(sb.toString());
    }

    // ================= القائمة =================
    private void renderList() {
        if (listBox == null) return;
        listBox.removeAllViews();
        sizeViews.clear(); dlButtons.clear();
        TreeMap<String, String> all = new TreeMap<>((x, y) -> GradleAdvisor.cmp(y, x));
        for (String[] r : remote) all.put(r[0], r[1]);
        for (String c : GradleAdvisor.cached(gh)) if (!all.containsKey(c)) all.put(c, GradleAdvisor.urlFor(c));
        if (all.isEmpty()) { listBox.addView(text("القائمة فارغة. اضغط «تحديث القائمة من الإنترنت».", WARN, 13)); return; }
        String newest = remote.isEmpty() ? null : remote.get(0)[0];
        String manual = manualVersion(), rec = recommended();
        boolean auto = uiPrefs().getBoolean("gradle_auto", false);
        int n = 0;
        for (Map.Entry<String, String> e : all.entrySet()) {
            // المنزَّلة تظهر دائمًا، وغيرها حتى حد العرض
            final String ver = e.getKey();
            final boolean inst = isInstalled(ver);
            if (!inst && n >= shown && !ver.equals(manual) && !ver.equals(rec)) continue;
            n++;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setBackgroundColor(CARD);
            row.setPadding(dp(10), dp(8), dp(10), dp(8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, dp(4), 0, dp(4));
            row.setLayoutParams(lp);
            StringBuilder h = new StringBuilder("Gradle ").append(ver);
            if (ver.equals(newest)) h.append("   ★ الأحدث");
            if (ver.equals(rec)) h.append("   👍 موصى به لمشروعك");
            if (ver.equals(manual)) h.append("   ⚙ المعتمد");
            row.addView(text(h.toString(), inst ? OK : TXT, 14));
            row.addView(text(inst ? "✔ منزَّل" : "⬇ غير منزَّل", inst ? OK : SUB, 13));
            TextView sz = text(sizeLabel(ver, inst), SUB, 13);
            sizeViews.put(ver, sz);
            row.addView(sz);
            requestSize(ver, e.getValue(), inst);
            LinearLayout btns = new LinearLayout(this);
            btns.setOrientation(LinearLayout.HORIZONTAL);
            if (!inst) {
                Button d = btn(remoteSize.containsKey(ver) ? "تنزيل  (\u200E" + mb(remoteSize.get(ver)) + "\u200E)" : "تنزيل", ACCENT);
                dlButtons.put(ver, d);
                d.setOnClickListener(v -> confirmDownload(ver, e.getValue()));
                btns.addView(d, new LinearLayout.LayoutParams(0, -2, 1f));
            } else {
                if (!ver.equals(manual)) {
                    Button u = btn("اعتماد", OK);
                    u.setOnClickListener(v -> adopt(ver));
                    btns.addView(u, new LinearLayout.LayoutParams(0, -2, 1f));
                }
                Button del = btn("حذف", DANGER);
                del.setOnClickListener(v -> confirmDelete(ver));
                btns.addView(del, new LinearLayout.LayoutParams(0, -2, 1f));
            }
            row.addView(btns);
            listBox.addView(row);
        }
    }

    private void adopt(String ver) {
        String warn = null;
        File proj = selectedProject();
        String agp = agpOf(proj), need = GradleAdvisor.minGradleFor(agp);
        if (need != null && ver.split("\\.")[0].compareTo(need.split("\\.")[0]) > 0)
            warn = "مشروعك يستخدم AGP " + agp + " (يحتاج Gradle ≥ " + need + "). الإصدار " + ver
                    + " من فرع أحدث وقد لا يتوافق معه. الأسلم: " + need + ".";
        new android.app.AlertDialog.Builder(this)
                .setMessage((warn == null ? "" : warn + "\n\n") + "اعتماد Gradle " + ver + "؟")
                .setPositiveButton("اعتمد", (d, x) -> {
                    uiPrefs().edit().putString("gradle_url", GradleAdvisor.urlFor(ver)).apply();
                    refresh(); renderList();
                })
                .setNegativeButton("إلغاء", null).show();
    }

    private void confirmDelete(String ver) {
        boolean cur = ver.equals(manualVersion());
        new android.app.AlertDialog.Builder(this)
                .setMessage("حذف Gradle " + ver + " من الجهاز؟" + (cur ? "\nهذا هو الإصدار المعتمد حاليًا، وسيُنزَّل مجددًا عند البناء إن لم تغيّره." : ""))
                .setPositiveButton("حذف", (d, x) -> {
                    rm(new File(gh, "wrapper/dists/" + GradleAdvisor.distName(ver)));
                    rm(new File(gh, "wrapper/dists/gradle-" + ver + "-all"));
                    rm(new File(gh, "caches/" + ver));
                    new File(gh, "local-dist-cache/gradle-" + ver + ".zip").delete();
                    diskSize.remove(ver);
                    status.setText("حُذف Gradle " + ver);
                    refresh(); renderList();
                })
                .setNegativeButton("إلغاء", null).show();
    }

    // ================= الأحجام =================
    private static String mb(long bytes) { return bytes >= 1048576 ? (bytes / 1048576) + " MB" : (bytes / 1024) + " KB"; }

    private String sizeLabel(String ver, boolean inst) {
        if (inst) {
            Long d = diskSize.get(ver);
            return d == null ? "المساحة على الجهاز: يحسب..." : "المساحة على الجهاز: \u200E" + mb(d) + "\u200E";
        }
        Long r = remoteSize.get(ver);
        return r == null ? "حجم التنزيل: يحسب..." : (r > 0 ? "حجم التنزيل: \u200E" + mb(r) + "\u200E" : "حجم التنزيل: غير معروف");
    }

    private void requestSize(final String ver, final String url, final boolean inst) {
        final String key = (inst ? "d:" : "r:") + ver;
        if (inst ? diskSize.containsKey(ver) : remoteSize.containsKey(ver)) return;
        if (!sizePending.add(key)) return;
        pool.execute(() -> {
            long v = -1;
            try {
                if (inst) {
                    File d = GradleAdvisor.installedDir(gh, ver);
                    v = d == null ? 0 : dirSize(d);
                } else {
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setRequestMethod("HEAD");
                    c.setConnectTimeout(15000); c.setReadTimeout(15000);
                    c.setInstanceFollowRedirects(true);
                    v = c.getContentLengthLong();
                }
            } catch (Exception ignored) {}
            final long val = v;
            ui.post(() -> {
                sizePending.remove(key);
                if (inst) diskSize.put(ver, Math.max(0, val)); else remoteSize.put(ver, Math.max(0, val));
                TextView tv = sizeViews.get(ver);
                if (tv != null) tv.setText(sizeLabel(ver, inst));
                Button b = dlButtons.get(ver);
                if (b != null && !inst && val > 0) b.setText("تنزيل  (\u200E" + mb(val) + "\u200E)");
                if (inst) refresh();
            });
        });
    }

    private static long dirSize(File f) {
        if (f.isFile()) return f.length();
        long t = 0;
        File[] ks = f.listFiles();
        if (ks != null) for (File k : ks) t += dirSize(k);
        return t;
    }

    // ================= جلب القائمة =================
    private void fetchGradle() {
        setStatus("يجلب إصدارات Gradle...");
        new Thread(() -> {
            try {
                List<String[]> l = GradleAdvisor.fetchStable();
                Collections.sort(l, (x, y) -> GradleAdvisor.cmp(y[0], x[0]));
                ui.post(() -> {
                    remote.clear(); remote.addAll(l);
                    status.setText("تم جلب " + l.size() + " إصدارًا (الأحدث أولًا).");
                    refresh(); renderList();
                });
            } catch (Exception e) { setStatus("تعذّر جلب القائمة (يمكنك إدارة المنزَّل بدونها): " + e.getMessage()); }
        }).start();
    }

    // ================= التنزيل =================
    private void confirmDownload(String ver, String url) {
        if (busy) { toast("عملية جارية"); return; }
        setStatus("يقدّر الحجم...");
        new Thread(() -> {
            long size = -1;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setRequestMethod("HEAD");
                c.setConnectTimeout(15000); c.setReadTimeout(15000);
                c.setInstanceFollowRedirects(true);
                size = c.getContentLengthLong();
            } catch (Exception ignored) {}
            final long sz = size;
            ui.post(() -> {
                status.setText("");
                long free = gh.getParentFile().getUsableSpace();
                String m = "تنزيل Gradle " + ver + "\nالحجم: " + (sz > 0 ? (sz / 1048576) + " MB" : "غير معروف")
                        + "\nالمساحة المتاحة: " + (free / 1048576) + " MB"
                        + (sz > 0 ? "\nيلزم نحو " + (sz * 3 / 1048576) + " MB أثناء التثبيت" : "");
                new android.app.AlertDialog.Builder(this).setMessage(m)
                        .setPositiveButton("تنزيل", (d, x) -> download(ver, url, sz))
                        .setNegativeButton("إلغاء", null).show();
            });
        }).start();
    }

    private static class Cancelled extends IOException {}

    private void download(String ver, String url, long sizeHint) {
        if (busy) return;
        busy = true; cancel = false;
        final Context appCtx = getApplicationContext();
        BuildService.start(appCtx);
        new Thread(() -> {
            File dlDir = new File(getFilesDir(), "gradle-dl"); dlDir.mkdirs();
            File part = new File(dlDir, "gradle-" + ver + ".part");
            File tmp = new File(gh, "wrapper/dists/.tmp-" + ver);
            try {
                String expect = fetchSha(url + ".sha256");
                if (expect == null) throw new IOException("تعذّر جلب بصمة SHA-256 الرسمية؛ أُلغي التنزيل حفاظًا على السلامة");
                int fails = 0; long lastHave = -1;
                for (int attempt = 1; ; attempt++) {
                    try {
                        long have = part.exists() ? part.length() : 0;
                        if (sizeHint > 0 && have > sizeHint) { part.delete(); have = 0; }
                        if (sizeHint > 0 && have == sizeHint) break;
                        setStatus((have > 0 ? "يستأنف" : "ينزّل") + " Gradle " + ver + " ...");
                        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                        c.setConnectTimeout(20000); c.setReadTimeout(30000);
                        c.setRequestProperty("User-Agent", "L2SBuilder");
                        if (have > 0) c.setRequestProperty("Range", "bytes=" + have + "-");
                        c.setInstanceFollowRedirects(true);
                        int rc = c.getResponseCode();
                        if (rc != 200 && rc != 206) throw new IOException("HTTP " + rc);
                        boolean append = rc == 206 && have > 0;
                        if (!append) have = 0;
                        long total = have + Math.max(0, c.getContentLengthLong());
                        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part, append)) {
                            byte[] buf = new byte[65536]; int n; long tot = have, last = 0;
                            while ((n = in.read(buf)) > 0) {
                                if (cancel) throw new Cancelled();
                                out.write(buf, 0, n); tot += n;
                                if (tot - last > 1048576) { last = tot; setStatus("ينزّل Gradle " + ver + ": " + (tot / 1048576) + "/" + (total / 1048576) + " MB"); }
                            }
                        }
                        break; // اكتمل البث بلا استثناء
                    } catch (Cancelled ce) {
                        throw new IOException("أُوقف التنزيل. اضغط تنزيل لاستئنافه.");
                    } catch (IOException e) {
                        long now = part.exists() ? part.length() : 0;
                        if (now > lastHave) fails = 0;      // تقدّم التنزيل: نصفّر العدّاد ونستمر
                        lastHave = now;
                        if (++fails > 10) throw new IOException(e.getMessage() + " (حُفظ الجزء المنزَّل للاستئناف، اضغط تنزيل لإكماله)");
                        setStatus("انقطع الاتصال، يُستأنف من " + (now / 1048576) + " MB — محاولة " + fails + "/10 ...");
                        long until = System.currentTimeMillis() + Math.min(20000L, 2000L * fails);
                        while (System.currentTimeMillis() < until) {
                            if (cancel) throw new IOException("أُوقف التنزيل. اضغط تنزيل لاستئنافه.");
                            try { Thread.sleep(500); } catch (InterruptedException ie) { throw e; }
                        }
                    }
                }
                setStatus("يتحقق من SHA-256...");
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                try (InputStream in = new FileInputStream(part)) {
                    byte[] buf = new byte[65536]; int n;
                    while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
                }
                if (!hex(md.digest()).equalsIgnoreCase(expect)) { part.delete(); throw new IOException("فشل التحقق من SHA-256؛ حُذف الملف التالف، أعد التنزيل"); }
                setStatus("يفك الحزمة...");
                rm(tmp); tmp.mkdirs();
                unzipSafe(part, tmp);
                File inner = new File(tmp, "gradle-" + ver);
                if (!new File(inner, "lib").isDirectory()) throw new IOException("بنية الحزمة غير متوقعة");
                File[] bins = new File(inner, "bin").listFiles();
                if (bins != null) for (File f : bins) f.setExecutable(true, false);
                String distUrl = GradleAdvisor.urlFor(ver);
                File hashDir = new File(gh, "wrapper/dists/" + GradleAdvisor.distName(ver) + "/" + GradleAdvisor.hashOf(distUrl));
                rm(hashDir); hashDir.mkdirs();
                if (!inner.renameTo(new File(hashDir, "gradle-" + ver))) throw new IOException("تعذّر نقل التوزيعة");
                new File(hashDir, GradleAdvisor.distName(ver) + ".zip.ok").createNewFile();
                rm(tmp); part.delete();
                ui.post(() -> { busy = false; diskSize.remove(ver); status.setText("✔ نُزّل Gradle " + ver + " وتم التحقق منه"); refresh(); renderList(); });
            } catch (Exception e) {
                rm(tmp);
                final String msg = e.getMessage();
                ui.post(() -> { busy = false; status.setText("✘ " + msg); });
            } finally {
                BuildService.stop(appCtx);
            }
        }).start();
    }

    private String fetchSha(String u) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(15000); c.setReadTimeout(15000);
            c.setInstanceFollowRedirects(true);
            if (c.getResponseCode() != 200) return null;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
                String l = r.readLine();
                if (l == null) return null;
                l = l.trim().split("\\s+")[0];
                return l.matches("[0-9a-fA-F]{64}") ? l : null;
            }
        } catch (Exception e) { return null; }
    }

    private void unzipSafe(File zip, File dest) throws IOException {
        String base = dest.getCanonicalPath() + File.separator;
        try (ZipInputStream z = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
            ZipEntry e; byte[] buf = new byte[65536];
            while ((e = z.getNextEntry()) != null) {
                File f = new File(dest, e.getName());
                if (!f.getCanonicalPath().startsWith(base)) throw new IOException("مسار مشبوه في الحزمة");
                if (e.isDirectory()) { f.mkdirs(); continue; }
                f.getParentFile().mkdirs();
                try (OutputStream o = new FileOutputStream(f)) { int n; while ((n = z.read(buf)) > 0) o.write(buf, 0, n); }
            }
        }
    }

    // ================= أدوات =================
    private void fillProjects() {
        File[] fs = new File(getFilesDir(), "projects").listFiles();
        List<String> names = new ArrayList<>();
        if (fs != null) { Arrays.sort(fs); for (File f : fs) if (f.isDirectory()) names.add(f.getName()); }
        if (names.isEmpty()) names.add("(لا توجد مشاريع)");
        projSpin.setBackgroundColor(CARD);
        projSpin.setPadding(dp(8), dp(10), dp(8), dp(10));
        projSpin.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, names) {
            @Override public View getView(int p, View c, android.view.ViewGroup g) {
                View v = super.getView(p, c, g);
                if (v instanceof TextView) { ((TextView) v).setTextColor(TXT); ((TextView) v).setTextSize(14); }
                return v;
            }
            @Override public View getDropDownView(int p, View c, android.view.ViewGroup g) {
                View v = super.getDropDownView(p, c, g);
                if (v instanceof TextView) { ((TextView) v).setTextColor(TXT); ((TextView) v).setTextSize(14); v.setBackgroundColor(CARD); }
                return v;
            }
        });
    }

    private static void rm(File f) {
        if (f == null || !f.exists()) return;
        File[] ks = f.listFiles();
        if (ks != null) for (File k : ks) rm(k);
        f.delete();
    }
    private static String hex(byte[] b) { StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format("%02x", x)); return s.toString(); }
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private void setStatus(String s) { ui.post(() -> status.setText(s)); }
    private TextView text(String s, int color, int sp) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextColor(color); t.setTextSize(sp);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }
    private TextView section(String s) {
        TextView t = text(s, ACCENT, 16);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setPadding(0, dp(18), 0, dp(6));
        return t;
    }
    private <T extends CompoundButton> T styleBox(T cb) {
        int[][] st = {{android.R.attr.state_checked}, {}};
        cb.setButtonTintList(new android.content.res.ColorStateList(st, new int[]{OK, 0xFFD0D3E6}));
        cb.setTextColor(TXT); cb.setTextSize(15);
        cb.setPadding(dp(8), dp(10), dp(8), dp(10));
        return cb;
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
