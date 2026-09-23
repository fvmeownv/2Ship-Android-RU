package io.github.fvmeownv.twoshipru;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Замена zipalign: переупаковывает APK так, чтобы несжатые файлы начинались с границы
 * 4 байт, а библиотеки .so с границы 16 КБ (подходит и для старых устройств с 4 КБ).
 * Выравнивание делается полем 0xD935 — так же, как у zipalign из Android SDK —
 * и проверяется для каждого файла сразу при записи.
 */
final class ZipAligner {
    private ZipAligner() {}

    private static final int LIB_ALIGN = 16384;
    private static final int DEFAULT_ALIGN = 4;
    private static final long FIXED_TIME = 1293840000000L; // 01.01.2011, в пределах формата DOS

    static void align(File in, File out, Worker w) throws IOException {
        int stored = 0, deflated = 0, libs = 0;
        CountingStream cos = new CountingStream(new BufferedOutputStream(new FileOutputStream(out), 1 << 16));
        try (ZipFile zf = new ZipFile(in); ZipOutputStream zos = new ZipOutputStream(cos)) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry src = en.nextElement();
                if (src.isDirectory()) continue;
                String name = src.getName();
                byte[] data;
                try (InputStream is = zf.getInputStream(src)) { data = readAll(is); }

                ZipEntry dst = new ZipEntry(name);
                dst.setTime(FIXED_TIME);
                if (src.getMethod() == ZipEntry.STORED) {
                    int align = name.endsWith(".so") ? LIB_ALIGN : DEFAULT_ALIGN;
                    CRC32 crc = new CRC32();
                    crc.update(data);
                    dst.setMethod(ZipEntry.STORED);
                    dst.setSize(data.length);
                    dst.setCompressedSize(data.length);
                    dst.setCrc(crc.getValue());
                    int nameLen = name.getBytes(StandardCharsets.UTF_8).length;
                    long dataStart = cos.count + 30 + nameLen + 6;
                    int pad = (int) ((align - (dataStart % align)) % align);
                    byte[] extra = new byte[6 + pad];
                    extra[0] = (byte) 0x35;
                    extra[1] = (byte) 0xD9;
                    extra[2] = (byte) ((2 + pad) & 255);
                    extra[3] = (byte) (((2 + pad) >> 8) & 255);
                    extra[4] = (byte) (align & 255);
                    extra[5] = (byte) ((align >> 8) & 255);
                    dst.setExtra(extra);
                    zos.putNextEntry(dst);
                    if (cos.count % align != 0)
                        throw new IOException("Выравнивание не удалось: " + name + " @ " + cos.count);
                    stored++;
                    if (name.endsWith(".so")) libs++;
                } else {
                    dst.setMethod(ZipEntry.DEFLATED);
                    zos.putNextEntry(dst);
                    deflated++;
                }
                zos.write(data);
                zos.closeEntry();
            }
        }
        w.log("    файлов без сжатия: " + stored + " (из них библиотек: " + libs + "), сжатых: " + deflated);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[1 << 16];
        int n;
        while ((n = in.read(b)) >= 0) o.write(b, 0, n);
        return o.toByteArray();
    }

    private static final class CountingStream extends FilterOutputStream {
        long count;
        CountingStream(OutputStream out) { super(out); }
        @Override public void write(int b) throws IOException { out.write(b); count++; }
        @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); count += len; }
    }
}
