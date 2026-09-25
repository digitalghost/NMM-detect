"""Inspect real public HEIF depth/gain-map examples; no guessed metric conversion."""
import argparse
import json
from pathlib import Path
import numpy as np
from PIL import Image
import pillow_heif


def main():
    parser=argparse.ArgumentParser();parser.add_argument('samples',type=Path);parser.add_argument('output',type=Path)
    args=parser.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    reports=[]
    for name in ['pug.heic','spatial_photo.heic','stereo_pair.heic']:
        source=args.samples/'heif_other'/name
        container=pillow_heif.open_heif(source)
        info=container.info
        report={'file':name,'primarySize':container.size,'primaryBitDepth':info['bit_depth'],
                'imageCount':len(container),'auxiliaryTypes':info.get('aux',{}),'depthImages':[]}
        for i,depth in enumerate(info.get('depth_images',[])):
            image=depth.to_pillow();array=np.asarray(image)
            image.save(args.output/f'{source.stem}-depth-{i}.png')
            report['depthImages'].append({'size':depth.size,'mode':depth.mode,'metadata':depth.info,
                'uniqueEncodedValues':int(len(np.unique(array))),'encodedRange':[float(array.min()),float(array.max())],
                'sourceToDepthLinearRatio':[container.size[0]/depth.size[0],container.size[1]/depth.size[1]],
                'pixelsFractionOfPrimary':array.size/(container.size[0]*container.size[1])})
        reports.append(report)
    (args.output/'depth-evaluation.json').write_text(json.dumps(reports,indent=2))
    print(json.dumps(reports,indent=2))


if __name__=='__main__':main()
