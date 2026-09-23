package io.github.fvmeownv.twoshipru;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.net.Uri;
import android.os.Build;

import java.io.File;
import java.util.Arrays;

/** Всё, что касается установленной игры 2 Ship. */
final class Game {
    private Game() {}

    static final int NOT_INSTALLED = 0, OURS = 1, FOREIGN = 2;

    /** Установлена ли игра, и если да — наша ли это русская версия (по подписи). */
    @SuppressWarnings("deprecation")
    static int state(Context c) {
        PackageManager pm = c.getPackageManager();
        Signature[] sigs;
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                PackageInfo pi = pm.getPackageInfo(Config.GAME_PKG, PackageManager.GET_SIGNING_CERTIFICATES);
                SigningInfo si = pi.signingInfo;
                if (si == null) return FOREIGN;
                sigs = si.hasMultipleSigners() ? si.getApkContentsSigners() : si.getSigningCertificateHistory();
            } else {
                PackageInfo pi = pm.getPackageInfo(Config.GAME_PKG, PackageManager.GET_SIGNATURES);
                sigs = pi.signatures;
            }
        } catch (PackageManager.NameNotFoundException e) {
            return NOT_INSTALLED;
        }
        try {
            byte[] ours = Keys.certificate(c).getEncoded();
            if (sigs != null) for (Signature s : sigs) if (Arrays.equals(s.toByteArray(), ours)) return OURS;
        } catch (Exception ignored) { }
        return FOREIGN;
    }

    static void launch(MainActivity a) throws Problem {
        Intent i = a.getPackageManager().getLaunchIntentForPackage(Config.GAME_PKG);
        if (i == null) throw new Problem("Игра не найдена на устройстве.");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        a.startActivity(i);
    }

    /** Закрывает игру, если она свёрнута, чтобы при следующем запуске она прочитала новые файлы. */
    static void kill(Context c, Worker w) {
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) am.killBackgroundProcesses(Config.GAME_PKG);
            w.log("Игра закрыта (если была открыта).");
        } catch (Exception e) {
            w.log("Не удалось закрыть игру: " + e.getMessage());
        }
    }

    @SuppressWarnings("deprecation")
    static void requestUninstall(MainActivity a) {
        Intent i = new Intent(Intent.ACTION_DELETE, Uri.fromParts("package", Config.GAME_PKG, null));
        a.startActivity(i);
    }

    /** Кладёт чистый образ туда, где игра его найдёт (как делал скрипт 01_INSTALL). */
    static void prepareFirstRun(Context c, Worker w) throws Exception {
        File clean = Config.cleanRom(c);
        if (clean.length() != Config.ROM_SIZE) throw new Problem("Образ игры пропал. Выберите его заново.");
        File inRoot = new File(Config.devRoot(), "MM.z64");
        if (inRoot.length() != Config.ROM_SIZE) {
            FileUtil.copy(clean, inRoot);
            w.log("Образ скопирован в " + inRoot.getPath());
        }
        File inDownload = new File(Config.downloadRomDir(), "MM_USA_v1.0.z64");
        if (inDownload.length() != Config.ROM_SIZE) {
            FileUtil.copy(clean, inDownload);
            w.log("Образ скопирован в " + inDownload.getPath());
        }
    }
}
