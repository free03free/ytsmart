package com.example.l2sbuilder;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * يفحص تطبيقًا (APK) أو مشروعًا ويكتب تقريرًا واحدًا: استخدام التشفير في الكود، المكتبات،
 * التعتيم (R8/ProGuard)، التغليف (packers)، الملفات عالية العشوائية، المكتبات الأصلية، ومخططات التوقيع.
 * الفحص ساكن: لا يشغّل التطبيق، وقد يخطئ (مؤشرات لا أدلة قاطعة).
 */
public final class CryptoScanner {
    private CryptoScanner() { }

    private static final int MAX_DEX = 64 * 1024 * 1024;
    private static final int MAX_SO = 8 * 1024 * 1024;

    // {المجموعة، الاسم، النمط (c=يحوي، e=يساوي، s=يبدأ)، النص}
    private static final String[][] SPEC = {
            {"api", "Cipher (تشفير/فك تشفير)", "c", "Ljavax/crypto/Cipher;"},
            {"api", "AES", "s", "AES/"}, {"api", "AES", "e", "AES"},
            {"api", "RSA", "s", "RSA/"}, {"api", "RSA", "e", "RSA"},
            {"api", "ChaCha20", "c", "ChaCha20"},
            {"api", "منحنيات بيضاوية (EC/ECDSA)", "e", "EC"}, {"api", "منحنيات بيضاوية (EC/ECDSA)", "e", "ECDSA"},
            {"api", "التجزئة MessageDigest", "c", "Ljava/security/MessageDigest;"},
            {"api", "HMAC", "c", "HmacSHA"},
            {"api", "اشتقاق مفاتيح (PBKDF2)", "c", "PBKDF2"},
            {"api", "SecureRandom", "c", "Ljava/security/SecureRandom;"},
            {"lib", "Android Keystore", "e", "AndroidKeyStore"},
            {"lib", "Jetpack Security (ملفات/تفضيلات مشفّرة)", "c", "Landroidx/security/crypto/"},
            {"lib", "SQLCipher (قاعدة بيانات مشفّرة)", "c", "Lnet/sqlcipher/"},
            {"lib", "Google Tink", "c", "Lcom/google/crypto/tink/"},
            {"lib", "BouncyCastle", "c", "Lorg/bouncycastle/"},
            {"lib", "Conscrypt", "c", "Lorg/conscrypt/"},
            {"lib", "libsodium", "c", "Lcom/goterl/"},
            {"net", "SSL/TLS (SSLContext)", "c", "Ljavax/net/ssl/SSLContext;"},
            {"net", "تثبيت الشهادات (Certificate Pinning)", "c", "CertificatePinner"},
            {"weak", "DES / 3DES", "s", "DES/"}, {"weak", "DES / 3DES", "e", "DES"}, {"weak", "DES / 3DES", "e", "DESede"},
            {"weak", "MD5", "e", "MD5"},
            {"weak", "SHA-1", "e", "SHA-1"}, {"weak", "SHA-1", "e", "SHA1"},
            {"weak", "وضع ECB", "c", "/ECB/"},
            {"weak", "RC4", "e", "RC4"}, {"weak", "RC4", "e", "ARCFOUR"},
            {"weak", "تعطيل التحقق من اسم المضيف", "c", "ALLOW_ALL_HOSTNAME_VERIFIER"},
            {"weak", "تعطيل التحقق من اسم المضيف", "c", "AllowAllHostnameVerifier"},
            {"weak", "TrustManager مخصّص (قد يقبل كل الشهادات)", "c", "Ljavax/net/ssl/X509TrustManager;"},
    };

    private static final String[] PACKER_CLASSES = {
            "Lcom/secneo/", "Lcom/qihoo/util/StubApp", "Lcom/tencent/StubShell", "Lcom/baidu/protect/",
            "Lcom/ali/mobisec/", "Lcom/ijiami/", "Lcom/bangcle/", "Lcom/payegis/", "Lcom/guardsquare/",
            "Lcom/arxan/", "Lcom/nagain/", "Lcom/netease/nis/"
    };
    private static final String[] PACKER_LIBS = {
            "libjiagu", "libdexhelper", "libsecexe", "libsecmain", "libprotectclass", "libshella", "libshellx",
            "libddog", "libnesec", "libmobisec", "libapktoolplus", "libxloader", "libegis", "libbaiduprotect",
            "libuusafe", "libexecmain", "libkwscmm", "libtup", "libreincloak"
    };
    private static final String[] NATIVE_CRYPTO_LIBS = {
            "libssl", "libcrypto", "libsodium", "libwolfssl", "libmbedtls", "libmbedcrypto", "libgcrypt", "libtomcrypt", "libboringssl"
    };
    private static final String[] SO_NEEDLES = {"EVP_", "AES_", "OPENSSL", "mbedtls_", "sodium_", "wolfSSL", "chacha"};

    // ------------------------------------------------------------------ APK
    public static String scanApk(Context c, File apk, String label) {
        StringBuilder sb = new StringBuilder();
        sb.append("تقرير التشفير: ").append(label).append("\n");
        sb.append("الحجم: ").append(apk.length() / 1024).append(" KB\n");
        PackageManager pm = c.getPackageManager();
        try {
            PackageInfo pi = pm.getPackageArchiveInfo(apk.getAbsolutePath(), 0);
            if (pi != null && pi.applicationInfo != null) {
                ApplicationInfo ai = pi.applicationInfo;
                sb.append("الحزمة: ").append(pi.packageName).append(" • SDK المستهدف ").append(ai.targetSdkVersion).append("\n");
                sb.append("\n— إعدادات التطبيق —\n");
                sb.append(flag((ai.flags & ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC) != 0, "يسمح بحركة HTTP غير مشفّرة (cleartext)", true));
                sb.append(flag((ai.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0, "قابل للتنقيح (debuggable)", true));
                sb.append(flag((ai.flags & ApplicationInfo.FLAG_ALLOW_BACKUP) != 0, "يسمح بالنسخ الاحتياطي لبياناته (allowBackup)", true));
            }
        } catch (Throwable ignored) { }

        ZipFile z = null;
        try {
            z = new ZipFile(apk);
            List<String> names = new ArrayList<>();
            List<String> dexNames = new ArrayList<>();
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                names.add(e.getName());
                if (e.getName().matches("classes\\d*\\.dex")) dexNames.add(e.getName());
            }
            sb.append(nsc(names));

            // ---- نصوص dex
            List<String> strings = new ArrayList<>();
            int classDefs = 0;
            for (String dn : dexNames) {
                byte[] d = readEntry(z, z.getEntry(dn), MAX_DEX);
                if (d == null) { sb.append("⚠ تعذّر قراءة ").append(dn).append(" (كبير جدًا)\n"); continue; }
                classDefs += le32(d, 0x60);
                strings.addAll(dexStrings(d));
            }
            sb.append("\nمصنّفات dex: ").append(dexNames.size()).append(" • الفئات: ").append(classDefs)
                    .append(" • النصوص المفحوصة: ").append(strings.size()).append("\n");

            Map<String, Integer> found = new LinkedHashMap<>();
            for (String[] sp : SPEC) { if (!found.containsKey(sp[0] + "|" + sp[1])) found.put(sp[0] + "|" + sp[1], 0); }
            for (String s : strings) {
                for (String[] sp : SPEC) {
                    if (match(s, sp[2], sp[3])) { String k = sp[0] + "|" + sp[1]; found.put(k, found.get(k) + 1); }
                }
            }
            appendGroup(sb, "— استخدام التشفير في الكود —", "api", found);
            appendGroup(sb, "— مكتبات وتخزين مشفّر —", "lib", found);
            appendGroup(sb, "— الشبكة —", "net", found);
            sb.append(httpCount(strings));
            appendWeak(sb, found);

            // ---- التعتيم
            sb.append("\n— التعتيم (R8/ProGuard) —\n").append(obfuscation(strings));

            // ---- التغليف
            sb.append("\n— التغليف والحماية —\n").append(packers(names, strings, classDefs, apk.length()));

            // ---- مكتبات أصلية
            sb.append("\n— المكتبات الأصلية (.so) —\n").append(nativeLibs(z, names));

            // ---- ملفات عالية العشوائية
            sb.append("\n— ملفات قد تكون مشفّرة —\n").append(entropyFiles(z, names));
        } catch (Throwable t) {
            sb.append("\n⚠ تعذّر إكمال الفحص: ").append(t).append("\n");
        } finally {
            if (z != null) { try { z.close(); } catch (Exception ignored) { } }
        }

        sb.append("\n— التوقيع —\n").append(signature(apk));
        sb.append("\nملاحظة: الفحص ساكن ويعتمد على مؤشرات في الحزمة؛ غيابها لا يثبت عدم وجود تشفير (قد يكون داخل مكتبة أصلية أو مغلّف).\n");
        return sb.toString();
    }

    private static String flag(boolean on, String what, boolean badWhenOn) {
        return (on ? (badWhenOn ? "⚠ " : "✔ ") : "✘ ") + what + (on ? ": نعم" : ": لا") + "\n";
    }

    private static String nsc(List<String> names) {
        boolean has = false;
        for (String n : names) if (n.startsWith("res/xml/") && n.toLowerCase(Locale.US).contains("network_security")) has = true;
        return (has ? "✔ " : "✘ ") + "ملف إعدادات أمان الشبكة (network security config): " + (has ? "موجود" : "غير موجود") + "\n";
    }

    private static boolean match(String s, String mode, String t) {
        switch (mode) {
            case "e": return s.equals(t);
            case "s": return s.startsWith(t);
            default: return s.contains(t);
        }
    }

    private static void appendGroup(StringBuilder sb, String title, String group, Map<String, Integer> found) {
        sb.append("\n").append(title).append("\n");
        for (Map.Entry<String, Integer> e : found.entrySet()) {
            if (!e.getKey().startsWith(group + "|")) continue;
            int n = e.getValue();
            sb.append(n > 0 ? "✔ " : "✘ ").append(e.getKey().substring(group.length() + 1))
                    .append(n > 0 ? " — موجود (" + n + " مؤشر)" : " — غير موجود").append("\n");
        }
    }

    private static void appendWeak(StringBuilder sb, Map<String, Integer> found) {
        sb.append("\n— خوارزميات وممارسات ضعيفة —\n");
        boolean any = false;
        for (Map.Entry<String, Integer> e : found.entrySet()) {
            if (!e.getKey().startsWith("weak|") || e.getValue() == 0) continue;
            any = true;
            sb.append("⚠ ").append(e.getKey().substring(5)).append(" (").append(e.getValue()).append(" مؤشر)\n");
        }
        if (!any) sb.append("✔ لم تُرصد مؤشرات ضعيفة معروفة\n");
        else sb.append("ℹ بعضها قد يأتي من مكتبات خارجية لا من كود التطبيق (مثل MD5 لأغراض غير أمنية).\n");
    }

    private static String httpCount(List<String> strings) {
        int n = 0;
        for (String s : strings) {
            if (s.startsWith("http://") && !s.startsWith("http://schemas.android") && !s.startsWith("http://www.w3.org")
                    && !s.startsWith("http://xmlpull") && !s.startsWith("http://ns.adobe") && !s.startsWith("http://localhost")
                    && !s.startsWith("http://127.") && !s.startsWith("http://10.0.2.2") && !s.startsWith("http://www.apache.org")
                    && !s.startsWith("http://java.sun.com") && !s.startsWith("http://xml.org")) n++;
        }
        return (n > 0 ? "⚠ " : "✔ ") + "روابط http:// بلا تشفير: " + (n > 0 ? n + " رابط" : "لا شيء") + "\n";
    }

    private static String obfuscation(List<String> strings) {
        int app = 0, shortN = 0;
        for (String s : strings) {
            if (s.length() < 3 || s.charAt(0) != 'L' || !s.endsWith(";") || s.indexOf(' ') >= 0) continue;
            if (s.startsWith("Ljava/") || s.startsWith("Ljavax/") || s.startsWith("Landroid/") || s.startsWith("Ldalvik/")
                    || s.startsWith("Landroidx/") || s.startsWith("Lkotlin/") || s.startsWith("Lkotlinx/")) continue;
            String body = s.substring(1, s.length() - 1);
            String simple = body.substring(body.lastIndexOf('/') + 1);
            int dollar = simple.indexOf('$');
            if (dollar > 0) simple = simple.substring(0, dollar);
            app++;
            if (simple.length() <= 2) shortN++;
        }
        if (app < 20) return "ℹ عدد فئات التطبيق قليل (" + app + ") فلا يمكن الحكم، وقد يدل على تغليف.\n";
        int pct = shortN * 100 / app;
        String verdict = pct >= 40 ? "✔ معتّم بقوة" : (pct >= 10 ? "✔ معتّم جزئيًا" : "✘ غير معتّم");
        return verdict + " — " + pct + "% من أسماء الفئات من حرفين فأقل (" + shortN + " من " + app + ")\n";
    }

    private static String packers(List<String> names, List<String> strings, int classDefs, long apkLen) {
        List<String> hits = new ArrayList<>();
        for (String n : names) {
            String low = n.toLowerCase(Locale.US);
            for (String p : PACKER_LIBS) if (low.contains(p) && !hits.contains(p)) hits.add("مكتبة " + p);
        }
        for (String s : strings) {
            for (String p : PACKER_CLASSES) if (s.startsWith(p) && !hits.contains(p)) hits.add("فئة " + p);
        }
        StringBuilder sb = new StringBuilder();
        if (hits.isEmpty()) sb.append("✘ لم تُرصد أدوات تغليف معروفة\n");
        else { sb.append("⚠ مؤشرات تغليف/حماية: ").append(hits.size()).append("\n"); for (String h : hits) sb.append("  • ").append(h).append("\n"); }
        if (classDefs > 0 && classDefs <= 8 && apkLen > 1024 * 1024) {
            sb.append("⚠ فئات قليلة (").append(classDefs).append(") مع حجم كبير: يرجّح أن الكود الحقيقي مشفّر ويُحمَّل وقت التشغيل.\n");
        }
        return sb.toString();
    }

    private static String nativeLibs(ZipFile z, List<String> names) {
        StringBuilder sb = new StringBuilder();
        int total = 0, scanned = 0;
        List<String> cryptoNames = new ArrayList<>();
        for (String n : names) {
            if (!n.startsWith("lib/") || !n.endsWith(".so")) continue;
            total++;
            String base = n.substring(n.lastIndexOf('/') + 1).toLowerCase(Locale.US);
            for (String c : NATIVE_CRYPTO_LIBS) if (base.startsWith(c) && !cryptoNames.contains(base)) cryptoNames.add(base);
        }
        if (total == 0) return "✘ لا مكتبات أصلية\n";
        sb.append("المكتبات: ").append(total).append("\n");
        sb.append(cryptoNames.isEmpty() ? "✘ لا مكتبات تشفير معروفة بالاسم\n" : "✔ مكتبات تشفير بالاسم: " + cryptoNames + "\n");
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (String n : names) {
            if (!n.startsWith("lib/") || !n.endsWith(".so") || scanned >= 12) continue;
            try {
                ZipEntry e = z.getEntry(n);
                byte[] b = readEntry(z, e, MAX_SO);
                if (b == null) continue;
                scanned++;
                for (String nd : SO_NEEDLES) {
                    if (indexOf(b, nd.getBytes(StandardCharsets.ISO_8859_1)) >= 0) seen.put(nd, seen.containsKey(nd) ? seen.get(nd) + 1 : 1);
                }
            } catch (Throwable ignored) { }
        }
        if (!seen.isEmpty()) sb.append("✔ رموز تشفير داخل المكتبات (").append(scanned).append(" مفحوصة): ").append(seen.keySet()).append("\n");
        else sb.append("✘ لم تُرصد رموز تشفير معروفة في ").append(scanned).append(" مكتبة مفحوصة\n");
        return sb.toString();
    }

    private static String entropyFiles(ZipFile z, List<String> names) {
        List<String> hits = new ArrayList<>();
        int checked = 0;
        for (String n : names) {
            String low = n.toLowerCase(Locale.US);
            if (n.endsWith("/") || low.startsWith("meta-inf/") || low.endsWith(".dex") || low.endsWith(".so")
                    || low.equals("androidmanifest.xml") || low.equals("resources.arsc")) continue;
            if (low.startsWith("res/") && !low.startsWith("res/raw/")) continue;
            try {
                ZipEntry e = z.getEntry(n);
                if (e == null || e.getSize() < 2048) continue;
                byte[] head = new byte[65536];
                int len = 0;
                try (InputStream in = z.getInputStream(e)) {
                    int r;
                    while (len < head.length && (r = in.read(head, len, head.length - len)) > 0) len += r;
                }
                if (len < 2048 || knownFormat(head, len)) continue;
                checked++;
                double h = entropy(head, len);
                if (h >= 7.5) hits.add(n + "  (" + (e.getSize() / 1024) + " KB، إنتروبيا " + String.format(Locale.US, "%.2f", h) + ")");
            } catch (Throwable ignored) { }
        }
        if (hits.isEmpty()) return "✘ لا ملفات عالية العشوائية بين " + checked + " ملف مفحوص (assets/raw)\n";
        StringBuilder sb = new StringBuilder("⚠ ملفات عالية العشوائية (مشفّرة أو مضغوطة بصيغة خاصة): " + hits.size() + "\n");
        for (int i = 0; i < Math.min(10, hits.size()); i++) sb.append("  • ").append(hits.get(i)).append("\n");
        if (hits.size() > 10) sb.append("  … و").append(hits.size() - 10).append(" أخرى\n");
        return sb.toString();
    }

    private static boolean knownFormat(byte[] h, int n) {
        int a = h[0] & 0xff, b = h[1] & 0xff, c = h[2] & 0xff, d = h[3] & 0xff;
        if (a == 'P' && b == 'K') return true;
        if (a == 0x89 && b == 'P') return true;
        if (a == 0xFF && b == 0xD8) return true;
        if (a == 'G' && b == 'I' && c == 'F') return true;
        if (a == 'O' && b == 'g' && c == 'g') return true;
        if (a == 'I' && b == 'D' && c == '3') return true;
        if (a == 0xFF && (b & 0xE0) == 0xE0) return true;
        if (a == 'R' && b == 'I' && c == 'F' && d == 'F') return true;
        if (a == 0x1F && b == 0x8B) return true;
        if (a == '%' && b == 'P' && c == 'D') return true;
        if (a == 'w' && b == 'O' && c == 'F') return true;
        if (a == 0x7F && b == 'E' && c == 'L') return true;
        if (a == 'd' && b == 'e' && c == 'x') return true;
        if (a == 0 && b == 1 && c == 0 && d == 0) return true;
        if (a == 'O' && b == 'T' && c == 'T') return true;
        if (n > 8 && h[4] == 'f' && h[5] == 't' && h[6] == 'y' && h[7] == 'p') return true;
        if (a == 0xFD && b == '7' && c == 'z') return true;
        if (a == 0x28 && b == 0xB5 && c == 0x2F) return true;
        int printable = 0, m = Math.min(n, 512);
        for (int i = 0; i < m; i++) { int x = h[i] & 0xff; if (x == 9 || x == 10 || x == 13 || (x >= 32 && x < 127)) printable++; }
        return printable * 100 / m > 90;
    }

    private static double entropy(byte[] b, int n) {
        int[] cnt = new int[256];
        for (int i = 0; i < n; i++) cnt[b[i] & 0xff]++;
        double h = 0;
        for (int x : cnt) if (x > 0) { double p = (double) x / n; h -= p * (Math.log(p) / Math.log(2)); }
        return h;
    }

    // ------------------------------------------------------------------ مخططات التوقيع
    private static String signature(File apk) {
        StringBuilder sb = new StringBuilder();
        boolean v1 = false;
        try (ZipFile z = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName().toUpperCase(Locale.US);
                if (n.startsWith("META-INF/") && (n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith(".EC"))) v1 = true;
            }
        } catch (Throwable ignored) { }
        boolean v2 = false, v3 = false;
        try (RandomAccessFile r = new RandomAccessFile(apk, "r")) {
            long len = r.length();
            int max = (int) Math.min(len, 65557L);
            byte[] tail = new byte[max];
            r.seek(len - max);
            r.readFully(tail);
            int e = -1;
            for (int i = max - 22; i >= 0; i--) {
                if (tail[i] == 0x50 && tail[i + 1] == 0x4b && tail[i + 2] == 5 && tail[i + 3] == 6) { e = i; break; }
            }
            if (e >= 0) {
                long cd = le32(tail, e + 16) & 0xFFFFFFFFL;
                byte[] m = new byte[16];
                r.seek(cd - 16);
                r.readFully(m);
                if ("APK Sig Block 42".equals(new String(m, StandardCharsets.ISO_8859_1))) {
                    r.seek(cd - 24);
                    long size = le64(r);
                    long start = cd - size - 8;
                    long pos = start + 8, end = cd - 24;
                    while (pos + 12 <= end) {
                        r.seek(pos);
                        long plen = le64(r);
                        long id = (long) le32(r) & 0xFFFFFFFFL;
                        if (id == 0x7109871aL) v2 = true;
                        if (id == 0xf05368c0L || id == 0x1b93ad61L) v3 = true;
                        if (plen <= 0) break;
                        pos += 8 + plen;
                    }
                }
            }
        } catch (Throwable ignored) { }
        sb.append(v1 ? "✔ v1 (JAR)\n" : "✘ v1 (JAR)\n");
        sb.append(v2 ? "✔ v2\n" : "✘ v2\n");
        sb.append(v3 ? "✔ v3\n" : "✘ v3\n");
        if (!v1 && !v2 && !v3) sb.append("⚠ الحزمة غير موقَّعة\n");
        return sb.toString();
    }

    // ------------------------------------------------------------------ dex
    private static List<String> dexStrings(byte[] d) {
        List<String> out = new ArrayList<>();
        if (d.length < 0x70 || d[0] != 'd' || d[1] != 'e' || d[2] != 'x') return out;
        int n = le32(d, 0x38), off = le32(d, 0x3C);
        if (n <= 0 || n > 3000000 || off <= 0) return out;
        for (int i = 0; i < n; i++) {
            int ip = off + 4 * i;
            if (ip + 4 > d.length) break;
            int so = le32(d, ip);
            if (so <= 0 || so >= d.length) continue;
            int p = so, b;
            do { b = d[p++]; } while ((b & 0x80) != 0 && p < d.length);
            int e = p;
            while (e < d.length && d[e] != 0) e++;
            out.add(new String(d, p, e - p, StandardCharsets.UTF_8));
        }
        return out;
    }

    private static int le32(byte[] d, int o) {
        if (o < 0 || o + 4 > d.length) return 0;
        return (d[o] & 0xff) | ((d[o + 1] & 0xff) << 8) | ((d[o + 2] & 0xff) << 16) | ((d[o + 3] & 0xff) << 24);
    }

    private static long le64(RandomAccessFile r) throws java.io.IOException {
        byte[] b = new byte[8];
        r.readFully(b);
        long v = 0;
        for (int i = 7; i >= 0; i--) v = (v << 8) | (b[i] & 0xff);
        return v;
    }

    private static int le32(RandomAccessFile r) throws java.io.IOException {
        byte[] b = new byte[4];
        r.readFully(b);
        return le32(b, 0);
    }

    private static byte[] readEntry(ZipFile z, ZipEntry e, int max) throws java.io.IOException {
        if (e == null) return null;
        if (e.getSize() > max) return null;
        try (InputStream in = z.getInputStream(e)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
                if (bo.size() > max) return null;
            }
            return bo.toByteArray();
        }
    }

    private static int indexOf(byte[] h, byte[] nd) {
        outer:
        for (int i = 0; i <= h.length - nd.length; i++) {
            for (int j = 0; j < nd.length; j++) if (h[i + j] != nd[j]) continue outer;
            return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------ مشروع (مصدر)
    private static final String[][] SRC = {
            {"Cipher.getInstance", "Cipher\\.getInstance\\(", ""},
            {"AES", "[\"']AES[/\"']", ""},
            {"RSA", "[\"']RSA[/\"']", ""},
            {"ChaCha20", "ChaCha20", ""},
            {"Android Keystore", "AndroidKeyStore", ""},
            {"Jetpack Security", "EncryptedSharedPreferences|EncryptedFile|security-crypto", ""},
            {"SQLCipher", "(?i)sqlcipher", ""},
            {"Tink", "com\\.google\\.crypto\\.tink|tink-android", ""},
            {"BouncyCastle", "(?i)bouncycastle", ""},
            {"Certificate Pinning", "CertificatePinner|certificatePinner", ""},
            {"SSLContext / TLS", "SSLContext", ""},
            {"MD5", "MessageDigest\\.getInstance\\(\\s*\"MD5\"", "weak"},
            {"SHA-1", "MessageDigest\\.getInstance\\(\\s*\"SHA-?1\"", "weak"},
            {"DES / 3DES", "[\"']DES(ede)?[/\"']", "weak"},
            {"وضع ECB", "/ECB/", "weak"},
            {"RC4", "[\"'](RC4|ARCFOUR)[\"']", "weak"},
            {"تعطيل التحقق من الشهادات/المضيف", "ALLOW_ALL_HOSTNAME_VERIFIER|AllowAllHostnameVerifier|trustAll|TrustAll", "weak"},
            {"مفتاح تشفير مكتوب في الكود", "SecretKeySpec\\(\\s*\"[^\"]{8,}\"", "weak"},
            {"سر/توكن مكتوب في الكود", "(?i)(api[_-]?key|secret|passw(or)?d|token)\\w*\\s*[=:]\\s*\"[^\"\\s]{8,}\"", "weak"},
            {"كلمة سر توقيع مكتوبة في gradle", "(?i)(storePassword|keyPassword)\\s*[= ]\\s*[\"'][^\"']+[\"']", "weak"},
            {"cleartext مسموح (Manifest)", "usesCleartextTraffic\\s*=\\s*\"true\"", "weak"},
            {"debuggable (Manifest)", "debuggable\\s*=\\s*\"true\"", "weak"},
            {"allowBackup (Manifest)", "allowBackup\\s*=\\s*\"true\"", "info"},
            {"network security config", "networkSecurityConfig", ""},
            {"تقليص/تعتيم الكود مفعّل", "(minifyEnabled\\s*=?\\s*true|isMinifyEnabled\\s*=\\s*true)", ""},
            {"تقليص الموارد", "(shrinkResources\\s*=?\\s*true|isShrinkResources\\s*=\\s*true)", ""},
            {"ملفات قواعد ProGuard مرتبطة", "proguardFiles?\\b", ""},
            {"روابط http:// بلا تشفير", "\"http://(?!schemas\\.android|www\\.w3|xmlpull|ns\\.adobe|localhost|127\\.|10\\.0\\.2\\.2)[^\"\\s]+\"", "weak"},
    };

    public static String scanProject(File root, String name) {
        StringBuilder sb = new StringBuilder("تقرير التشفير للمشروع: " + name + "\n");
        Pattern[] pats = new Pattern[SRC.length];
        for (int i = 0; i < SRC.length; i++) pats[i] = Pattern.compile(SRC[i][1]);
        int[] counts = new int[SRC.length];
        List<List<String>> where = new ArrayList<>();
        for (int i = 0; i < SRC.length; i++) where.add(new ArrayList<>());
        int[] files = {0};
        boolean[] hasProguardFile = {false};
        walk(root, root, 0, pats, counts, where, files, hasProguardFile);
        sb.append("الملفات المفحوصة: ").append(files[0]).append("\n");
        String[] titles = {"", "weak", "info"};
        sb.append("\n— الموجود في المشروع —\n");
        boolean anyGood = false;
        for (int i = 0; i < SRC.length; i++) {
            if (!SRC[i][2].isEmpty()) continue;
            anyGood = true;
            sb.append(counts[i] > 0 ? "✔ " : "✘ ").append(SRC[i][0])
                    .append(counts[i] > 0 ? " — موجود (" + counts[i] + " موضع، مثال: " + where.get(i).get(0) + ")" : " — غير موجود").append("\n");
        }
        if (!anyGood) sb.append("—\n");
        sb.append("ملف proguard-rules/.pro: ").append(hasProguardFile[0] ? "✔ موجود" : "✘ غير موجود").append("\n");
        sb.append("\n— ممارسات ضعيفة أو خطرة —\n");
        boolean any = false;
        for (int i = 0; i < SRC.length; i++) {
            if (!"weak".equals(SRC[i][2]) && !"info".equals(SRC[i][2])) continue;
            if (counts[i] == 0) continue;
            any = true;
            sb.append("weak".equals(SRC[i][2]) ? "⚠ " : "ℹ ").append(SRC[i][0]).append(" — ").append(counts[i])
                    .append(" موضع (مثال: ").append(where.get(i).get(0)).append(")\n");
        }
        if (!any) sb.append("✔ لم تُرصد ممارسات ضعيفة معروفة\n");
        sb.append("\nملاحظة: لا تُعرض قيم الأسرار في التقرير، بل مواضعها فقط. الفحص بالأنماط وقد يعطي إنذارات كاذبة أو يفوّت حالات.\n");
        return sb.toString();
    }

    private static void walk(File root, File f, int depth, Pattern[] pats, int[] counts, List<List<String>> where,
                             int[] files, boolean[] pro) {
        if (depth > 14 || files[0] > 4000) return;
        String nm = f.getName();
        if (f.isDirectory()) {
            if (nm.equals("build") || nm.equals(".gradle") || nm.equals(".git") || nm.equals("node_modules") || nm.equals(".idea")) return;
            File[] ks = f.listFiles();
            if (ks != null) for (File k : ks) walk(root, k, depth + 1, pats, counts, where, files, pro);
            return;
        }
        String low = nm.toLowerCase(Locale.US);
        if (low.endsWith(".pro")) pro[0] = true;
        boolean ok = low.endsWith(".java") || low.endsWith(".kt") || low.endsWith(".gradle") || low.endsWith(".kts")
                || low.endsWith(".xml") || low.endsWith(".pro") || low.endsWith(".properties");
        if (!ok || f.length() > 1024 * 1024) return;
        files[0]++;
        try {
            String rel = root.toURI().relativize(f.toURI()).getPath();
            String[] lines = new String(Compat.readAllBytes(f), StandardCharsets.UTF_8).split("\n");
            boolean code = low.endsWith(".java") || low.endsWith(".kt");
            boolean gradle = low.endsWith(".gradle") || low.endsWith(".kts");
            boolean manifest = low.equals("androidmanifest.xml");
            for (int ln = 0; ln < lines.length; ln++) {
                for (int i = 0; i < pats.length; i++) {
                    String lbl = SRC[i][0];
                    // أنماط خاصة بنوع الملف لتقليل الإنذارات الكاذبة
                    if (lbl.contains("(Manifest)") && !manifest) continue;
                    if (lbl.startsWith("network security config") && !manifest) continue;
                    if ((lbl.startsWith("تقليص") || lbl.startsWith("ملفات قواعد")) && !gradle) continue;
                    if (lbl.startsWith("كلمة سر توقيع") && !gradle) continue;
                    if (lbl.startsWith("روابط http") && !code) continue;
                    if (low.endsWith(".xml") && !manifest && !lbl.startsWith("network")) continue;
                    if (pats[i].matcher(lines[ln]).find()) {
                        counts[i]++;
                        if (where.get(i).size() < 1) where.get(i).add(rel + ":" + (ln + 1));
                    }
                }
            }
        } catch (Throwable ignored) { }
    }
}
