package io.github.fvmeownv.twoshipru;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

final class FileUtil {
    private FileUtil() {}

    static void copy(InputStream in, File dst) throws IOException {
        File parent = dst.getParentFile();
        if (parent != null) parent.mkdirs();
        File tmp = new File(dst.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            byte[] b = new byte[1 << 16];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
        }
        if (dst.exists() && !dst.delete()) throw new IOException("не удалось заменить " + dst);
        if (!tmp.renameTo(dst)) throw new IOException("не удалось записать " + dst);
    }

    static void copy(File src, File dst) throws IOException {
        try (InputStream in = new FileInputStream(src)) { copy(in, dst); }
    }

    static void write(File dst, byte[] data) throws IOException {
        try (InputStream in = new java.io.ByteArrayInputStream(data)) { copy(in, dst); }
    }

    /** Копирует папку целиком, возвращает число файлов. */
    static int copyDir(File src, File dst) throws IOException {
        int n = 0;
        File[] list = src.listFiles();
        if (list == null) return 0;
        dst.mkdirs();
        for (File f : list) {
            File t = new File(dst, f.getName());
            if (f.isDirectory()) n += copyDir(f, t);
            else { copy(f, t); n++; }
        }
        return n;
    }

    static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] list = f.listFiles();
            if (list != null) for (File x : list) deleteRecursive(x);
        }
        f.delete();
    }
}
