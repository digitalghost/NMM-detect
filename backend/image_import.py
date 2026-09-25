"""Decode every imported image into one bounded, colour-managed working PNG."""
import io
import warnings
from PIL import Image, ImageCms, ImageOps
from pillow_heif import register_heif_opener

register_heif_opener()
MAX_IMPORT_BYTES = 64 * 1024 * 1024
MAX_IMPORT_PIXELS = 60_000_000
MAX_WORKING_EDGE = 2048


def normalize_import(data: bytes) -> bytes:
    if not data or len(data) > MAX_IMPORT_BYTES:
        raise ValueError("照片为空或超过 64 MB，请选择较小的照片")
    with warnings.catch_warnings():
        warnings.simplefilter('error', Image.DecompressionBombWarning)
        with Image.open(io.BytesIO(data)) as original:
            # HEIF plugin selects the primary image, not a thumbnail/auxiliary image.
            if original.width * original.height > MAX_IMPORT_PIXELS:
                raise ValueError("照片超过 6000 万像素，请先缩小尺寸")
            image = ImageOps.exif_transpose(original)
            profile = image.info.get("icc_profile")
            if profile:
                try:
                    image = ImageCms.profileToProfile(
                        image,
                        ImageCms.ImageCmsProfile(io.BytesIO(profile)),
                        ImageCms.createProfile("sRGB"),
                        outputMode="RGB",
                    )
                except (ImageCms.PyCMSError, OSError, ValueError) as exc:
                    raise ValueError("照片的色彩配置损坏，无法可靠转换") from exc
            else:
                image = image.convert("RGB")
            image.thumbnail((MAX_WORKING_EDGE, MAX_WORKING_EDGE), Image.Resampling.LANCZOS)
            # Do not forward EXIF (including orientation/GPS) or auxiliary blobs to preview.
            image.info.clear()
            result = io.BytesIO()
            image.save(result, "PNG")
            return result.getvalue()
