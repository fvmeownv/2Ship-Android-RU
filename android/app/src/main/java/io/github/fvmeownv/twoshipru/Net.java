package io.github.fvmeownv.twoshipru;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

final class Net {
    private Net() {}

    /** Скачивает файл (3 попытки) и сверяет SHA-256. Неверный файл на диск не попадает. */
    static void download(String url, File dest, String expectSha256, Worker w) throws Exception {
        File part = new File(dest.getPath() + ".part");
        Exception last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            HttpURLConnection con = null;
            try {
                w.log("    попытка " + attempt + " из 3");
                con = open(url);
                long total = con.getContentLengthLong();
                if (total > 0) w.log("    размер: " + (total >> 20) + " МБ");
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                try (InputStream in = new BufferedInputStream(con.getInputStream(), 1 << 16);
                     OutputStream out = new FileOutputStream(part)) {
                    byte[] buf = new byte[1 << 16];
                    long done = 0;
                    int lastStep = -1, n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        md.update(buf, 0, n);
                        done += n;
                        if (total > 0) {
                            int step = (int) (done * 10 / total);
                            if (step != lastStep) { lastStep = step; w.log("    скачано " + step * 10 + "%"); }
                        }
                    }
                }
                String got = Hash.hex(md.digest());
                if (!got.equalsIgnoreCase(expectSha256)) {
                    part.delete();
                    throw new Problem("Скачанный файл игры не совпадает с ожидаемым (контрольная сумма "
                            + got.substring(0, 12) + "…). Возможно, авторы порта заменили файл. Сообщите об этом в Issues.");
                }
                if (dest.exists()) dest.delete();
                if (!part.renameTo(dest)) throw new IOException("не удалось сохранить " + dest);
                return;
            } catch (Problem p) {
                throw p;
            } catch (Exception e) {
                last = e;
                part.delete();
                w.log("    не получилось: " + e.getMessage());
                Thread.sleep(2000L * attempt);
            } finally {
                if (con != null) con.disconnect();
            }
        }
        throw new Problem("Не удалось скачать игру. Проверьте интернет и нажмите кнопку ещё раз."
                + (last != null ? "\n(" + last.getMessage() + ")" : ""));
    }

    private static HttpURLConnection open(String address) throws IOException {
        URL u = new URL(address);
        for (int hop = 0; hop < 8; hop++) {
            HttpURLConnection con = (HttpURLConnection) u.openConnection();
            con.setInstanceFollowRedirects(false);
            con.setConnectTimeout(20000);
            con.setReadTimeout(60000);
            con.setRequestProperty("User-Agent", "2Ship-RU-Installer");
            int code = con.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = con.getHeaderField("Location");
                con.disconnect();
                if (loc == null) throw new IOException("HTTP " + code + " без адреса");
                u = new URL(u, loc);
                continue;
            }
            if (code != 200) { con.disconnect(); throw new IOException("HTTP " + code); }
            return con;
        }
        throw new IOException("слишком много перенаправлений");
    }
}
