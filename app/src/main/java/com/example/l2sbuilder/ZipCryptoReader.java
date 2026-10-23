package com.example.l2sbuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * قارئ zip بسيط (بدون مكتبات خارجية) يقرأ الأرشيف من مصفوفة بايتات.
 * يدعم ملفات بلا تشفير، وملفات محمية بتشفير ZIP التقليدي (ZipCrypto) بكلمة سر.
 * لا يدعم تشفير AES ولا ZIP64.
 */
public final class ZipCryptoReader {

    public static class Entry {
        public String name;
        public int flags, method, time;
        public long crc, csize, usize, localOffset;
        public boolean isEncrypted() { return (flags & 1) != 0; }
        public boolean isDirectory() { return name.endsWith("/"); }
    }

    /** كلمة السر غير صحيحة (أو مطلوبة ولم تُعطَ). */
    public static class WrongPasswordException extends IOException {
        public WrongPasswordException(String m) { super(m); }
    }

    /** نوع تشفير/ضغط غير مدعوم. */
    public static class UnsupportedZipException extends IOException {
        public UnsupportedZipException(String m) { super(m); }
    }

    private static final int[] CRC_TABLE = new int[256];
    static {
        for (int n = 0; n < 256; n++) {
            int c = n;
            for (int k = 0; k < 8; k++) c = (c & 1) != 0 ? (c >>> 1) ^ 0xEDB88320 : c >>> 1;
            CRC_TABLE[n] = c;
        }
    }

    private ZipCryptoReader() {}

    private static int u16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] b, int o) {
        return (u16(b, o) & 0xFFFFL) | ((long) u16(b, o + 2) << 16);
    }

    /** يقرأ فهرس الأرشيف (central directory). */
    public static List<Entry> list(byte[] z) throws IOException {
        int eocd = -1;
        int min = Math.max(0, z.length - 22 - 65535);
        for (int i = z.length - 22; i >= min; i--) {
            if (u32(z, i) == 0x06054b50L) { eocd = i; break; }
        }
        if (eocd < 0) throw new IOException("ليس ملف zip صالحًا");
        int count = u16(z, eocd + 10);
        long cdOff = u32(z, eocd + 16);
        if (count == 0xFFFF || cdOff == 0xFFFFFFFFL) throw new UnsupportedZipException("ZIP64 غير مدعوم");
        List<Entry> res = new ArrayList<>();
        int p = (int) cdOff;
        for (int i = 0; i < count; i++) {
            if (p + 46 > z.length || u32(z, p) != 0x02014b50L) throw new IOException("فهرس zip تالف");
            Entry e = new Entry();
            e.flags = u16(z, p + 8);
            e.method = u16(z, p + 10);
            e.time = u16(z, p + 12);
            e.crc = u32(z, p + 16);
            e.csize = u32(z, p + 20);
            e.usize = u32(z, p + 24);
            int nl = u16(z, p + 28), xl = u16(z, p + 30), cl = u16(z, p + 32);
            e.localOffset = u32(z, p + 42);
            e.name = new String(z, p + 46, nl, StandardCharsets.UTF_8);
            res.add(e);
            p += 46 + nl + xl + cl;
        }
        return res;
    }

    /** يفك ضغط (وفك تشفير إن لزم) محتوى عنصر واحد. password قد تكون null لعنصر غير مشفّر. */
    public static byte[] read(byte[] z, Entry e, String password) throws IOException {
        if (e.method == 99) throw new UnsupportedZipException("تشفير AES غير مدعوم — استخدم zip بلا تشفير أو بتشفير ZipCrypto");
        if (e.method != 0 && e.method != 8) throw new UnsupportedZipException("طريقة ضغط غير مدعومة: " + e.method);
        if ((e.flags & 0x40) != 0) throw new UnsupportedZipException("تشفير قوي غير مدعوم");
        int lo = (int) e.localOffset;
        if (lo + 30 > z.length || u32(z, lo) != 0x04034b50L) throw new IOException("رأس عنصر تالف: " + e.name);
        int start = lo + 30 + u16(z, lo + 26) + u16(z, lo + 28);
        long csize = e.csize;
        if (start < 0 || start + csize > z.length) throw new IOException("بيانات عنصر ناقصة: " + e.name);

        byte[] data = new byte[(int) csize];
        System.arraycopy(z, start, data, 0, (int) csize);

        if (e.isEncrypted()) {
            if (password == null) throw new WrongPasswordException("كلمة السر مطلوبة");
            if (data.length < 12) throw new IOException("بيانات مشفّرة قصيرة: " + e.name);
            int[] k = {0x12345678, 0x23456789, 0x34567890};
            for (byte b : password.getBytes(StandardCharsets.UTF_8)) update(k, b & 0xFF);
            int last = 0;
            byte[] plain = new byte[data.length - 12];
            for (int i = 0; i < data.length; i++) {
                int t = (k[2] | 2) & 0xFFFF;
                int c = (data[i] & 0xFF) ^ (((t * (t ^ 1)) >>> 8) & 0xFF);
                update(k, c);
                if (i < 12) last = c; else plain[i - 12] = (byte) c;
            }
            // بايت التحقق: إما أعلى بايت من CRC أو أعلى بايت من وقت التعديل (عند وجود واصف بيانات)
            boolean ok = last == (int) ((e.crc >>> 24) & 0xFF) || last == ((e.time >>> 8) & 0xFF);
            if (!ok) throw new WrongPasswordException("كلمة السر غير صحيحة");
            data = plain;
        }

        byte[] out;
        if (e.method == 0) {
            out = data;
        } else {
            Inflater inf = new Inflater(true);
            try {
                inf.setInput(data);
                ByteArrayOutputStream bos = new ByteArrayOutputStream((int) Math.max(32, Math.min(e.usize, 1 << 26)));
                byte[] buf = new byte[1 << 16];
                while (!inf.finished()) {
                    int n = inf.inflate(buf);
                    if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                    bos.write(buf, 0, n);
                }
                out = bos.toByteArray();
            } catch (DataFormatException ex) {
                if (e.isEncrypted()) throw new WrongPasswordException("كلمة السر غير صحيحة");
                throw new IOException("بيانات مضغوطة تالفة: " + e.name);
            } finally {
                inf.end();
            }
        }
        CRC32 crc = new CRC32();
        crc.update(out, 0, out.length);
        if (crc.getValue() != e.crc) {
            if (e.isEncrypted()) throw new WrongPasswordException("كلمة السر غير صحيحة");
            throw new IOException("فشل التحقق من سلامة العنصر: " + e.name);
        }
        return out;
    }

    private static void update(int[] k, int c) {
        k[0] = (k[0] >>> 8) ^ CRC_TABLE[(k[0] ^ c) & 0xFF];
        k[1] = (k[1] + (k[0] & 0xFF)) * 134775813 + 1;
        k[2] = (k[2] >>> 8) ^ CRC_TABLE[(k[2] ^ (k[1] >>> 24)) & 0xFF];
    }
}
