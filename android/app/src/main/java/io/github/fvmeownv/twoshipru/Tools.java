package io.github.fvmeownv.twoshipru;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/**
 * Запускает утилиты из tools/src (те же файлы, что собираются в .jar для ПК) и переносит
 * всё, что они печатают, в журнал приложения. Утилиты лежат без пакета, поэтому
 * вызываются по имени класса.
 */
final class Tools {
    private Tools() {}

    static synchronized void run(Worker w, String className, String... args) throws Exception {
        PrintStream oldOut = System.out, oldErr = System.err;
        LineSink sink = new LineSink(w);
        PrintStream ps = new PrintStream(sink, true, "UTF-8");
        System.setOut(ps);
        System.setErr(ps);
        try {
            Method main = Class.forName(className).getMethod("main", String[].class);
            main.invoke(null, (Object) args);
        } catch (InvocationTargetException e) {
            Throwable t = e.getCause();
            if (t instanceof Exception) throw (Exception) t;
            if (t instanceof Error) throw (Error) t;
            throw e;
        } finally {
            ps.flush();
            sink.close();
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
    }

    private static final class LineSink extends OutputStream {
        private final Worker w;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

        LineSink(Worker w) { this.w = w; }

        @Override public void write(int b) {
            if (b == '\n') emit();
            else if (b != '\r') buf.write(b);
        }

        @Override public void write(byte[] b, int off, int len) {
            for (int i = off; i < off + len; i++) write(b[i]);
        }

        @Override public void close() { emit(); }

        private void emit() {
            if (buf.size() == 0) return;
            w.log("    " + new String(buf.toByteArray(), StandardCharsets.UTF_8));
            buf.reset();
        }
    }
}
