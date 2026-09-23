package io.github.fvmeownv.twoshipru;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;

/**
 * Где сейчас находится пользователь. Определяется заново каждый раз, когда он возвращается
 * в приложение, поэтому что бы ни случилось (закрыл, отменил, удалил игру), приложение
 * всегда показывает правильный следующий шаг.
 */
final class State {
    enum Step { ACCESS, ROM, INSTALL, FIRST_RUN, MODS, DONE }

    boolean access, rom, canInstall, o2r, mods;
    int game;
    String profile = Mods.NONE;
    Step step;

    static State detect(Context c) {
        State s = new State();
        s.access = hasAccess(c);
        s.rom = Config.romOk(c).isFile()
                && Config.cleanRom(c).length() == Config.ROM_SIZE
                && Config.rusRom(c).length() == Config.ROM_SIZE;
        s.canInstall = c.getPackageManager().canRequestPackageInstalls();
        s.game = Game.state(c);
        if (s.access) {
            s.o2r = Config.stockO2r().length() > 1024 * 1024;
            s.mods = Config.profileText().isFile() && Config.profileGfx().isFile();
            s.profile = Mods.currentProfile();
        }
        if (!s.access) s.step = Step.ACCESS;
        else if (!s.rom) s.step = Step.ROM;
        else if (s.game != Game.OURS) s.step = Step.INSTALL;
        else if (!s.o2r) s.step = Step.FIRST_RUN;
        else if (!s.mods) s.step = Step.MODS;
        else s.step = Step.DONE;
        return s;
    }

    static boolean hasAccess(Context c) {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return c.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }
}
