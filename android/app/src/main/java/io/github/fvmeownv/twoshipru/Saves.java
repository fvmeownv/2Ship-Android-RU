package io.github.fvmeownv.twoshipru;

import java.io.File;

final class Saves {
    private Saves() {}

    /** Копия сохранений перед заменой игры. Сами сохранения при удалении игры не пропадают. */
    static void backup(Worker w) throws Exception {
        File saves = Config.saves();
        File[] list = saves.listFiles();
        if (list == null || list.length == 0) {
            w.log("Сохранений пока нет — копировать нечего.");
            return;
        }
        File dst = new File(Config.backupRoot(), "saves_" + Worker.stamp());
        int n = FileUtil.copyDir(saves, dst);
        w.log("Копия сохранений (" + n + " файл.): " + dst.getPath());
    }
}
