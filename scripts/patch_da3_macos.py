"""Make DA3's optional export stack lazy so inference works on Apple Silicon."""

from pathlib import Path

import depth_anything_3


api_path = Path(depth_anything_3.__file__).with_name("api.py")
source = api_path.read_text()
source = source.replace("from depth_anything_3.utils.export import export\n", "")
needle = "        export(prediction, export_format, export_dir, **kwargs)"
replacement = (
    "        # Lazy: export extras include CUDA-only packages.\n"
    "        from depth_anything_3.utils.export import export\n"
    "        export(prediction, export_format, export_dir, **kwargs)"
)
if needle in source:
    api_path.write_text(source.replace(needle, replacement))
    print(f"Patched {api_path}")
else:
    print(f"No patch needed: {api_path}")
