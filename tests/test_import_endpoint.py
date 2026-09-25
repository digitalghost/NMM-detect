"""Exercise the live bundled Mac backend, not the development interpreter."""
import io
import json
from pathlib import Path
import sys
import urllib.request
import urllib.error
from PIL import Image

base=sys.argv[1];directory=Path('dist/heif-tests');results=[]
for case in json.loads((directory/'import-cases.json').read_text()):
    boundary='nmm-import-check-boundary'
    body=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{case["name"]}"\r\nContent-Type: {case["type"]}\r\n\r\n').encode()+(directory/case['name']).read_bytes()+f'\r\n--{boundary}--\r\n'.encode()
    request=urllib.request.Request(base+'/api/import-image',data=body,headers={'Content-Type':f'multipart/form-data; boundary={boundary}'})
    result={'name':case['name']}
    try:
        with urllib.request.urlopen(request,timeout=90) as response:
            image=Image.open(io.BytesIO(response.read()))
            result.update(width=image.width,height=image.height,format=image.format)
            result['passed']=not case.get('error') and image.size==(case['width'],case['height']) and image.format=='PNG'
    except urllib.error.HTTPError as error:
        result.update(status=error.code,passed=bool(case.get('error')) and error.code==400)
    results.append(result)
report={'passed':all(r['passed'] for r in results),'cases':results}
print(json.dumps(report,indent=2))
assert report['passed'],report
