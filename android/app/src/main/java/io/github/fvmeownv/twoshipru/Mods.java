package io.github.fvmeownv.twoshipru;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Сборка русского текста и графики (утилиты из tools/src) и раскладка профилей —
 * та же схема папок, что у скриптов для ПК: ru_profiles/text, ru_profiles/graphics → mods.
 */
final class Mods {
    private Mods() {}

    static final String BOTH = "both", TEXT = "text", GRAPHICS = "graphics", NONE = "none";

    static void build(Context c, Worker w) throws Exception {
        File o2r = Config.stockO2r();
        if (!o2r.isFile()) throw new Problem("Игра ещё не создала свои файлы. Сначала выполните шаг «Первый запуск игры».");

        w.log("Проверяю, что игра закончила создавать mm.o2r…");
        long s1 = o2r.length();
        Thread.sleep(2000);
        long s2 = o2r.length();
        Thread.sleep(2000);
        long s3 = o2r.length();
        if (s1 != s2 || s2 != s3)
            throw new Problem("Игра ещё создаёт свои файлы. Подождите минуту-две и нажмите кнопку снова.");
        try (ZipFile z = new ZipFile(o2r)) {
            if (z.getEntry("version") == null || z.getEntry("portVersion") == null) throw new ZipException("нет version");
        } catch (ZipException e) {
            o2r.delete();
            throw new Problem("Файл игры mm.o2r недописан или повреждён, я его удалил. Повторите шаг «Первый запуск игры».");
        }
        w.log("    mm.o2r: " + (s3 >> 20) + " МБ, в порядке");

        Game.kill(c, w);

        File work = Config.work(c);
        File clean = Config.cleanRom(c), rus = Config.rusRom(c);
        File gfx = new File(work, Config.GFX_NAME);
        File textBase = new File(work, "Zelda64Rus_MM_Text_base.o2r");
        File text = new File(work, Config.TXT_NAME);
        String stamp = Worker.stamp();

        w.log("Собираю русскую графику…");
        Tools.run(w, "O2rGraphicsOverlayBuilder", clean.getPath(), rus.getPath(), o2r.getPath(), gfx.getPath(),
                new File(Config.reports(c), "overlay_" + stamp + ".txt").getPath());
        w.log("Собираю русский текст и шрифт…");
        Tools.run(w, "O2rTextModBuilder", clean.getPath(), rus.getPath(), o2r.getPath(), textBase.getPath(),
                new File(Config.reports(c), "textmod_" + stamp + ".txt").getPath());
        w.log("Добавляю буквы для «НАЖМИ СТАРТ»…");
        Tools.run(w, "O2rTitleGlyphPatcher", textBase.getPath(), text.getPath());
        textBase.delete();

        w.log("Кладу файлы в папку игры…");
        Config.profileGfxDir().mkdirs();
        Config.profileTextDir().mkdirs();
        removeOld(Config.profileGfxDir());
        removeOld(Config.profileTextDir());
        FileUtil.copy(gfx, Config.profileGfx());
        FileUtil.copy(text, Config.profileText());
        gfx.delete();
        text.delete();
        applyProfile(BOTH, w);

        File dl = Config.downloadRomDir();
        if (dl.exists()) { FileUtil.deleteRecursive(dl); w.log("Временная копия образа из «Загрузок» удалена."); }
    }

    /** Включает нужный набор. Чужие моды в папке mods не трогаются. */
    static void applyProfile(String profile, Worker w) throws IOException {
        File mods = Config.mods();
        mods.mkdirs();
        removeOld(mods);
        if (BOTH.equals(profile) || TEXT.equals(profile)) {
            FileUtil.copy(Config.profileText(), new File(mods, Config.TXT_NAME));
        }
        if (BOTH.equals(profile) || GRAPHICS.equals(profile)) {
            FileUtil.copy(Config.profileGfx(), new File(mods, Config.GFX_NAME));
        }
        w.log("Включено: " + label(profile));
    }

    static String currentProfile() {
        boolean t = new File(Config.mods(), Config.TXT_NAME).isFile();
        boolean g = new File(Config.mods(), Config.GFX_NAME).isFile();
        return t && g ? BOTH : t ? TEXT : g ? GRAPHICS : NONE;
    }

    static String label(String p) {
        switch (p) {
            case BOTH: return "всё на русском";
            case TEXT: return "только текст";
            case GRAPHICS: return "только графика";
            default: return "без русификации";
        }
    }

    /** Удаляет все поколения наших файлов (как Remove-OldGraphics / Remove-OldText в Common.ps1). */
    private static void removeOld(File dir) {
        File[] list = dir.listFiles();
        if (list == null) return;
        for (File f : list) {
            String n = f.getName();
            if (!n.endsWith(".o2r")) continue;
            if (n.startsWith("00_Zelda64Rus_Graphics") || n.startsWith("10_Zelda64Rus_MM_Graphics")
                    || n.startsWith("Russian_MM_5.0.1") || n.startsWith("20_Zelda64Rus_MM_Text")) {
                f.delete();
            }
        }
    }
}
