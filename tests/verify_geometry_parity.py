"""Compare production Java geometry with production NumPy/OpenCV geometry."""
import argparse
import json
import os
from pathlib import Path
import struct
import subprocess
import sys
import tempfile

import numpy as np
from PIL import Image
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from backend.nmm_shader import depth_to_normals, depth_to_detail_normals


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--geometry',type=Path)
    args=parser.parse_args()
    java=Path(os.environ['JAVA_HOME'])/'bin'
    source=Path('android-probe/app/src/main/java/com/digitalghost/nmmprobe')
    report={}
    with tempfile.TemporaryDirectory(prefix='nmm-geometry-') as directory:
        tmp=Path(directory)
        subprocess.run([str(java/'javac'),'-d',directory,str(source/'PerspectiveGeometry.java'),str(source/'DepthCoordinates.java'),'tests/GeometryCli.java'],check=True)
        fixtures=[]
        if args.geometry:
            data=args.geometry.read_bytes();w,h=struct.unpack('>ii',data[:8]);v=np.frombuffer(data[8:],dtype='>f4').astype(np.float32)
            fixtures.append(('real',v[9:9+w*h].reshape(h,w),v[9+w*h:].reshape(h,w),v[:9].reshape(3,3)))
        else:
            h,w=192,256;y,x=np.mgrid[-1:1:complex(h),-1:1:complex(w)].astype(np.float32)
            mask=((x*x+y*y)<.8**2).astype(np.float32)
            k=np.array([[235,0,110],[0,225,102],[0,0,1]],np.float32)
            for a in [.05,.2,.5]: fixtures.append((f'curve-{a}',2+a*(x*x+y*y),mask,k))
            fixtures.append(('plane',np.full((h,w),2,np.float32),mask,k))
            fixtures.append(('sloped',2+.3*x-.2*y,mask,k))
            depth=2+.3*(x*x+y*y)+.006*np.sin(x*80)*np.cos(y*65)
            depth[mask==0]=0
            fixtures.append(('micro-boundary',depth,mask,k))
            depth=depth.copy();depth[75:78,90:93]=np.nan;fixtures.append(('nonfinite',depth,mask,k))
        for name,depth,mask,k in fixtures:
            h,w=depth.shape
            path=tmp/'input.bin';output=tmp/'output.bin'
            path.write_bytes(struct.pack('>ii',w,h)+k.astype('>f4').tobytes()+depth.astype('>f4').tobytes()+mask.astype('>f4').tobytes())
            subprocess.run([str(java/'java'),'-cp',directory,'com.digitalghost.nmmprobe.GeometryCli',str(path),str(output)],check=True)
            actual=np.frombuffer(output.read_bytes(),dtype='>f4').reshape(2,h,w,3).astype(np.float32)
            report[name]={}
            for i,function in enumerate([depth_to_normals,depth_to_detail_normals]):
                expected=function(depth,mask,k)
                # Web may leave a zero normal in a wholly invalid depth hole;
                # Android deliberately substitutes a finite front-facing normal there.
                valid=(mask>.1)&(np.linalg.norm(expected,axis=-1)>1e-6)
                a=actual[i][valid].astype(float);b=expected[valid].astype(float)
                a/=np.linalg.norm(a,axis=-1,keepdims=True);b/=np.linalg.norm(b,axis=-1,keepdims=True)
                angles=np.degrees(np.arccos(np.clip(np.sum(a*b,axis=-1),-1,1)))
                result={'meanDegrees':float(angles.mean()),'p99Degrees':float(np.percentile(angles,99)),'maxDegrees':float(angles.max())}
                report[name]['detail' if i else 'broad']=result
                assert result['p99Degrees']<.15 and result['meanDegrees']<.03,(name,result)
                if args.geometry:
                    image_path=args.geometry.parent/('detail_normals.png' if i else 'normals.png')
                    if image_path.exists():
                        device=np.asarray(Image.open(image_path).convert('RGB'),dtype=float)
                        reference=np.clip((expected+1)*127.5,0,255).astype(np.uint8).astype(float)
                        difference=np.abs(device-reference)[mask>.1]
                        result['devicePngMeanChannelDifference']=float(difference.mean())
                        result['devicePngP99ChannelDifference']=float(np.percentile(difference,99))
                        assert difference.mean()<.1 and np.percentile(difference,99)<=1, result
        print(json.dumps({'passed':True,'cases':report},indent=2))


if __name__=='__main__':main()
