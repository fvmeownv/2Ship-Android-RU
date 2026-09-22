import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/**
 * Zelda64Rus graphics overlay builder.
 *
 * YAR pass. MM keeps item names (item_name_static) and map point names
 * (map_name_static) inside Yaz0 archives where every texture is compressed on its own, so their
 * bytes never appear verbatim in the decompressed DMA file and the anchor pass cannot see them.
 * The YAR pass unpacks both ROMs' archives entry by entry and maps each changed entry to the
 * stock O2R resource whose payload is byte-identical to the clean-ROM entry. This is an exact
 * mapping, not a heuristic. Collisions are now reported instead of dropped (--strict-collisions
 * restores the v5.0 behaviour).
 *
 * Same conservative approach as v4.1 (compare clean USA 1.0 ROM against the Zelda64Rus ROM,
 * substitute matching texture payloads inside a stock 2Ship mm.o2r), plus three new safety nets
 * that exist specifically to stop the v4.1 "wrong do_action label" class of defect:
 *
 *  1. COLLISION  - if two different stock resources end up with byte-identical replacements,
 *                  the RU ROM layout has shifted under us and both slots are dropped.
 *  2. CROSSMATCH - if a replacement equals the stock payload of a DIFFERENT resource,
 *                  we grabbed a neighbouring slot; the slot is dropped.
 *  3. REPORT     - every decision is written to a machine-diffable report so two builds
 *                  can be compared directly.
 */
public final class O2rGraphicsOverlayBuilder {
    private static final int DMA_OFFSET = 0x1A500;
    private static final int DMA_COUNT = 1552;
    private static final int TEX_HEADER = 0x50;
    private static final String STOCK_SHA1 = "d6133ace5afaa0882cf214cf88daba39e266c078";
    private static final String RU_SHA1 = "f01bbd2d7f633dde6581c4099a28a8f3fff8ed07";

    static final class DmaEntry {
        final long vStart, vEnd, rStart, rEnd;
        DmaEntry(long a,long b,long c,long d){vStart=a;vEnd=b;rStart=c;rEnd=d;}
        long vSize(){return vEnd-vStart;}
        boolean absent(){return rStart==0xFFFFFFFFL;}
    }
    static final class Pair { final int index; final byte[] stock, rus;
        Pair(int i, byte[] s, byte[] r){index=i;stock=s;rus=r;} }
    static final class Candidate {
        final String name; final byte[] full, payload; final long anchor; final int anchorOffset;
        final List<byte[]> replacements = new ArrayList<>();
        String drop;
        Candidate(String n, byte[] f, byte[] p, long a, int ao){name=n;full=f;payload=p;anchor=a;anchorOffset=ao;}
    }

    private static long u32be(byte[] a,int o){
        return ((long)(a[o]&255)<<24)|((long)(a[o+1]&255)<<16)|((long)(a[o+2]&255)<<8)|(long)(a[o+3]&255);
    }
    private static long longBE(byte[] a,int o){ long v=0; for(int i=0;i<8;i++) v=(v<<8)|(a[o+i]&255L); return v; }
    private static String hex(byte[] d){ StringBuilder s=new StringBuilder(); for(byte b:d) s.append(String.format("%02x",b&255)); return s.toString(); }
    private static String sha256(byte[] d) throws Exception { return hex(MessageDigest.getInstance("SHA-256").digest(d)); }
    private static String sha1(Path p) throws Exception {
        MessageDigest md=MessageDigest.getInstance("SHA-1");
        try(InputStream in=Files.newInputStream(p)){ byte[] b=new byte[1<<20]; int n; while((n=in.read(b))>0) md.update(b,0,n); }
        return hex(md.digest());
    }
    private static DmaEntry[] dma(byte[] rom){
        DmaEntry[] out=new DmaEntry[DMA_COUNT];
        for(int i=0;i<DMA_COUNT;i++){ int o=DMA_OFFSET+i*16;
            out[i]=new DmaEntry(u32be(rom,o),u32be(rom,o+4),u32be(rom,o+8),u32be(rom,o+12)); }
        return out;
    }
    private static byte[] slice(byte[] a,int s,int e){return Arrays.copyOfRange(a,s,e);}
    private static byte[] yaz0(byte[] src,int expected) throws IOException { return yaz0(src,0,expected); }
    private static byte[] yaz0(byte[] src,int off,int expected) throws IOException {
        if(off<0||src.length<off+16||src[off]!='Y'||src[off+1]!='a'||src[off+2]!='z'||src[off+3]!='0') throw new IOException("Expected Yaz0 block");
        int size=(int)u32be(src,off+4);
        if(size<0||size>64*1024*1024) throw new IOException("Yaz0 size implausible");
        if(expected>0&&size!=expected) throw new IOException("Yaz0 size mismatch");
        byte[] out=new byte[size]; int sp=off+16,dp=0,bits=0,code=0;
        while(dp<size){
            if(bits==0){ if(sp>=src.length) throw new EOFException("Yaz0 code"); code=src[sp++]&255; bits=8; }
            if((code&0x80)!=0){ if(sp>=src.length) throw new EOFException("Yaz0 literal"); out[dp++]=src[sp++]; }
            else {
                if(sp+1>=src.length) throw new EOFException("Yaz0 pair");
                int b1=src[sp++]&255,b2=src[sp++]&255;
                int dist=((b1&15)<<8)|b2, from=dp-dist-1, count=b1>>>4;
                if(count==0){ if(sp>=src.length) throw new EOFException("Yaz0 len"); count=(src[sp++]&255)+0x12; } else count+=2;
                if(from<0) throw new IOException("Yaz0 bad distance");
                while(count-->0&&dp<size) out[dp++]=out[from++];
            }
            code=(code<<1)&255; bits--;
        }
        return out;
    }
    private static byte[] dmaFile(byte[] rom,DmaEntry e) throws IOException {
        if(e.absent()) return null;
        int vs=(int)e.vSize(), rs=(int)e.rStart;
        if(e.rEnd==0){ if(rs<0||rs+vs>rom.length) throw new IOException("DMA raw out of ROM"); return slice(rom,rs,rs+vs); }
        int re=(int)e.rEnd;
        if(rs<0||re<rs||re>rom.length) throw new IOException("DMA compressed out of ROM");
        byte[] c=slice(rom,rs,re);
        if(c.length>=4&&c[0]=='Y'&&c[1]=='a'&&c[2]=='z'&&c[3]=='0') return yaz0(c,vs);
        if(c.length==vs) return c;
        throw new IOException("Unknown DMA encoding at 0x"+Long.toHexString(e.rStart));
    }
    /** MM Yaz0 archive: word0 = header size H; entry k lives at H + word_k (entry 0 at H);
     *  the last word marks the end, so there are H/4 - 1 entries. Returns null if not a YAR. */
    private static List<byte[]> yar(byte[] f){
        try{
            if(f==null||f.length<16) return null;
            long H=u32be(f,0);
            if(H<8||H%4!=0||H>=f.length||H>0x4000) return null;
            int n=(int)(H/4);
            List<byte[]> out=new ArrayList<>();
            for(int k=0;k<n-1;k++){
                long o = k==0 ? 0 : u32be(f,k*4);
                long a = H+o;
                if(a<0||a+16>f.length) return null;
                int ai=(int)a;
                if(f[ai]!='Y'||f[ai+1]!='a'||f[ai+2]!='z'||f[ai+3]!='0') return null;
                out.add(yaz0(f,ai,0));
            }
            return out.isEmpty()?null:out;
        }catch(Exception e){ return null; }
    }
    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream o=new ByteArrayOutputStream(); byte[] b=new byte[1<<20]; int n;
        while((n=in.read(b))>=0) o.write(b,0,n); return o.toByteArray();
    }
    private static boolean eqAt(byte[] hay,int pos,byte[] needle){
        if(pos<0||pos+needle.length>hay.length) return false;
        for(int i=0;i<needle.length;i++) if(hay[pos+i]!=needle[i]) return false;
        return true;
    }
    private static int anchorScore(byte[] p,int o){
        boolean[] seen=new boolean[256]; int distinct=0,nonzero=0,trans=0,prev=-1;
        for(int i=0;i<8;i++){ int v=p[o+i]&255; if(!seen[v]){seen[v]=true;distinct++;}
            if(v!=0&&v!=255)nonzero++; if(prev>=0&&v!=prev)trans++; prev=v; }
        return distinct*20+nonzero*4+trans;
    }
    private static int bestAnchorOffset(byte[] p){
        if(p.length<8) return -1;
        int best=-1,bestScore=-1;
        for(int o=0;o<=p.length-8;o++){ int s=anchorScore(p,o); if(s>bestScore){bestScore=s;best=o;} }
        return bestScore<45?-1:best;
    }
    private static void putZip(ZipOutputStream z,String name,byte[] d) throws IOException {
        ZipEntry e=new ZipEntry(name); e.setTime(0); z.putNextEntry(e); z.write(d); z.closeEntry();
    }

    public static void main(String[] args) throws Exception {
        if(args.length<4){
            System.err.println("Usage: builder <clean.z64> <rus.z64> <stock-mm.o2r> <out.o2r> [report.txt] [--exclude=a,b] [--strict-collisions]");
            System.exit(2);
        }
        Path stockRom=Paths.get(args[0]), ruRom=Paths.get(args[1]), stockO2r=Paths.get(args[2]), out=Paths.get(args[3]);
        Path report = (args.length>4 && !args[4].startsWith("--")) ? Paths.get(args[4]) : null;
        List<String> excludes=new ArrayList<>(Arrays.asList("nes_font_static/","text/"));
        boolean strictCollisions=false;
        for(String a:args){
            if(a.startsWith("--exclude=")) for(String s:a.substring(10).split(",")) { if(!s.isEmpty()) excludes.add(s); }
            if(a.equals("--strict-collisions")) strictCollisions=true;
        }

        String hs=sha1(stockRom), hr=sha1(ruRom);
        if(!hs.equals(STOCK_SHA1)) throw new IllegalStateException("Clean ROM SHA-1 mismatch: "+hs);
        if(!hr.equals(RU_SHA1)) throw new IllegalStateException("Zelda64Rus ROM SHA-1 mismatch: "+hr);
        if(!Files.isRegularFile(stockO2r)||Files.size(stockO2r)<1024*1024) throw new IllegalStateException("stock mm.o2r missing or too small");

        byte[] srom=Files.readAllBytes(stockRom), rrom=Files.readAllBytes(ruRom);
        DmaEntry[] sd=dma(srom), rd=dma(rrom);
        List<Pair> changed=new ArrayList<>(); int layoutMismatch=0;
        Map<String,byte[]> yarMap=new HashMap<>();      // sha256(clean entry) -> RU entry
        Set<String> yarAmbiguous=new HashSet<>();
        int yarArchives=0, yarEntriesChanged=0;
        List<String> yarArchiveLines=new ArrayList<>();
        for(int i=0;i<DMA_COUNT;i++){
            DmaEntry a=sd[i],b=rd[i];
            if(a.vStart!=b.vStart||a.vEnd!=b.vEnd||a.rStart!=b.rStart||a.rEnd!=b.rEnd) layoutMismatch++;
            if(a.absent()) continue;
            byte[] x=dmaFile(srom,a), y=dmaFile(rrom,b);
            if(x!=null&&!Arrays.equals(x,y)){
                changed.add(new Pair(i,x,y));
                List<byte[]> ys=yar(x), yr=yar(y);
                if(ys!=null&&yr!=null&&ys.size()==yr.size()){
                    yarArchives++; int ch=0;
                    for(int k=0;k<ys.size();k++){
                        byte[] ea=ys.get(k), eb=yr.get(k);
                        if(Arrays.equals(ea,eb)||ea.length!=eb.length) continue;
                        ch++; String h=sha256(ea);
                        byte[] prev=yarMap.putIfAbsent(h,eb);
                        if(prev!=null&&!Arrays.equals(prev,eb)) yarAmbiguous.add(h);
                    }
                    yarEntriesChanged+=ch;
                    yarArchiveLines.add("DMA "+i+": entries="+ys.size()+" changed="+ch);
                }
            }
        }
        if(layoutMismatch!=0) throw new IllegalStateException("DMA layout differs; refusing unsafe overlay generation");
        if(changed.size()<5) throw new IllegalStateException("Unexpectedly few changed DMA files: "+changed.size());

        Map<Long,List<Candidate>> anchors=new HashMap<>();
        Map<String,byte[]> passThrough=new LinkedHashMap<>();
        List<Candidate> candidates=new ArrayList<>();
        Map<String,String> stockPayloadOwner=new HashMap<>();
        List<String> excludedNames=new ArrayList<>(), lowInfoNames=new ArrayList<>();
        Map<String,byte[]> yarOut=new LinkedHashMap<>();
        List<String> yarAmbiguousNames=new ArrayList<>();
        int zipEntries=0,xeto=0;
        try(ZipFile zf=new ZipFile(stockO2r.toFile())){
            Enumeration<? extends ZipEntry> en=zf.entries();
            while(en.hasMoreElements()){
                ZipEntry ze=en.nextElement(); if(ze.isDirectory()) continue; zipEntries++;
                byte[] d; try(InputStream in=zf.getInputStream(ze)){ d=readAll(in); }
                String n=ze.getName();
                if(n.equals("version")||n.equals("portVersion")){ passThrough.put(n,d); continue; }
                if(d.length<TEX_HEADER+32||d[4]!='X'||d[5]!='E'||d[6]!='T'||d[7]!='O') continue;
                xeto++;
                boolean skip=false;
                for(String ex:excludes) if(n.startsWith(ex)){ skip=true; break; }
                if(skip){ excludedNames.add(n); continue; }
                byte[] p=Arrays.copyOfRange(d,TEX_HEADER,d.length);
                String ph=sha256(p);
                if(yarMap.containsKey(ph)){
                    if(yarAmbiguous.contains(ph)){ yarAmbiguousNames.add(n); continue; }
                    byte[] rp=yarMap.get(ph);
                    byte[] od=d.clone();
                    System.arraycopy(rp,0,od,TEX_HEADER,rp.length);
                    yarOut.put(n,od);
                    continue;
                }
                stockPayloadOwner.put(ph,n);
                int ao=bestAnchorOffset(p);
                if(ao<0){ lowInfoNames.add(n); continue; }
                Candidate c=new Candidate(n,d,p,longBE(p,ao),ao);
                candidates.add(c);
                anchors.computeIfAbsent(c.anchor,k->new ArrayList<>()).add(c);
            }
        }
        if(!passThrough.containsKey("version")||!passThrough.containsKey("portVersion"))
            throw new IllegalStateException("stock O2R lacks version metadata");

        for(Pair pair:changed){
            byte[] s=pair.stock, r=pair.rus;
            for(int pos=0;pos<=s.length-8;pos++){
                List<Candidate> list=anchors.get(longBE(s,pos)); if(list==null) continue;
                for(Candidate c:list){
                    int start=pos-c.anchorOffset;
                    if(!eqAt(s,start,c.payload)) continue;
                    if(start<0||start+c.payload.length>r.length) continue;
                    byte[] repl=Arrays.copyOfRange(r,start,start+c.payload.length);
                    if(!Arrays.equals(repl,c.payload)) c.replacements.add(repl);
                }
            }
        }

        // Resolve each candidate to a single replacement, then run the v5 safety nets.
        Map<String,List<Candidate>> byReplacement=new LinkedHashMap<>();
        int ambiguous=0, unchanged=0;
        for(Candidate c:candidates){
            if(c.replacements.isEmpty()){ unchanged++; c.drop="UNCHANGED"; continue; }
            byte[] first=c.replacements.get(0); boolean allSame=true;
            for(int i=1;i<c.replacements.size();i++) if(!Arrays.equals(first,c.replacements.get(i))){ allSame=false; break; }
            if(!allSame){ ambiguous++; c.drop="AMBIGUOUS"; continue; }
            byteReplacementRegister(byReplacement,sha256(first),c);
        }
        int collision=0, crossmatch=0;
        List<String> collisionKept=new ArrayList<>();
        for(Map.Entry<String,List<Candidate>> e:byReplacement.entrySet()){
            List<Candidate> group=e.getValue();
            if(group.size()>1){
                if(strictCollisions){
                    for(Candidate c:group){ c.drop="COLLISION(with "+group.size()+" slots)"; }
                    collision+=group.size(); continue;
                }
                for(Candidate c:group) collisionKept.add("SHARED_IMAGE("+group.size()+") "+c.name);
            }
            String owner=stockPayloadOwner.get(e.getKey());
            if(owner!=null){
                for(Candidate c:group) if(!owner.equals(c.name)){ c.drop="CROSSMATCH(stock payload of "+owner+")"; crossmatch++; }
            }
        }

        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.deleteIfExists(out);
        int patched=0; List<String> patchedNames=new ArrayList<>(), droppedLines=new ArrayList<>();
        try(ZipOutputStream zos=new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(out)))){
            putZip(zos,"version",passThrough.get("version"));
            putZip(zos,"portVersion",passThrough.get("portVersion"));
            for(Map.Entry<String,byte[]> e:yarOut.entrySet()){
                putZip(zos,e.getKey(),e.getValue()); patched++; patchedNames.add(e.getKey()+"  [YAR]");
            }
            for(Candidate c:candidates){
                if(c.drop!=null){ if(!"UNCHANGED".equals(c.drop)) droppedLines.add("DROPPED "+c.drop+" "+c.name); continue; }
                byte[] first=c.replacements.get(0);
                byte[] outData=c.full.clone();
                System.arraycopy(first,0,outData,TEX_HEADER,first.length);
                putZip(zos,c.name,outData); patched++; patchedNames.add(c.name);
            }
        }

        StringBuilder rep=new StringBuilder();
        rep.append("O2R GRAPHICS OVERLAY REPORT v1.0.0\n");
        rep.append("clean_rom_sha1=").append(hs).append('\n');
        rep.append("rus_rom_sha1=").append(hr).append('\n');
        rep.append("stock_o2r=").append(stockO2r.getFileName()).append(" bytes=").append(Files.size(stockO2r)).append('\n');
        rep.append("dma_layout_mismatch=").append(layoutMismatch).append('\n');
        rep.append("dma_changed_files=").append(changed.size()).append('\n');
        rep.append("o2r_entries=").append(zipEntries).append(" xeto=").append(xeto).append('\n');
        rep.append("yar_archives_changed=").append(yarArchives).append('\n');
        rep.append("yar_entries_changed=").append(yarEntriesChanged).append('\n');
        rep.append("yar_textures_patched=").append(yarOut.size()).append('\n');
        rep.append("yar_ambiguous_skipped=").append(yarAmbiguousNames.size()).append('\n');
        rep.append("candidates=").append(candidates.size()).append('\n');
        rep.append("excluded_by_prefix=").append(excludedNames.size()).append('\n');
        rep.append("low_info_skipped=").append(lowInfoNames.size()).append('\n');
        rep.append("ambiguous_skipped=").append(ambiguous).append('\n');
        rep.append("collision_skipped=").append(collision).append('\n');
        rep.append("collision_kept_shared_image=").append(collisionKept.size()).append('\n');
        rep.append("crossmatch_skipped=").append(crossmatch).append('\n');
        rep.append("unmatched_or_unchanged=").append(unchanged).append('\n');
        rep.append("textures_patched=").append(patched).append('\n');
        rep.append("overlay_bytes=").append(Files.size(out)).append('\n');
        rep.append("overlay_sha256=").append(sha256(Files.readAllBytes(out))).append('\n');
        rep.append("\n--- YAR ARCHIVES ---\n");
        for(String l:yarArchiveLines) rep.append(l).append('\n');
        for(String n:yarAmbiguousNames) rep.append("YAR_AMBIGUOUS ").append(n).append('\n');
        rep.append("\n--- SHARED IMAGE (kept; review if a label looks wrong) ---\n");
        for(String l:collisionKept) rep.append(l).append('\n');
        rep.append("\n--- PATCHED ---\n");
        for(String n:patchedNames) rep.append(n).append('\n');
        rep.append("\n--- DROPPED (needs attention) ---\n");
        for(String l:droppedLines) rep.append(l).append('\n');
        rep.append("\n--- LOW INFO (payload too flat to anchor) ---\n");
        for(String n:lowInfoNames) rep.append(n).append('\n');
        rep.append("\n--- EXCLUDED BY PREFIX ---\n");
        for(String n:excludedNames) rep.append(n).append('\n');

        System.out.print(rep.substring(0, rep.indexOf("--- PATCHED ---")));
        System.out.println("PATCHED_COUNT="+patched+" DROPPED_COUNT="+droppedLines.size());
        if(report!=null){ Files.createDirectories(report.toAbsolutePath().getParent());
            Files.write(report,rep.toString().getBytes("UTF-8")); System.out.println("report="+report.toAbsolutePath()); }

        if(patched<5){ Files.deleteIfExists(out); throw new IllegalStateException("Only "+patched+" textures mapped; refusing weak overlay"); }
        if(Files.size(out)<1000){ Files.deleteIfExists(out); throw new IllegalStateException("Output overlay unexpectedly small"); }
        System.out.println("OK: overlay written to "+out.toAbsolutePath());
    }

    private static void byteReplacementRegister(Map<String,List<Candidate>> m,String k,Candidate c){
        m.computeIfAbsent(k,x->new ArrayList<>()).add(c);
    }
}
