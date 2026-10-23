package com.example.l2sbuilder;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.*;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * مركز التوقيع:
 * 1) خزنة مفاتيح: إنشاء / استيراد / تصدير / تعيين مفتاح للبناء ولإصدار release / حذف.
 * 2) توقيع APK من الجهاز بعد فحصه (الحزمة، الصلاحيات، التوقيع الحالي، مطابقة النسخة المثبّتة).
 * 3) ملف توقيع لكل مشروع (النوع، المفتاح، استبدال التوقيع، مخططات التوقيع، R8) يطبّقه البناء تلقائيًا.
 * 4) نسخة احتياطية للخزنة مشفّرة بكلمة سر (تنجو من حذف التطبيق) واستعادتها.
 * 5) حماية العمليات الحساسة بالبصمة أو قفل الشاشة.
 * كل ما سبق قابل للضبط من الإعدادات.
 */
public class SigningActivity extends Activity {
    private static final int BG = 0xFF11121A, CARD = 0xFF1C1E2A, ACCENT = 0xFF7C5CFF,
            OK = 0xFF22D3B8, WARN = 0xFFFFB84D, DANGER = 0xFFFF5C7A, TXT = 0xFFF2F2F7, SUB = 0xFFA8ABBE;
    private static final int REQ_KEY = 71, REQ_APK = 72, REQ_VAULT = 73, REQ_AUTH = 74;
    private static final String JAVA = "/usr/lib/jvm/java-21-openjdk-arm64";
    private static final String DEBUG_ID = "debug-original";

    private static class Res { String text = ""; int code = -1; }

    /** صف إعداد: sw مفتاح تبديل، ch اختيار، num رقم، txt نص، hdr عنوان. */
    private static final String[] LBL = {"حسب الإعداد العام", "تفعيل", "تعطيل"};
    private static final String[] VAL = {"inherit", "on", "off"};

    private static class Row {
        final String type, key, title, desc;
        final String[] labels, values;
        Row(String type, String key, String title, String desc, String[] labels, String[] values) {
            this.type = type; this.key = key; this.title = title; this.desc = desc; this.labels = labels; this.values = values;
        }
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final List<SigningStore.Key> keys = new ArrayList<>();
    private File signRoot, keysDir, workDir, outDir;
    private LinearLayout keysBox, resultBox, projectsBox, vaultBox;
    private TextView status, apkInfo, inspectView, logView, vaultInfo;
    private Spinner keySpin;
    private ScrollView scroll;
    private View backupAnchor;
    private File pickedApk, signedApk, lastVault;
    private String pickedName = "";
    private SigningStore.ApkInfo lastInfo;
    private volatile boolean busy;
    private final StringBuilder logBuf = new StringBuilder();
    private SigningStore.Kv g;
    private long lastAuthMs;
    private Runnable pendingAuth;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        g = SigningStore.globalKv(this);
        signRoot = SigningStore.root(this);
        keysDir = SigningStore.keysDir(this);
        workDir = new File(signRoot, "work");
        outDir = new File(signRoot, "out");
        workDir.mkdirs(); outDir.mkdirs();

        scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(14);
        root.setPadding(p, p, p, p);
        scroll.addView(root);
        setContentView(scroll);

        TextView t = text("مركز التوقيع", TXT, 20);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(t);
        status = text("", SUB, 13);
        root.addView(status);

        // ---- 1) خزنة المفاتيح
        root.addView(section("خزنة المفاتيح"));
        LinearLayout row = hrow();
        Button bNew = btn("➕ مفتاح جديد", ACCENT);
        Button bImp = btn("📂 استيراد من الجهاز", 0xFF3F51B5);
        row.addView(bNew, weight());
        row.addView(bImp, weight());
        root.addView(row);
        bNew.setOnClickListener(v -> newKeyDialog());
        bImp.setOnClickListener(v -> pick(REQ_KEY));
        keysBox = vbox();
        root.addView(keysBox);
        root.addView(text("اضغط مطولًا على أي مفتاح لنسخ بصمته SHA-256 كاملة.", SUB, 11));

        // ---- 2) توقيع APK
        root.addView(section("توقيع تطبيق (APK) من الجهاز"));
        Button bApk = btn("📱 اختر ملف APK", 0xFF009688);
        root.addView(bApk);
        bApk.setOnClickListener(v -> pick(REQ_APK));
        apkInfo = text("لم تختر ملفًا بعد", SUB, 13);
        root.addView(apkInfo);
        inspectView = text("", SUB, 12);
        inspectView.setTextIsSelectable(true);
        root.addView(inspectView);
        keySpin = new Spinner(this);
        root.addView(keySpin);
        LinearLayout srow = hrow();
        Button bSign = btn("🔏 وقّع التطبيق", OK);
        bSign.setTextColor(0xFF11121A);
        Button bSOpt = btn("⚙ خيارات التوقيع", 0xFF3F51B5);
        srow.addView(bSign, weight());
        srow.addView(bSOpt, weight());
        root.addView(srow);
        bSign.setOnClickListener(v -> signApk());
        bSOpt.setOnClickListener(v -> settingsDialog("خيارات توقيع APK", g, signRows(), null));
        resultBox = vbox();
        root.addView(resultBox);
        root.addView(text("تنبيه: التطبيق الموقَّع بمفتاح مختلف لا يُثبَّت فوق النسخة القديمة؛ احذفها أولًا (أو استخدم المفتاح نفسه).", SUB, 12));

        // ---- فحص التشفير
        root.addView(section("فحص التشفير والتعتيم"));
        root.addView(text("تقرير واحد: استخدام التشفير في الكود، المكتبات، التعتيم، التغليف، الملفات المشفّرة، ومخططات التوقيع.", SUB, 13));
        LinearLayout crow = hrow();
        Button bScanApk = btn("🧬 الـAPK المختار", 0xFF009688);
        Button bScanApp = btn("📲 تطبيق مثبّت", 0xFF3F51B5);
        crow.addView(bScanApk, weight());
        crow.addView(bScanApp, weight());
        root.addView(crow);
        bScanApk.setOnClickListener(v -> scanPickedApk());
        bScanApp.setOnClickListener(v -> scanInstalledDialog());

        // ---- 3) المشاريع
        root.addView(section("توقيع المشاريع"));
        root.addView(text("لكل مشروع ملف توقيع يطبّقه البناء تلقائيًا: المفتاح، النوع (debug أو release)، ومخططات التوقيع وغيرها. المشاريع بلا ملف تُبنى بمفتاح البناء العام.", SUB, 13));
        projectsBox = vbox();
        root.addView(projectsBox);
        Button bProj = btn("🏗 ارفع مشروعًا وابنِه", 0xFF4CAF50);
        root.addView(bProj);
        bProj.setOnClickListener(v -> {
            Intent i = new Intent(this, MainActivity.class);
            i.putExtra("l2s_pick_project", true);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
            finish();
        });

        // ---- 4) نسخة احتياطية للخزنة
        backupAnchor = section("نسخة احتياطية للخزنة");
        root.addView(backupAnchor);
        vaultInfo = text("", SUB, 13);
        root.addView(vaultInfo);
        LinearLayout brow = hrow();
        Button bExp = btn("📤 تصدير مشفّر", 0xFFFF9800);
        Button bRest = btn("📥 استعادة", 0xFF3F51B5);
        brow.addView(bExp, weight());
        brow.addView(bRest, weight());
        root.addView(brow);
        bExp.setOnClickListener(v -> exportVaultDialog());
        bRest.setOnClickListener(v -> pick(REQ_VAULT));
        vaultBox = vbox();
        root.addView(vaultBox);

        // ---- 5) الإعدادات
        root.addView(section("الإعدادات"));
        root.addView(settingsButton("🔐 الحماية بالبصمة", "متى تُطلب البصمة أو قفل الشاشة", () -> settingsDialog("الحماية بالبصمة", g, authRows(), null)));
        root.addView(settingsButton("✍ خيارات توقيع APK", "zipalign، مخططات التوقيع، التحقق، التسمية", () -> settingsDialog("خيارات توقيع APK", g, signRows(), null)));
        root.addView(settingsButton("🔎 فحص APK", "الصلاحيات، مطابقة النسخة المثبّتة، اختيار المفتاح", () -> settingsDialog("فحص APK", g, inspectRows(), null)));
        root.addView(settingsButton("🚀 بناء release", "المفتاح الافتراضي، R8، استبدال التوقيع", () -> settingsDialog("بناء release", g, releaseRows(), null)));
        root.addView(settingsButton("🛡 النسخ الاحتياطي", "قوة التشفير، التذكير، سلوك المفاتيح المكرّرة", () -> settingsDialog("النسخ الاحتياطي", g, backupRows(), null)));

        root.addView(section("السجل"));
        logView = text("", SUB, 12);
        logView.setTextIsSelectable(true);
        root.addView(logView);

        refreshKeys();
        pool.execute(this::seedDebugKey);

        final String pj = getIntent().getStringExtra("l2s_project");
        if (pj != null && !pj.isEmpty()) ui.post(() -> projectDialog(pj));
        if ("pick_key".equals(getIntent().getStringExtra("l2s_open"))) {
            ui.postDelayed(this::pickKeyDialog, 400);
        }
        if ("backup".equals(getIntent().getStringExtra("l2s_open"))) {
            ui.postDelayed(() -> scroll.smoothScrollTo(0, Math.max(0, backupAnchor.getTop() - dp(8))), 350);
        }
    }

    // تغيير مفتاح التوقيع: قائمة بالمفاتيح المحفوظة، والاختيار يعيّنه مفتاح البناء الحالي
    private void pickKeyDialog() {
        if (keys.isEmpty()) { toast("لا توجد مفاتيح محفوظة بعد. أنشئ مفتاحًا أو استورد واحدًا."); return; }
        final String active = SigningStore.prefs(this).getString("active", "");
        final String[] items = new String[keys.size()];
        int cur = -1;
        for (int i = 0; i < keys.size(); i++) {
            SigningStore.Key k = keys.get(i);
            boolean on = k.id.equals(active);
            if (on) cur = i;
            items[i] = (on ? "✔ " : "") + k.name + "  (" + k.alias + ")";
        }
        new AlertDialog.Builder(this)
                .setTitle("تغيير مفتاح التوقيع")
                .setSingleChoiceItems(items, cur, (d, which) -> {
                    d.dismiss();
                    final SigningStore.Key k = keys.get(which);
                    if (k.id.equals(active)) { toast("«" + k.name + "» هو المفتاح الحالي أصلًا"); return; }
                    requireAuth("activate", () -> activate(k));
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    @Override protected void onDestroy() { super.onDestroy(); pool.shutdownNow(); }

    // ------------------------------------------------------------------ الحماية بالبصمة / قفل الشاشة
    private String authLabel(String a) {
        switch (a) {
            case "export": return "تصدير مفتاح";
            case "edit": return "تعديل مفتاح";
            case "delete": return "حذف مفتاح";
            case "backup": return "نسخة احتياطية للخزنة";
            case "sign": return "توقيع تطبيق";
            case "activate": return "تعيين مفتاح البناء";
            default: return "عملية حساسة";
        }
    }

    /** ينفّذ ok بعد التحقق إن كان الإعداد auth_<action> مفعّلًا (مع مهلة تذكّر وسياسة غياب قفل الشاشة). */
    private void requireAuth(String action, final Runnable ok) {
        if (!SigningStore.on(g, "auth_" + action)) { ok.run(); return; }
        long timeout = 60;
        try { timeout = Long.parseLong(g.get("auth_timeout")); } catch (Exception ignored) { }
        if (timeout > 0 && lastAuthMs > 0 && System.currentTimeMillis() - lastAuthMs < timeout * 1000L) { ok.run(); return; }
        android.app.KeyguardManager km = (android.app.KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        boolean secure = km != null && km.isDeviceSecure();
        if (!secure) {
            if ("deny".equals(g.get("auth_nolock"))) toast("فعّل قفل الشاشة (بصمة أو رمز) أولًا؛ إعداداتك تمنع «" + authLabel(action) + "» بدونه.");
            else ok.run();
            return;
        }
        final Runnable done = () -> { lastAuthMs = System.currentTimeMillis(); ok.run(); };
        if (Build.VERSION.SDK_INT >= 28) promptBio(action, done, km);
        else credential(done, km);
    }

    @android.annotation.TargetApi(28)
    private void promptBio(String action, final Runnable done, final android.app.KeyguardManager km) {
        android.hardware.biometrics.BiometricPrompt.Builder bb = new android.hardware.biometrics.BiometricPrompt.Builder(this)
                .setTitle("تأكيد الهوية").setSubtitle(authLabel(action));
        if (Build.VERSION.SDK_INT >= 30) {
            bb.setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
                    | android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL);
        } else {
            bb.setNegativeButton("قفل الشاشة", getMainExecutor(), (d, w) -> credential(done, km));
        }
        bb.build().authenticate(new android.os.CancellationSignal(), getMainExecutor(),
                new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                    @Override public void onAuthenticationSucceeded(android.hardware.biometrics.BiometricPrompt.AuthenticationResult r) { done.run(); }
                    @Override public void onAuthenticationError(int code, CharSequence msg) {
                        if (code == android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS
                                || code == android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT
                                || code == android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE) {
                            credential(done, km);
                        } else if (code != android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                                && code != android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED) {
                            toast("لم يتم التأكيد: " + msg);
                        }
                    }
                });
    }

    private void credential(final Runnable done, android.app.KeyguardManager km) {
        Intent i = km.createConfirmDeviceCredentialIntent("تأكيد الهوية", "أكّد بقفل الشاشة للمتابعة");
        if (i == null) { toast("تعذّر فتح تأكيد قفل الشاشة"); return; }
        pendingAuth = done;
        startActivityForResult(i, REQ_AUTH);
    }

    // ------------------------------------------------------------------ الخزنة
    private void refreshKeys() {
        keys.clear();
        keys.addAll(SigningStore.listKeys(this));
        String active = SigningStore.prefs(this).getString("active", "");
        keysBox.removeAllViews();
        if (keys.isEmpty()) keysBox.addView(text("لا توجد مفاتيح محفوظة بعد. أنشئ مفتاحًا أو استورد واحدًا.", SUB, 13));
        List<String> names = new ArrayList<>();
        for (SigningStore.Key k : keys) {
            names.add(k.name + "  (" + k.alias + ")");
            keysBox.addView(keyCard(k, k.id.equals(active)));
        }
        int sel = keySpin.getSelectedItemPosition();
        final float dn = getResources().getDisplayMetrics().density;
        keySpin.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, names) {
            @Override public View getView(int pos, View cv, android.view.ViewGroup parent) {
                TextView t = (TextView) super.getView(pos, cv, parent);
                t.setTextColor(TXT);
                t.setTextSize(14);
                t.setSingleLine(false);
                t.setPadding((int) (12 * dn), (int) (10 * dn), (int) (12 * dn), (int) (10 * dn));
                return t;
            }
            @Override public View getDropDownView(int pos, View cv, android.view.ViewGroup parent) {
                TextView t = (TextView) super.getDropDownView(pos, cv, parent);
                t.setTextColor(0xFF11121A);
                t.setTextSize(14);
                t.setSingleLine(false);
                t.setPadding((int) (14 * dn), (int) (12 * dn), (int) (14 * dn), (int) (12 * dn));
                return t;
            }
        });
        keySpin.setBackgroundColor(CARD);
        keySpin.setDropDownWidth(android.view.ViewGroup.LayoutParams.MATCH_PARENT);
        if (sel >= 0 && sel < names.size()) keySpin.setSelection(sel);
        String rem = SigningStore.backupReminder(this);
        status.setText(keys.size() + " مفتاح محفوظ" + (active.isEmpty() ? "" : " • مفتاح البناء: " + nameOf(active))
                + (rem.isEmpty() ? "" : "\n" + rem));
        status.setTextColor(rem.isEmpty() ? SUB : WARN);
        vaultInfo.setText(vaultStatusText());
        refreshProjects();
    }

    private String vaultStatusText() {
        long last = 0;
        try { last = Long.parseLong(g.get("last_backup")); } catch (Exception ignored) { }
        String when = last == 0 ? "لم تُنسخ بعد" : "آخر نسخة: " + new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(last))
                + ("1".equals(g.get("dirty")) ? " (تغيّرت الخزنة بعدها)" : "");
        return "النسخة مشفّرة بكلمة سر تختارها، فتُستعاد على أي جهاز. تُحفظ في Downloads وفي مجلد النسخ الاحتياطي الدائم إن كان محددًا.\n" + when;
    }

    private String nameOf(String id) {
        for (SigningStore.Key k : keys) if (k.id.equals(id)) return k.name;
        return id;
    }

    private View keyCard(final SigningStore.Key k, boolean active) {
        LinearLayout c = vbox();
        c.setBackgroundColor(CARD);
        int p = dp(10);
        c.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        c.setLayoutParams(lp);
        boolean relDef = k.id.equals(g.get("rel_defkey"));
        TextView n = text((active ? "✅ " : "🔑 ") + k.name, TXT, 16);
        n.setTypeface(null, android.graphics.Typeface.BOLD);
        c.addView(n);
        String sub = "الاسم المستعار: " + k.alias + "\nSHA-256: " + (k.sha.isEmpty() ? "—" : shortSha(k.sha))
                + "\n" + k.created + (active ? "\nمفتاح البناء الحالي (debug)" : "") + (relDef ? "\n🚀 مفتاح release الافتراضي" : "")
                + (k.locked ? "\n⚠ كلمة السر غير متاحة: أعد استيراد المفتاح" : "");
        c.addView(text(sub, active ? OK : SUB, 12));
        LinearLayout r1 = hrow(), r2 = hrow();
        Button a = btn("للبناء (debug)", ACCENT), rl = btn(relDef ? "release ✔" : "للإصدار (release)", 0xFF6A4CE0);
        Button e = btn("تصدير", 0xFF3F51B5), d = btn("حذف", DANGER);
        r1.addView(a, weight()); r1.addView(rl, weight());
        r2.addView(e, weight()); r2.addView(d, weight());
        LinearLayout r3 = hrow();
        Button dt = btn("🔎 تفاصيل", 0xFF009688), ed = btn("✏ تعديل", 0xFF607D8B);
        r3.addView(dt, weight()); r3.addView(ed, weight());
        c.addView(r1); c.addView(r2); c.addView(r3);
        dt.setOnClickListener(v -> detailsDialog(k));
        ed.setOnClickListener(v -> requireAuth("edit", () -> editKeyDialog(k)));
        a.setOnClickListener(v -> requireAuth("activate", () -> activate(k)));
        rl.setOnClickListener(v -> {
            g.put("rel_defkey", relDef ? "" : k.id);
            toast(relDef ? "أُلغي المفتاح الافتراضي لـ release (يُستعمل مفتاح البناء)" : "«" + k.name + "» صار مفتاح release الافتراضي");
            refreshKeys();
        });
        e.setOnClickListener(v -> requireAuth("export", () -> exportKey(k)));
        d.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("حذف «" + k.name + "»؟")
                .setMessage("لن تستطيع تحديث التطبيقات الموقَّعة بهذا المفتاح إن لم يكن لديك نسخة منه. هل صدّرت نسخة احتياطية؟")
                .setPositiveButton("حذف", (x, y) -> requireAuth("delete", () -> deleteKey(k))).setNegativeButton("إلغاء", null).show());
        c.setOnLongClickListener(v -> {
            if (k.sha.isEmpty()) return true;
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) { cm.setPrimaryClip(ClipData.newPlainText("sha256", k.sha)); toast("نُسخت البصمة"); }
            return true;
        });
        return c;
    }

    private static String shortSha(String s) { return s.length() > 23 ? s.substring(0, 23) + "…" : s; }

    /** يضيف مفتاح debug الحالي إلى الخزنة مرة واحدة ليمكن الرجوع إليه بعد تجربة مفاتيح أخرى. */
    private void seedDebugKey() {
        try {
            File src = new File(getFilesDir(), "android-home/debug.keystore");
            File dir = new File(keysDir, DEBUG_ID);
            if (!src.isFile() || dir.exists()) return;
            dir.mkdirs();
            Compat.copy(src, new File(dir, "key.ks"));
            SigningStore.Key k = new SigningStore.Key();
            k.id = DEBUG_ID; k.name = "debug الأصلي"; k.alias = "androiddebugkey"; k.pw = "android";
            k.created = SigningStore.now();
            SigningStore.writeKey(this, k);
            Res r = inspect(k, false);
            if (r.code == 0) { parseInfo(k, r.text); SigningStore.writeKey(this, k); }
            ui.post(this::refreshKeys);
        } catch (Exception ignored) {
        }
    }

    private static long daysUntil9999() {
        Calendar c = new GregorianCalendar(9999, Calendar.DECEMBER, 31);
        return Math.max(1L, (c.getTimeInMillis() - System.currentTimeMillis()) / 86400000L);
    }

    private Res genKey(SigningStore.Key k, int bits, String dname, long days) {
        Map<String, String> env = new HashMap<>();
        env.put("L2S_PW", k.pw); env.put("L2S_ALIAS", k.alias); env.put("L2S_ID", k.id);
        env.put("L2S_DN", dname); env.put("L2S_SIZE", String.valueOf(bits));
        env.put("L2S_DAYS", String.valueOf(days));
        return guest(bits >= 4096 ? 300 : 150, env, "set -e; K=/sign/keys/$L2S_ID/key.ks; rm -f $K; "
                + "keytool -genkeypair -keystore $K -storetype PKCS12 -alias \"$L2S_ALIAS\" -keyalg RSA -keysize $L2S_SIZE "
                + "-validity $L2S_DAYS -dname \"$L2S_DN\" -storepass:env L2S_PW -keypass:env L2S_PW; "
                + "keytool -list -v -keystore $K -storepass:env L2S_PW");
    }

    private void newKeyDialog() {
        final EditText name = field("اسم المفتاح (مثل: تطبيقاتي)", false), alias = field("الاسم المستعار (alias)", false),
                pw = field("كلمة السر (6 أحرف فأكثر)", true), yrs = field("مدة الصلاحية بالسنوات (1–100)", false),
                org = field("المؤسسة O (اختياري)", false), cc = field("رمز الدولة C من حرفين (اختياري)", false);
        alias.setText("key0"); yrs.setText("25");
        yrs.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        final CheckBox unlimited = new CheckBox(this);
        unlimited.setText("بدون مدة محددة (حتى 9999-12-31)");
        unlimited.setTextColor(TXT);
        unlimited.setOnCheckedChangeListener((cb, on) -> yrs.setEnabled(!on));
        final Spinner size = new Spinner(this);
        size.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"RSA 2048 (سريع، كافٍ للأغلب)", "RSA 3072", "RSA 4096 (أبطأ في الإنشاء)"}));
        final Spinner tmpl = new Spinner(this);
        tmpl.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"قالب: مخصّص", "قالب: Android Debug القياسي (تجريبي فقط)"}));
        tmpl.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (pos == 1) {
                    name.setText("Android Debug"); alias.setText("androiddebugkey"); pw.setText("android");
                    yrs.setText("30"); org.setText("Android"); cc.setText("US"); unlimited.setChecked(false); size.setSelection(0);
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        form("مفتاح جديد", new View[]{tmpl, name, alias, pw, yrs, unlimited, size, org, cc}, () -> {
            final String nm = name.getText().toString().trim(), al = alias.getText().toString().trim(), pass = pw.getText().toString();
            int y = 25;
            try { y = Math.max(1, Math.min(100, Integer.parseInt(yrs.getText().toString().trim()))); } catch (Exception ignored) { }
            if (nm.isEmpty() || al.isEmpty() || pass.length() < 6) { toast("أكمل الحقول (كلمة السر 6 أحرف على الأقل)"); return; }
            final boolean unlim = unlimited.isChecked();
            final long days = unlim ? daysUntil9999() : y * 365L;
            final int bits = new int[]{2048, 3072, 4096}[Math.max(0, Math.min(2, size.getSelectedItemPosition()))];
            String dn = "CN=" + dnSafe(nm);
            String o = dnSafe(org.getText().toString().trim());
            if (!o.isEmpty()) dn += ", O=" + o;
            String c2 = cc.getText().toString().replaceAll("[^A-Za-z]", "");
            if (c2.length() >= 2) dn += ", C=" + c2.substring(0, 2).toUpperCase(Locale.US);
            final String dname = dn;
            work("ينشئ المفتاح…", () -> {
                SigningStore.Key k = new SigningStore.Key();
                k.id = "k" + System.currentTimeMillis();
                k.name = nm; k.alias = al; k.pw = pass; k.created = SigningStore.now();
                new File(keysDir, k.id).mkdirs();
                Res r = genKey(k, bits, dname, days);
                String note = unlim ? "بلا مدة محددة (حتى 9999-12-31)" : (days / 365) + " سنة";
                if (r.code != 0 && unlim) {
                    log("أداتك لم تقبل شهادة حتى 9999؛ تُعاد المحاولة بمدة 100 سنة.");
                    r = genKey(k, bits, dname, 36500L);
                    note = "100 سنة (تعذّر «بلا حد»)";
                }
                if (r.code == 0 && parseInfo(k, r.text)) {
                    try { SigningStore.writeKey(this, k); log("تم إنشاء المفتاح «" + nm + "» (RSA " + bits + "، الصلاحية: " + note + ")"); } catch (Exception e) { log("فشل الحفظ: " + e); }
                } else {
                    deleteRec(new File(keysDir, k.id));
                    log("فشل إنشاء المفتاح:\n" + tail(r.text));
                }
                ui.post(this::refreshKeys);
            });
        });
    }

    // ------------------------------------------------------------------ تفاصيل المفتاح وتعديله
    private static String grab(String out, String regex) {
        Matcher m = Pattern.compile(regex).matcher(out);
        return m.find() ? m.group(1).trim() : "—";
    }

    private void detailsDialog(final SigningStore.Key k) {
        if (k.locked) { toast("كلمة السر غير متاحة لهذا المفتاح فلا يمكن قراءة تفاصيله"); return; }
        work("يقرأ تفاصيل المفتاح…", () -> {
            Res r = inspect(k, !k.alias.isEmpty());
            final String body = r.code == 0 ? keyDetails(k, r.text) : "تعذّرت قراءة المفتاح:\n" + tail(r.text);
            ui.post(() -> showReport("تفاصيل «" + k.name + "»", body));
        });
    }

    private String keyDetails(SigningStore.Key k, String out) {
        StringBuilder sb = new StringBuilder();
        File ks = new File(new File(keysDir, k.id), "key.ks");
        sb.append("الاسم: ").append(k.name).append("\n");
        sb.append("المعرّف الداخلي: ").append(k.id).append("\n");
        sb.append("أُضيف: ").append(k.created).append("\n");
        sb.append("الاسم المستعار: ").append(grab(out, "Alias name:\\s*(.+)")).append("\n");
        sb.append("نوع الملف: ").append(grab(out, "Keystore type:\\s*(.+)")).append(" • ").append(ks.length()).append(" بايت\n");
        sb.append("عدد المدخلات: ").append(grab(out, "contains\\s+(\\d+)\\s+entr")).append("\n");
        sb.append("كلمة السر: ").append(k.kpw.isEmpty() ? "محفوظة (مشفّرة)" : "محفوظة (للملف وللمفتاح منفصلتان)").append("\n");
        sb.append("\n— الشهادة —\n");
        sb.append("المالك: ").append(grab(out, "Owner:\\s*(.+)")).append("\n");
        sb.append("المُصدِر: ").append(grab(out, "Issuer:\\s*(.+)")).append("\n");
        sb.append("الرقم التسلسلي: ").append(grab(out, "Serial number:\\s*(.+)")).append("\n");
        sb.append("خوارزمية التوقيع: ").append(grab(out, "Signature algorithm name:\\s*(.+)")).append("\n");
        sb.append("المفتاح العام: ").append(grab(out, "Subject Public Key Algorithm:\\s*(.+)")).append("\n");
        sb.append("إصدار X.509: ").append(grab(out, "Version:\\s*(\\d+)")).append("\n");
        String from = grab(out, "Valid from:\\s*(.+?)\\s+until:"), until = grab(out, "until:\\s*(.+)");
        sb.append("صالح من: ").append(from).append("\nإلى: ").append(until).append("\n");
        sb.append(validityVerdict(until)).append("\n");
        sb.append("\n— البصمات —\n");
        sb.append("SHA-256:\n").append(grab(out, "SHA-?256:\\s*([0-9A-Fa-f:]+)")).append("\n");
        sb.append("SHA-1:\n").append(grab(out, "SHA-?1:\\s*([0-9A-Fa-f:]+)")).append("\n");
        String active = SigningStore.prefs(this).getString("active", "");
        sb.append("\n— الاستعمال —\n");
        sb.append(k.id.equals(active) ? "✅ مفتاح البناء العام (debug)\n" : "");
        sb.append(k.id.equals(g.get("rel_defkey")) ? "🚀 مفتاح release الافتراضي\n" : "");
        int used = 0;
        File[] ps = new File(getFilesDir(), "projects").listFiles(File::isDirectory);
        if (ps != null) for (File p : ps) {
            if (k.id.equals(SigningStore.profileKv(this, p.getName()).get("key"))) { sb.append("📁 ").append(p.getName()).append("\n"); used++; }
        }
        if (used == 0 && !k.id.equals(active) && !k.id.equals(g.get("rel_defkey"))) sb.append("غير مستعمل حاليًا\n");
        sb.append("\nملاحظة: الشهادة لا تُعدَّل بعد إنشائها (بما فيها المدة)؛ لتغيير الصلاحية أنشئ مفتاحًا جديدًا.\n");
        return sb.toString();
    }

    private String validityVerdict(String until) {
        try {
            Date d = new SimpleDateFormat("EEE MMM dd HH:mm:ss zzz yyyy", Locale.US).parse(until);
            Calendar c = Calendar.getInstance();
            c.setTime(d);
            int yr = c.get(Calendar.YEAR);
            if (yr >= 9000) return "✔ بلا مدة محددة عمليًا (حتى " + yr + ")";
            if (d.getTime() < System.currentTimeMillis()) return "⛔ الشهادة منتهية الصلاحية";
            long years = (d.getTime() - System.currentTimeMillis()) / (365L * 86400000L);
            Calendar play = new GregorianCalendar(2033, Calendar.OCTOBER, 22);
            return (d.before(play.getTime()) ? "⚠ تنتهي قبل 2033-10-22 الذي تشترطه Google Play للتطبيقات الجديدة — " : "✔ ") + "متبقٍّ نحو " + years + " سنة";
        } catch (Exception e) {
            return "";
        }
    }

    private void editKeyDialog(final SigningStore.Key k) {
        if (k.locked) { toast("كلمة السر غير متاحة؛ أعد استيراد المفتاح بدل تعديله"); return; }
        final EditText name = field("اسم المفتاح", false), alias = field("الاسم المستعار (alias)", false),
                npw = field("كلمة سر جديدة (اتركها فارغة لعدم التغيير)", true);
        name.setText(k.name); alias.setText(k.alias);
        TextView hint = text("تغيير الاسم المستعار أو كلمة السر يعيد كتابة ملف المفاتيح بصيغة PKCS12 بكلمة سر واحدة للملف والمفتاح، ويحتفظ بالمفتاح المحدد فقط.", SUB, 12);
        form("تعديل «" + k.name + "»", new View[]{hint, name, alias, npw}, () -> {
            final String nm = name.getText().toString().trim(), al = alias.getText().toString().trim(), np = npw.getText().toString();
            if (nm.isEmpty() || al.isEmpty()) { toast("الاسم والاسم المستعار مطلوبان"); return; }
            if (!np.isEmpty() && np.length() < 6) { toast("كلمة السر الجديدة 6 أحرف على الأقل"); return; }
            final boolean heavy = !al.equals(k.alias) || !np.isEmpty();
            work("يعدّل المفتاح…", () -> {
                try {
                    if (!heavy) {
                        k.name = nm;
                        SigningStore.writeKey(this, k);
                        log("أُعيدت تسمية المفتاح إلى «" + nm + "»");
                    } else {
                        String newPw = np.isEmpty() ? k.pw : np;
                        Map<String, String> env = new HashMap<>();
                        env.put("L2S_PW", k.pw); env.put("L2S_KPW", k.kpw.isEmpty() ? k.pw : k.kpw);
                        env.put("L2S_ALIAS", k.alias); env.put("L2S_ID", k.id);
                        env.put("L2S_NEWALIAS", al); env.put("L2S_NEWPW", newPw);
                        Res r = guest(120, env, "set -e; D=/sign/keys/$L2S_ID; rm -f $D/new.ks; "
                                + "keytool -importkeystore -srckeystore $D/key.ks -srcstorepass:env L2S_PW -srcalias \"$L2S_ALIAS\" -srckeypass:env L2S_KPW "
                                + "-destkeystore $D/new.ks -deststoretype PKCS12 -deststorepass:env L2S_NEWPW -destkeypass:env L2S_NEWPW "
                                + "-destalias \"$L2S_NEWALIAS\" -noprompt; "
                                + "keytool -list -v -keystore $D/new.ks -storepass:env L2S_NEWPW && mv -f $D/new.ks $D/key.ks");
                        if (r.code == 0) {
                            k.name = nm; k.alias = al; k.pw = newPw; k.kpw = "";
                            parseInfo(k, r.text);
                            SigningStore.writeKey(this, k);
                            log("تم تعديل المفتاح «" + nm + "» (alias: " + k.alias + (np.isEmpty() ? "" : "، كلمة السر تغيّرت") + ")");
                        } else {
                            new File(new File(keysDir, k.id), "new.ks").delete();
                            log("فشل التعديل، بقي المفتاح كما هو:\n" + tail(r.text));
                        }
                    }
                } catch (Exception e) { log("فشل التعديل: " + e); }
                ui.post(this::refreshKeys);
            });
        });
    }

    private static String dnSafe(String s) { return s.replaceAll("[,=+<>#;\"\\\\]", " ").trim(); }

    private void importKeyDialog(final Uri u) {
        String fn = displayName(u);
        final EditText name = field("اسم المفتاح", false), pw = field("كلمة سر ملف المفاتيح", true),
                kpw = field("كلمة سر المفتاح (اتركها فارغة إن كانت نفسها)", true),
                al = field("alias محدد (اتركه فارغًا لأول مفتاح خاص)", false);
        name.setText(fn.replaceAll("\\.[A-Za-z0-9]{1,8}$", ""));
        form("استيراد «" + fn + "»", new View[]{name, pw, kpw, al}, () -> {
            final String nm = name.getText().toString().trim(), pass = pw.getText().toString(), kp = kpw.getText().toString(),
                    wantAlias = al.getText().toString().trim();
            if (nm.isEmpty() || pass.isEmpty()) { toast("اكتب الاسم وكلمة السر"); return; }
            work("يستورد المفتاح…", () -> {
                SigningStore.Key k = new SigningStore.Key();
                k.id = "k" + System.currentTimeMillis();
                k.name = nm; k.pw = pass; k.kpw = kp; k.alias = wantAlias; k.created = SigningStore.now();
                File dir = new File(keysDir, k.id);
                dir.mkdirs();
                try (InputStream in = getContentResolver().openInputStream(u)) {
                    Compat.copy(in, new File(dir, "key.ks"));
                } catch (Exception e) {
                    deleteRec(dir); log("تعذّر قراءة الملف: " + e); return;
                }
                Res r = inspect(k, !wantAlias.isEmpty());
                if (r.code == 0 && parseInfo(k, r.text)) {
                    try { SigningStore.writeKey(this, k); log("تم استيراد «" + nm + "» (alias: " + k.alias + ")"); } catch (Exception e) { log("فشل الحفظ: " + e); }
                } else {
                    deleteRec(dir);
                    log("تعذّر فتح ملف المفاتيح (كلمة سر خاطئة أو alias غير موجود أو لا يحوي مفتاحًا خاصًا):\n" + tail(r.text));
                }
                ui.post(this::refreshKeys);
            });
        });
    }

    private Res inspect(SigningStore.Key k, boolean byAlias) {
        Map<String, String> env = new HashMap<>();
        env.put("L2S_PW", k.pw); env.put("L2S_ID", k.id); env.put("L2S_ALIAS", k.alias);
        return guest(90, env, "keytool -list -v -keystore /sign/keys/$L2S_ID/key.ks -storepass:env L2S_PW"
                + (byAlias ? " -alias \"$L2S_ALIAS\"" : ""));
    }

    /** يقرأ من مخرجات keytool: أول مفتاح خاص (الاسم المستعار) وبصمة SHA-256. */
    private boolean parseInfo(SigningStore.Key k, String out) {
        int i = out.indexOf("PrivateKeyEntry");
        if (i < 0) return false;
        Matcher a = Pattern.compile("Alias name:\\s*(.+)").matcher(out);
        String alias = null;
        while (a.find()) { if (a.start() < i) alias = a.group(1).trim(); }
        if (alias != null && !alias.isEmpty()) k.alias = alias;
        Matcher s = Pattern.compile("SHA-?256:\\s*([0-9A-Fa-f:]{95})").matcher(out.substring(i));
        if (s.find()) k.sha = s.group(1).toUpperCase(Locale.US);
        return !k.alias.isEmpty();
    }

    private void activate(final SigningStore.Key k) {
        if (k.locked) { toast("كلمة السر غير متاحة لهذا المفتاح"); return; }
        work("يعيّن مفتاح البناء…", () -> {
            Map<String, String> env = new HashMap<>();
            env.put("L2S_PW", k.pw); env.put("L2S_KPW", k.kpw.isEmpty() ? k.pw : k.kpw);
            env.put("L2S_ALIAS", k.alias); env.put("L2S_ID", k.id);
            Res r = guest(120, env, "set -e; rm -f /sign/tmp-debug.ks; "
                    + "keytool -importkeystore -srckeystore /sign/keys/$L2S_ID/key.ks -srcstorepass:env L2S_PW "
                    + "-srcalias \"$L2S_ALIAS\" -srckeypass:env L2S_KPW -destkeystore /sign/tmp-debug.ks "
                    + "-deststoretype PKCS12 -deststorepass android -destkeypass android -destalias androiddebugkey -noprompt");
            File tmp = new File(signRoot, "tmp-debug.ks");
            if (r.code == 0 && tmp.isFile()) {
                try {
                    File ah = new File(getFilesDir(), "android-home");
                    ah.mkdirs();
                    Compat.copy(tmp, new File(ah, "debug.keystore"));
                    tmp.delete();
                    SigningStore.prefs(this).edit().putString("active", k.id).apply();
                    log("«" + k.name + "» صار مفتاح البناء العام (debug). المشاريع التي لها ملف توقيع خاص تستعمل مفتاحها.");
                } catch (Exception e) { log("فشل النسخ: " + e); }
            } else {
                log("فشل تعيين المفتاح:\n" + tail(r.text));
            }
            ui.post(this::refreshKeys);
        });
    }

    private void exportKey(SigningStore.Key k) {
        try {
            File out = new File(new File(ShareProvider.root(this), "keys"), safe(k.name) + ".keystore");
            out.getParentFile().mkdirs();
            Compat.copy(new File(new File(keysDir, k.id), "key.ks"), out);
            Intent it = new Intent(Intent.ACTION_SEND);
            it.setType("application/octet-stream");
            it.putExtra(Intent.EXTRA_STREAM, ShareProvider.uriFor(this, out));
            it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(it, "تصدير المفتاح (احفظ كلمة السر بشكل منفصل)"));
        } catch (Exception e) { toast("فشل التصدير: " + e); }
    }

    private void deleteKey(SigningStore.Key k) {
        SigningStore.deleteKey(this, k.id);
        refreshKeys();
    }

    // ------------------------------------------------------------------ فحص APK
    private void showInspect(SigningStore.ApkInfo a) {
        if (a == null) { inspectView.setText(""); return; }
        if (a.error != null) { inspectView.setText("⚠ " + a.error); inspectView.setTextColor(WARN); return; }
        StringBuilder sb = new StringBuilder();
        sb.append("📦 ").append(a.pkg).append(" • ").append(a.versionName).append(" (").append(a.versionCode).append(")\n");
        sb.append("SDK: الأدنى ").append(a.minSdk).append(" • المستهدف ").append(a.targetSdk).append("\n");
        sb.append(a.sha.isEmpty() ? "🔓 غير موقَّع (لا شهادة)\n" : "🔑 توقيعه الحالي: " + shortSha(a.sha) + "\n");
        boolean warn = false;
        if (a.installed) {
            if (a.instSha.isEmpty()) sb.append("ℹ مثبّت على الجهاز (").append(a.instVersion).append(") وتعذّر قراءة توقيعه\n");
            else if (a.sameSigner) sb.append("✅ مثبّت على الجهاز (").append(a.instVersion).append(") بنفس توقيع هذا الملف\n");
            else { sb.append("⚠ مثبّت على الجهاز (").append(a.instVersion).append(") بتوقيع مختلف: لن يُثبَّت فوقه\n"); warn = true; }
        } else {
            sb.append("ℹ غير ظاهر كمثبّت على الجهاز\n");
        }
        String mode = g.get("insp_perms");
        if (!"none".equals(mode)) {
            List<String> shown = "all".equals(mode) ? a.perms : a.dangerous;
            sb.append("🔐 الصلاحيات: ").append(a.perms.size()).append(" (الخطرة ").append(a.dangerous.size()).append(")");
            if (!shown.isEmpty()) {
                sb.append("\n");
                for (String p : shown) sb.append("• ").append("all".equals(mode) ? p : p.substring(p.lastIndexOf('.') + 1)).append("\n");
            }
        }
        inspectView.setText(sb.toString().trim());
        inspectView.setTextColor(warn ? WARN : SUB);
    }

    /** يختار في القائمة المفتاح الذي يطابق توقيع النسخة المثبّتة (أو توقيع الملف نفسه). */
    private void autoSelectKey(SigningStore.ApkInfo a) {
        if (!SigningStore.on(g, "insp_autokey") || a == null || a.error != null) return;
        String[] wants = {a.instSha, a.sha};
        for (String w : wants) {
            if (w == null || w.isEmpty()) continue;
            for (int i = 0; i < keys.size(); i++) {
                if (!keys.get(i).sha.isEmpty() && SigningStore.normSha(keys.get(i).sha).equals(SigningStore.normSha(w))) {
                    keySpin.setSelection(i);
                    log("اختير تلقائيًا المفتاح «" + keys.get(i).name + "» لأن بصمته تطابق " + (w.equals(a.instSha) ? "النسخة المثبّتة." : "توقيع الملف."));
                    return;
                }
            }
        }
    }

    // ------------------------------------------------------------------ توقيع APK
    private void signApk() {
        if (pickedApk == null || !pickedApk.isFile()) { toast("اختر ملف APK أولًا"); return; }
        int pos = keySpin.getSelectedItemPosition();
        if (pos < 0 || pos >= keys.size()) { toast("أنشئ مفتاحًا أو استورد واحدًا أولًا"); return; }
        final SigningStore.Key k = keys.get(pos);
        if (k.locked) { toast("كلمة السر غير متاحة لهذا المفتاح"); return; }
        final Runnable go = () -> requireAuth("sign", () -> doSign(k));
        String pol = g.get("insp_mismatch");
        if (lastInfo != null && lastInfo.installed && !lastInfo.instSha.isEmpty() && !k.sha.isEmpty()
                && !SigningStore.normSha(k.sha).equals(SigningStore.normSha(lastInfo.instSha))) {
            if ("block".equals(pol)) { toast("بصمة المفتاح لا تطابق النسخة المثبّتة، والإعداد يمنع التوقيع. اختر المطابق أو غيّر الإعداد."); return; }
            if ("warn".equals(pol)) {
                new AlertDialog.Builder(this).setTitle("بصمة مختلفة عن النسخة المثبّتة")
                        .setMessage("المفتاح «" + k.name + "» لا يطابق توقيع النسخة المثبّتة، فلن يُثبَّت الناتج فوقها إلا بعد حذفها.")
                        .setPositiveButton("وقّع رغم ذلك", (x, y) -> go.run()).setNegativeButton("إلغاء", null).show();
                return;
            }
        }
        go.run();
    }

    private String signScript() {
        String zmode = g.get("sign_zipalign"), align = g.get("sign_align");
        String fail = "on".equals(zmode) ? "echo ZIPALIGN_FAILED; exit 3" : "cp in.apk aligned.apk; echo ZIPALIGN_SKIPPED";
        StringBuilder sh = new StringBuilder("set -e; BT=$(ls -d /opt/sdk/build-tools/* | sort -V | tail -1); cd /sign/work; rm -f aligned.apk out-signed.apk; ");
        if ("off".equals(zmode)) {
            sh.append("cp in.apk aligned.apk; echo ZIPALIGN_OFF; ");
        } else {
            sh.append("if [ -x \"$BT/zipalign\" ]; then ");
            if ("16k".equals(align)) {
                sh.append("if \"$BT/zipalign\" -P 16 -f 4 in.apk aligned.apk; then echo ZIPALIGN_OK16; ")
                        .append("elif \"$BT/zipalign\" -p -f 4 in.apk aligned.apk; then echo ZIPALIGN_FALLBACK4; else ").append(fail).append("; fi; ");
            } else {
                sh.append("if \"$BT/zipalign\" -p -f 4 in.apk aligned.apk; then echo ZIPALIGN_OK; else ").append(fail).append("; fi; ");
            }
            sh.append("else ").append(fail).append("; fi; ");
        }
        sh.append("\"$BT/apksigner\" sign --ks /sign/keys/$L2S_ID/key.ks --ks-key-alias \"$L2S_ALIAS\" --ks-pass env:L2S_PW --key-pass env:L2S_KPW");
        for (int v = 1; v <= 4; v++) {
            String s = g.get("sign_v" + v);
            if ("on".equals(s)) sh.append(" --v").append(v).append("-signing-enabled true");
            else if ("off".equals(s)) sh.append(" --v").append(v).append("-signing-enabled false");
        }
        String min = g.get("sign_minsdk").trim();
        if (min.matches("\\d{1,3}")) sh.append(" --min-sdk-version ").append(min);
        sh.append(" --out out-signed.apk aligned.apk; ");
        if (SigningStore.on(g, "sign_verify")) sh.append("\"$BT/apksigner\" verify --verbose --print-certs out-signed.apk");
        else sh.append("echo VERIFY_SKIPPED");
        return sh.toString();
    }

    private void doSign(final SigningStore.Key k) {
        String suffix = g.get("sign_suffix").replaceAll("[^A-Za-z0-9_.\\-]", "");
        final String outName = pickedName.replaceAll("(?i)\\.apk$", "") + suffix + ".apk";
        final String script = signScript();
        work("يوقّع التطبيق…", () -> {
            ui.post(() -> resultBox.removeAllViews());
            Map<String, String> env = new HashMap<>();
            env.put("L2S_PW", k.pw); env.put("L2S_KPW", k.kpw.isEmpty() ? k.pw : k.kpw);
            env.put("L2S_ALIAS", k.alias); env.put("L2S_ID", k.id);
            Res r = guest(300, env, script);
            File made = new File(workDir, "out-signed.apk");
            if (r.code == 0 && made.isFile()) {
                try {
                    File dst = new File(outDir, outName);
                    Compat.copy(made, dst);
                    made.delete();
                    signedApk = dst;
                    StringBuilder note = new StringBuilder();
                    if (r.text.contains("ZIPALIGN_SKIPPED")) note.append("\n(تعذّرت محاذاة zipalign أو لم تتوفر فتُخطّيت؛ قد يمنع ذلك التثبيت على أندرويد 11+)");
                    if (r.text.contains("ZIPALIGN_FALLBACK4")) note.append("\n(لم تدعم أداتك محاذاة 16KB فاستُخدمت محاذاة 4KB)");
                    if (r.text.contains("ZIPALIGN_OFF")) note.append("\n(المحاذاة معطّلة من الإعدادات)");
                    for (String ln : r.text.split("\n")) {
                        String t = ln.trim();
                        if (t.startsWith("Verif") || t.startsWith("Signer #1 certificate SHA-256")) note.append("\n").append(t);
                    }
                    log("تم التوقيع بالمفتاح «" + k.name + "»: " + outName + note);
                    trimHistory();
                    ui.post(this::showResult);
                } catch (Exception e) { log("فشل حفظ الناتج: " + e); }
            } else {
                log("فشل التوقيع:\n" + tail(r.text));
            }
        });
    }

    /** يُبقي آخر N ناتج موقَّع فقط حسب الإعداد (0 = بلا حد). */
    private void trimHistory() {
        int keep;
        try { keep = Integer.parseInt(g.get("sign_keep")); } catch (Exception e) { keep = 0; }
        if (keep <= 0) return;
        File[] fs = outDir.listFiles((d, n) -> n.endsWith(".apk"));
        if (fs == null || fs.length <= keep) return;
        Arrays.sort(fs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = keep; i < fs.length; i++) fs[i].delete();
    }

    private void showResult() {
        resultBox.removeAllViews();
        resultBox.addView(text("✔ " + signedApk.getName() + " (" + (signedApk.length() / 1024) + " KB)", OK, 14));
        SigningStore.ApkInfo a = SigningStore.inspectApk(this, signedApk);
        if (a.error == null && !a.sha.isEmpty()) resultBox.addView(text("بصمة التوقيع: " + shortSha(a.sha), SUB, 12));
        LinearLayout r = hrow();
        Button s = btn("مشاركة", 0xFF3F51B5), i = btn("تثبيت", ACCENT);
        r.addView(s, weight()); r.addView(i, weight());
        resultBox.addView(r);
        s.setOnClickListener(v -> sendApk(false));
        i.setOnClickListener(v -> sendApk(true));
    }

    private void sendApk(boolean install) {
        try {
            File out = new File(new File(ShareProvider.root(this), "signed"), signedApk.getName());
            out.getParentFile().mkdirs();
            Compat.copy(signedApk, out);
            Uri u = ShareProvider.uriFor(this, out);
            String mime = "application/vnd.android.package-archive";
            if (install) {
                Intent it = new Intent(Intent.ACTION_VIEW);
                it.setDataAndType(u, mime);
                it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(it);
            } else {
                Intent it = new Intent(Intent.ACTION_SEND);
                it.setType(mime);
                it.putExtra(Intent.EXTRA_STREAM, u);
                it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(it, "مشاركة التطبيق الموقَّع"));
            }
        } catch (Exception e) { toast("تعذّر: " + e); }
    }

    // ------------------------------------------------------------------ فحص التشفير
    private void scanPickedApk() {
        if (pickedApk == null || !pickedApk.isFile()) { toast("اختر ملف APK أولًا من قسم التوقيع"); return; }
        final File f = pickedApk;
        final String nm = pickedName;
        work("يفحص التشفير…", () -> {
            String rep = CryptoScanner.scanApk(this, f, nm);
            ui.post(() -> showReport("تقرير: " + nm, rep));
        });
    }

    private void scanInstalledDialog() {
        android.content.pm.PackageManager pm = getPackageManager();
        Intent q = new Intent(Intent.ACTION_MAIN);
        q.addCategory(Intent.CATEGORY_LAUNCHER);
        List<android.content.pm.ResolveInfo> ri = pm.queryIntentActivities(q, 0);
        final List<String[]> apps = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (android.content.pm.ResolveInfo r : ri) {
            android.content.pm.ApplicationInfo ai = r.activityInfo.applicationInfo;
            if (!seen.add(ai.packageName)) continue;
            apps.add(new String[]{String.valueOf(r.loadLabel(pm)), ai.packageName, ai.sourceDir});
        }
        Collections.sort(apps, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        if (apps.isEmpty()) { toast("لا تطبيقات ظاهرة"); return; }
        String[] items = new String[apps.size()];
        for (int i = 0; i < items.length; i++) items[i] = apps.get(i)[0] + "\n" + apps.get(i)[1];
        new AlertDialog.Builder(this).setTitle("اختر تطبيقًا").setItems(items, (d, i) -> {
            final String[] a = apps.get(i);
            work("يفحص " + a[0] + "…", () -> {
                String rep = CryptoScanner.scanApk(this, new File(a[2]), a[0] + " (" + a[1] + ")")
                        + "\nيُفحص الملف الأساسي base.apk فقط (قد توجد حزم تقسيم splits).\n";
                ui.post(() -> showReport("تقرير: " + a[0], rep));
            });
        }).show();
    }

    private void scanProject(final String name) {
        work("يفحص المشروع…", () -> {
            File root = new File(new File(getFilesDir(), "projects"), name);
            String rep = CryptoScanner.scanProject(root, name);
            ui.post(() -> showReport("تقرير: " + name, rep));
        });
    }

    private void showReport(String title, final String body) {
        TextView tv = text(body, TXT, 12);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(16), dp(8), dp(16), dp(8));
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this).setTitle(title).setView(sv)
                .setPositiveButton("إغلاق", null)
                .setNeutralButton("مشاركة", (d, w) -> {
                    try {
                        File out = new File(new File(ShareProvider.root(this), "reports"), "crypto-report.txt");
                        out.getParentFile().mkdirs();
                        Compat.write(out, body.getBytes("UTF-8"));
                        Intent it = new Intent(Intent.ACTION_SEND);
                        it.setType("text/plain");
                        it.putExtra(Intent.EXTRA_STREAM, ShareProvider.uriFor(this, out));
                        it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(it, "مشاركة التقرير"));
                    } catch (Exception e) { toast("تعذّرت المشاركة: " + e); }
                }).show();
    }

    // ------------------------------------------------------------------ ملفات توقيع المشاريع
    private void refreshProjects() {
        projectsBox.removeAllViews();
        File[] ds = new File(getFilesDir(), "projects").listFiles(File::isDirectory);
        if (ds == null || ds.length == 0) { projectsBox.addView(text("لا توجد مشاريع بعد. ارفع مشروعًا أولًا.", SUB, 13)); return; }
        Arrays.sort(ds, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (final File d : ds) {
            final String name = d.getName();
            LinearLayout c = vbox();
            c.setBackgroundColor(CARD);
            int p = dp(10);
            c.setPadding(p, p, p, p);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(8);
            c.setLayoutParams(lp);
            TextView n = text("📁 " + name, TXT, 15);
            n.setTypeface(null, android.graphics.Typeface.BOLD);
            c.addView(n);
            c.addView(text(profileSummary(name), SUB, 12));
            Button b = btn("⚙ إعدادات التوقيع", ACCENT);
            c.addView(b);
            b.setOnClickListener(v -> projectDialog(name));
            Button sc = btn("🧬 فحص التشفير", 0xFF009688);
            c.addView(sc);
            sc.setOnClickListener(v -> scanProject(name));
            projectsBox.addView(c);
        }
    }

    private String profileSummary(String name) {
        if (!SigningStore.hasProfile(this, name)) return "افتراضي: debug بمفتاح البناء العام";
        SigningStore.Kv pk = SigningStore.profileKv(this, name);
        String kid = pk.get("key");
        String kn = kid.isEmpty() ? "المفتاح العام" : nameOf(kid);
        return "النوع: " + pk.get("btype") + " • المفتاح: " + kn;
    }

    private void projectDialog(final String name) {
        final SigningStore.Kv pk = SigningStore.profileKv(this, name);
        List<String> kl = new ArrayList<>(), kv = new ArrayList<>();
        kl.add("افتراضي (مفتاح البناء/الإصدار العام)"); kv.add("");
        for (SigningStore.Key k : keys) { kl.add(k.name + (k.locked ? " ⚠" : "")); kv.add(k.id); }
        String[] tri = {"حسب الإعداد العام", "مفعّل", "معطّل"}, triV = {"inherit", "on", "off"};
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("hdr", "", "المفتاح والنوع", "", null, null));
        rows.add(new Row("ch", "key", "مفتاح هذا المشروع", "يُستعمل في البناء بدل المفتاح العام.", kl.toArray(new String[0]), kv.toArray(new String[0])));
        rows.add(new Row("ch", "signmode", "طريقة التوقيع", "«بدون توقيع» يبني ملفًا غير موقَّع لتوقّعه لاحقًا من «توقيع تطبيق». «debug القياسي» يستعمل مفتاح Android Debug المعروف (للتجربة فقط، لا يصلح للنشر).",
                new String[]{"بمفتاح من الخزنة", "بدون توقيع (يُوقَّع لاحقًا)", "توقيع debug القياسي (تجريبي)"}, new String[]{"key", "unsigned", "stddebug"}));
        rows.add(new Row("ch", "btype", "نوع البناء", "release يبني assembleRelease موقَّعًا ويُحفظ ناتجه بصيغة اسم-release.apk.",
                new String[]{"debug", "release"}, new String[]{"debug", "release"}));
        rows.add(new Row("sw", "apply_debug", "تطبيق مفتاح المشروع على debug", "عند تعطيله يبقى debug بمفتاح البناء العام.", null, null));
        rows.add(new Row("ch", "override", "استبدال توقيع المشروع (release)", "إن كان build.gradle يعرّف signingConfig لـ release: هل نستبدله بمفتاحك؟",
                new String[]{"حسب الإعداد العام", "نعم، استبدله", "لا، احترم توقيع المشروع"}, new String[]{"inherit", "yes", "no"}));
        rows.add(new Row("hdr", "", "مخططات التوقيع", "", null, null));
        rows.add(new Row("ch", "v1", "المخطط v1 (JAR)", "ضروري لأندرويد 6 فأقل، ويُعطَّل أحيانًا للحد الأدنى 24+.", tri, triV));
        rows.add(new Row("ch", "v2", "المخطط v2", "الأساسي من أندرويد 7.", tri, triV));
        rows.add(new Row("ch", "v3", "المخطط v3", "يدعم تدوير المفاتيح لاحقًا.", tri, triV));
        rows.add(new Row("hdr", "", "release", "", null, null));
        rows.add(new Row("ch", "minify", "تقليص الكود R8", "تعطيله يسرّع البناء ويوفّر الذاكرة على الجهاز.",
                new String[]{"حسب الإعداد العام", "كما يحدده المشروع", "تعطيل", "تفعيل"}, new String[]{"inherit", "project", "off", "on"}));
        rows.add(new Row("ch", "debuggable", "قابلية التنقيح", "",
                new String[]{"حسب الإعداد العام", "كما يحدده المشروع", "إيقاف", "تشغيل"}, new String[]{"inherit", "project", "off", "on"}));
        rows.add(new Row("hdr", "", "تعتيم متقدم (release فقط)", "", null, null));
        rows.add(new Row("ch", "hard_full", "R8 Full Mode", "تعتيم أعمق من الافتراضي؛ قد يكسر كودًا يعتمد على الانعكاس (reflection) بلا قواعد Keep مناسبة.", LBL, VAL));
        rows.add(new Row("ch", "hard_repack", "دمج كل الأصناف في حزمة واحدة", "يُخفي بنية الحزم الأصلية لمشروعك.", LBL, VAL));
        rows.add(new Row("ch", "hard_nolog", "حذف استدعاءات Log", "تُزال نهائيًا من الناتج (v، d، i، w، e، wtf).", LBL, VAL));
        Button preview = btn("👁 عرض الخطة الفعلية للبناء", 0xFF009688);
        Button reset = btn("↩ إعادة ضبط ملف هذا المشروع", DANGER);
        preview.setOnClickListener(v -> showPlan(name));
        reset.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("إعادة الضبط؟")
                .setMessage("يعود «" + name + "» إلى البناء الافتراضي (debug بمفتاح البناء العام).")
                .setPositiveButton("إعادة ضبط", (x, y) -> { SigningStore.deleteProfile(this, name); toast("أُعيد الضبط، أعد فتح الإعدادات"); refreshProjects(); })
                .setNegativeButton("إلغاء", null).show());
        settingsDialog("توقيع: " + name, pk, rows, new View[]{preview, reset});
    }

    private void showPlan(String name) {
        SigningStore.Plan p = SigningStore.plan(this, name);
        StringBuilder sb = new StringBuilder();
        if (p.error != null) {
            sb.append("⛔ لن يبدأ البناء:\n").append(p.error);
        } else {
            sb.append("المهمة: ").append(p.task).append("\nالناتج: app/build/outputs/apk/").append(p.variant).append("\n");
            sb.append(p.active ? "المفتاح: " + p.keyName + "\n" : "المفتاح: لا يُحقن توقيع (مفتاح debug العام)\n");
            if (!p.note.isEmpty()) sb.append("\n").append(p.note);
            sb.append("\nمتغيرات تُمرَّر لـ Gradle (بلا كلمات السر):\n");
            for (String e : p.env) if (!e.contains("PW=")) sb.append("• ").append(e).append("\n");
            if (p.env.isEmpty()) sb.append("• لا شيء\n");
        }
        new AlertDialog.Builder(this).setTitle("خطة بناء «" + name + "»").setMessage(sb.toString().trim()).setPositiveButton("حسنًا", null).show();
    }

    // ------------------------------------------------------------------ النسخة الاحتياطية المشفّرة
    private void exportVaultDialog() {
        requireAuth("backup", () -> {
            final int minLen = intOf(g.get("bk_minpass"), 8);
            final EditText p1 = field("كلمة سر النسخة (" + minLen + " أحرف فأكثر)", true), p2 = field("أعد كتابة كلمة السر", true);
            TextView hint = text("هذه الكلمة تشفّر الملف وحدها. إن نسيتها لا يمكن استعادة المفاتيح.", WARN, 12);
            form("نسخة احتياطية مشفّرة", new View[]{hint, p1, p2}, () -> {
                final String a = p1.getText().toString();
                if (a.length() < minLen) { toast("كلمة السر أقصر من " + minLen + " أحرف"); return; }
                if (!a.equals(p2.getText().toString())) { toast("الكلمتان غير متطابقتين"); return; }
                work("يشفّر النسخة…", () -> doExportVault(a));
            });
        });
    }

    private void doExportVault(String pass) {
        try {
            SigningStore.Export ex = SigningStore.exportBundle(this, pass, intOf(g.get("bk_iter"), 210000),
                    SigningStore.on(g, "bk_profiles"), new File(ShareProvider.root(this), "vault"));
            String a = SigningStore.saveToDownloads(this, ex.file, "application/octet-stream");
            String b = SigningStore.saveToBackupFolder(this, ex.file, "application/octet-stream");
            log("شُفّر " + ex.keys + " مفتاح" + (ex.locked > 0 ? " (تُخطّي " + ex.locked + " لتعذّر فك كلمة سرها)" : "") + "\n"
                    + a + (b.isEmpty() ? "" : "\n" + b) + "\nاحتفظ بكلمة سر النسخة منفصلة عن الملف.");
            lastVault = ex.file;
            ui.post(() -> {
                vaultBox.removeAllViews();
                vaultBox.addView(text("✔ " + ex.file.getName(), OK, 13));
                Button sh = btn("مشاركة الملف المشفّر", 0xFF3F51B5);
                vaultBox.addView(sh);
                sh.setOnClickListener(v -> shareVault());
                refreshKeys();
            });
        } catch (Exception e) {
            log("فشل النسخ الاحتياطي: " + e.getMessage());
        }
    }

    private void shareVault() {
        if (lastVault == null || !lastVault.isFile()) { toast("لا توجد نسخة جاهزة"); return; }
        Intent it = new Intent(Intent.ACTION_SEND);
        it.setType("application/octet-stream");
        it.putExtra(Intent.EXTRA_STREAM, ShareProvider.uriFor(this, lastVault));
        it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(it, "مشاركة نسخة المفاتيح المشفّرة"));
    }

    private void importVaultDialog(final Uri u) {
        final EditText pass = field("كلمة سر النسخة", true);
        String mode = g.get("bk_dupmode");
        String ml = "overwrite".equals(mode) ? "استبدال المفتاح الموجود" : ("copy".equals(mode) ? "إضافة نسخة ثانية" : "تخطّي الموجود");
        TextView hint = text("المفاتيح المكرّرة (نفس المعرّف أو البصمة): " + ml + ". يُغيَّر من الإعدادات ← النسخ الاحتياطي.", SUB, 12);
        form("استعادة «" + displayName(u) + "»", new View[]{hint, pass}, () -> {
            final String ps = pass.getText().toString();
            if (ps.isEmpty()) { toast("اكتب كلمة السر"); return; }
            work("يستعيد المفاتيح…", () -> {
                try (InputStream in = getContentResolver().openInputStream(u)) {
                    String sum = SigningStore.importBundle(this, in, ps, g.get("bk_dupmode"), SigningStore.on(g, "bk_profiles"));
                    log("اكتملت الاستعادة: " + sum);
                } catch (Exception e) {
                    log("فشلت الاستعادة: " + e.getMessage());
                }
                ui.post(this::refreshKeys);
            });
        });
    }

    // ------------------------------------------------------------------ صفوف الإعدادات
    private Row sw(String key, String title, String desc) { return new Row("sw", key, title, desc, null, null); }
    private Row ch(String key, String title, String desc, String[] l, String[] v) { return new Row("ch", key, title, desc, l, v); }
    private Row hdr(String title) { return new Row("hdr", "", title, "", null, null); }

    private List<Row> authRows() {
        List<Row> r = new ArrayList<>();
        r.add(hdr("اطلب البصمة (أو قفل الشاشة) قبل"));
        r.add(sw("auth_export", "تصدير مفتاح", "مشاركة ملف المفتاح خارج التطبيق."));
        r.add(sw("auth_edit", "تعديل مفتاح", "تغيير الاسم المستعار أو كلمة السر."));
        r.add(sw("auth_delete", "حذف مفتاح", "الحذف نهائي."));
        r.add(sw("auth_backup", "تصدير النسخة الاحتياطية للخزنة", "ملف يحوي كل مفاتيحك (مشفّرًا)."));
        r.add(sw("auth_sign", "توقيع APK", "استعمال مفتاح من الخزنة."));
        r.add(sw("auth_activate", "تعيين مفتاح البناء", "تغيير مفتاح debug العام."));
        r.add(hdr("السلوك"));
        r.add(ch("auth_timeout", "تذكّر التأكيد", "بعد نجاح التأكيد لا يُطلب مرة أخرى خلال هذه المدة.",
                new String[]{"كل مرة", "30 ثانية", "دقيقة", "5 دقائق", "15 دقيقة"}, new String[]{"0", "30", "60", "300", "900"}));
        r.add(ch("auth_nolock", "إن لم يكن للجهاز قفل شاشة", "لا يمكن التحقق بلا قفل.",
                new String[]{"اسمح بالمتابعة", "امنع العملية"}, new String[]{"allow", "deny"}));
        return r;
    }

    private List<Row> signRows() {
        List<Row> r = new ArrayList<>();
        String[] tri = {"تلقائي (حسب apksigner)", "مفعّل", "معطّل"}, triV = {"auto", "on", "off"};
        r.add(hdr("المحاذاة (zipalign)"));
        r.add(ch("sign_zipalign", "zipalign", "تلقائي: يخطّاه إن لم يتوفر. مطلوب: يفشل التوقيع بدونه.",
                new String[]{"تلقائي", "مطلوب", "معطّل"}, new String[]{"auto", "on", "off"}));
        r.add(ch("sign_align", "حجم المحاذاة", "16KB لأجهزة الصفحات الكبيرة؛ يتراجع إلى 4KB إن لم تدعمه أداتك.",
                new String[]{"4KB", "16KB"}, new String[]{"4", "16k"}));
        r.add(hdr("مخططات التوقيع"));
        r.add(ch("sign_v1", "v1 (JAR)", "", tri, triV));
        r.add(ch("sign_v2", "v2", "", tri, triV));
        r.add(ch("sign_v3", "v3", "", tri, triV));
        r.add(ch("sign_v4", "v4 (للتثبيت التزايدي)", "", tri, triV));
        r.add(new Row("num", "sign_minsdk", "أدنى SDK للتوقيع", "اتركه فارغًا ليقرأه apksigner من الحزمة.", null, null));
        r.add(hdr("بعد التوقيع"));
        r.add(sw("sign_verify", "تحقق من التوقيع", "تشغيل apksigner verify وعرض المخططات المتحقَّق منها."));
        r.add(new Row("txt", "sign_suffix", "لاحقة اسم الناتج", "يصير الاسم: الأصل + اللاحقة + .apk", null, null));
        r.add(ch("sign_keep", "عدد النواتج المحفوظة", "تُحذف الأقدم تلقائيًا.",
                new String[]{"3", "10", "30", "بلا حد"}, new String[]{"3", "10", "30", "0"}));
        return r;
    }

    private List<Row> inspectRows() {
        List<Row> r = new ArrayList<>();
        r.add(ch("insp_perms", "عرض الصلاحيات", "",
                new String[]{"الخطرة فقط", "الكل", "إخفاء"}, new String[]{"dangerous", "all", "none"}));
        r.add(sw("insp_autokey", "اختيار المفتاح المطابق تلقائيًا", "يختار المفتاح الذي تطابق بصمته النسخة المثبّتة."));
        r.add(ch("insp_mismatch", "عند اختلاف بصمة المفتاح عن المثبّت", "",
                new String[]{"حذّرني", "امنع التوقيع", "تجاهل"}, new String[]{"warn", "block", "ignore"}));
        return r;
    }

    private List<Row> releaseRows() {
        List<Row> r = new ArrayList<>();
        List<String> kl = new ArrayList<>(), kv = new ArrayList<>();
        kl.add("مفتاح البناء الحالي"); kv.add("");
        for (SigningStore.Key k : keys) { kl.add(k.name); kv.add(k.id); }
        r.add(hdr("المفتاح"));
        r.add(ch("rel_defkey", "مفتاح release الافتراضي", "للمشاريع التي لم يُعيَّن لها مفتاح.", kl.toArray(new String[0]), kv.toArray(new String[0])));
        r.add(ch("rel_nokey", "عند غياب المفتاح", "",
                new String[]{"أوقف البناء", "ابنِ نسخة غير موقَّعة"}, new String[]{"block", "unsigned"}));
        r.add(sw("rel_override", "استبدال توقيع المشروع", "إن عرّف المشروع signingConfig لـ release يُستبدل بمفتاحك."));
        r.add(hdr("الافتراضيات (يمكن تجاوزها لكل مشروع)"));
        r.add(ch("rel_minify", "تقليص الكود R8", "",
                new String[]{"كما يحدده المشروع", "تعطيل", "تفعيل"}, new String[]{"project", "off", "on"}));
        r.add(ch("rel_debuggable", "قابلية التنقيح", "",
                new String[]{"كما يحدده المشروع", "إيقاف", "تشغيل"}, new String[]{"project", "off", "on"}));
        r.add(hdr("تعتيم متقدم (افتراضي، يمكن تجاوزه لكل مشروع)"));
        r.add(sw("hard_full", "R8 Full Mode", "تعتيم أعمق؛ اختبر release بعده."));
        r.add(sw("hard_repack", "دمج كل الأصناف في حزمة واحدة", ""));
        r.add(sw("hard_nolog", "حذف استدعاءات Log", ""));
        return r;
    }

    private List<Row> backupRows() {
        List<Row> r = new ArrayList<>();
        r.add(hdr("التشفير"));
        r.add(ch("bk_minpass", "أقل طول لكلمة السر", "",
                new String[]{"6", "8", "12", "16"}, new String[]{"6", "8", "12", "16"}));
        r.add(ch("bk_iter", "قوة اشتقاق المفتاح", "الأعلى أمانًا والأبطأ في الإنشاء والاستعادة.",
                new String[]{"100 ألف", "210 آلاف (موصى)", "600 ألف"}, new String[]{"100000", "210000", "600000"}));
        r.add(hdr("المحتوى"));
        r.add(sw("bk_profiles", "تضمين ملفات توقيع المشاريع", ""));
        r.add(ch("bk_dupmode", "عند استعادة مفتاح موجود", "",
                new String[]{"تخطَّ", "استبدل", "أضف نسخة ثانية"}, new String[]{"skip", "overwrite", "copy"}));
        r.add(hdr("التذكير"));
        r.add(ch("bk_remind", "ذكّرني بالنسخ الاحتياطي", "يظهر تنبيه أعلى الشاشة إن لم تُنسخ الخزنة.",
                new String[]{"لا", "بعد 7 أيام", "بعد 30 يومًا"}, new String[]{"0", "7", "30"}));
        return r;
    }

    private View settingsButton(String title, String desc, final Runnable r) {
        Button b = btn(title + "\n" + desc, 0xFF2A2D40);
        b.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        b.setOnClickListener(v -> r.run());
        return b;
    }

    private void settingsDialog(String title, final SigningStore.Kv kv, final List<Row> rows, final View[] extras) {
        final Dialog dlg = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        LinearLayout outer = vbox();
        outer.setBackgroundColor(BG);
        TextView t = text(title, TXT, 18);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setPadding(dp(14), dp(14), dp(14), dp(6));
        outer.addView(t);
        ScrollView s = new ScrollView(this);
        final LinearLayout box = vbox();
        int p = dp(14);
        box.setPadding(p, p / 2, p, p);
        s.addView(box);
        outer.addView(s, new LinearLayout.LayoutParams(-1, 0, 1f));
        Button close = btn("إغلاق", ACCENT);
        outer.addView(close);
        close.setOnClickListener(v -> dlg.dismiss());
        final Runnable[] render = new Runnable[1];
        render[0] = () -> {
            box.removeAllViews();
            for (Row r : rows) box.addView(rowView(r, kv, render[0]));
            if (extras != null) for (View e : extras) { if (e.getParent() != null) ((android.view.ViewGroup) e.getParent()).removeView(e); box.addView(e); }
        };
        render[0].run();
        dlg.setContentView(outer);
        dlg.setOnDismissListener(d -> refreshKeys());
        dlg.show();
    }

    private View rowView(final Row r, final SigningStore.Kv kv, final Runnable rerender) {
        if ("hdr".equals(r.type)) {
            TextView h = text(r.title, ACCENT, 14);
            h.setTypeface(null, android.graphics.Typeface.BOLD);
            h.setPadding(0, dp(14), 0, dp(4));
            return h;
        }
        LinearLayout c = vbox();
        c.setBackgroundColor(CARD);
        int p = dp(10);
        c.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(6);
        c.setLayoutParams(lp);
        c.addView(text(r.title, TXT, 15));
        if (!r.desc.isEmpty()) c.addView(text(r.desc, SUB, 12));
        final String cur = kv.get(r.key);
        if ("sw".equals(r.type)) {
            Switch s = new Switch(this);
            s.setChecked("1".equals(cur));
            s.setOnCheckedChangeListener((b, v) -> kv.put(r.key, v ? "1" : "0"));
            c.addView(s);
        } else if ("ch".equals(r.type)) {
            String label = cur;
            for (int i = 0; i < r.values.length; i++) if (r.values[i].equals(cur)) label = r.labels[i];
            Button b = btn(label + "  ▾", 0xFF2A2D40);
            b.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle(r.title)
                    .setItems(r.labels, (d, i) -> { kv.put(r.key, r.values[i]); rerender.run(); }).show());
            c.addView(b);
        } else {
            Button b = btn((cur.isEmpty() ? "— (فارغ)" : cur) + "  ✎", 0xFF2A2D40);
            final boolean numeric = "num".equals(r.type);
            b.setOnClickListener(v -> {
                final EditText e = field(r.title, false);
                e.setText(cur);
                if (numeric) e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
                form(r.title, new View[]{e}, () -> {
                    String val = e.getText().toString().trim();
                    if (numeric) val = val.replaceAll("[^0-9]", "");
                    kv.put(r.key, val);
                    rerender.run();
                });
            });
            c.addView(b);
        }
        return c;
    }

    // ------------------------------------------------------------------ اختيار الملفات
    private void pick(int code) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, code);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_AUTH) {
            Runnable r = pendingAuth;
            pendingAuth = null;
            if (res == RESULT_OK && r != null) { lastAuthMs = System.currentTimeMillis(); r.run(); }
            return;
        }
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri u = data.getData();
        if (req == REQ_KEY) importKeyDialog(u);
        else if (req == REQ_VAULT) importVaultDialog(u);
        else if (req == REQ_APK) {
            final String nm = displayName(u);
            work("ينسخ الملف ويفحصه…", () -> {
                File f = new File(workDir, "in.apk");
                try (InputStream in = getContentResolver().openInputStream(u)) {
                    Compat.copy(in, f);
                    pickedApk = f; pickedName = nm;
                    final SigningStore.ApkInfo a = SigningStore.inspectApk(this, f);
                    lastInfo = a;
                    final String shown = nm + " • " + (f.length() / 1024) + " KB";
                    ui.post(() -> { apkInfo.setText(shown); showInspect(a); autoSelectKey(a); });
                } catch (Exception e) { log("تعذّر نسخ الملف: " + e); }
            });
        }
    }

    private String displayName(Uri u) {
        try (Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0 && c.getString(i) != null) return c.getString(i);
            }
        } catch (Exception ignored) { }
        String s = u.getLastPathSegment();
        return s == null ? "file" : s;
    }

    // ------------------------------------------------------------------ تشغيل الأوامر داخل Debian (نفس أسلوب MainActivity)
    private File toolsBase() {
        File root = new File(getFilesDir(), "tools");
        File b = new File(root, "tooltest");
        return new File(b, "bin").exists() ? b : root;
    }

    private File findRoot(File d, int depth) {
        if (new File(d, "usr/bin/env").exists()) return d;
        if (depth == 0) return null;
        File[] ks = d.listFiles();
        if (ks != null) for (File k : ks) {
            if (k.isDirectory() && !Compat.isSymbolicLink(k)) { File r = findRoot(k, depth - 1); if (r != null) return r; }
        }
        return null;
    }

    private Res guest(int timeoutSec, Map<String, String> env, String script) {
        Res res = new Res();
        File base = toolsBase();
        File rootfs = findRoot(new File(getFilesDir(), "rootfs"), 2);
        if (rootfs == null || !new File(base, "bin/proot").exists()) {
            res.text = "بيئة Debian غير جاهزة. أكمل الإعداد الأولي من الشاشة الرئيسية أولًا.\n";
            return res;
        }
        File sdk = new File(getFilesDir(), "sdk"), ah = new File(getFilesDir(), "android-home");
        sdk.mkdirs(); ah.mkdirs(); signRoot.mkdirs();
        new File(rootfs, "sign").mkdirs();
        new File(rootfs, "root/.android").mkdirs();
        List<String> c = new ArrayList<>();
        c.add(new File(base, "bin/proot").getAbsolutePath());
        c.add("--link2symlink"); c.add("--kill-on-exit"); c.add("-0"); c.add("-r"); c.add(rootfs.getAbsolutePath());
        for (String bnd : new String[]{"/dev", "/proc", "/sys", "/system"}) { c.add("-b"); c.add(bnd); }
        if (new File("/apex").exists()) { c.add("-b"); c.add("/apex"); }
        c.add("-b"); c.add(base.getAbsolutePath() + ":/opt/l2s");
        c.add("-b"); c.add(sdk.getAbsolutePath() + ":/opt/sdk");
        c.add("-b"); c.add(ah.getAbsolutePath() + ":/root/.android");
        c.add("-b"); c.add(signRoot.getAbsolutePath() + ":/sign");
        c.add("-w"); c.add("/root");
        c.add("/usr/bin/env"); c.add("-i");
        c.add("HOME=/root");
        c.add("PATH=" + JAVA + "/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        c.add("LANG=C.UTF-8"); c.add("TMPDIR=/tmp");
        c.add("JAVA_HOME=" + JAVA); c.add("ANDROID_HOME=/opt/sdk");
        if (env != null) for (Map.Entry<String, String> e : env.entrySet()) c.add(e.getKey() + "=" + e.getValue());
        c.add("/bin/sh"); c.add("-c"); c.add(script);
        try {
            ProcessBuilder pb = new ProcessBuilder(c);
            pb.redirectErrorStream(true);
            Map<String, String> pe = pb.environment();
            pe.put("LD_LIBRARY_PATH", new File(base, "lib").getAbsolutePath());
            pe.put("PROOT_LOADER", new File(base, "libexec/proot/loader").getAbsolutePath());
            pe.put("PROOT_TMP_DIR", getCacheDir().getAbsolutePath());
            final Process p = pb.start();
            final StringBuilder sb = new StringBuilder();
            Thread rd = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"))) {
                    String l; int n = 0;
                    while ((l = br.readLine()) != null) {
                        if (n++ < 400) synchronized (sb) { sb.append(l).append('\n'); }
                    }
                } catch (IOException ignored) { }
            });
            rd.start();
            boolean done = Compat.waitFor(p, timeoutSec, TimeUnit.SECONDS);
            if (!done) Compat.destroyForcibly(p);
            rd.join(3000);
            synchronized (sb) { res.text = sb.toString(); }
            if (!done) res.text += "[انتهت المهلة]\n";
            res.code = done ? p.exitValue() : -1;
        } catch (Exception e) {
            res.text = "فشل التشغيل: " + e + "\n";
        }
        return res;
    }

    // ------------------------------------------------------------------ أدوات عامة
    private void work(String label, Runnable r) {
        if (busy) { toast("انتظر انتهاء العملية الجارية"); return; }
        busy = true;
        status.setText("⏳ " + label);
        pool.execute(() -> {
            try { r.run(); } catch (Throwable t) { log("خطأ: " + t); }
            finally { busy = false; ui.post(() -> { if (status.getText().toString().startsWith("⏳")) refreshKeys(); }); }
        });
    }

    private void log(String s) {
        logBuf.append(new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date())).append("  ").append(s).append("\n\n");
        if (logBuf.length() > 6000) logBuf.delete(0, logBuf.length() - 6000);
        ui.post(() -> logView.setText(logBuf.toString()));
    }

    private static String tail(String s) {
        String[] ls = s.trim().split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, ls.length - 8); i < ls.length; i++) sb.append(ls[i]).append('\n');
        return sb.toString().trim();
    }

    private static String safe(String s) { String r = s.replaceAll("[^\\p{L}\\p{N}_-]+", "_"); return r.isEmpty() ? "key" : r; }

    private static int intOf(String s, int d) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return d; } }

    private static void deleteRec(File f) {
        File[] ch = f.listFiles();
        if (ch != null) for (File c : ch) deleteRec(c);
        f.delete();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    // ------------------------------------------------------------------ واجهة
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private LinearLayout vbox() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout hrow() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private TextView text(String s, int color, int sp) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextColor(color); t.setTextSize(sp);
        t.setPadding(0, dp(3), 0, dp(3));
        return t;
    }

    private TextView section(String s) {
        TextView t = text(s, ACCENT, 15);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setPadding(0, dp(18), 0, dp(6));
        return t;
    }

    private Button btn(String s, int color) {
        Button b = new Button(this);
        b.setText(s); b.setTextColor(TXT); b.setBackgroundColor(color); b.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(4); lp.leftMargin = dp(3); lp.rightMargin = dp(3);
        b.setLayoutParams(lp);
        return b;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.leftMargin = dp(3); lp.rightMargin = dp(3);
        return lp;
    }

    private EditText field(String hint, boolean pass) {
        EditText e = new EditText(this);
        e.setHint(hint); e.setSingleLine(true);
        if (pass) e.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return e;
    }

    private void form(String title, View[] fields, final Runnable ok) {
        LinearLayout box = vbox();
        int p = dp(16);
        box.setPadding(p, p / 2, p, 0);
        for (View v : fields) box.addView(v);
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this).setTitle(title).setView(sv)
                .setPositiveButton("موافق", (d, w) -> ok.run()).setNegativeButton("إلغاء", null).show();
    }
}
