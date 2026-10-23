package com.example.l2sbuilder;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * كاتب zip محمي بكلمة سر (تشفير ZIP التقليدي ZipCrypto) دون أي مكتبة خارجية.
 * تفتحه أدوات مثل ZArchiver وWinRAR و7-Zip و unzip بإدخال كلمة السر.
 * ملاحظة: هذا التشفير يمنع الفتح العادي، لكنه أضعف من AES أمام المختصين.
 */
public class ZipCryptoWriter implements Closeable {

    private static final int[] CRC_TABLE = new int[256];
    static {
        for (int n = 0; n < 256; n++) {
            int c = n;
            for (int k = 0; k < 8; k++) c = (c & 1) != 0 ? (c >>> 1) ^ 0xEDB88320 : c >>> 1;
            CRC_TABLE[n] = c;
        }
    }

    private static class Entry {
        byte[] name;
        int time, date, crc;
        long csize, usize, offset;
    }

    private final CountingOut out;
    private final byte[] password;
    private final List<Entry> entries = new ArrayList<>();
    private final SecureRandom rnd = new SecureRandom();
    private final int level;

    private int k0, k1, k2;

    public ZipCryptoWriter(OutputStream os, String password, int level) {
        this.out = new CountingOut(new BufferedOutputStream(os, 1 << 16));
        this.password = password.getBytes(StandardCharsets.UTF_8);
        this.level = level;
    }

    // ---------- ZipCrypto ----------
    private static int crc32(int old, int b) {
        return (old >>> 8) ^ CRC_TABLE[(old ^ b) & 0xFF];
    }

    private void updateKeys(int c) {
        k0 = crc32(k0, c);
        k1 = (k1 + (k0 & 0xFF)) * 134775813 + 1;
        k2 = crc32(k2, k1 >>> 24);
    }

    private void initKeys() {
        k0 = 0x12345678;
        k1 = 0x23456789;
        k2 = 0x34567890;
        for (byte b : password) updateKeys(b & 0xFF);
    }

    private int encryptByte(int plain) {
        int t = (k2 | 2) & 0xFFFF;
        int ks = ((t * (t ^ 1)) >>> 8) & 0xFF;
        updateKeys(plain);
        return (plain ^ ks) & 0xFF;
    }

    // ---------- كتابة الملفات ----------
    public void addFile(String entryName, File f) throws IOException {
        Entry e = new Entry();
        e.name = entryName.getBytes(StandardCharsets.UTF_8);
        long lm = f.lastModified();
        int[] dos = Compat.dosTime(lm);
        e.time = dos[0];
        e.date = dos[1];
        e.offset = out.count;

        // رأس محلي: مشفّر (bit0) + واصف بيانات (bit3) + أسماء UTF-8 (bit11)
        writeInt(0x04034b50);
        writeShort(20);
        writeShort(0x0809);
        writeShort(8);
        writeShort(e.time);
        writeShort(e.date);
        writeInt(0);
        writeInt(0);
        writeInt(0);
        writeShort(e.name.length);
        writeShort(0);
        out.write(e.name);

        initKeys();
        byte[] hdr = new byte[12];
        rnd.nextBytes(hdr);
        hdr[11] = (byte) ((e.time >> 8) & 0xFF);   // بايت التحقق عند استخدام واصف البيانات
        for (int i = 0; i < 12; i++) out.write(encryptByte(hdr[i] & 0xFF));
        long csize = 12;

        CRC32 crc = new CRC32();
        Deflater def = new Deflater(level, true);
        byte[] in = new byte[1 << 16];
        byte[] dbuf = new byte[1 << 16];
        long usize = 0;
        try (InputStream is = new FileInputStream(f)) {
            int r;
            while ((r = is.read(in)) > 0) {
                crc.update(in, 0, r);
                usize += r;
                def.setInput(in, 0, r);
                while (!def.needsInput()) {
                    int n = def.deflate(dbuf);
                    csize += writeEnc(dbuf, n);
                }
            }
            def.finish();
            while (!def.finished()) {
                int n = def.deflate(dbuf);
                csize += writeEnc(dbuf, n);
            }
        } finally {
            def.end();
        }

        e.crc = (int) crc.getValue();
        e.csize = csize;
        e.usize = usize;
        writeInt(0x08074b50);
        writeInt(e.crc);
        writeInt((int) e.csize);
        writeInt((int) e.usize);
        entries.add(e);
    }

    private int writeEnc(byte[] b, int n) throws IOException {
        for (int i = 0; i < n; i++) b[i] = (byte) encryptByte(b[i] & 0xFF);
        out.write(b, 0, n);
        return n;
    }

    @Override
    public void close() throws IOException {
        long cdStart = out.count;
        for (Entry e : entries) {
            writeInt(0x02014b50);
            writeShort(20);
            writeShort(20);
            writeShort(0x0809);
            writeShort(8);
            writeShort(e.time);
            writeShort(e.date);
            writeInt(e.crc);
            writeInt((int) e.csize);
            writeInt((int) e.usize);
            writeShort(e.name.length);
            writeShort(0);
            writeShort(0);
            writeShort(0);
            writeShort(0);
            writeInt(0);
            writeInt((int) e.offset);
            out.write(e.name);
        }
        long cdSize = out.count - cdStart;
        writeInt(0x06054b50);
        writeShort(0);
        writeShort(0);
        writeShort(entries.size());
        writeShort(entries.size());
        writeInt((int) cdSize);
        writeInt((int) cdStart);
        writeShort(0);
        out.flush();
        out.close();
    }

    private void writeShort(int v) throws IOException {
        out.write(v & 0xFF);
        out.write((v >>> 8) & 0xFF);
    }

    private void writeInt(int v) throws IOException {
        writeShort(v & 0xFFFF);
        writeShort((v >>> 16) & 0xFFFF);
    }

    private static class CountingOut extends OutputStream {
        private final OutputStream o;
        long count = 0;
        CountingOut(OutputStream o) { this.o = o; }
        @Override public void write(int b) throws IOException { o.write(b); count++; }
        @Override public void write(byte[] b, int off, int len) throws IOException { o.write(b, off, len); count += len; }
        @Override public void flush() throws IOException { o.flush(); }
        @Override public void close() throws IOException { o.close(); }
    }
}
