package io.github.fvmeownv.twoshipru;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;

final class Hash {
    private Hash() {}

    static String hex(byte[] d) {
        StringBuilder s = new StringBuilder(d.length * 2);
        for (byte b : d) s.append(String.format("%02x", b & 255));
        return s.toString();
    }

    static String of(String alg, byte[] data) throws Exception {
        return hex(MessageDigest.getInstance(alg).digest(data));
    }

    static String of(String alg, File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance(alg);
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[1 << 16];
            int n;
            while ((n = in.read(b)) > 0) md.update(b, 0, n);
        }
        return hex(md.digest());
    }
}
