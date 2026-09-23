package io.github.fvmeownv.twoshipru;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Поиск и проверка образа пользователя, применение патча Zelda64Rus (VcdiffPatcher из tools/src). */
final class Rom {
    private Rom() {}

    /** Ищет образ в «Загрузках» и соседних местах. Если не нашёл — открывает выбор файла. */
    static Worker.UiAction findAndPrepare(Context c, Worker w) throws Exception {
        List<File> found = new ArrayList<>();
        scan(Config.downloads(), 3, found);
        scan(Config.sdcard(), 1, found);
        scan(Config.devRoot(), 1, found);
        if (found.isEmpty()) w.log("Файлов размером 32 МБ в «Загрузках» не нашлось.");
        for (File f : found) {
            w.log("Проверяю " + f.getPath());
            byte[] rom = read(new FileInputStream(f));
            String kind = normalize(rom);
            if (kind == null) { w.log("    это не образ Nintendo 64"); continue; }
            String sha1 = Hash.of("SHA-1", rom);
            if (sha1.equals(Config.CLEAN_SHA1)) { install(c, rom, kind, w); return null; }
            if (sha1.equals(Config.RUS_SHA1)) w.log("    это уже русский образ — нужен оригинальный английский");
            else w.log("    другая версия игры (SHA-1 " + sha1 + ")");
        }
        w.log("Подходящий образ не найден, открываю выбор файла.");
        return MainActivity::pickRom;
    }

    static void prepareFromUri(Context c, Uri uri, Worker w) throws Exception {
        InputStream in = c.getContentResolver().openInputStream(uri);
        if (in == null) throw new Problem("Не удалось открыть выбранный файл.");
        byte[] rom = read(in);
        String kind = normalize(rom);
        if (kind == null) throw new Problem("Это не образ Nintendo 64. Нужен файл Majora's Mask (США, версия 1.0) размером 32 МБ.");
        String sha1 = Hash.of("SHA-1", rom);
        if (sha1.equals(Config.RUS_SHA1))
            throw new Problem("Это уже русский образ. Нужен оригинальный английский (США, версия 1.0) — перевод приложение наложит само.");
        if (!sha1.equals(Config.CLEAN_SHA1))
            throw new Problem("Это другая версия игры. Нужна Majora's Mask для США, версия 1.0.\n"
                    + "Ожидалось SHA-1 " + Config.CLEAN_SHA1 + "\nУ файла: " + sha1);
        install(c, rom, kind, w);
    }

    private static void install(Context c, byte[] rom, String kind, Worker w) throws Exception {
        if (!"z64".equals(kind)) w.log("    порядок байт " + kind + " — привёл к z64");
        if (!Hash.of("SHA-256", rom).equals(Config.CLEAN_SHA256))
            throw new Problem("Контрольная сумма образа не совпала. Нужна Majora's Mask для США, версия 1.0.");
        w.log("Образ подходит: Majora's Mask, США, версия 1.0.");
        Config.romOk(c).delete();
        File clean = Config.cleanRom(c), rus = Config.rusRom(c);
        FileUtil.write(clean, rom);

        w.log("Накладываю перевод Zelda64Rus…");
        File delta = new File(c.getCacheDir(), "zelda64rus.delta");
        try (InputStream in = c.getAssets().open(Config.DELTA_ASSET)) { FileUtil.copy(in, delta); }
        try {
            Tools.run(w, "VcdiffPatcher", clean.getPath(), delta.getPath(), rus.getPath(), Config.RUS_SHA1);
        } finally {
            delta.delete();
        }
        if (!Hash.of("SHA-256", rus).equals(Config.RUS_SHA256))
            throw new Problem("Русский образ получился не таким, как ожидалось. Сохраните отчёт и пришлите его.");
        FileUtil.write(Config.romOk(c), "ok".getBytes());
        w.log("Русский образ готов.");
    }

    private static void scan(File dir, int depth, List<File> out) {
        File[] list = dir.listFiles();
        if (list == null) return;
        for (File f : list) {
            if (f.isFile() && f.length() == Config.ROM_SIZE) {
                if (!out.contains(f)) out.add(f);
            } else if (depth > 1 && f.isDirectory() && !f.getName().startsWith(".") && !f.getName().equals("Android")) {
                scan(f, depth - 1, out);
            }
        }
    }

    private static byte[] read(InputStream in) throws IOException, Problem {
        try (InputStream s = in) {
            byte[] rom = new byte[(int) Config.ROM_SIZE];
            int off = 0, n;
            while (off < rom.length && (n = s.read(rom, off, rom.length - off)) > 0) off += n;
            if (off != rom.length || s.read() != -1)
                throw new Problem("Размер файла не подходит: нужен ровно 33 554 432 байта (Majora's Mask, США, версия 1.0).");
            return rom;
        }
    }

    /** Приводит образ к порядку байт .z64. Возвращает исходный формат или null, если это не образ N64. */
    static String normalize(byte[] r) {
        int b0 = r[0] & 255, b1 = r[1] & 255, b2 = r[2] & 255, b3 = r[3] & 255;
        if (b0 == 0x80 && b1 == 0x37 && b2 == 0x12 && b3 == 0x40) return "z64";
        if (b0 == 0x37 && b1 == 0x80 && b2 == 0x40 && b3 == 0x12) {
            for (int i = 0; i + 1 < r.length; i += 2) { byte t = r[i]; r[i] = r[i + 1]; r[i + 1] = t; }
            return "v64";
        }
        if (b0 == 0x40 && b1 == 0x12 && b2 == 0x37 && b3 == 0x80) {
            for (int i = 0; i + 3 < r.length; i += 4) {
                byte t0 = r[i], t1 = r[i + 1];
                r[i] = r[i + 3]; r[i + 1] = r[i + 2]; r[i + 2] = t1; r[i + 3] = t0;
            }
            return "n64";
        }
        return null;
    }
}
