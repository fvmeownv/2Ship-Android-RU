import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/**
 * Makes a copy of the RU text mod in which the glyph slots of Latin Q and W carry the
 * translators' own Ж and И glyphs (slots 0x83 and 0x85 of the same font). Q and W never appear as
 * visible text in the RU dialogue data, so nothing else changes. Combined with the patched title
 * table in the APK this spells "НАЖМИ СТАРТ" on the title screen. Every other entry is copied
 * byte-for-byte.
 */
public final class O2rTitleGlyphPatcher {
    private static final String Q = "nes_font_static/gMsgChar51LatinCapitalLetterQTex";
    private static final String W = "nes_font_static/gMsgChar57LatinCapitalLetterWTex";
    private static final int HDR = 0x50;

    static byte[] all(InputStream in) throws IOException {
        ByteArrayOutputStream o=new ByteArrayOutputStream(); byte[] b=new byte[1<<16]; int n;
        while((n=in.read(b))>=0) o.write(b,0,n); return o.toByteArray(); }
    static String sha256(byte[] d) throws Exception {
        StringBuilder s=new StringBuilder();
        for(byte b:MessageDigest.getInstance("SHA-256").digest(d)) s.append(String.format("%02x",b&255));
        return s.toString(); }
    static String byPrefix(ZipFile z,String prefix){
        Enumeration<? extends ZipEntry> e=z.entries();
        while(e.hasMoreElements()){ String n=e.nextElement().getName(); if(n.startsWith(prefix)) return n; }
        return null; }

    public static void main(String[] a) throws Exception {
        if(a.length<2){ System.err.println("Usage: title-glyph-patcher <Russian_MM_5.0.1.o2r> <out.o2r>"); System.exit(2); }
        Path in=Paths.get(a[0]), out=Paths.get(a[1]);
        String h=sha256(Files.readAllBytes(in));
        Map<String,byte[]> src=new LinkedHashMap<>();
        String zh, ih;
        try(ZipFile z=new ZipFile(in.toFile())){
            zh=byPrefix(z,"nes_font_static/gMsgChar83"); ih=byPrefix(z,"nes_font_static/gMsgChar85");
            if(zh==null||ih==null||z.getEntry(Q)==null||z.getEntry(W)==null) throw new IllegalStateException("Glyph slots not found in text mod");
            Enumeration<? extends ZipEntry> e=z.entries();
            while(e.hasMoreElements()){ ZipEntry ze=e.nextElement(); if(ze.isDirectory()) continue;
                try(InputStream is=z.getInputStream(ze)){ src.put(ze.getName(),all(is)); } }
        }
        byte[] q=src.get(Q), w=src.get(W), zg=src.get(zh), ig=src.get(ih);
        for(byte[][] pr:new byte[][][]{{q,zg},{w,ig}}){
            if(pr[0].length!=pr[1].length) throw new IllegalStateException("Glyph size mismatch");
            for(int o=0x40;o<HDR;o++) if(pr[0][o]!=pr[1][o]) throw new IllegalStateException("Glyph format mismatch");
        }
        byte[] nq=q.clone(), nw=w.clone();
        System.arraycopy(zg,HDR,nq,HDR,zg.length-HDR);
        System.arraycopy(ig,HDR,nw,HDR,ig.length-HDR);
        src.put(Q,nq); src.put(W,nw);
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.deleteIfExists(out);
        try(ZipOutputStream zos=new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(out)))){
            for(Map.Entry<String,byte[]> e:src.entrySet()){
                ZipEntry ze=new ZipEntry(e.getKey()); ze.setTime(0); zos.putNextEntry(ze); zos.write(e.getValue()); zos.closeEntry(); }
        }
        System.out.println("TITLE GLYPH PATCH v1.0.0");
        System.out.println("source="+in.getFileName()+" sha256="+h);
        System.out.println("Q <- "+zh);
        System.out.println("W <- "+ih);
        System.out.println("entries="+src.size()+" out_sha256="+sha256(Files.readAllBytes(out)));
        System.out.println("OK: "+out.toAbsolutePath());
    }
}
