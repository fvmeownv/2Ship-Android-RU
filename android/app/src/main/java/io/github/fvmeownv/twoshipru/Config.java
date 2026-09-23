package io.github.fvmeownv.twoshipru;

import android.content.Context;
import android.os.Environment;

import java.io.File;

/** Все пути, адреса и контрольные суммы в одном месте. Значения те же, что в lib/Common.ps1. */
final class Config {
    private Config() {}

    static final String GAME_PKG = "com.twoshipfork.mm";
    static final String PORT_VERSION = "v5.0.1-android.2";
    static final String OFFICIAL_APK_URL =
            "https://github.com/linkzenic/2ship2harkinian-Android/releases/download/v5.0.1-android.2/2Ship-Android-v5.0.1-android.2.apk";
    static final String OFFICIAL_APK_SHA256 = "3bf3406661f71a28f8d09dbddfdc51a410ab44bc1284b7c7508df70a0b70bc3f";

    static final long ROM_SIZE = 33554432L;
    static final String CLEAN_SHA1 = "d6133ace5afaa0882cf214cf88daba39e266c078";
    static final String CLEAN_SHA256 = "efb1365b3ae362604514c0f9a1a2d11f5dc8688ba5be660a37debf5e3be43f2b";
    static final String RUS_SHA1 = "f01bbd2d7f633dde6581c4099a28a8f3fff8ed07";
    static final String RUS_SHA256 = "eeb99c2830a96e845b9cdc98334caf8ccad9e176af6d0349442577fda6730eab";

    static final String DELTA_ASSET = "zelda64rus/Zelda_MM(U)_(V1.0)_Rus_2.0b.delta";
    static final String README_ASSET = "zelda64rus/ZeldaMM64_Rus_v2.0beta_readme.txt";

    static final String GFX_NAME = "10_Zelda64Rus_MM_Graphics.o2r";
    static final String TXT_NAME = "20_Zelda64Rus_MM_Text.o2r";

    // --- папки на общей памяти устройства (те же, что использовали скрипты для ПК) ---
    static File sdcard() { return Environment.getExternalStorageDirectory(); }
    static File devRoot() { return new File(sdcard(), "2S2H"); }
    static File stockO2r() { return new File(devRoot(), "mm.o2r"); }
    static File mods() { return new File(devRoot(), "mods"); }
    static File saves() { return new File(devRoot(), "saves"); }
    static File profiles() { return new File(devRoot(), "ru_profiles"); }
    static File profileTextDir() { return new File(profiles(), "text"); }
    static File profileGfxDir() { return new File(profiles(), "graphics"); }
    static File profileText() { return new File(profileTextDir(), TXT_NAME); }
    static File profileGfx() { return new File(profileGfxDir(), GFX_NAME); }
    static File downloads() { return new File(sdcard(), "Download"); }
    static File downloadRomDir() { return new File(downloads(), "2S2H_MM_RU"); }
    static File backupRoot() { return new File(sdcard(), "2S2H_RU_backup"); }

    // --- внутренние файлы приложения (не видны другим программам) ---
    private static File dir(Context c, String name) {
        File d = new File(c.getFilesDir(), name);
        d.mkdirs();
        return d;
    }
    static File work(Context c) { return dir(c, "work"); }
    static File reports(Context c) { return dir(c, "reports"); }
    static File logs(Context c) { return dir(c, "logs"); }
    static File cleanRom(Context c) { return new File(work(c), "MM_USA_v1.0.z64"); }
    static File rusRom(Context c) { return new File(work(c), "MM_RUS_2.0b.z64"); }
    static File romOk(Context c) { return new File(work(c), "rom.ok"); }
    static File ruApk(Context c) { return new File(work(c), "2Ship-RU.apk"); }
}
