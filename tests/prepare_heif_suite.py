"""Generate local-only decoder fixtures; public samples are not shipped in the app."""
import io
import json
from pathlib import Path
import sys
from PIL import Image
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from backend.image_import import normalize_import

root=Path(sys.argv[1]);out=Path('dist/heif-tests');out.mkdir(parents=True,exist_ok=True)
cases=[]
for name,source in [('camera-depth.heic',root/'heif_other/pug.heic'),('rgb10.heif',root/'heif/RGB_10__128x128.heif')]:
    data=source.read_bytes();(out/name).write_bytes(data)
    image=Image.open(io.BytesIO(normalize_import(data)))
    cases.append({'name':name,'type':'image/heic','width':image.width,'height':image.height})
image=Image.new('RGB',(160,96),'red');exif=Image.Exif();exif[274]=6
image.save(out/'rotated.heic','HEIF',exif=exif.tobytes(),quality=95)
cases.append({'name':'rotated.heic','type':'image/heic','width':96,'height':160})
(out/'magic.bin').write_bytes((out/'miniature.HEIF').read_bytes())
image=Image.open(io.BytesIO(normalize_import((out/'magic.bin').read_bytes())))
cases.append({'name':'magic.bin','type':'application/octet-stream','width':image.width,'height':image.height})
(out/'broken.heic').write_bytes(b'not an image')
cases.append({'name':'broken.heic','type':'image/heic','error':True})
(out/'import-cases.json').write_text(json.dumps(cases))
print(json.dumps(cases,indent=2))
