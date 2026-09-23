package io.github.fvmeownv.twoshipru;

import android.content.Context;

import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;

import java.io.File;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;

/** Подпись APK библиотекой apksig (та же, что внутри apksigner из Android SDK) и проверка результата. */
final class Signer {
    private Signer() {}

    static void sign(Context c, File in, File out, Worker w) throws Exception {
        PrivateKey key = Keys.privateKey(c);
        X509Certificate cert = Keys.certificate(c);
        ApkSigner.SignerConfig cfg =
                new ApkSigner.SignerConfig.Builder("CERT", key, Collections.singletonList(cert)).build();
        new ApkSigner.Builder(Collections.singletonList(cfg))
                .setInputApk(in)
                .setOutputApk(out)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setV4SigningEnabled(false)
                .build()
                .sign();
        verify(out, w);
    }

    static void verify(File apk, Worker w) throws Exception {
        ApkVerifier.Result r = new ApkVerifier.Builder(apk).build().verify();
        if (!r.isVerified()) {
            for (Object issue : r.getErrors()) w.log("    " + issue);
            throw new Problem("Подпись русской версии игры не прошла проверку. Сохраните отчёт и пришлите его.");
        }
        w.log("    подпись проверена: v1=" + r.isVerifiedUsingV1Scheme()
                + " v2=" + r.isVerifiedUsingV2Scheme() + " v3=" + r.isVerifiedUsingV3Scheme());
    }
}
