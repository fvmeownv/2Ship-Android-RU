package io.github.fvmeownv.twoshipru;

import android.content.Context;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;

/**
 * Постоянный ключ, которым подписывается русская версия игры. Он одинаковый у всех,
 * поэтому игра обновляется поверх без удаления. Ключ открытый — как и встроенный
 * debug-ключ uber-apk-signer, которым подписывала версия для ПК.
 */
final class Keys {
    private Keys() {}

    private static PrivateKey key;
    private static X509Certificate cert;

    static synchronized PrivateKey privateKey(Context c) throws Exception {
        if (key == null) {
            String pem = new String(readAsset(c, "signing/key.pem"), StandardCharsets.US_ASCII);
            StringBuilder b64 = new StringBuilder();
            for (String line : pem.split("\n")) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("-----")) b64.append(line);
            }
            byte[] der = Base64.decode(b64.toString(), Base64.DEFAULT);
            key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        }
        return key;
    }

    static synchronized X509Certificate certificate(Context c) throws Exception {
        if (cert == null) {
            try (InputStream in = c.getAssets().open("signing/cert.pem")) {
                cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
            }
        }
        return cert;
    }

    static byte[] readAsset(Context c, String name) throws Exception {
        try (InputStream in = c.getAssets().open(name)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[1 << 16];
            int n;
            while ((n = in.read(b)) >= 0) o.write(b, 0, n);
            return o.toByteArray();
        }
    }
}
