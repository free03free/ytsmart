package com.example.l2sbuilder;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * الطبقة المشتركة لمركز التوقيع (تستخدمها SigningActivity وMainActivity):
 * الخزنة وتشفير كلمات السر، إعدادات عامة وملفات توقيع لكل مشروع، خطة التوقيع وقت البناء (init script)،
 * فحص APK، والنسخة الاحتياطية المشفّرة بكلمة سر.
 */
public final class SigningStore {
    private SigningStore() { }

    private static final String WRAP = "l2s_sign_wrap";
    private static final Pattern ID_OK = Pattern.compile("[A-Za-z0-9_\\-]{1,64}");
    private static final byte[] MAGIC = new byte[]{'L', '2', 'S', 'K', 'E', 'Y', 'S', '1'};

    // ------------------------------------------------------------------ نماذج
    public static class Key {
        public String id, name, alias = "", sha = "", created = "";
        public String pw = "", kpw = "";
        /** تعذّر فك تشفير كلمة السر (مثلًا بعد نقل البيانات لجهاز آخر). */
        public boolean locked;
    }

    public static class Plan {
        /** يُحقن توقيع (مفتاح) في بناء Gradle. */
        public boolean active;
        /** يلزم تمرير init script (توقيع أو إعدادات release). */
        public boolean init;
        /** بناء بلا توقيع عمدًا (يُوقَّع لاحقًا). */
        public boolean unsigned;
        /** تعتيم متقدم (R8 Full Mode / دمج الأصناف / حذف Log) — فعّال فقط لنسخة release. */
        public boolean harden, hardenFull, hardenRepack, hardenNoLog;
        public String variant = "debug";
        public String task = "assembleDebug";
        public final List<String> env = new ArrayList<>();
        public String keyId = "", keyName = "", sha = "";
        public String note = "";
        public String error = null;
    }

    public static class ApkInfo {
        public String error = null;
        public String pkg = "", versionName = "", sha = "";
        public long versionCode;
        public int minSdk, targetSdk;
        public final List<String> perms = new ArrayList<>();
        public final List<String> dangerous = new ArrayList<>();
        public boolean installed, sameSigner;
        public String instVersion = "", instSha = "";
    }

    public static class Export { public File file; public int keys, locked; }

    public interface Kv { String get(String k); void put(String k, String v); }

    // ------------------------------------------------------------------ الإعدادات
    private static final Map<String, String> GDEF = new HashMap<>();
    private static final Map<String, String> PDEF = new HashMap<>();

    static {
        String[][] g = {
                {"auth_export", "1"}, {"auth_edit", "1"}, {"auth_delete", "1"}, {"auth_backup", "1"}, {"auth_sign", "0"}, {"auth_activate", "0"},
                {"auth_timeout", "60"}, {"auth_nolock", "allow"},
                {"sign_zipalign", "auto"}, {"sign_align", "4"},
                {"sign_v1", "auto"}, {"sign_v2", "auto"}, {"sign_v3", "auto"}, {"sign_v4", "auto"},
                {"sign_minsdk", ""}, {"sign_verify", "1"}, {"sign_suffix", "-signed"}, {"sign_keep", "10"},
                {"insp_perms", "dangerous"}, {"insp_autokey", "1"}, {"insp_mismatch", "warn"},
                {"rel_nokey", "block"}, {"rel_defkey", ""}, {"rel_override", "0"}, {"rel_minify", "project"}, {"rel_debuggable", "project"},
                {"hard_full", "0"}, {"hard_repack", "0"}, {"hard_nolog", "0"},
                {"bk_minpass", "8"}, {"bk_iter", "210000"}, {"bk_profiles", "1"}, {"bk_remind", "7"}, {"bk_dupmode", "skip"},
                {"last_backup", "0"}, {"dirty", "0"}
        };
        for (String[] p : g) GDEF.put(p[0], p[1]);
        String[][] pr = {
                {"key", ""}, {"signmode", "key"}, {"btype", "debug"}, {"apply_debug", "1"}, {"override", "inherit"},
                {"v1", "inherit"}, {"v2", "inherit"}, {"v3", "inherit"}, {"minify", "inherit"}, {"debuggable", "inherit"},
                {"hard_full", "inherit"}, {"hard_repack", "inherit"}, {"hard_nolog", "inherit"}
        };
        for (String[] p : pr) PDEF.put(p[0], p[1]);
    }

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("l2s_sign", Context.MODE_PRIVATE);
    }

    public static Kv globalKv(final Context c) {
        return new Kv() {
            @Override public String get(String k) {
                String d = GDEF.containsKey(k) ? GDEF.get(k) : "";
                try { return prefs(c).getString(k, d); } catch (Exception e) { return d; }
            }
            @Override public void put(String k, String v) { prefs(c).edit().putString(k, v).apply(); }
        };
    }

    private static String pkey(String project) { return "proj:" + project; }

    public static boolean hasProfile(Context c, String project) { return prefs(c).contains(pkey(project)); }

    public static void deleteProfile(Context c, String project) { prefs(c).edit().remove(pkey(project)).apply(); }

    public static void renameProfile(Context c, String from, String to) {
        String s = prefs(c).getString(pkey(from), null);
        if (s == null) return;
        prefs(c).edit().putString(pkey(to), s).remove(pkey(from)).apply();
    }

    public static Kv profileKv(final Context c, final String project) {
        return new Kv() {
            private JSONObject load() {
                try { return new JSONObject(prefs(c).getString(pkey(project), "{}")); } catch (Exception e) { return new JSONObject(); }
            }
            @Override public String get(String k) { return load().optString(k, PDEF.containsKey(k) ? PDEF.get(k) : ""); }
            @Override public void put(String k, String v) {
                try {
                    JSONObject j = load();
                    j.put(k, v);
                    prefs(c).edit().putString(pkey(project), j.toString()).apply();
                } catch (Exception ignored) { }
            }
        };
    }

    public static boolean on(Kv kv, String k) { return "1".equals(kv.get(k)); }

    /** قيمة ملف المشروع، وإن كانت «inherit» فقيمة الإعداد العام المقابل. */
    private static String eff(Kv pk, Kv g, String pkey, String gkey) {
        String v = pk.get(pkey);
        return (v == null || v.isEmpty() || "inherit".equals(v)) ? g.get(gkey) : v;
    }

    // ------------------------------------------------------------------ المسارات والخزنة
    public static File root(Context c) {
        File d = new File(c.getFilesDir(), "signing");
        d.mkdirs();
        return d;
    }

    public static File keysDir(Context c) {
        File d = new File(root(c), "keys");
        d.mkdirs();
        return d;
    }

    public static boolean validId(String id) { return id != null && ID_OK.matcher(id).matches(); }

    public static List<Key> listKeys(Context c) {
        List<Key> out = new ArrayList<>();
        File[] ds = keysDir(c).listFiles();
        if (ds == null) return out;
        java.util.Arrays.sort(ds, (a, b) -> a.getName().compareTo(b.getName()));
        for (File d : ds) { Key k = readKey(c, d); if (k != null) out.add(k); }
        return out;
    }

    public static Key getKey(Context c, String id) {
        if (!validId(id)) return null;
        File d = new File(keysDir(c), id);
        return d.isDirectory() ? readKey(c, d) : null;
    }

    private static Key readKey(Context c, File dir) {
        File m = new File(dir, "meta.json");
        if (!m.isFile()) return null;
        try {
            JSONObject j = new JSONObject(new String(readAll(m), "UTF-8"));
            Key k = new Key();
            k.id = dir.getName();
            k.name = j.optString("name", k.id);
            k.alias = j.optString("alias");
            k.sha = j.optString("sha");
            k.created = j.optString("created");
            String pw = dec(j.optString("pw")), kpw = dec(j.optString("kpw"));
            k.locked = pw == null || kpw == null;
            k.pw = pw == null ? "" : pw;
            k.kpw = kpw == null ? "" : kpw;
            return k;
        } catch (Exception e) {
            return null;
        }
    }

    public static void writeKey(Context c, Key k) throws Exception {
        File dir = new File(keysDir(c), k.id);
        dir.mkdirs();
        JSONObject j = new JSONObject();
        j.put("name", k.name);
        j.put("alias", k.alias);
        j.put("sha", k.sha);
        j.put("created", k.created);
        j.put("pw", enc(k.pw));
        j.put("kpw", k.kpw.isEmpty() ? "" : enc(k.kpw));
        Compat.write(new File(dir, "meta.json"), j.toString().getBytes("UTF-8"));
        prefs(c).edit().putString("dirty", "1").apply();
    }

    public static void deleteKey(Context c, String id) {
        if (!validId(id)) return;
        deleteRec(new File(keysDir(c), id));
        SharedPreferences.Editor e = prefs(c).edit().putString("dirty", "1");
        if (id.equals(prefs(c).getString("active", ""))) e.remove("active");
        if (id.equals(prefs(c).getString("rel_defkey", ""))) e.remove("rel_defkey");
        e.apply();
    }

    // ------------------------------------------------------------------ تشفير كلمات السر (Android Keystore)
    private static SecretKey wrapKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(WRAP)) {
            KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            g.init(new KeyGenParameterSpec.Builder(WRAP, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build());
            g.generateKey();
        }
        return (SecretKey) ks.getKey(WRAP, null);
    }

    public static String enc(String s) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, wrapKey());
        byte[] ct = c.doFinal(s.getBytes("UTF-8"));
        return Base64.encodeToString(c.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP);
    }

    /** "" للفارغ، وnull إن تعذّر الفك. */
    public static String dec(String s) {
        if (s == null || s.isEmpty()) return "";
        try {
            String[] p = s.split(":");
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, wrapKey(), new GCMParameterSpec(128, Base64.decode(p[0], Base64.NO_WRAP)));
            return new String(c.doFinal(Base64.decode(p[1], Base64.NO_WRAP)), "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ خطة التوقيع وقت البناء
    private static void addEnv(Plan p, String name, String val) {
        if (val != null && !val.isEmpty()) p.env.add(name + "=" + val);
    }

    private static String triEnv(String v) { return "on".equals(v) ? "on" : ("off".equals(v) ? "off" : ""); }

    /** "inherit" يرجع الإعداد العام (مفتاح تبديل 1/0)، و"on"/"off" يحسمان مباشرة. */
    private static boolean triBool(Kv pk, Kv g, String key) {
        String v = pk.get(key);
        if ("on".equals(v)) return true;
        if ("off".equals(v)) return false;
        return on(g, key);
    }

    /** يحدد كيف يُبنى المشروع: النوع، والمفتاح، وإعدادات release. لا تُكتب كلمات السر في أي ملف؛ تُمرَّر كمتغيرات بيئة. */
    /** مفتاح لبناء واحد فقط («بناء بمفتاح آخر»): يُستهلك عند أول خطة، وصالح لدقيقتين فقط. */
    public static volatile String oneShotKey = "";
    public static volatile long oneShotUntil = 0;

    public static Plan plan(Context c, String project) {
        String one = (System.currentTimeMillis() < oneShotUntil && oneShotKey != null) ? oneShotKey.trim() : "";
        oneShotKey = ""; oneShotUntil = 0;
        Plan p = new Plan();
        Kv g = globalKv(c), pk = profileKv(c, project);
        boolean rel = "release".equals(pk.get("btype"));
        String sm = pk.get("signmode");
        boolean unsigned = "unsigned".equals(sm), std = "stddebug".equals(sm);
        p.variant = rel ? "release" : "debug";
        p.task = rel ? "assembleRelease" : "assembleDebug";

        String own = pk.get("key").trim();
        if (!one.isEmpty()) { own = one; unsigned = false; std = false; }
        String keyId = own;
        if (std) keyId = "debug-original";
        else if (rel && keyId.isEmpty() && !unsigned) {
            keyId = g.get("rel_defkey").trim();
            if (keyId.isEmpty()) keyId = prefs(c).getString("active", "");
        }
        boolean debugApply = !rel && !unsigned && (std || (!own.isEmpty() && (!one.isEmpty() || "1".equals(pk.get("apply_debug")))));

        Key k = null;
        if (!unsigned && !keyId.isEmpty() && (rel || debugApply)) {
            k = getKey(c, keyId);
            if (k == null) {
                p.error = std
                        ? "مفتاح debug القياسي غير موجود في الخزنة (يُضاف تلقائيًا عند فتح «مركز التوقيع» أول مرة).\n"
                        : "المفتاح المعيَّن لمشروع «" + project + "» غير موجود في الخزنة.\nافتح «مركز التوقيع» ← توقيع المشاريع وعيّن مفتاحًا آخر.\n";
                return p;
            }
            if (k.locked) {
                p.error = "كلمة سر المفتاح «" + k.name + "» غير متاحة (تعذّر فك التشفير).\nأعد استيراد المفتاح من «مركز التوقيع».\n";
                return p;
            }
        }
        if (unsigned) {
            p.unsigned = true;
            addEnv(p, "L2S_SIGN_NONE", rel ? "release" : "debug");
            p.note += "🔓 بناء بلا توقيع: الناتج لا يُثبَّت قبل أن توقّعه من «مركز التوقيع» ← توقيع تطبيق.\n";
        } else if (rel && k == null) {
            if ("block".equals(g.get("rel_nokey"))) {
                p.error = "بناء release يحتاج مفتاحًا ولم يُعيَّن أي مفتاح.\nعيّن مفتاحًا للمشروع أو مفتاح release الافتراضي من «مركز التوقيع»،\nأو اختر «بدون توقيع» في إعدادات المشروع، أو غيّر الإعداد «عند غياب المفتاح».\n";
                return p;
            }
            p.unsigned = true;
            p.note += "⚠ بناء release بلا مفتاح: الناتج غير موقَّع ولن يُثبَّت على الجهاز.\n";
        }

        if (k != null) {
            p.active = true;
            p.keyId = k.id; p.keyName = k.name; p.sha = k.sha;
            addEnv(p, "L2S_SIGN_STORE", "/sign/keys/" + k.id + "/key.ks");
            addEnv(p, "L2S_SIGN_STOREPW", k.pw);
            addEnv(p, "L2S_SIGN_ALIAS", k.alias);
            addEnv(p, "L2S_SIGN_KEYPW", k.kpw.isEmpty() ? k.pw : k.kpw);
            addEnv(p, "L2S_SIGN_TARGET", rel ? "release" : "debug");
            String ov = pk.get("override");
            boolean override = !one.isEmpty() || "yes".equals(ov) || (!"no".equals(ov) && "1".equals(g.get("rel_override")));
            addEnv(p, "L2S_SIGN_OVERRIDE", override ? "1" : "0");
            addEnv(p, "L2S_SIGN_V1", triEnv(eff(pk, g, "v1", "sign_v1")));
            addEnv(p, "L2S_SIGN_V2", triEnv(eff(pk, g, "v2", "sign_v2")));
            addEnv(p, "L2S_SIGN_V3", triEnv(eff(pk, g, "v3", "sign_v3")));
            addEnv(p, "L2S_SIGN_V4", triEnv(g.get("sign_v4")));
            p.note += "🔏 التوقيع: المفتاح «" + k.name + "» • النوع " + p.variant
                    + (rel ? " • استبدال توقيع المشروع: " + (override ? "نعم" : "لا (يُحترم إن وُجد)") : "") + "\n";
        }
        if (rel) {
            String mf = eff(pk, g, "minify", "rel_minify");
            if ("off".equals(mf) || "on".equals(mf)) addEnv(p, "L2S_REL_MINIFY", mf);
            String dg = eff(pk, g, "debuggable", "rel_debuggable");
            if ("off".equals(dg) || "on".equals(dg)) addEnv(p, "L2S_REL_DEBUGGABLE", dg);
            if ("off".equals(mf)) p.note += "ℹ تقليص الكود (R8) مُعطَّل لنسخة release.\n";
            p.hardenFull = triBool(pk, g, "hard_full");
            p.hardenRepack = triBool(pk, g, "hard_repack");
            p.hardenNoLog = triBool(pk, g, "hard_nolog");
            p.harden = p.hardenFull || p.hardenRepack || p.hardenNoLog;
            if (p.harden) {
                addEnv(p, "L2S_HARDEN", "1");
                addEnv(p, "L2S_REL_MINIFY", "on");               // التعتيم يحتاج تقليص الكود مفعّلًا
                List<String> parts = new ArrayList<>();
                if (p.hardenFull) parts.add("R8 Full Mode");
                if (p.hardenRepack) parts.add("دمج الأصناف");
                if (p.hardenNoLog) parts.add("حذف Log");
                p.note += "🛡 تعتيم متقدم: " + String.join(" • ", parts) + "\n⚠ اختبر نسخة release الموقَّعة؛ هذا المستوى قد يكسر كودًا يعتمد على الانعكاس (reflection) أو مكتبات كـGson/Room بلا قواعد Keep مناسبة.\n";
            }
        } else if (triBool(pk, g, "hard_full") || triBool(pk, g, "hard_repack") || triBool(pk, g, "hard_nolog")) {
            p.note += "ℹ التعتيم المتقدم مفعّل في الإعدادات لكنه يُطبَّق فقط على نسخة release؛ غيّر نوع البناء لتفعيله.\n";
        }
        p.init = !p.env.isEmpty();
        return p;
    }

    /** init script يضيف signingConfig ويضبط release. نصّه إنكليزي بلا رموز غير ASCII ليُقرأ بأي ترميز. */
    public static void writeInit(File base) {
        String[] g = {
                "// L2S generated: signing is injected from environment variables (no secrets are written to disk).",
                "allprojects { p ->",
                "    p.afterEvaluate { proj ->",
                "        try {",
                "            if (!proj.plugins.hasPlugin('com.android.application')) return",
                "            def ext = proj.extensions.findByName('android')",
                "            if (ext == null) return",
                "            def env = System.getenv()",
                "            def tri = { String v -> v == 'on' ? Boolean.TRUE : (v == 'off' ? Boolean.FALSE : null) }",
                "            def store = env['L2S_SIGN_STORE']",
                "            def target = env['L2S_SIGN_TARGET'] ?: ''",
                "            def sc = null",
                "            if (store) {",
                "                sc = ext.signingConfigs.findByName('l2sSign')",
                "                if (sc == null) sc = ext.signingConfigs.create('l2sSign')",
                "                sc.storeFile = new File(store)",
                "                sc.storePassword = env['L2S_SIGN_STOREPW']",
                "                sc.keyAlias = env['L2S_SIGN_ALIAS']",
                "                sc.keyPassword = env['L2S_SIGN_KEYPW']",
                "                ['V1', 'V2', 'V3', 'V4'].each { n ->",
                "                    def b = tri(env['L2S_SIGN_' + n])",
                "                    if (b != null) {",
                "                        try { sc.\"enable${n}Signing\" = b } catch (Throwable t) { proj.logger.lifecycle('L2S: cannot set ' + n + ': ' + t.message) }",
                "                    }",
                "                }",
                "            }",
                "            if (sc != null && target == 'debug') {",
                "                def dbt = ext.buildTypes.findByName('debug')",
                "                if (dbt != null) dbt.signingConfig = sc",
                "            }",
                "            def none = env['L2S_SIGN_NONE']",
                "            if (none == 'debug') {",
                "                def dbt0 = ext.buildTypes.findByName('debug')",
                "                if (dbt0 != null) dbt0.signingConfig = null",
                "            }",
                "            def rbt = ext.buildTypes.findByName('release')",
                "            if (rbt != null && none == 'release') rbt.signingConfig = null",
                "            if (rbt != null) {",
                "                if (sc != null && target == 'release') {",
                "                    if (env['L2S_SIGN_OVERRIDE'] == '1' || rbt.signingConfig == null) rbt.signingConfig = sc",
                "                }",
                "                def mf = env['L2S_REL_MINIFY']",
                "                if (mf == 'off') {",
                "                    try { rbt.minifyEnabled = false } catch (Throwable t) { }",
                "                    try { rbt.shrinkResources = false } catch (Throwable t) { }",
                "                } else if (mf == 'on') {",
                "                    try { rbt.minifyEnabled = true } catch (Throwable t) { }",
                "                }",
                "                def dg = env['L2S_REL_DEBUGGABLE']",
                "                if (dg == 'off') { try { rbt.debuggable = false } catch (Throwable t) { } }",
                "                else if (dg == 'on') { try { rbt.debuggable = true } catch (Throwable t) { } }",
                "                if (env['L2S_HARDEN'] == '1') {",
                "                    def hf = new File('/opt/l2s/l2s-harden.pro')",
                "                    if (hf.exists()) { try { rbt.proguardFile(hf) } catch (Throwable t) { proj.logger.lifecycle('L2S: cannot add harden rules: ' + t.message) } }",
                "                }",
                "            }",
                "        } catch (Throwable t) {",
                "            proj.logger.lifecycle('L2S signing init failed: ' + t)",
                "        }",
                "    }",
                "}",
                ""
        };
        try {
            StringBuilder sb = new StringBuilder();
            for (String l : g) sb.append(l).append('\n');
            Compat.write(new File(base, "l2s-sign.init.gradle"), sb.toString().getBytes("UTF-8"));
        } catch (Exception ignored) { }
    }

    /** يكتب قواعد R8 الإضافية للتعتيم المتقدم (دمج الأصناف وحذف Log) حسب خطة البناء. لا تُكتب إن لم يكن التعتيم مفعّلًا. */
    public static void writeHardenRules(File base, Plan p) {
        File f = new File(base, "l2s-harden.pro");
        if (p == null || !p.harden) { f.delete(); return; }
        StringBuilder sb = new StringBuilder("# L2S generated hardening rules\n");
        if (p.hardenRepack) sb.append("-repackageclasses ''\n");
        if (p.hardenNoLog) {
            sb.append("-assumenosideeffects class android.util.Log {\n")
              .append("    public static *** v(...);\n public static *** d(...);\n public static *** i(...);\n")
              .append("    public static *** w(...);\n public static *** e(...);\n public static *** wtf(...);\n}\n");
        }
        try { Compat.write(f, sb.toString().getBytes("UTF-8")); } catch (Exception ignored) { }
    }

    /** سطر يُضاف لتقرير البناء: بصمة توقيع الناتج ومطابقتها للمفتاح المختار. */
    public static String certLine(Context c, File apk, Plan p) {
        ApkInfo a = inspectApk(c, apk);
        if (a.error != null) return "⚠ تعذّر فحص توقيع الناتج: " + a.error + "\n";
        if (a.sha.isEmpty()) {
            return p != null && p.unsigned
                    ? "🔓 الناتج «" + apk.getName() + "» غير موقَّع عمدًا؛ وقّعه لاحقًا من «مركز التوقيع» ← توقيع تطبيق.\n"
                    : "⚠ الناتج «" + apk.getName() + "» بلا توقيع.\n";
        }
        StringBuilder s = new StringBuilder("🔏 توقيع الناتج SHA-256: " + a.sha + "\n");
        if (p != null && !p.sha.isEmpty()) {
            s.append(normSha(a.sha).equals(normSha(p.sha))
                    ? "✔ يطابق المفتاح «" + p.keyName + "».\n"
                    : "⚠ لا يطابق بصمة المفتاح «" + p.keyName + "»؛ ربما فرض المشروع توقيعه الخاص (فعّل «استبدال توقيع المشروع»).\n");
        }
        return s.toString();
    }

    // ------------------------------------------------------------------ فحص APK
    public static String normSha(String s) { return s == null ? "" : s.replace(":", "").trim().toUpperCase(Locale.US); }

    private static String shaHex(byte[] der) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(der);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < d.length; i++) {
            if (i > 0) sb.append(':');
            sb.append(String.format(Locale.US, "%02X", d[i] & 0xff));
        }
        return sb.toString();
    }

    private static String signerSha(PackageInfo pi) {
        try {
            Signature[] sigs = null;
            if (Build.VERSION.SDK_INT >= 28) {
                SigningInfo si = pi.signingInfo;
                if (si != null) sigs = si.getApkContentsSigners();
            }
            if ((sigs == null || sigs.length == 0) && pi.signatures != null) sigs = pi.signatures;
            if (sigs == null || sigs.length == 0) return "";
            return shaHex(sigs[0].toByteArray());
        } catch (Throwable t) {
            return "";
        }
    }

    private static boolean isDangerous(PackageManager pm, String perm) {
        try {
            PermissionInfo pi = pm.getPermissionInfo(perm, 0);
            int lvl = Build.VERSION.SDK_INT >= 28 ? pi.getProtection() : (pi.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE);
            return lvl == PermissionInfo.PROTECTION_DANGEROUS;
        } catch (Throwable t) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    public static ApkInfo inspectApk(Context c, File apk) {
        ApkInfo a = new ApkInfo();
        PackageManager pm = c.getPackageManager();
        int sigFlag = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo pi = null;
        try { pi = pm.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_PERMISSIONS | sigFlag); } catch (Throwable ignored) { }
        if (pi == null) {
            try { pi = pm.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_PERMISSIONS); } catch (Throwable ignored) { }
        }
        if (pi == null) { a.error = "تعذّر قراءة الحزمة (ملف تالف أو غير مدعوم)"; return a; }
        a.pkg = pi.packageName == null ? "" : pi.packageName;
        a.versionName = pi.versionName == null ? "" : pi.versionName;
        a.versionCode = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        if (pi.applicationInfo != null) {
            a.targetSdk = pi.applicationInfo.targetSdkVersion;
            if (Build.VERSION.SDK_INT >= 24) a.minSdk = pi.applicationInfo.minSdkVersion;
        }
        if (pi.requestedPermissions != null) {
            for (String perm : pi.requestedPermissions) {
                a.perms.add(perm);
                if (isDangerous(pm, perm)) a.dangerous.add(perm);
            }
        }
        a.sha = signerSha(pi);
        if (!a.pkg.isEmpty()) {
            try {
                PackageInfo inst = pm.getPackageInfo(a.pkg, sigFlag);
                a.installed = true;
                a.instVersion = (inst.versionName == null ? "" : inst.versionName);
                a.instSha = signerSha(inst);
                a.sameSigner = !a.sha.isEmpty() && normSha(a.sha).equals(normSha(a.instSha));
            } catch (Throwable ignored) { }
        }
        return a;
    }

    // ------------------------------------------------------------------ نسخة احتياطية مشفّرة بكلمة سر
    private static byte[] kdf(String pass, byte[] salt, int iter, int kdfId) throws Exception {
        SecretKeyFactory f = SecretKeyFactory.getInstance(kdfId == 1 ? "PBKDF2WithHmacSHA256" : "PBKDF2WithHmacSHA1");
        return f.generateSecret(new PBEKeySpec(pass.toCharArray(), salt, iter, 256)).getEncoded();
    }

    public static Export exportBundle(Context c, String pass, int iter, boolean withProfiles, File outDir) throws Exception {
        Export ex = new Export();
        JSONObject j = new JSONObject();
        j.put("v", 1);
        j.put("created", now());
        JSONArray arr = new JSONArray();
        for (Key k : listKeys(c)) {
            File ks = new File(new File(keysDir(c), k.id), "key.ks");
            if (k.locked || !ks.isFile()) { ex.locked++; continue; }
            JSONObject o = new JSONObject();
            o.put("id", k.id); o.put("name", k.name); o.put("alias", k.alias); o.put("sha", k.sha); o.put("created", k.created);
            o.put("pw", k.pw); o.put("kpw", k.kpw);
            o.put("ks", Base64.encodeToString(readAll(ks), Base64.NO_WRAP));
            arr.put(o);
            ex.keys++;
        }
        if (ex.keys == 0) throw new IllegalStateException("لا توجد مفاتيح قابلة للنسخ" + (ex.locked > 0 ? " (كلمات سر " + ex.locked + " مفتاح غير متاحة)" : ""));
        j.put("keys", arr);
        if (withProfiles) {
            JSONObject pr = new JSONObject();
            for (Map.Entry<String, ?> e : prefs(c).getAll().entrySet()) {
                if (e.getKey().startsWith("proj:") && e.getValue() instanceof String) {
                    try { pr.put(e.getKey().substring(5), new JSONObject((String) e.getValue())); } catch (Exception ignored) { }
                }
            }
            j.put("profiles", pr);
        }
        int kdfId = Build.VERSION.SDK_INT >= 26 ? 1 : 2;
        byte[] salt = new byte[16], iv = new byte[12];
        SecureRandom sr = new SecureRandom();
        sr.nextBytes(salt); sr.nextBytes(iv);
        Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
        ci.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(kdf(pass, salt, iter, kdfId), "AES"), new GCMParameterSpec(128, iv));
        byte[] ct = ci.doFinal(j.toString().getBytes("UTF-8"));
        outDir.mkdirs();
        File f = new File(outDir, "l2s-keys-" + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date()) + ".l2skeys");
        try (DataOutputStream o = new DataOutputStream(new FileOutputStream(f))) {
            o.write(MAGIC); o.writeByte(kdfId); o.writeInt(iter); o.write(salt); o.write(iv); o.write(ct);
        }
        prefs(c).edit().putString("dirty", "0").putString("last_backup", String.valueOf(System.currentTimeMillis())).apply();
        ex.file = f;
        return ex;
    }

    /** mode: skip | overwrite | copy. يعيد ملخصًا مقروءًا. */
    public static String importBundle(Context c, InputStream in, String pass, String mode, boolean withProfiles) throws Exception {
        byte[] all = readAll(in, 8 * 1024 * 1024);
        if (all.length < 8 + 1 + 4 + 16 + 12 + 16) throw new IllegalArgumentException("الملف ليس نسخة مفاتيح صالحة");
        DataInputStream d = new DataInputStream(new ByteArrayInputStream(all));
        byte[] mg = new byte[8];
        d.readFully(mg);
        if (!java.util.Arrays.equals(mg, MAGIC)) throw new IllegalArgumentException("الملف ليس نسخة مفاتيح من L2S Builder");
        int kdfId = d.readUnsignedByte();
        int iter = d.readInt();
        if ((kdfId != 1 && kdfId != 2) || iter < 1000 || iter > 5000000) throw new IllegalArgumentException("ترويسة النسخة غير صالحة");
        byte[] salt = new byte[16], iv = new byte[12];
        d.readFully(salt); d.readFully(iv);
        byte[] ct = new byte[all.length - 8 - 1 - 4 - 16 - 12];
        d.readFully(ct);
        byte[] plain;
        try {
            Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
            ci.init(Cipher.DECRYPT_MODE, new SecretKeySpec(kdf(pass, salt, iter, kdfId), "AES"), new GCMParameterSpec(128, iv));
            plain = ci.doFinal(ct);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalArgumentException("كلمة سر النسخة خاطئة أو الملف تالف");
        }
        JSONObject j = new JSONObject(new String(plain, "UTF-8"));
        JSONArray arr = j.optJSONArray("keys");
        if (arr == null) throw new IllegalArgumentException("النسخة لا تحوي مفاتيح");
        List<Key> cur = listKeys(c);
        int added = 0, over = 0, skipped = 0, bad = 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            byte[] ks = Base64.decode(o.optString("ks"), Base64.NO_WRAP);
            if (ks.length == 0 || ks.length > 5 * 1024 * 1024) { bad++; continue; }
            String sha = o.optString("sha");
            String id = o.optString("id");
            String same = null;                       // مفتاح موجود بنفس المعرّف أو بنفس البصمة
            for (Key e : cur) {
                if (e.id.equals(id) || (!sha.isEmpty() && normSha(e.sha).equals(normSha(sha)))) { same = e.id; break; }
            }
            String target;
            if (same == null) target = validId(id) ? id : "k" + System.currentTimeMillis() + "_" + i;
            else if ("overwrite".equals(mode)) target = same;
            else if ("copy".equals(mode)) target = "k" + System.currentTimeMillis() + "_" + i;
            else { skipped++; continue; }
            File dir = new File(keysDir(c), target);
            dir.mkdirs();
            Compat.write(new File(dir, "key.ks"), ks);
            Key k = new Key();
            k.id = target; k.name = o.optString("name", target) + ("copy".equals(mode) && same != null ? " (نسخة)" : "");
            k.alias = o.optString("alias"); k.sha = sha; k.created = o.optString("created");
            k.pw = o.optString("pw"); k.kpw = o.optString("kpw");
            writeKey(c, k);
            if (same != null && "overwrite".equals(mode)) over++; else added++;
        }
        int profs = 0;
        JSONObject pr = j.optJSONObject("profiles");
        if (withProfiles && pr != null) {
            java.util.Iterator<String> it = pr.keys();
            while (it.hasNext()) {
                String name = it.next();
                if (name.isEmpty() || name.contains("/") || name.contains("\\")) continue;
                if (hasProfile(c, name) && !"overwrite".equals(mode)) continue;
                JSONObject one = pr.optJSONObject(name);
                if (one == null) continue;
                prefs(c).edit().putString(pkey(name), one.toString()).apply();
                profs++;
            }
        }
        return "أُضيف " + added + " • استُبدل " + over + " • تُخطّي " + skipped + (bad > 0 ? " • تالف " + bad : "")
                + (profs > 0 ? " • ملفات مشاريع " + profs : "");
    }

    // ------------------------------------------------------------------ حفظ خارج التطبيق (ينجو من حذفه)
    public static String saveToDownloads(Context c, File src, String mime) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, src.getName());
                v.put(MediaStore.Downloads.MIME_TYPE, mime);
                v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri u = c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (u == null) return "تعذّر الحفظ في Downloads.";
                try (OutputStream os = c.getContentResolver().openOutputStream(u); InputStream is = new FileInputStream(src)) {
                    pipe(is, os);
                }
                return "حُفظ في Downloads/" + src.getName() + " ✔";
            }
            File dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            dl.mkdirs();
            File dst = new File(dl, src.getName());
            try (OutputStream os = new FileOutputStream(dst); InputStream is = new FileInputStream(src)) { pipe(is, os); }
            return "حُفظ في " + dst.getAbsolutePath() + " ✔";
        } catch (Exception e) {
            return "تعذّر الحفظ في Downloads: " + e;
        }
    }

    /** يعيد "" إن لم يكن مجلد النسخ الاحتياطي الدائم محددًا (يُعدّ من «النسخ الاحتياطي والحماية»). */
    public static String saveToBackupFolder(Context c, File src, String mime) {
        String s = c.getSharedPreferences("l2s_ui", Context.MODE_PRIVATE).getString("backup_folder_uri", null);
        if (s == null || s.isEmpty()) return "";
        try {
            Uri tree = Uri.parse(s);
            String docId = DocumentsContract.getTreeDocumentId(tree);
            Uri dirDoc = DocumentsContract.buildDocumentUriUsingTree(tree, docId);
            Uri nf = DocumentsContract.createDocument(c.getContentResolver(), dirDoc, mime, src.getName());
            if (nf == null) return "تعذّر إنشاء ملف بمجلد النسخ الاحتياطي.";
            try (OutputStream os = c.getContentResolver().openOutputStream(nf); InputStream is = new FileInputStream(src)) { pipe(is, os); }
            return "نُسخ أيضًا إلى مجلد النسخ الاحتياطي الدائم ✔";
        } catch (Exception e) {
            return "تعذّر النسخ إلى مجلد النسخ الاحتياطي: " + e;
        }
    }

    /** هل الخزنة بحاجة لتذكير بالنسخ الاحتياطي؟ يعيد "" إن لا. */
    public static String backupReminder(Context c) {
        Kv g = globalKv(c);
        int days;
        try { days = Integer.parseInt(g.get("bk_remind")); } catch (Exception e) { days = 0; }
        if (days <= 0 || listKeys(c).isEmpty()) return "";
        long last;
        try { last = Long.parseLong(g.get("last_backup")); } catch (Exception e) { last = 0; }
        if (last == 0) return "⚠ لم تُنسخ الخزنة احتياطيًا بعد. حذف التطبيق يفقدك مفاتيحك.";
        boolean dirty = "1".equals(g.get("dirty"));
        long age = (System.currentTimeMillis() - last) / 86400000L;
        if (dirty && age >= days) return "⚠ تغيّرت الخزنة منذ آخر نسخة احتياطية قبل " + age + " يومًا.";
        return "";
    }

    // ------------------------------------------------------------------ أدوات
    public static String now() { return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date()); }

    static void pipe(InputStream is, OutputStream os) throws IOException {
        byte[] b = new byte[65536];
        int n;
        while ((n = is.read(b)) > 0) os.write(b, 0, n);
    }

    static byte[] readAll(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) { return readAll(in, Integer.MAX_VALUE); }
    }

    static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) {
            bo.write(b, 0, n);
            if (bo.size() > max) throw new IOException("الملف كبير جدًا");
        }
        return bo.toByteArray();
    }

    static void deleteRec(File f) {
        File[] ch = f.listFiles();
        if (ch != null) for (File c : ch) deleteRec(c);
        f.delete();
    }
}
