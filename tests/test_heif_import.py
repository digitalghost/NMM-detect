"""Local decoder tests and generation of a real encoded HEIF integration fixture."""
import io
from pathlib import Path
import sys
import unittest

from PIL import Image, ImageCms
import pillow_heif
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from backend.image_import import normalize_import


class ImportTests(unittest.TestCase):
    def encoded(self, mode='RGB', orientation=None):
        image=Image.new(mode,(160,96), 'red' if mode=='RGB' else 128)
        data=io.BytesIO()
        exif=Image.Exif()
        if orientation: exif[274]=orientation
        image.save(data,'HEIF',quality=95,exif=exif.tobytes())
        return data.getvalue()

    def test_rgb_and_grayscale(self):
        for mode in ['RGB','L']:
            image=Image.open(io.BytesIO(normalize_import(self.encoded(mode))))
            self.assertEqual(image.format,'PNG');self.assertEqual(image.mode,'RGB');self.assertEqual(image.size,(160,96))

    def test_orientation(self):
        data=self.encoded(orientation=6)
        image=Image.open(io.BytesIO(normalize_import(data)))
        self.assertEqual(image.size,(96,160));self.assertFalse(image.getexif())

    def test_corrupt(self):
        for data in [b'',b'not an image',self.encoded()[:50]]:
            with self.assertRaises(Exception): normalize_import(data)

    def test_size_and_color_profile(self):
        source=Image.new('RGB',(2400,1200),'blue');data=io.BytesIO()
        source.save(data,'HEIF',quality=95,icc_profile=ImageCms.ImageCmsProfile(ImageCms.createProfile('sRGB')).tobytes())
        image=Image.open(io.BytesIO(normalize_import(data.getvalue())))
        self.assertEqual(image.size,(2048,1024));self.assertGreater(image.getpixel((100,100))[2],240)

    def test_primary_not_first_frame(self):
        first=Image.new('RGB',(64,48),'blue');second=Image.new('RGB',(160,96),'red');data=io.BytesIO()
        first.save(data,'HEIF',save_all=True,append_images=[second],primary_index=1)
        image=Image.open(io.BytesIO(normalize_import(data.getvalue())))
        self.assertEqual(image.size,(160,96));self.assertGreater(image.getpixel((20,20))[0],220)


if __name__=='__main__':
    if len(sys.argv)>1 and sys.argv[1]=='--fixture':
        directory=Path('dist/heif-tests');directory.mkdir(parents=True,exist_ok=True)
        image=Image.open(sys.argv[2]).convert('RGB')
        image.save(directory/'miniature.HEIF','HEIF',quality=95)
        print(directory/'miniature.HEIF')
    else: unittest.main()
