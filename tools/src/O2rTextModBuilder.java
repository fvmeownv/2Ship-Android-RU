import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/**
 * Builds the Russian text mod (dialogue table + font) directly from the user's own
 * Zelda64Rus-patched ROM, so no third-party .o2r file is needed.
 *
 * Message resource layout, derived and verified against a stock mm.o2r:
 *   0x50 resource header (copied from stock), then a 5-byte prefix, then per message:
 *     u16 textId (LE) | 10-byte header | u32 text length (LE) | text ending with 0xBF
 * In the ROM the same message is stored as an 11-byte header followed by the text, with the
 * next header aligned to 4 bytes. The two header forms differ: the ROM keeps its 16-bit fields
 * big-endian and packs one byte where the resource keeps a 16-bit value, hence convertHeader().
 *
 * Correctness is not assumed: with a clean ROM as input this builder reproduces the stock
 * message resource byte for byte, which is asserted at run time before the RU build is written.
 */
public final class O2rTextModBuilder {
    private static final int DMA_OFFSET = 0x1A500, DMA_COUNT = 1552, HDR = 0x50;
    private static final int GLYPH = 128, FIRST_GLYPH_CODE = 0x20, PROBE_CODE = 0x41;
    private static final String STOCK_SHA1 = "d6133ace5afaa0882cf214cf88daba39e266c078";
    private static final String RU_SHA1 = "f01bbd2d7f633dde6581c4099a28a8f3fff8ed07";
    private static final String MSG = "text/message_data_static/message_data_static";

    static long u32be(byte[] a,int o){ return ((long)(a[o]&255)<<24)|((a[o+1]&255)<<16)|((a[o+2]&255)<<8)|(a[o+3]&255); }
    static int u16le(byte[] a,int o){ return (a[o]&255)|((a[o+1]&255)<<8); }
    static int u32le(byte[] a,int o){ return (a[o]&255)|((a[o+1]&255)<<8)|((a[o+2]&255)<<16)|((a[o+3]&255)<<24); }
    static void putU16le(ByteArrayOutputStream b,int v){ b.write(v&255); b.write((v>>8)&255); }
    static void putU32le(ByteArrayOutputStream b,int v){ b.write(v&255); b.write((v>>8)&255); b.write((v>>16)&255); b.write((v>>24)&255); }
    static byte[] all(InputStream in) throws IOException {
        ByteArrayOutputStream o=new ByteArrayOutputStream(); byte[] b=new byte[1<<16]; int n;
        while((n=in.read(b))>=0) o.write(b,0,n); return o.toByteArray(); }
    static String sha1(Path p) throws Exception {
        MessageDigest md=MessageDigest.getInstance("SHA-1");
        try(InputStream in=Files.newInputStream(p)){ byte[] b=new byte[1<<20]; int n; while((n=in.read(b))>0) md.update(b,0,n); }
        StringBuilder s=new StringBuilder(); for(byte b:md.digest()) s.append(String.format("%02x",b&255)); return s.toString(); }

    static byte[] yaz0(byte[] src,int off) throws IOException {
        int size=(int)u32be(src,off+4);
        byte[] out=new byte[size]; int sp=off+16,dp=0,bits=0,code=0;
        while(dp<size){
            if(bits==0){ code=src[sp++]&255; bits=8; }
            if((code&0x80)!=0) out[dp++]=src[sp++];
            else{
                int b1=src[sp++]&255,b2=src[sp++]&255;
                int dist=((b1&15)<<8)|b2, from=dp-dist-1, cnt=b1>>>4;
                if(cnt==0) cnt=(src[sp++]&255)+0x12; else cnt+=2;
                if(from<0) throw new IOException("bad Yaz0 distance");
                while(cnt-->0&&dp<size) out[dp++]=out[from++];
            }
            code=(code<<1)&255; bits--;
        }
        return out;
    }
    static byte[][] dmaFiles(byte[] rom) throws IOException {
        byte[][] out=new byte[DMA_COUNT][];
        for(int i=0;i<DMA_COUNT;i++){
            int o=DMA_OFFSET+i*16;
            long vs=u32be(rom,o), ve=u32be(rom,o+4), rs=u32be(rom,o+8), re=u32be(rom,o+12);
            if(rs==0xFFFFFFFFL) continue;
            int size=(int)(ve-vs);
            if(re==0){ out[i]=Arrays.copyOfRange(rom,(int)rs,(int)rs+size); continue; }
            byte[] blob=Arrays.copyOfRange(rom,(int)rs,(int)re);
            out[i]= (blob.length>=4&&blob[0]=='Y'&&blob[1]=='a'&&blob[2]=='z'&&blob[3]=='0') ? yaz0(blob,0)
                  : (blob.length==size? blob : null);
        }
        return out;
    }
    static int indexOf(byte[] hay,byte[] needle,int from){
        outer:
        for(int i=Math.max(0,from);i<=hay.length-needle.length;i++){
            for(int j=0;j<needle.length;j++) if(hay[i+j]!=needle[j]) continue outer;
            return i;
        }
        return -1;
    }
    /** ROM header (11 bytes) -> resource header (10 bytes): one byte widened, 16-bit fields byte-swapped. */
    static byte[] convertHeader(byte[] h,int o){
        return new byte[]{ h[o], h[o+1], h[o+2], 0, h[o+4], h[o+3], h[o+6], h[o+5], h[o+8], h[o+7] };
    }
    static final class Msg { final byte[] hdr; final byte[] text; Msg(byte[] h,byte[] t){hdr=h;text=t;} }

    /** Walks messages stored back to back: 11-byte header, text up to and including 0xBF, 4-byte alignment. */
    static List<Msg> walkRom(byte[] f,int first,int limit){
        List<Msg> out=new ArrayList<>(); int o=first;
        while(o+12<=f.length && out.size()<limit){
            byte[] h=Arrays.copyOfRange(f,o,o+11);
            int t=o+11, k=-1;
            for(int i=t;i<f.length;i++) if(f[i]==(byte)0xBF){ k=i; break; }
            if(k<0) break;
            out.add(new Msg(h,Arrays.copyOfRange(f,t,k+1)));
            o=(k+1+3)&~3;
        }
        return out;
    }
    static byte[] buildPayload(byte[] prefix,List<Msg> msgs,int[] ids){
        ByteArrayOutputStream b=new ByteArrayOutputStream();
        b.write(prefix,0,prefix.length);
        for(int i=0;i<ids.length;i++){
            Msg m=msgs.get(i);
            putU16le(b,ids[i]);
            b.write(convertHeader(m.hdr,0),0,10);
            putU32le(b,m.text.length);
            b.write(m.text,0,m.text.length);
        }
        return b.toByteArray();
    }
    static void put(ZipOutputStream z,String n,byte[] d) throws IOException {
        ZipEntry e=new ZipEntry(n); e.setTime(0); z.putNextEntry(e); z.write(d); z.closeEntry(); }

    public static void main(String[] a) throws Exception {
        if(a.length<4){ System.err.println("Usage: text-mod-builder <clean.z64> <rus.z64> <stock-mm.o2r> <out.o2r> [report.txt]"); System.exit(2); }
        Path clean=Paths.get(a[0]), ru=Paths.get(a[1]), stockO2r=Paths.get(a[2]), out=Paths.get(a[3]);
        Path report=a.length>4?Paths.get(a[4]):null;
        String h1=sha1(clean), h2=sha1(ru);
        if(!h1.equals(STOCK_SHA1)) throw new IllegalStateException("Clean ROM SHA-1 mismatch: "+h1);
        if(!h2.equals(RU_SHA1)) throw new IllegalStateException("Zelda64Rus ROM SHA-1 mismatch: "+h2);

        byte[] stockMsgRaw, version, portVersion;
        Map<String,byte[]> stockGlyphs=new TreeMap<>();
        try(ZipFile z=new ZipFile(stockO2r.toFile())){
            if(z.getEntry(MSG)==null) throw new IllegalStateException("stock mm.o2r has no message resource");
            try(InputStream in=z.getInputStream(z.getEntry(MSG))){ stockMsgRaw=all(in); }
            try(InputStream in=z.getInputStream(z.getEntry("version"))){ version=all(in); }
            try(InputStream in=z.getInputStream(z.getEntry("portVersion"))){ portVersion=all(in); }
            Enumeration<? extends ZipEntry> en=z.entries();
            while(en.hasMoreElements()){ ZipEntry e=en.nextElement();
                if(e.getName().startsWith("nes_font_static/")){ try(InputStream in=z.getInputStream(e)){ stockGlyphs.put(e.getName(),all(in)); } } }
        }
        if(stockGlyphs.isEmpty()) throw new IllegalStateException("stock mm.o2r has no font glyphs");

        // parse the stock resource: ids, prefix and the reference messages
        byte[] pay=Arrays.copyOfRange(stockMsgRaw,HDR,stockMsgRaw.length);
        List<int[]> spans=new ArrayList<>(); List<byte[]> stockHdrs=new ArrayList<>(), stockTexts=new ArrayList<>();
        List<Integer> idList=new ArrayList<>();
        int o=5;
        while(o+16<=pay.length){
            int id=u16le(pay,o); byte[] hd=Arrays.copyOfRange(pay,o+2,o+12);
            int len=u32le(pay,o+12);
            if(len<0||o+16+len>pay.length) break;
            idList.add(id); stockHdrs.add(hd); stockTexts.add(Arrays.copyOfRange(pay,o+16,o+16+len));
            spans.add(new int[]{o,len}); o+=16+len;
        }
        if(o!=pay.length) throw new IllegalStateException("Unexpected message resource layout");
        int[] ids=new int[idList.size()];
        for(int i=0;i<ids.length;i++) ids[i]=idList.get(i);

        byte[][] cf=dmaFiles(Files.readAllBytes(clean)), rf=dmaFiles(Files.readAllBytes(ru));

        // locate the message file and the offset of the first header
        int msgIdx=-1, firstHdr=-1;
        byte[] probe=stockTexts.get(0);
        for(int i=0;i<DMA_COUNT;i++){
            if(cf[i]==null||cf[i].length<1024) continue;
            int p=indexOf(cf[i],probe,0);
            if(p>=11){ msgIdx=i; firstHdr=p-11; break; }
        }
        if(msgIdx<0) throw new IllegalStateException("Message file not found in ROM");
        if(rf[msgIdx]==null) throw new IllegalStateException("Message file missing in RU ROM");

        // self-check: the same procedure on the clean ROM must reproduce the stock resource exactly
        List<Msg> cm=walkRom(cf[msgIdx],firstHdr,ids.length);
        if(cm.size()<ids.length) throw new IllegalStateException("Clean ROM walk produced "+cm.size()+" messages");
        byte[] check=buildPayload(Arrays.copyOfRange(pay,0,5),cm,ids);
        if(!Arrays.equals(check,pay)) throw new IllegalStateException("Self-check failed: message walk does not reproduce the stock resource");

        List<Msg> rm=walkRom(rf[msgIdx],firstHdr,ids.length);
        if(rm.size()<ids.length) throw new IllegalStateException("RU ROM walk produced only "+rm.size()+" messages");
        byte[] ruPayload=buildPayload(Arrays.copyOfRange(pay,0,5),rm,ids);
        byte[] msgOut=new byte[HDR+ruPayload.length];
        System.arraycopy(stockMsgRaw,0,msgOut,0,HDR);
        System.arraycopy(ruPayload,0,msgOut,HDR,ruPayload.length);

        // locate the font file: probe with a glyph that is certainly not blank
        String probeName=null;
        for(String n:stockGlyphs.keySet()) if(n.contains(String.format("gMsgChar%02X",PROBE_CODE))){ probeName=n; break; }
        if(probeName==null) throw new IllegalStateException("Font probe glyph not found");
        byte[] pg=Arrays.copyOfRange(stockGlyphs.get(probeName),HDR,stockGlyphs.get(probeName).length);
        int fontIdx=-1, fontBase=-1;
        for(int i=0;i<DMA_COUNT;i++){
            if(cf[i]==null||cf[i].length<GLYPH*64) continue;
            int p=indexOf(cf[i],pg,0);
            if(p>=0){ int base=p-(PROBE_CODE-FIRST_GLYPH_CODE)*GLYPH; if(base>=0){ fontIdx=i; fontBase=base; break; } }
        }
        if(fontIdx<0) throw new IllegalStateException("Font file not found in ROM");

        int verified=0, changed=0;
        Map<String,byte[]> outGlyphs=new TreeMap<>();
        for(Map.Entry<String,byte[]> e:stockGlyphs.entrySet()){
            String n=e.getKey();
            int idx=n.indexOf("gMsgChar"); if(idx<0) continue;
            int code=Integer.parseInt(n.substring(idx+8,idx+10),16);
            int off=fontBase+(code-FIRST_GLYPH_CODE)*GLYPH;
            if(off<0||off+GLYPH>cf[fontIdx].length||off+GLYPH>rf[fontIdx].length) continue;
            byte[] stockPix=Arrays.copyOfRange(e.getValue(),HDR,e.getValue().length);
            if(stockPix.length!=GLYPH) continue;
            if(!Arrays.equals(stockPix,Arrays.copyOfRange(cf[fontIdx],off,off+GLYPH)))
                throw new IllegalStateException("Font self-check failed for "+n);
            verified++;
            byte[] ruPix=Arrays.copyOfRange(rf[fontIdx],off,off+GLYPH);
            if(Arrays.equals(stockPix,ruPix)) continue;
            byte[] full=e.getValue().clone();
            System.arraycopy(ruPix,0,full,HDR,GLYPH);
            outGlyphs.put(n,full); changed++;
        }
        if(changed<50) throw new IllegalStateException("Only "+changed+" glyphs differ; refusing to write a weak text mod");

        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.deleteIfExists(out);
        try(ZipOutputStream z=new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(out)))){
            put(z,"version",version); put(z,"portVersion",portVersion);
            for(Map.Entry<String,byte[]> e:outGlyphs.entrySet()) put(z,e.getKey(),e.getValue());
            put(z,MSG,msgOut);
        }
        StringBuilder rep=new StringBuilder("TEXT MOD BUILD REPORT v1.1.0\n");
        rep.append("clean_rom_sha1=").append(h1).append('\n').append("rus_rom_sha1=").append(h2).append('\n');
        rep.append("message_dma_index=").append(msgIdx).append(" first_header_offset=").append(firstHdr).append('\n');
        rep.append("messages=").append(ids.length).append('\n');
        rep.append("self_check_clean_rebuild=OK\n");
        rep.append("font_dma_index=").append(fontIdx).append(" font_base=").append(fontBase).append('\n');
        rep.append("glyphs_verified=").append(verified).append(" glyphs_changed=").append(changed).append('\n');
        rep.append("entries=").append(outGlyphs.size()+3).append(" bytes=").append(Files.size(out)).append('\n');
        System.out.print(rep);
        if(report!=null){ Files.createDirectories(report.toAbsolutePath().getParent()); Files.write(report,rep.toString().getBytes("UTF-8")); }
        System.out.println("OK: "+out.toAbsolutePath());
    }
}
