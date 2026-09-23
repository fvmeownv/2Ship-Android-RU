package io.github.fvmeownv.twoshipru;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** Установка через системный установщик Android. Итог приходит в MainActivity. */
final class Installer {
    private Installer() {}

    static void install(Context c, File apk, Worker w) throws Exception {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(Config.GAME_PKG);
        params.setSize(apk.length());
        int id = pi.createSession(params);
        PackageInstaller.Session session = pi.openSession(id);
        boolean committed = false;
        try {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                session.fsync(out);
            }
            Intent status = new Intent(c, MainActivity.class)
                    .setAction(MainActivity.ACTION_INSTALL_STATUS)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent pending = PendingIntent.getActivity(c, id, status, flags);
            session.commit(pending.getIntentSender());
            committed = true;
            w.log("Передал игру установщику Android. Подтвердите установку на экране.");
        } finally {
            if (!committed) {
                try { session.abandon(); } catch (Exception ignored) { }
            }
            session.close();
        }
    }
}
