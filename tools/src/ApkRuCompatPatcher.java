import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * Patches the native ARM64 library inside the official 2Ship Android APK
 * with the Russian compatibility data used by banana2322/2ship2harkinian-rus.
 *
 * Required patch: NES font advance-width table (fixes compressed/overlapping Cyrillic).
 * Optional data patches mirror Russian-fork tables: localized rupee label and owl-warp labels.
 * The program intentionally refuses to continue if the critical width signature is
 * absent or ambiguous, so an unknown/new APK is never silently modified.
 */
public final class ApkRuCompatPatcher {
    private static final float[] WIDTHS_STOCK = new float[] {
        8,8,6,9,9,14,12,3,7,7,7,9,4,6,4,9,
        10,5,9,9,10,9,9,9,9,9,6,6,9,11,9,11,
        13,12,9,11,11,8,8,12,10,4,8,10,8,13,11,13,
        9,13,10,10,9,10,11,15,11,10,10,7,10,7,10,9,
        5,8,9,8,9,9,6,9,8,4,6,8,4,12,9,9,
        9,9,7,8,7,8,9,12,8,9,8,7,5,7,10,6,
        12,12,12,12,11,8,8,8,8,6,6,6,6,10,13,13,
        13,13,10,10,10,10,9,8,8,8,8,8,9,9,9,9,
        4,4,4,4,8,9,9,9,9,8,8,8,8,8,11,6,
        14,14,14,14,14,14,14,14,14,14,14,14,14,14,14,14
    };

    private static final float[] WIDTHS_RU = new float[] {
        8,8,6,9,9,14,12,3,7,7,7,9,4,6,4,9,
        10,5,9,9,10,9,9,9,9,9,6,6,9,11,9,11,
        13,11,9,11,11,8,8,12,10,4,8,10,8,13,11,13,
        9,13,10,10,9,10,11,15,11,10,10,7,10,7,10,9,
        5,8,9,8,9,9,6,9,8,4,6,8,4,12,9,9,
        9,9,7,8,7,8,9,12,8,9,8,7,5,7,10,9,
        8,12,7,12,9,10,9,10,11,10,10,11,10,10,13,14,
        11,12,8,11,12,9,8,7,7,9,8,11,7,8,7,7,
        7,11,8,8,7,11,8,7,11,11,8,10,7,8,11,8,
        14,14,14,14,14,14,14,14,14,14,14,14,14,14,14,14
    };

    private static final byte[] RUPEE_TABLE_STOCK = concatRows(8,
        ascii("Rupee(s)"), ascii("Rubin(e)"), ascii("Rubis"), ascii("Rupia(s)"));
    // \x70\x79\xA3\x9D\x9E = "рупий" in the one-byte font mapping used by the mod.
    private static final byte[] RUPEE_TABLE_RU = concatRows(8,
        new byte[]{0x70,0x79,(byte)0xA3,(byte)0x9D,(byte)0x9E},
        ascii("Rubin(e)"), ascii("Rubis"), ascii("Rupia(s)"));
    private static final byte[] RUPEE_LENGTHS_STOCK = new byte[]{8,8,5,8};
    private static final byte[] RUPEE_LENGTHS_RU    = new byte[]{5,8,5,8};

    private static final String[] OWL_STOCK_STRINGS = new String[]{
        "Great Bay Coast", "Zora Cape", "Snowhead", "Mountain Village", "Clock Town",
        "Milk Road", "Woodfall", "Southern Swamp", "Ikana Canyon", "Stone Tower", "Entrance"
    };
    private static final byte[][] OWL_RU_STRINGS = new byte[][]{
        hex("89 6F 96 65 70 65 9B AC AE 20 84 61 A0 9D 97 61"), // Побережью Залива
        hex("88 AB 63 79 20 84 6F 70 61"),                         // Мысу Зора
        hex("43 A2 65 9B A2 6F A1 79 20 89 9D 9F 79"),             // Снежному Пику
        hex("80 6F 70 A2 6F 9E 20 99 65 70 65 97 A2 65"),          // Горной деревне
        hex("8D 61 63 6F 97 6F A1 79 20 98 6F 70 6F 99 79"),       // Часовому городу
        hex("88 A0 65 A7 A2 6F A1 79 20 A3 79 A4 9D"),             // Млечному пути
        hex("87 65 63 A2 6F 9E 20 54 6F A3 9D"),                   // Лесной Топи
        hex("94 9B A2 6F A1 79 20 96 6F A0 6F A4 79"),             // Южному болоту
        hex("4B 61 A2 AC 6F A2 79 20 85 9F 61 A2 AB"),             // Каньону Иканы
        hex("4B 61 A1 65 A2 A2 6F 9E 20 96 61 A8 A2 65"),          // Каменной башне
        hex("42 78 6F 99 79")                                      // Входу
    };

    private static final byte[] OWL_TEXT_STOCK = buildOwlStock();
    private static final byte[] OWL_TEXT_RU = concatRows(16, OWL_RU_STRINGS);
    private static final byte[] OWL_LEN_STOCK = s16le(new int[]{15,9,8,16,10,9,8,14,12,11,8});
    private static final byte[] OWL_LEN_RU = s16le(new int[]{16,9,13,14,15,13,11,13,13,14,5});

    // Title screen. The port spells "PRESS START" with 10 indices into the ordered font
    // (0-9, A-Z, ...): 5 letters, a gap, 5 letters. "НАЖМИ СТАРТ" fits exactly. Eight of its letters
    // are Latin look-alikes (H A M C T A P T); Ж and И are served from the Q and W slots, whose glyphs
    // the RU text mod copy replaces. The trailing "__OTR__" pins the match to the title actor's data.
    private static final byte[] TITLE_STOCK = cat(new byte[]{0x19,0x1B,0x0E,0x1C,0x1C,0x1C,0x1D,0x0A,0x1B,0x1D}, "__OTR__".getBytes());
    private static final byte[] TITLE_RU    = cat(new byte[]{0x11,0x0A,0x1A,0x16,0x20,0x0C,0x1D,0x0A,0x19,0x1D}, "__OTR__".getBytes());
    private static byte[] cat(byte[] a, byte[] b){ byte[] r=Arrays.copyOf(a,a.length+b.length); System.arraycopy(b,0,r,a.length,b.length); return r; }

    private static byte[] ascii(String s) {
        try { return s.getBytes("US-ASCII"); } catch (Exception e) { throw new RuntimeException(e); }
    }
    private static byte[] hex(String s) {
        String[] p=s.trim().split("\\s+"); byte[] r=new byte[p.length];
        for(int i=0;i<p.length;i++) r[i]=(byte)Integer.parseInt(p[i],16); return r;
    }
    private static byte[] floatsLE(float[] a) {
        ByteBuffer b=ByteBuffer.allocate(a.length*4).order(ByteOrder.LITTLE_ENDIAN);
        for(float v:a)b.putFloat(v); return b.array();
    }
    private static byte[] s16le(int[] a) {
        ByteBuffer b=ByteBuffer.allocate(a.length*2).order(ByteOrder.LITTLE_ENDIAN);
        for(int v:a)b.putShort((short)v); return b.array();
    }
    private static byte[] concatRows(int rowSize, byte[]... rows) {
        byte[] out=new byte[rowSize*rows.length];
        for(int i=0;i<rows.length;i++) {
            if(rows[i].length>rowSize) throw new IllegalArgumentException("row too long: "+rows[i].length+">"+rowSize);
            System.arraycopy(rows[i],0,out,i*rowSize,rows[i].length);
        }
        return out;
    }
    private static byte[] buildOwlStock() {
        byte[][] rows=new byte[OWL_STOCK_STRINGS.length][];
        for(int i=0;i<rows.length;i++) rows[i]=ascii(OWL_STOCK_STRINGS[i]);
        return concatRows(16, rows);
    }

    private static int count(byte[] data, byte[] needle) {
        int n=0; for(int i=0;i<=data.length-needle.length;i++) {
            boolean ok=true; for(int j=0;j<needle.length;j++) if(data[i+j]!=needle[j]) {ok=false;break;}
            if(ok){n++; i+=needle.length-1;}
        } return n;
    }
    private static int replaceAll(byte[] data, byte[] from, byte[] to) {
        if(from.length!=to.length) throw new IllegalArgumentException("size mismatch");
        int n=0; for(int i=0;i<=data.length-from.length;i++) {
            boolean ok=true; for(int j=0;j<from.length;j++) if(data[i+j]!=from[j]) {ok=false;break;}
            if(ok){System.arraycopy(to,0,data,i,to.length); n++; i+=from.length-1;}
        } return n;
    }
    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] buf=new byte[1<<20]; int n;
        while((n=in.read(buf))>=0) out.write(buf,0,n); return out.toByteArray();
    }
    private static boolean isOldV1Signature(String name) {
        String u=name.toUpperCase(Locale.ROOT);
        if(!u.startsWith("META-INF/")) return false;
        return u.equals("META-INF/MANIFEST.MF") || u.endsWith(".SF") || u.endsWith(".RSA") || u.endsWith(".DSA") || u.endsWith(".EC");
    }

    private static final class Stats {
        int width, rupeeTable, rupeeLen, owlText, owlLen, title, nativeFiles;
    }

    public static void main(String[] args) throws Exception {
        if(args.length!=2) {
            System.err.println("Usage: java -jar apk-ru-compat-patcher.jar <official.apk> <patched-unsigned.apk>");
            System.exit(2);
        }
        Path in=Paths.get(args[0]), out=Paths.get(args[1]);
        if(!Files.isRegularFile(in)) throw new FileNotFoundException(in.toString());
        Files.createDirectories(out.toAbsolutePath().getParent());

        byte[] wOld=floatsLE(WIDTHS_STOCK), wNew=floatsLE(WIDTHS_RU);
        Stats st=new Stats();

        try(ZipFile zf=new ZipFile(in.toFile()); ZipOutputStream zos=new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(out)))) {
            Enumeration<? extends ZipEntry> en=zf.entries();
            while(en.hasMoreElements()) {
                ZipEntry src=en.nextElement(); String name=src.getName();
                if(isOldV1Signature(name)) continue;
                byte[] data;
                try(InputStream is=zf.getInputStream(src)) { data=readAll(is); }

                boolean nativeArm64=name.startsWith("lib/arm64-v8a/") && name.endsWith(".so");
                if(nativeArm64) {
                    st.nativeFiles++;
                    st.width += replaceAll(data,wOld,wNew);
                    st.rupeeTable += replaceAll(data,RUPEE_TABLE_STOCK,RUPEE_TABLE_RU);
                    st.rupeeLen += replaceAll(data,RUPEE_LENGTHS_STOCK,RUPEE_LENGTHS_RU);
                    st.owlText += replaceAll(data,OWL_TEXT_STOCK,OWL_TEXT_RU);
                    st.owlLen += replaceAll(data,OWL_LEN_STOCK,OWL_LEN_RU);
                    st.title += replaceAll(data,TITLE_STOCK,TITLE_RU);
                }

                ZipEntry dst=new ZipEntry(name);
                dst.setTime(src.getTime());
                if(src.getComment()!=null) dst.setComment(src.getComment());
                if(src.getExtra()!=null) dst.setExtra(src.getExtra());
                dst.setMethod(src.getMethod());
                if(src.getMethod()==ZipEntry.STORED) {
                    CRC32 crc=new CRC32(); crc.update(data);
                    dst.setSize(data.length); dst.setCompressedSize(data.length); dst.setCrc(crc.getValue());
                }
                zos.putNextEntry(dst); zos.write(data); zos.closeEntry();
            }
        }

        System.out.println("ARM64 native libraries scanned: "+st.nativeFiles);
        System.out.println("PATCH font_width_table="+st.width);
        System.out.println("PATCH rupee_text_table="+st.rupeeTable);
        System.out.println("PATCH rupee_length_table="+st.rupeeLen);
        System.out.println("PATCH owl_warp_text_table="+st.owlText);
        System.out.println("PATCH owl_warp_length_table="+st.owlLen);
        System.out.println("PATCH title_press_start_table="+st.title);

        if(st.width!=1) {
            Files.deleteIfExists(out);
            throw new IllegalStateException("Critical Russian width table signature count is "+st.width+" (expected exactly 1). Refusing to produce an APK.");
        }
        if(st.title!=1) {
            Files.deleteIfExists(out);
            throw new IllegalStateException("Title table signature count is "+st.title+" (expected exactly 1 in the pinned official APK). Refusing to produce an APK.");
        }
        // Optional patches are intentionally not fatal: compiler/linker layout can change,
        // while the width-table correction is the mandatory compatibility fix.
        System.out.println("OK: critical Russian text-spacing compatibility patch applied; optional Russian-fork data tables were attempted.");
    }
}
