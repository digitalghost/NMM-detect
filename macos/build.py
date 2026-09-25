"""Build a self-contained Apple Silicon application using the working local env.

Run: .venv/bin/python macos/build.py [--dmg]
No dependencies or model weights are downloaded during packaging.
"""
import argparse
import hashlib
import os
from pathlib import Path
import plistlib
import shutil
import subprocess
import sys
import sysconfig

ROOT = Path(__file__).resolve().parents[1]
DIST = ROOT / "dist" / "macos"
APP = DIST / "NMM Studio.app"
CONTENTS = APP / "Contents"
RES = CONTENTS / "Resources"

def run(*args):
    subprocess.run([str(arg) for arg in args], check=True)

def copy_tree(source, target, *exclude):
    target.mkdir(parents=True, exist_ok=True)
    args = ["rsync", "-a", "--delete", "--delete-excluded"]
    for pattern in exclude:
        args.extend(["--exclude", pattern])
    run(*args, str(source) + "/", str(target) + "/")

def main():
    global DIST, APP, CONTENTS, RES
    parser = argparse.ArgumentParser()
    parser.add_argument("--dmg", action="store_true")
    parser.add_argument("--output", type=Path, default=DIST)
    args = parser.parse_args()
    DIST = args.output.resolve()
    APP = DIST / "NMM Studio.app"
    CONTENTS = APP / "Contents"
    RES = CONTENTS / "Resources"
    if os.uname().machine != "arm64":
        raise SystemExit("This build recipe requires an Apple Silicon Mac.")
    required_models = [
        ROOT / ".models" / "sam3" / "config.json",
        ROOT / ".models" / "sam3" / "model.safetensors",
        ROOT / ".models" / "da3-large-1.1" / "config.json",
        ROOT / ".models" / "da3-large-1.1" / "model.safetensors",
    ]
    missing_models = [str(path) for path in required_models if not path.is_file()]
    if missing_models:
        raise SystemExit("Missing model files:\n" + "\n".join(missing_models))
    (CONTENTS / "MacOS").mkdir(parents=True, exist_ok=True)
    RES.mkdir(parents=True, exist_ok=True)
    print("Bundling Python and installed dependencies…", flush=True)
    copy_tree(Path(sys.base_prefix), RES / "python", "__pycache__", "*.pyc")
    copy_tree(Path(sysconfig.get_paths()["purelib"]), RES / "site-packages", "__pycache__", "*.pyc", "_virtualenv.*")
    studio = RES / "studio"
    studio.mkdir(exist_ok=True)
    copy_tree(ROOT / "backend", studio / "backend", "__pycache__", "*.pyc")
    for name in ("index.html", "app.js", "styles.css", "LICENSE"):
        shutil.copy2(ROOT / name, studio / name)
    print("Bundling offline model weights…", flush=True)
    copy_tree(ROOT / ".models", studio / ".models", "sam3.pt", ".DS_Store", ".cache")
    shutil.copy2(ROOT / "macos/server_launcher.py", RES / "server_launcher.py")
    info = {
        "CFBundleName": "NMM Studio", "CFBundleDisplayName": "NMM Studio",
        "CFBundleIdentifier": "com.digitalghost.nmmstudio.mac",
        "CFBundleExecutable": "NMMStudio", "CFBundlePackageType": "APPL", "CFBundleIconFile": "AppIcon",
        "CFBundleShortVersionString": "0.3.5", "CFBundleVersion": "5",
        "LSMinimumSystemVersion": "14.0", "NSHighResolutionCapable": True,
        "NSAppTransportSecurity": {"NSAllowsLocalNetworking": True},
        "NSHumanReadableCopyright": "NMM Studio. See bundled application and model licenses.",
    }
    with (CONTENTS / "Info.plist").open("wb") as stream:
        plistlib.dump(info, stream)
    print("Compiling native window…", flush=True)
    run("xcrun", "swiftc", ROOT / "macos/Icon.swift", "-o", DIST / "make-icon")
    run(DIST / "make-icon", DIST / "AppIcon.iconset")
    run("iconutil", "-c", "icns", DIST / "AppIcon.iconset", "-o", RES / "AppIcon.icns")
    run("xcrun", "swiftc", "-O", "-target", "arm64-apple-macos14.0", ROOT / "macos/Main.swift", "-o", CONTENTS / "MacOS/NMMStudio", "-framework", "Cocoa", "-framework", "WebKit")
    # Local ad-hoc signing makes the bundle verifiable on this Mac. A public
    # distribution build needs the owner's Developer ID and notarization.
    run("codesign", "--force", "--deep", "--sign", "-", APP)
    run("codesign", "--verify", "--deep", "--strict", APP)
    print(f"Application ready: {APP}", flush=True)
    if args.dmg:
        staging = DIST / "disk-image"
        staging.mkdir(exist_ok=True)
        # Stage only the app and installation instructions in the disk image.
        copy_tree(APP, staging / APP.name)
        applications = staging / "Applications"
        if not applications.exists(): applications.symlink_to("/Applications")
        shutil.copy2(ROOT / "macos/使用说明.txt", staging / "使用说明.txt")
        run("hdiutil", "create", "-volname", "NMM Studio", "-srcfolder", staging,
            "-ov", "-format", "ULFO", DIST / "NMM-Studio-AppleSilicon.dmg")
        image = DIST / "NMM-Studio-AppleSilicon.dmg"
        with image.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        (DIST / "SHA256SUMS.txt").write_text(f"{digest}  {image.name}\n")

if __name__ == "__main__":
    main()
