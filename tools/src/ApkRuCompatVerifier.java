import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Verifier: multi-signal check + fingerprint report, so a recycled APK can be identified. */
public final class ApkRuCompatVerifier {
    private static final float[] STOCK = {
        8,8,6,9,9,14,12,3,7,7,7,9,4,6,4,9,10,5,9,9,10,9,9,9,9,9,6,6,9,11,9,11,
        13,12,9,11,11,8,8,12,10,4,8,10,8,13,11,13,9,13,10,10,9,10,11,15,11,10,10,7,10,7,10,9,
        5,8,9,8,9,9,6,9,8,4,6,8,4,12,9,9,9,9,7,8,7,8,9,12,8,9,8,7,5,7,10,6,
        12,12,12,12,11,8,8,8,8,6,6,6,6,10,13,13,13,13,10,10,10,10,9,8,8,8,8,8,9,9,9,9,
        4,4,4,4,8,9,9,9,9,8,8,8,8,8,11,6,14,14,14,14,14,14,14,14,14,14,14,14,14,14,14,14 };
    private static final float[] RU = {
        8,8,6,9,9,14,12,3,7,7,7,9,4,6,4,9,10,5,9,9,10,9,9,9,9,9,6,6,9,11,9,11,
        13,11,9,11,11,8,8,12,10,4,8,10,8,13,11,13,9,13,10,10,9,10,11,15,11,10,10,7,10,7,10,9,
        5,8,9,8,9,9,6,9,8,4,6,8,4,12,9,9,9,9,7,8,7,8,9,12,8,9,8,7,5,7,10,9,
        8,12,7,12,9,10,9,10,11,10,10,11,10,10,13,14,11,12,8,11,12,9,8,7,7,9,8,11,7,8,7,7,
        7,11,8,8,7,11,8,7,11,11,8,10,7,8,11,8,14,14,14,14,14,14,14,14,14,14,14,14,14,14,14,14 };

    static byte[] floats(float[] a){ ByteBuffer b=ByteBuffer.allocate(a.length*4).order(ByteOrder.LITTLE_ENDIAN);
        for(float v:a) b.putFloat(v); return b.array(); }
    static byte[] ascii(String s){ return s.getBytes(java.nio.charset.StandardCharsets.US_ASCII); }
    static byte[] cat(byte[] a, byte[] b){ byte[] x=Arrays.copyOf(a,a.length+b.length); System.arraycopy(b,0,x,a.length,b.length); return x; }
    static int count(byte[] d,byte[] n){ int c=0; outer:
        for(int i=0;i<=d.length-n.length;i++){ for(int j=0;j<n.length;j++) if(d[i+j]!=n[j]) continue outer; c++; i+=n.length-1; } return c; }
    static byte[] all(InputStream in) throws IOException {
        ByteArrayOutputStream o=new ByteArrayOutputStream(); byte[] b=new byte[1<<20]; int n;
        while((n=in.read(b))>=0) o.write(b,0,n); return o.toByteArray(); }
    static String sha256(byte[] d) throws Exception {
        StringBuilder s=new StringBuilder();
        for(byte b:MessageDigest.getInstance("SHA-256").digest(d)) s.append(String.format("%02x",b&255));
        return s.toString(); }

    public static void main(String[] args) throws Exception {
        if(args.length<1){ System.err.println("Usage: verifier <apk> [report.txt]"); System.exit(2); }
        Path p=Paths.get(args[0]);
        Path report = args.length>1 ? Paths.get(args[1]) : null;
        byte[] r=floats(RU), s=floats(STOCK);
        byte[] rupeeRu = {0x70,0x79,(byte)0xA3,(byte)0x9D,(byte)0x9E};
        byte[] otr = ascii("__OTR__");
        byte[] titleRu = cat(new byte[]{0x11,0x0A,0x1A,0x16,0x20,0x0C,0x1D,0x0A,0x19,0x1D}, otr);
        byte[] titleSt = cat(new byte[]{0x19,0x1B,0x0E,0x1C,0x1C,0x1C,0x1D,0x0A,0x1B,0x1D}, otr);
        int tru=0, tst=0;
        int ru=0, stock=0, libs=0, rupee=0;
        StringBuilder rep=new StringBuilder("APK RU VERIFIER REPORT v1.0.0\n");
        rep.append("apk=").append(p.getFileName()).append(" bytes=").append(Files.size(p)).append('\n');
        rep.append("apk_sha256=").append(sha256(Files.readAllBytes(p))).append('\n');
        try(ZipFile z=new ZipFile(p.toFile())){
            List<String> names=new ArrayList<>();
            Enumeration<? extends ZipEntry> e=z.entries();
            while(e.hasMoreElements()){ ZipEntry q=e.nextElement(); names.add(q.getName()); }
            Collections.sort(names);
            for(String n:names){
                if(!(n.startsWith("lib/arm64-v8a/")&&n.endsWith(".so"))) continue;
                libs++;
                byte[] d; try(InputStream in=z.getInputStream(z.getEntry(n))){ d=all(in); }
                int cru=count(d,r), cst=count(d,s), crup=count(d,rupeeRu);
                int ctr=count(d,titleRu), cts=count(d,titleSt); tru+=ctr; tst+=cts;
                ru+=cru; stock+=cst; rupee+=crup;
                rep.append("lib=").append(n).append(" bytes=").append(d.length)
                   .append(" sha256=").append(sha256(d))
                   .append(" ru_width=").append(cru).append(" stock_width=").append(cst)
                   .append(" ru_rupee=").append(crup)
                   .append(" ru_title=").append(ctr).append(" stock_title=").append(cts).append('\n');
            }
        }
        rep.append("TOTAL arm64_libs=").append(libs).append(" RU_WIDTH_TABLE=").append(ru)
           .append(" STOCK_WIDTH_TABLE=").append(stock).append(" RU_RUPEE_TABLE=").append(rupee)
           .append(" RU_TITLE_TABLE=").append(tru).append(" STOCK_TITLE_TABLE=").append(tst).append('\n');
        boolean ok = (ru==1 && stock==0 && tru==1 && tst==0);
        rep.append("VERDICT=").append(ok?"OK":"FAIL").append('\n');
        System.out.print(rep);
        if(report!=null){ Files.createDirectories(report.toAbsolutePath().getParent());
            Files.write(report,rep.toString().getBytes("UTF-8")); }
        if(!ok) throw new IllegalStateException("APK is not the expected RU build (width + title tables missing)");
        System.out.println("OK: Russian font-width table and Russian title table present.");
    }
}
