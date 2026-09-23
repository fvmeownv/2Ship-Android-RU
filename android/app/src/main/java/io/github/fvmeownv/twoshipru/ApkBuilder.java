package io.github.fvmeownv.twoshipru;

import android.content.Context;

import java.io.File;
import java.io.IOException;

/**
 * Собирает русскую версию игры: скачивает официальный APK, вносит исправления
 * (ApkRuCompatPatcher из tools/src), выравнивает, подписывает и проверяет
 * (ApkRuCompatVerifier). Всё это ДО любых действий с установленной игрой.
 */
final class ApkBuilder {
    private ApkBuilder() {}

    static File ensure(Context c, Worker w) throws Exception {
        File ru = Config.ruApk(c);
        if (ru.isFile()) {
            w.log("Проверяю ранее собранную русскую версию…");
            try {
                Tools.run(w, "ApkRuCompatVerifier", ru.getPath());
                Signer.verify(ru, w);
                w.log("Русская версия уже собрана и проверена.");
                return ru;
            } catch (Exception e) {
                w.log("Старая сборка не прошла проверку, соберу заново.");
                ru.delete();
            }
        }

        File work = Config.work(c);
        File official = new File(work, "official.apk");
        if (official.isFile() && Hash.of("SHA-256", official).equalsIgnoreCase(Config.OFFICIAL_APK_SHA256)) {
            w.log("Файл игры уже скачан.");
        } else {
            w.log("Скачиваю игру 2 Ship " + Config.PORT_VERSION + " со страницы её авторов на GitHub…");
            Net.download(Config.OFFICIAL_APK_URL, official, Config.OFFICIAL_APK_SHA256, w);
            w.log("Файл игры скачан и проверен.");
        }

        File unsigned = new File(work, "stage-unsigned.apk");
        File aligned = new File(work, "stage-aligned.apk");
        File signed = new File(work, "stage-signed.apk");
        delete(unsigned, aligned, signed);
        try {
            w.log("Вношу русские исправления в игру…");
            Tools.run(w, "ApkRuCompatPatcher", official.getPath(), unsigned.getPath());
            w.log("Упаковываю…");
            ZipAligner.align(unsigned, aligned, w);
            w.log("Подписываю…");
            Signer.sign(c, aligned, signed, w);
            w.log("Проверяю русскую версию…");
            File report = new File(Config.reports(c), "apk_verify_" + Worker.stamp() + ".txt");
            Tools.run(w, "ApkRuCompatVerifier", signed.getPath(), report.getPath());
            if (!signed.renameTo(ru)) throw new IOException("не удалось сохранить " + ru);
        } finally {
            delete(unsigned, aligned, signed);
        }
        official.delete();
        w.log("Русская версия игры собрана.");
        return ru;
    }

    private static void delete(File... files) {
        for (File f : files) if (f.exists()) f.delete();
    }
}
