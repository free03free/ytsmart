package com.example.l2sbuilder;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
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
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.*;

/** مدير AGP: فحص توافق المشروع، تقرير نصائح وتحذيرات، إصلاحات بنقرة، وتغيير إصدار AGP مع نسخ احتياطي وتراجع. */
public class AgpManagerActivity extends Activity {
    private static final int BG = 0xFF11121A, CARD = 0xFF1C1E2A, ACCENT = 0xFF7C5CFF,
            OK = 0xFF22D3B8, WARN = 0xFFFFB84D, DANGER = 0xFFFF5C7A, TXT = 0xFFF2F2F7, SUB = 0xFFA8ABBE;
    private static final int JDK_IN_PROOT = 21;   // بيئة البناء تستخدم java-21
    private static final String DEFAULT_GRADLE = "8.11.1";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final List<String> versions = new ArrayList<>();   // أحدث patch لكل minor، الأحدث أولًا
    private ScrollView sv;
    private TextView status, verdict, summary, agpInfo, agpHeader;
    private Spinner projSpin;
    private LinearLayout findingsBox, agpBox;
    private Button fixAll;
    private EditText manual;
    private File gh, sdk;
    private ProjectDoctor.Report rep;
    private int shownAgp = 8;
    private final Map<String, Long> gSize = new HashMap<>();      // حجم تنزيل Gradle لكل إصدار (0 = غير معروف)
    private final Set<String> gPending = new HashSet<>();
    private final ExecutorService sizePool = Executors.newFixedThreadPool(2);

    private SharedPreferences prefs() { return getSharedPreferences("l2s_ui", MODE_PRIVATE); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        gh = new File(getFilesDir(), "gradle-home");
        sdk = new File(getFilesDir(), "sdk");
        sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(14);
        root.setPadding(p, p, p, p);
        sv.addView(root);
        setContentView(sv);

        TextView t = text("مدير AGP والفحص الشامل", TXT, 20);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(t);
        status = text("", SUB, 13);
        root.addView(status);
        root.addView(text("1) اختر مشروعك: يُفحص تلقائيًا.\n2) اقرأ النتيجة، واضغط زر الإصلاح تحت كل مشكلة (أو «إصلاح الكل»).\n3) كل تعديل يُحفظ له نسخة احتياطية ويمكن التراجع عنه.", SUB, 13));

        root.addView(section("المشروع"));
        projSpin = new Spinner(this);
        root.addView(projSpin);
        fillProjects();

        root.addView(section("نتيجة الفحص"));
        verdict = text("", TXT, 17);
        verdict.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(verdict);
        summary = text("", SUB, 13);
        root.addView(summary);
        fixAll = btn("🔧 إصلاح كل ما يمكن إصلاحه تلقائيًا", OK);
        fixAll.setOnClickListener(v -> confirmFixAll());
        fixAll.setVisibility(View.GONE);
        root.addView(fixAll);
        findingsBox = new LinearLayout(this);
        findingsBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(findingsBox);

        LinearLayout row = new LinearLayout(this);
        Button copy = btn("نسخ التقرير", ACCENT);
        copy.setOnClickListener(v -> copyReport());
        Button redo = btn("إعادة الفحص", CARD);
        redo.setOnClickListener(v -> analyze());
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(redo, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(row);
        Button undo = btn("↩ تراجع عن آخر تعديل", WARN);
        undo.setOnClickListener(v -> confirmUndo());
        root.addView(undo);

        agpHeader = section("تغيير إصدار AGP");
        root.addView(agpHeader);
        agpInfo = text("", TXT, 13);
        root.addView(agpInfo);
        Button fetch = btn("تحديث قائمة الإصدارات من الإنترنت", ACCENT);
        fetch.setOnClickListener(v -> fetchVersions());
        root.addView(fetch);
        agpBox = new LinearLayout(this);
        agpBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(agpBox);
        Button more = btn("عرض المزيد", CARD);
        more.setOnClickListener(v -> { shownAgp += 8; renderAgp(); });
        root.addView(more);
        root.addView(text("أو اكتب إصدارًا بنفسك (مثل 8.13.0):", SUB, 13));
        manual = new EditText(this);
        manual.setTextColor(TXT); manual.setHintTextColor(SUB); manual.setHint("8.13.0");
        manual.setBackgroundColor(CARD); manual.setPadding(dp(10), dp(10), dp(10), dp(10));
        manual.setSingleLine(true);
        root.addView(manual);
        Button apply = btn("تطبيق الإصدار المكتوب", ACCENT);
        apply.setOnClickListener(v -> {
            String s = manual.getText().toString().trim();
            if (!s.matches("\\d+\\.\\d+\\.\\d+")) { toast("الصيغة: رقم.رقم.رقم مثل 8.13.0"); return; }
            confirmAgp(s);
        });
        root.addView(apply);

        projSpin.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> a, View v, int pos, long id) { analyze(); }
            @Override public void onNothingSelected(AdapterView<?> a) {}
        });
        fetchVersions();
    }

    // ================= المشروع والحالة =================
    private File selectedProject() {
        Object it = projSpin.getSelectedItem();
        if (it == null || it.toString().startsWith("(")) return null;
        return new File(getFilesDir(), "projects/" + it);
    }

    private File backupBase(File proj) { return new File(getFilesDir(), "agp-backups/" + proj.getName()); }

    private String manualGradle() {
        Matcher m = Pattern.compile("gradle-([0-9.]+)-(?:bin|all)").matcher(prefs().getString("gradle_url", ""));
        return m.find() ? m.group(1) : DEFAULT_GRADLE;
    }

    private String effectiveGradle(File proj) {
        String rec = GradleAdvisor.minGradleFor(GradleAdvisor.detectAgp(proj));
        return (prefs().getBoolean("gradle_auto", false) && rec != null) ? rec : manualGradle();
    }

    private void analyze() {
        final File proj = selectedProject();
        if (proj == null) { verdict.setText("لا توجد مشاريع"); verdict.setTextColor(SUB); findingsBox.removeAllViews(); return; }
        status.setText("يفحص «" + proj.getName() + "» ...");
        pool.execute(() -> {
            String eff = effectiveGradle(proj);
            boolean inst = GradleAdvisor.installedDir(gh, eff) != null;
            ProjectDoctor.Report r;
            try { r = ProjectDoctor.analyze(proj, sdk, gh, JDK_IN_PROOT, eff, inst); }
            catch (Throwable e) { ui.post(() -> status.setText("تعذّر الفحص: " + e)); return; }
            final ProjectDoctor.Report fr = r;
            ui.post(() -> { rep = fr; status.setText("تم الفحص."); render(); renderAgp(); });
        });
    }

    // ================= عرض التقرير =================
    private void render() {
        if (rep == null) return;
        int v = rep.verdict();
        verdict.setText(rep.verdictText());
        verdict.setTextColor(v == ProjectDoctor.BAD ? DANGER : v == ProjectDoctor.WARN ? WARN : OK);
        summary.setText("AGP " + (rep.agp == null ? "؟" : rep.agp) + "  |  Gradle المعتمد " + (rep.effGradle == null ? "؟" : rep.effGradle)
                + "  |  JDK " + rep.jdk + "\n✘ " + rep.count(ProjectDoctor.BAD) + "   ⚠ " + rep.count(ProjectDoctor.WARN) + "   ℹ " + rep.count(ProjectDoctor.INFO) + "   ✔ " + rep.count(ProjectDoctor.OK));
        findingsBox.removeAllViews();
        boolean anyAuto = false;
        for (int lvl = ProjectDoctor.BAD; lvl >= ProjectDoctor.OK; lvl--) {
            for (final ProjectDoctor.Finding f : rep.items) {
                if (f.level != lvl) continue;
                LinearLayout c = new LinearLayout(this);
                c.setOrientation(LinearLayout.VERTICAL);
                c.setBackgroundColor(CARD);
                c.setPadding(dp(10), dp(8), dp(10), dp(8));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.setMargins(0, dp(4), 0, dp(4));
                c.setLayoutParams(lp);
                String icon = lvl == ProjectDoctor.BAD ? "✘ " : lvl == ProjectDoctor.WARN ? "⚠ " : lvl == ProjectDoctor.INFO ? "ℹ " : "✔ ";
                int col = lvl == ProjectDoctor.BAD ? DANGER : lvl == ProjectDoctor.WARN ? WARN : lvl == ProjectDoctor.INFO ? SUB : OK;
                TextView h = text(icon + f.title, col, 14);
                h.setTypeface(null, android.graphics.Typeface.BOLD);
                c.addView(h);
                c.addView(text(f.detail, TXT, 13));
                if (f.fixId != null) {
                    Button fb = btn(f.fixLabel, (f.fixId.equals("SDKMGR") || f.fixId.equals("AGPLIST")) ? ACCENT : OK);
                    fb.setOnClickListener(x -> runFix(f));
                    c.addView(fb);
                    if (isAutoFix(f.fixId)) anyAuto = true;
                }
                findingsBox.addView(c);
            }
        }
        fixAll.setVisibility(anyAuto ? View.VISIBLE : View.GONE);
    }

    private static boolean isAutoFix(String id) { return !"SDKMGR".equals(id) && !"SETAGP".equals(id) && !"AGPLIST".equals(id); }

    // ================= الإصلاحات =================
    private void runFix(final ProjectDoctor.Finding f) {
        if ("SDKMGR".equals(f.fixId)) { startActivity(new Intent(this, SdkManagerActivity.class)); return; }
        if ("SETAGP".equals(f.fixId)) { confirmAgp(f.fixArg); return; }
        if ("AGPLIST".equals(f.fixId)) { sv.post(() -> sv.smoothScrollTo(0, agpBox.getTop())); return; }
        new AlertDialog.Builder(this)
                .setTitle(f.fixLabel)
                .setMessage("سيُنفَّذ هذا التعديل، وتُحفظ نسخة احتياطية قبله ويمكنك التراجع.\n\n" + f.title)
                .setPositiveButton("نفّذ", (d, x) -> applyFixes(Collections.singletonList(f)))
                .setNegativeButton("إلغاء", null).show();
    }

    private void confirmFixAll() {
        if (rep == null) return;
        final List<ProjectDoctor.Finding> fs = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (ProjectDoctor.Finding f : rep.items) if (f.fixId != null && isAutoFix(f.fixId)) { fs.add(f); sb.append("• ").append(f.fixLabel).append("\n"); }
        if (fs.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle("إصلاح الكل")
                .setMessage(sb + "\nتُحفظ نسخة احتياطية أولًا ويمكنك التراجع.")
                .setPositiveButton("نفّذ", (d, x) -> applyFixes(fs))
                .setNegativeButton("إلغاء", null).show();
    }

    private void applyFixes(final List<ProjectDoctor.Finding> fs) {
        final File proj = selectedProject();
        if (proj == null || rep == null) return;
        status.setText("يطبّق الإصلاحات ...");
        final File root = rep.root;
        pool.execute(() -> {
            StringBuilder log = new StringBuilder();
            ProjectDoctor.Backup bk = new ProjectDoctor.Backup(backupBase(proj), root);
            for (ProjectDoctor.Finding f : fs) {
                try {
                    if ("SETGRADLE".equals(f.fixId)) {
                        prefs().edit().putString("gradle_url", GradleAdvisor.urlFor(f.fixArg)).apply();
                        log.append("• اعتُمد Gradle ").append(f.fixArg).append(" في التطبيق")
                           .append(GradleAdvisor.installedDir(gh, f.fixArg) == null ? " (غير منزَّل: نزّله من «مدير Gradle»)" : "").append("\n");
                    } else log.append(ProjectDoctor.applyFix(root, f.fixId, f.fixArg, bk));
                } catch (Throwable e) { log.append("✘ ").append(f.fixLabel).append(": ").append(e).append("\n"); }
            }
            final String msg = log.toString();
            ui.post(() -> { done("تم", msg.isEmpty() ? "لم يتغير شيء." : msg); analyze(); });
        });
    }

    private void confirmUndo() {
        final File proj = selectedProject();
        if (proj == null) return;
        new AlertDialog.Builder(this)
                .setMessage("التراجع عن آخر تعديل أجرته من هذه الشاشة على «" + proj.getName() + "»؟")
                .setPositiveButton("تراجع", (d, x) -> pool.execute(() -> {
                    String r = ProjectDoctor.undoLast(backupBase(proj));
                    ui.post(() -> { toast(r == null ? "لا توجد نسخة احتياطية للتراجع" : r); analyze(); });
                }))
                .setNegativeButton("إلغاء", null).show();
    }

    // ================= AGP =================
    private void fetchVersions() {
        status.setText("يجلب إصدارات AGP ...");
        pool.execute(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/maven-metadata.xml").openConnection();
                c.setConnectTimeout(20000); c.setReadTimeout(30000);
                if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) { String l; while ((l = r.readLine()) != null) sb.append(l); }
                TreeMap<String, String> best = new TreeMap<>((x, y) -> GradleAdvisor.cmp(y, x));
                Matcher m = Pattern.compile("<version>(\\d+\\.\\d+\\.\\d+)</version>").matcher(sb);
                while (m.find()) {
                    String v = m.group(1), mm = v.substring(0, v.lastIndexOf('.'));
                    if (GradleAdvisor.minGradleFor(v) == null) continue;
                    if (!best.containsKey(mm) || GradleAdvisor.cmp(v, best.get(mm)) > 0) best.put(mm, v);
                }
                List<String> list = new ArrayList<>(best.values());
                Collections.sort(list, (x, y) -> GradleAdvisor.cmp(y, x));
                ui.post(() -> { versions.clear(); versions.addAll(list); status.setText("جُلب " + list.size() + " إصدار AGP."); renderAgp(); });
            } catch (Throwable e) {
                ui.post(() -> { status.setText("تعذّر جلب القائمة (تحقق من الإنترنت). يمكنك كتابة الإصدار يدويًا."); renderAgp(); });
            }
        });
    }

    private void renderAgp() {
        if (agpBox == null) return;
        String cur = rep == null ? null : rep.agp;
        agpInfo.setText("الإصدار الحالي في المشروع: " + (cur == null ? "غير مكتشف" : cur)
                + (cur != null && rep.maxInstalled > 0 && GradleAdvisor.maxApiFor(cur) > 0 && rep.maxInstalled > GradleAdvisor.maxApiFor(cur)
                ? "\nمنصة API " + rep.maxInstalled + " المثبتة أحدث مما يدعمه، والإصدار الأنسب لها: " + GradleAdvisor.minAgpForApi(rep.maxInstalled) + " فأعلى." : ""));
        agpBox.removeAllViews();
        {
            String sel = prefs().getString("agp_target", "");
            boolean on = prefs().getBoolean("agp_on_build", false);
            agpBox.addView(text(sel.isEmpty() ? "AGP للبناء: لم يُعتمد إصدار (يُستخدم ما في المشروع)."
                    : "AGP المعتمد للبناء: " + sel + (on ? "  ✔ يُطبَّق تلقائيًا عند كل بناء" : "  ⏸ متوقف"), on ? OK : SUB, 13));
            if (!sel.isEmpty()) {
                Button tg = btn(on ? "⏸ أوقف التطبيق التلقائي عند البناء" : "▶ فعّل التطبيق التلقائي عند البناء", on ? WARN : OK);
                tg.setOnClickListener(x -> { prefs().edit().putBoolean("agp_on_build", !on).apply(); renderAgp(); });
                agpBox.addView(tg);
                Button cl = btn("✖ إلغاء اعتماد AGP", WARN);
                cl.setOnClickListener(x -> { prefs().edit().remove("agp_target").putBoolean("agp_on_build", false).apply(); renderAgp(); });
                agpBox.addView(cl);
            }
        }
        if (versions.isEmpty()) { agpBox.addView(text("القائمة فارغة. اضغط «تحديث القائمة من الإنترنت».", WARN, 13)); return; }
        int n = 0;
        for (final String v : versions) {
            if (n++ >= shownAgp && !v.equals(cur)) continue;
            String[] ev = evalAgp(v, cur);
            int lvl = Integer.parseInt(ev[0]);
            LinearLayout c = new LinearLayout(this);
            c.setOrientation(LinearLayout.VERTICAL);
            c.setBackgroundColor(CARD);
            c.setPadding(dp(10), dp(8), dp(10), dp(8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, dp(4), 0, dp(4));
            c.setLayoutParams(lp);
            TextView h = text("AGP " + v + (v.equals(cur) ? "   ⚙ الحالي" : "") + (v.equals(versions.get(0)) ? "   ★ الأحدث" : ""), v.equals(cur) ? OK : TXT, 14);
            h.setTypeface(null, android.graphics.Typeface.BOLD);
            c.addView(h);
            c.addView(text(ev[1], lvl >= ProjectDoctor.WARN ? WARN : SUB, 13));
            boolean agpCached = GradleAdvisor.agpCached(gh, v);
            c.addView(text(agpCached ? "✔ AGP " + v + " مخزّن على الجهاز"
                    : "⬇ AGP " + v + " غير مخزّن: يُنزَّل مع اعتمادياته عند أول بناء (حجمه كبير نسبيًا ولا يمكن حسابه مسبقًا بدقة)", agpCached ? OK : SUB, 12));
            final String needG = GradleAdvisor.minGradleFor(v);
            if (!agpCached) {
                Button ab = btn("⬇ تنزيل AGP " + v + " الآن", ACCENT);
                ab.setOnClickListener(x -> confirmPrefetch(v, needG));
                c.addView(ab);
            } else {
                long dl = prefs().getLong("agp_dl_mb_" + v, 0);
                if (dl > 0) c.addView(text("حجم ما نُزِّل لهذا الإصدار ≈ \u200E" + dl + " MB\u200E", SUB, 12));
            }
            if (needG != null && GradleAdvisor.installedDir(gh, needG) == null) {
                requestGradleSize(needG);
                Long sz = gSize.get(needG);
                Button gb = btn("⬇ تنزيل Gradle " + needG + (sz != null && sz > 0 ? "  (\u200E" + (sz / 1048576) + " MB\u200E)" : ""), ACCENT);
                gb.setOnClickListener(x -> {
                    Intent gi = new Intent(this, GradleManagerActivity.class);
                    gi.putExtra("download_ver", needG);
                    startActivity(gi);
                });
                c.addView(gb);
            }
            {
                final boolean isSel = v.equals(prefs().getString("agp_target", ""));
                Button pb = btn(isSel ? "📌 معتمد للبناء" : "📌 اعتمد AGP " + v + " للبناء (مؤقت، يُستعاد ملف المشروع)", isSel ? OK : ACCENT);
                pb.setOnClickListener(x -> {
                    prefs().edit().putString("agp_target", v).putBoolean("agp_on_build", true).apply();
                    toast("اعتُمد AGP " + v + " وسيُطبَّق عند البناء");
                    renderAgp();
                });
                c.addView(pb);
            }
            if (!v.equals(cur)) {
                Button b = btn("تغيير إلى " + v, lvl >= ProjectDoctor.WARN ? WARN : OK);
                b.setOnClickListener(x -> confirmAgp(v));
                c.addView(b);
            }
            agpBox.addView(c);
        }
    }

    /** {مستوى, نص} لتقييم ملاءمة إصدار AGP للمشروع الحالي. */
    private String[] evalAgp(String t, String cur) {
        List<String> notes = new ArrayList<>();
        int lvl = ProjectDoctor.OK;
        String need = GradleAdvisor.minGradleFor(t);
        if (need != null) notes.add("يحتاج Gradle ≥ " + need + (GradleAdvisor.installedDir(gh, need) != null ? " (✔ منزَّل)" : " (⬇ غير منزَّل، " + gradleSizeLabel(need) + ")"));
        int maxApi = GradleAdvisor.maxApiFor(t);
        if (rep != null && maxApi > 0) {
            if (rep.maxCompile > maxApi) { notes.add("compileSdk " + rep.maxCompile + " أعلى مما يدعمه (حتى API " + maxApi + ")"); lvl = Math.max(lvl, ProjectDoctor.WARN); }
            if (rep.maxInstalled > maxApi) notes.add("منصة API " + rep.maxInstalled + " المثبتة أحدث منه (lint معطّل تلقائيًا فلا مشكلة)");
            else if (cur != null && GradleAdvisor.maxApiFor(cur) > 0 && rep.maxInstalled > GradleAdvisor.maxApiFor(cur)) notes.add("✔ يحل مشكلة المنصة الحديثة (API " + rep.maxInstalled + ")");
        }
        if (cur != null) {
            int a = numOf(t), b = numOf(cur);
            if (a > b) { notes.add("قفزة إلى الفرع " + a + ".x: قد تحتاج سكربتات البناء تعديلًا يدويًا"); lvl = Math.max(lvl, ProjectDoctor.WARN); }
            else if (GradleAdvisor.cmp(t, cur) < 0) notes.add("تخفيض عن الحالي");
        }
        if (GradleAdvisor.minJdkFor(t) > JDK_IN_PROOT) { notes.add("يحتاج JDK أحدث من المتوفر"); lvl = ProjectDoctor.BAD; }
        if (notes.isEmpty()) notes.add("لا ملاحظات.");
        return new String[]{String.valueOf(lvl), android.text.TextUtils.join("\n", notes)};
    }

    private void confirmPrefetch(final String agp, final String gv) {
        if (gv == null) { toast("لا أعرف Gradle المناسب لهذا الإصدار"); return; }
        if (GradleAdvisor.installedDir(gh, gv) == null) {
            new AlertDialog.Builder(this)
                    .setMessage("لتنزيل AGP " + agp + " يلزم أولًا Gradle " + gv + " (غير منزَّل). تنزيله الآن؟")
                    .setPositiveButton("نزّل Gradle", (d, x) -> {
                        Intent gi = new Intent(this, GradleManagerActivity.class);
                        gi.putExtra("download_ver", gv);
                        startActivity(gi);
                    })
                    .setNegativeButton("إلغاء", null).show();
            return;
        }
        long free = gh.getParentFile().getUsableSpace() / 1048576;
        new AlertDialog.Builder(this)
                .setTitle("تنزيل AGP " + agp)
                .setMessage("سيشغّل التطبيق Gradle " + gv + " على مشروع مؤقت صغير لتنزيل AGP واعتمادياته إلى مخزن Gradle، فلا يحتاج أول بناء حقيقي إلى تنزيلها.\n\n"
                        + "• يحتاج إنترنتًا.\n• الحجم كبير نسبيًا ولا يمكن حسابه مسبقًا بدقة؛ يظهر المنزَّل أثناء التنزيل وبعده.\n• المساحة الحرة الآن: \u200E" + free + " MB\u200E.\n• يمكنك إيقافه، ويُستأنف ما نُزِّل.")
                .setPositiveButton("ابدأ", (d, x) -> startPrefetch(agp, gv))
                .setNegativeButton("إلغاء", null).show();
    }

    private AlertDialog prefDlg;
    private TextView prefMsg;

    private void startPrefetch(final String agp, final String gv) {
        if (AgpPrefetcher.isRunning()) { showPrefetchDialog(); return; }
        if (!AgpPrefetcher.startBackground(this, agp, gv)) { toast("يوجد تنزيل جارٍ بالفعل"); return; }
        showPrefetchDialog();
    }

    /** نافذة التقدّم: إغلاقها («إخفاء») لا يوقف التنزيل، فهو يعمل في خدمة أمامية. */
    private void showPrefetchDialog() {
        if (prefDlg != null && prefDlg.isShowing()) return;
        prefMsg = text(AgpPrefetcher.lastMessage(), TXT, 14);
        prefMsg.setPadding(dp(20), dp(16), dp(20), dp(8));
        prefDlg = new AlertDialog.Builder(this)
                .setTitle("تنزيل AGP " + AgpPrefetcher.runningAgp())
                .setView(prefMsg)
                .setCancelable(true)
                .setPositiveButton("إخفاء (يستمر بالخلفية)", null)
                .setNegativeButton("إيقاف", (d, x) -> AgpPrefetcher.cancel())
                .create();
        prefDlg.show();
        attachPrefetch();
    }

    private void attachPrefetch() {
        AgpPrefetcher.attach(m -> ui.post(() -> { if (prefMsg != null) prefMsg.setText(m); }),
                (agp, err, mb) -> ui.post(() -> {
                    if (prefDlg != null) { try { prefDlg.dismiss(); } catch (Exception ignored) {} }
                    if (isFinishing()) return;
                    if (err == null) done("اكتمل التنزيل ✔", "نُزِّل AGP " + agp + " واعتمادياته (≈ " + mb + " MB).\nقد يُنزَّل أول بناء ملفًا صغيرًا إضافيًا خاصًا بالمشروع.");
                    else done("لم يكتمل التنزيل", err);
                    renderAgp();
                }));
    }

    private String gradleSizeLabel(String ver) {
        Long sz = gSize.get(ver);
        if (sz == null) return "حجم التنزيل: يحسب...";
        return sz > 0 ? "حجم التنزيل ≈ \u200E" + (sz / 1048576) + " MB\u200E" : "حجم التنزيل غير معروف";
    }

    private void requestGradleSize(final String ver) {
        if (gSize.containsKey(ver) || !gPending.add(ver)) return;
        sizePool.execute(() -> {
            long v = 0;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(GradleAdvisor.urlFor(ver)).openConnection();
                c.setRequestMethod("HEAD");
                c.setConnectTimeout(15000); c.setReadTimeout(15000);
                c.setInstanceFollowRedirects(true);
                v = Math.max(0, c.getContentLengthLong());
            } catch (Exception ignored) {}
            final long val = v;
            ui.post(() -> { gPending.remove(ver); gSize.put(ver, val); renderAgp(); });
        });
    }

    @Override protected void onResume() {
        super.onResume();
        if (AgpPrefetcher.isRunning()) showPrefetchDialog();
        if (rep != null) analyze();   // بعد العودة من مدير Gradle/SDK: حدّث الحالة
    }

    private static int numOf(String s) { try { return Integer.parseInt(s.split("\\.")[0]); } catch (Exception e) { return 0; } }

    private void confirmAgp(final String target) {
        final File proj = selectedProject();
        if (proj == null || rep == null) { toast("اختر مشروعًا أولًا"); return; }
        String[] ev = evalAgp(target, rep.agp);
        String need = GradleAdvisor.minGradleFor(target);
        new AlertDialog.Builder(this)
                .setTitle("تغيير AGP إلى " + target)
                .setMessage("الحالي: " + (rep.agp == null ? "غير مكتشف" : rep.agp) + "\n\n" + ev[1]
                        + (need != null ? "\n\nسيُحدَّث gradle-wrapper في المشروع إن كان أقدم من " + need + "، ويُعتمد Gradle " + need + " في التطبيق إن كان أقدم." : "")
                        + "\n\nتُحفظ نسخة احتياطية أولًا ويمكنك التراجع.")
                .setPositiveButton("طبّق", (d, x) -> doAgp(proj, target))
                .setNegativeButton("إلغاء", null).show();
    }

    private void doAgp(final File proj, final String target) {
        status.setText("يغيّر AGP ...");
        final File root = rep.root;
        pool.execute(() -> {
            StringBuilder log = new StringBuilder();
            try {
                ProjectDoctor.Backup bk = new ProjectDoctor.Backup(backupBase(proj), root);
                log.append(ProjectDoctor.changeAgp(root, target, bk));
                String need = GradleAdvisor.minGradleFor(target);
                if (need != null && GradleAdvisor.cmp(manualGradle(), need) < 0) {
                    prefs().edit().putString("gradle_url", GradleAdvisor.urlFor(need)).apply();
                    log.append("• اعتُمد Gradle ").append(need).append(" في التطبيق")
                       .append(GradleAdvisor.installedDir(gh, need) == null ? " (غير منزَّل: نزّله من «مدير Gradle» قبل البناء)" : "").append("\n");
                }
            } catch (Throwable e) { log.append("✘ ").append(e).append("\n"); }
            final String msg = log.toString();
            ui.post(() -> { done("تم تغيير AGP", msg); analyze(); });
        });
    }

    // ================= أدوات =================
    private void done(String title, String msg) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("حسنًا", null).show();
    }

    private void copyReport() {
        if (rep == null) { toast("لا يوجد تقرير بعد"); return; }
        File p = selectedProject();
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("report", rep.asText(p == null ? "" : p.getName())));
        toast("نُسخ التقرير");
    }

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

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
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
    private Button btn(String s, int color) {
        Button b = new Button(this);
        b.setText(s); b.setTextColor(Color.WHITE); b.setBackgroundColor(color); b.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(dp(2), dp(4), dp(2), dp(4));
        b.setLayoutParams(lp);
        return b;
    }

    @Override protected void onDestroy() { super.onDestroy(); AgpPrefetcher.attach(null, null); if (prefDlg != null) { try { prefDlg.dismiss(); } catch (Exception ignored) {} } pool.shutdownNow(); sizePool.shutdownNow(); }
}
