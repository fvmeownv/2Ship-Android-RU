import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/**
 * Minimal VCDIFF (RFC 3284) decoder, enough to apply the Zelda64Rus xdelta3 patch.
 * The patch uses the default code table and no secondary compression, both of which are
 * asserted before decoding. xdelta3 also sets VCD_ADLER32 (0x04) in the window indicator and
 * stores a four byte window checksum after the section lengths, which is skipped here.
 * Output is verified by SHA-1 before it is written.
 */
public final class VcdiffPatcher {
    private static final int NOOP=0, ADD=1, RUN=2, COPY=3;
    private static final int NEAR=4, SAME=3;

    static final class Inst { int type,size,mode; Inst(int t,int s,int m){type=t;size=s;mode=m;} }
    private static final Inst[][] TABLE = buildTable();

    private static Inst[][] buildTable(){
        Inst[][] t=new Inst[256][]; int i=0;
        t[i++]=new Inst[]{new Inst(RUN,0,0), new Inst(NOOP,0,0)};
        for(int size=0;size<=17;size++) t[i++]=new Inst[]{new Inst(ADD,size,0), new Inst(NOOP,0,0)};
        for(int mode=0;mode<=8;mode++){
            t[i++]=new Inst[]{new Inst(COPY,0,mode), new Inst(NOOP,0,0)};
            for(int size=4;size<=18;size++) t[i++]=new Inst[]{new Inst(COPY,size,mode), new Inst(NOOP,0,0)};
        }
        for(int mode=0;mode<=5;mode++)
            for(int a=1;a<=4;a++)
                for(int c=4;c<=6;c++) t[i++]=new Inst[]{new Inst(ADD,a,0), new Inst(COPY,c,mode)};
        for(int mode=6;mode<=8;mode++)
            for(int a=1;a<=4;a++) t[i++]=new Inst[]{new Inst(ADD,a,0), new Inst(COPY,4,mode)};
        for(int mode=0;mode<=8;mode++) t[i++]=new Inst[]{new Inst(COPY,4,mode), new Inst(ADD,1,0)};
        if(i!=256) throw new IllegalStateException("code table size "+i);
        return t;
    }

    static final class Reader {
        final byte[] b; int p;
        Reader(byte[] b,int p){this.b=b;this.p=p;}
        int u8(){ return b[p++]&255; }
        long varint(){ long v=0; while(true){ int c=b[p++]&255; v=(v<<7)|(c&0x7F); if((c&0x80)==0) return v; } }
        boolean done(){ return p>=b.length; }
    }

    static final class Cache {
        final int[] near=new int[NEAR]; final int[] same=new int[SAME*256]; int next;
        int decode(Reader r,int mode,int here){
            int addr;
            if(mode==0) addr=(int)r.varint();
            else if(mode==1) addr=here-(int)r.varint();
            else if(mode<2+NEAR) addr=near[mode-2]+(int)r.varint();
            else addr=same[(mode-(2+NEAR))*256 + r.u8()];
            near[next]=addr; next=(next+1)%NEAR;
            same[addr%(SAME*256)]=addr;
            return addr;
        }
    }

    static String sha1(byte[] d) throws Exception {
        StringBuilder s=new StringBuilder();
        for(byte b:MessageDigest.getInstance("SHA-1").digest(d)) s.append(String.format("%02x",b&255));
        return s.toString();
    }

    public static byte[] apply(byte[] src, byte[] patch) throws IOException {
        if(patch.length<5||(patch[0]&255)!=0xD6||(patch[1]&255)!=0xC3||(patch[2]&255)!=0xC4)
            throw new IOException("Not a VCDIFF patch");
        if(patch[3]!=0) throw new IOException("Unsupported VCDIFF version");
        Reader r=new Reader(patch,4);
        int hdr=r.u8();
        if((hdr&1)!=0) throw new IOException("Patch uses secondary compression, unsupported");
        if((hdr&2)!=0) throw new IOException("Patch uses a custom code table, unsupported");
        if((hdr&4)!=0){ long n=r.varint(); r.p+=n; }

        ByteArrayOutputStream out=new ByteArrayOutputStream(Math.max(src.length,1<<20));
        byte[] produced=new byte[0];
        while(!r.done()){
            int win=r.u8();
            int srcLen=0, srcPos=0;
            if((win&3)!=0){ srcLen=(int)r.varint(); srcPos=(int)r.varint(); }
            r.varint();                      // delta encoding length
            int targetLen=(int)r.varint();
            if(r.u8()!=0) throw new IOException("Per-window secondary compression, unsupported");
            int dataLen=(int)r.varint(), instLen=(int)r.varint(), addrLen=(int)r.varint();
            if((win&4)!=0) r.p+=4;   // VCD_ADLER32 window checksum (xdelta3 extension)
            int dataOff=r.p, instOff=dataOff+dataLen, addrOff=instOff+instLen;
            Reader data=new Reader(patch,dataOff), inst=new Reader(patch,instOff), addr=new Reader(patch,addrOff);
            r.p=addrOff+addrLen;

            byte[] base;
            if((win&1)!=0) base=Arrays.copyOfRange(src,srcPos,srcPos+srcLen);
            else if((win&2)!=0) base=Arrays.copyOfRange(produced,srcPos,srcPos+srcLen);
            else base=new byte[0];

            byte[] tgt=new byte[targetLen]; int tp=0;
            Cache cache=new Cache();
            while(tp<targetLen){
                int code=inst.u8();
                for(Inst in:TABLE[code]){
                    if(in.type==NOOP) continue;
                    int size=in.size==0?(int)inst.varint():in.size;
                    if(in.type==ADD){ System.arraycopy(patch,data.p,tgt,tp,size); data.p+=size; tp+=size; }
                    else if(in.type==RUN){ byte v=patch[data.p++]; for(int k=0;k<size;k++) tgt[tp++]=v; }
                    else {
                        int a=cache.decode(addr,in.mode,base.length+tp);
                        for(int k=0;k<size;k++,tp++,a++)
                            tgt[tp]= a<base.length ? base[a] : tgt[a-base.length];
                    }
                }
            }
            out.write(tgt,0,targetLen);
            produced=out.toByteArray();
        }
        return out.toByteArray();
    }

    public static void main(String[] a) throws Exception {
        if(a.length<4){ System.err.println("Usage: vcdiff-patcher <source> <patch> <output> <expected-sha1>"); System.exit(2); }
        byte[] src=Files.readAllBytes(Paths.get(a[0])), patch=Files.readAllBytes(Paths.get(a[1]));
        byte[] outBytes=apply(src,patch);
        String got=sha1(outBytes);
        System.out.println("output_bytes="+outBytes.length);
        System.out.println("output_sha1="+got);
        if(!got.equalsIgnoreCase(a[3])) throw new IllegalStateException("SHA-1 mismatch: expected "+a[3]+", got "+got);
        Path out=Paths.get(a[2]);
        if(out.toAbsolutePath().getParent()!=null) Files.createDirectories(out.toAbsolutePath().getParent());
        Files.write(out,outBytes);
        System.out.println("OK: "+out.toAbsolutePath());
    }
}
