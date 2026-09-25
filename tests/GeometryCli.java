package com.digitalghost.nmmprobe;
import java.io.*;

/** Runs the actual Android Java geometry on a desktop JVM, without Android stubs. */
public final class GeometryCli {
    public static void main(String[] args) throws Exception {
        // Unequal resize scales and crop offsets must transform both focal and principal point.
        float[] camera=DepthCoordinates.unpadCamera(new float[]{600,0,378,0,700,504,0,0,1},0,126,756,756,1008,1008);
        if(Math.abs(camera[0]-800)>1e-4||Math.abs(camera[2]-504)>1e-4||Math.abs(camera[5]-504)>1e-4)throw new AssertionError("Camera mapping");
        float[] restored=DepthCoordinates.unpadDepth(new float[]{0,1,2,3,4,5,6,7,8,9,10,11},4,3,4,3,1,1,2,1,2,1);
        if(restored[0]!=5||restored[1]!=6)throw new AssertionError("Depth unpadding");
        restored=DepthCoordinates.unpadDepth(new float[]{999,5,6,999},4,1,4,1,1,0,2,1,8,1);
        if(restored[0]!=5||restored[7]!=6)throw new AssertionError("Padding leaked into enlarged depth");
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(args[0])));
            DataOutputStream out=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(args[1])))) {
            int w=in.readInt(),h=in.readInt();float[] k=new float[9],depth=new float[w*h],mask=new float[w*h];
            for(int i=0;i<9;i++)k[i]=in.readFloat();
            for(int i=0;i<depth.length;i++)depth[i]=in.readFloat();
            for(int i=0;i<mask.length;i++)mask[i]=in.readFloat();
            for(boolean detail:new boolean[]{false,true}) {
                float[] n=PerspectiveGeometry.normals(depth,mask,w,h,k,detail);
                for(float value:n){if(!Float.isFinite(value))throw new AssertionError("Nonfinite normal");out.writeFloat(value);}
            }
        }
    }
}
