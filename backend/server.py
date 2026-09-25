from __future__ import annotations

import io
import os
import json
import shutil
import subprocess
import tempfile
import threading
import time
import uuid
import warnings
from pathlib import Path
import re
from urllib.parse import urlsplit

from fastapi import FastAPI, File, Form, HTTPException, Request, UploadFile
from fastapi.responses import FileResponse, JSONResponse, Response
from fastapi.staticfiles import StaticFiles
from PIL import Image, ImageDraw
from pillow_heif import register_heif_opener

from .pipeline import LocalNMMPipeline, MODEL_ROOT
from .image_import import normalize_import, MAX_IMPORT_BYTES


ROOT = Path(__file__).resolve().parents[1]
register_heif_opener()
OUTPUT_ROOT = Path(os.environ.get("NMM_OUTPUT_ROOT", ROOT / "output"))
OUTPUT_ROOT.mkdir(parents=True, exist_ok=True)
OUTPUT_MAX_RUNS = max(2, int(os.environ.get("NMM_OUTPUT_MAX_RUNS", "20")))
OUTPUT_MAX_BYTES = max(
    64 * 1024 * 1024,
    int(os.environ.get("NMM_OUTPUT_MAX_BYTES", str(512 * 1024 * 1024))),
)
OUTPUT_MAX_AGE_SECONDS = max(
    3600,
    int(os.environ.get("NMM_OUTPUT_MAX_AGE_SECONDS", str(7 * 24 * 3600))),
)

app = FastAPI(title="NMM Local Studio")
pipeline = LocalNMMPipeline()
inference_lock = threading.Lock()
DEPTH_RESOLUTION = 1008
MAX_EXPORT_PIXELS = 18_000_000


def _run_directory_size(path: Path) -> int:
    total = 0
    try:
        for entry in path.rglob("*"):
            if entry.is_file():
                total += entry.stat().st_size
    except OSError:
        return total
    return total


def _cleanup_output_cache(protected: set[str] | None = None) -> None:
    """Bound persistent analysis artifacts by age, count and total bytes."""
    protected = protected or set()
    now = time.time()
    runs: list[tuple[Path, float, int]] = []
    for path in OUTPUT_ROOT.iterdir():
        if not path.is_dir() or not re.fullmatch(r"[0-9a-f]{12}", path.name):
            continue
        try:
            modified = path.stat().st_mtime
        except OSError:
            continue
        if path.name not in protected and now - modified > OUTPUT_MAX_AGE_SECONDS:
            shutil.rmtree(path, ignore_errors=True)
            continue
        runs.append((path, modified, _run_directory_size(path)))

    runs.sort(key=lambda item: item[1], reverse=True)
    kept_bytes = 0
    kept_count = 0
    for path, _modified, size in runs:
        keep = path.name in protected or (
            kept_count < OUTPUT_MAX_RUNS and kept_bytes + size <= OUTPUT_MAX_BYTES
        )
        if keep:
            kept_count += 1
            kept_bytes += size
        else:
            shutil.rmtree(path, ignore_errors=True)


def _write_results(
    results: dict[str, Image.Image],
    protected: set[str] | None = None,
) -> tuple[str, dict[str, str]]:
    run_id = uuid.uuid4().hex[:12]
    run_dir = OUTPUT_ROOT / run_id
    run_dir.mkdir()
    files: dict[str, str] = {}
    try:
        for name, artifact in results.items():
            path = run_dir / f"{name}.png"
            artifact.save(path)
            files[name] = f"/output/{run_id}/{name}.png"
    except Exception:
        shutil.rmtree(run_dir, ignore_errors=True)
        raise
    finally:
        for artifact in results.values():
            artifact.close()
    _cleanup_output_cache({run_id, *(protected or set())})
    return run_id, files


def _same_local_origin(request: Request, origin: str) -> bool:
    try:
        candidate = urlsplit(origin)
        request_port = request.url.port or (443 if request.url.scheme == "https" else 80)
        candidate_port = candidate.port or (443 if candidate.scheme == "https" else 80)
    except ValueError:
        return False
    return (
        candidate.scheme == request.url.scheme
        and candidate.hostname in {"localhost", "127.0.0.1", "::1"}
        and candidate_port == request_port
    )


@app.middleware("http")
async def protect_local_service(request: Request, call_next):
    """Reject DNS rebinding and cross-site writes to the loopback service."""
    if request.url.hostname not in {"localhost", "127.0.0.1", "::1"}:
        return JSONResponse({"detail": "Local host required"}, status_code=403)
    if request.method not in {"GET", "HEAD", "OPTIONS"} and request.url.path.startswith("/api/"):
        if request.headers.get("sec-fetch-site") == "cross-site":
            return JSONResponse({"detail": "Cross-site request rejected"}, status_code=403)
        origin = request.headers.get("origin")
        if origin and not _same_local_origin(request, origin):
            return JSONResponse({"detail": "Cross-site request rejected"}, status_code=403)
    response = await call_next(request)
    response.headers.setdefault("X-Content-Type-Options", "nosniff")
    response.headers.setdefault("Referrer-Policy", "no-referrer")
    response.headers.setdefault("Cross-Origin-Resource-Policy", "same-origin")
    response.headers.setdefault("X-Frame-Options", "DENY")
    response.headers.setdefault(
        "Content-Security-Policy",
        "default-src 'self'; img-src 'self' blob: data:; style-src 'self' 'unsafe-inline'; "
        "script-src 'self'; connect-src 'self' data:; media-src 'self' blob:; object-src 'none'; "
        "base-uri 'none'; frame-ancestors 'none'",
    )
    return response


_cleanup_output_cache()


@app.get("/api/health")
def health() -> dict[str, object]:
    return {
        "ok": True,
        "device": pipeline.device,
        "models": {
            "sam3": (MODEL_ROOT / "sam3").exists(),
            "da3": (MODEL_ROOT / "da3-large-1.1").exists(),
        },
        "depth": {"resolution": DEPTH_RESOLUTION, "subject_focus": True},
    }


@app.post("/api/import-image")
def import_image(file: UploadFile = File(...)) -> Response:
    try:
        png = normalize_import(file.file.read(MAX_IMPORT_BYTES + 1))
        return Response(png, media_type="image/png", headers={"Cache-Control": "no-store"})
    except Exception as exc:
        raise HTTPException(status_code=400, detail=f"无法读取照片：{exc}") from exc


@app.post("/api/analyze")
def analyze(
    file: UploadFile = File(...),
    prompt: str = Form("miniature figure"),
    regions: str = Form("[]"),
) -> dict[str, object]:
    try:
        prompt = prompt.strip()
        if not prompt or len(prompt) > 120:
            raise ValueError("Prompt must contain 1 to 120 characters")
        parsed_regions = json.loads(regions)
        if not isinstance(parsed_regions, list) or len(parsed_regions) > 8:
            raise ValueError("Supplement regions must be a list with at most 8 boxes")
        clean_regions: list[list[float]] = []
        for region in parsed_regions:
            if not isinstance(region, list) or len(region) != 4:
                raise ValueError("Each supplement region must contain four coordinates")
            x1, y1, x2, y2 = [float(value) for value in region]
            if not (0 <= x1 < x2 <= 1 and 0 <= y1 < y2 <= 1):
                raise ValueError("Supplement region coordinates must be normalized from 0 to 1")
            clean_regions.append([x1, y1, x2, y2])
        normalized = normalize_import(file.file.read(MAX_IMPORT_BYTES + 1))
        image = Image.open(io.BytesIO(normalized)).convert("RGB")
    except (TypeError, ValueError, json.JSONDecodeError) as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except Exception as exc:
        raise HTTPException(status_code=400, detail=f"无法读取照片：{exc}") from exc

    if not inference_lock.acquire(blocking=False):
        image.close()
        raise HTTPException(status_code=409, detail="AI 正在处理另一项任务，请稍候")
    try:
        results = pipeline.run(image, prompt, clean_regions, DEPTH_RESOLUTION)
        depth_precision = dict(pipeline.last_depth_info)
        run_id, files = _write_results(results)
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc
    finally:
        image.close()
        inference_lock.release()
    return {"id": run_id, "artifacts": files, "depth_precision": depth_precision}


@app.post("/api/refine")
def refine_region(
    analysis_id: str = Form(...),
    region: str = Form(...),
) -> dict[str, object]:
    if not re.fullmatch(r"[0-9a-f]{12}", analysis_id):
        raise HTTPException(status_code=400, detail="Invalid analysis id")
    try:
        values = json.loads(region)
        if not isinstance(values, list) or len(values) != 4:
            raise ValueError("Local region must contain four coordinates")
        x1, y1, x2, y2 = [float(value) for value in values]
        if not (0 <= x1 < x2 <= 1 and 0 <= y1 < y2 <= 1):
            raise ValueError("Local region coordinates must be between 0 and 1")
    except (TypeError, ValueError, json.JSONDecodeError) as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    source_dir = OUTPUT_ROOT / analysis_id
    cutout_path = source_dir / "cutout.png"
    mask_path = source_dir / "mask.png"
    if not cutout_path.exists() or not mask_path.exists():
        raise HTTPException(status_code=404, detail="Original analysis result not found")
    try:
        os.utime(source_dir, None)
    except OSError:
        pass
    image: Image.Image | None = None
    mask: Image.Image | None = None
    if not inference_lock.acquire(blocking=False):
        raise HTTPException(status_code=409, detail="AI 正在处理另一项任务，请稍候")
    try:
        image = Image.open(cutout_path).convert("RGB")
        mask = Image.open(mask_path).convert("L")
        left = max(0, min(image.width - 1, int(round(x1 * image.width))))
        top = max(0, min(image.height - 1, int(round(y1 * image.height))))
        right = max(left + 1, min(image.width, int(round(x2 * image.width))))
        bottom = max(top + 1, min(image.height, int(round(y2 * image.height))))
        if min(right - left, bottom - top) < 32:
            raise ValueError("Local refinement region is too small")
        results, refinement = pipeline.refine_region(
            image, mask, (left, top, right, bottom), DEPTH_RESOLUTION
        )
        run_id, files = _write_results(results, {analysis_id})
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc
    finally:
        if image is not None:
            image.close()
        if mask is not None:
            mask.close()
        inference_lock.release()
    return {"id": run_id, "source_id": analysis_id, "artifacts": files, "refinement": refinement}


@app.post("/api/export-gif")
def export_gif(
    original: UploadFile = File(...),
    nmm: UploadFile = File(...),
) -> Response:
    """Create an iPhone-friendly, perfectly aligned before/after animation."""
    try:
        original_bytes = original.file.read(24 * 1024 * 1024 + 1)
        nmm_bytes = nmm.file.read(24 * 1024 * 1024 + 1)
        if len(original_bytes) > 24 * 1024 * 1024 or len(nmm_bytes) > 24 * 1024 * 1024:
            raise ValueError("Export frame is too large")
        frames = [_decode_export_frame(original_bytes), _decode_export_frame(nmm_bytes)]
        if frames[0].size != frames[1].size:
            raise ValueError("Export frames must have identical dimensions")
        if not frames[0].width or not frames[0].height:
            raise ValueError("Export frame is empty")
        if frames[0].width * frames[0].height > MAX_EXPORT_PIXELS:
            raise ValueError("Export frame dimensions are too large")

        # GIF is intentionally capped: it remains quick to open in Photos while
        # the lossless PNG export carries the fine painting detail.
        max_side = 1400
        if max(frames[0].size) > max_side:
            scale = max_side / max(frames[0].size)
            size = (max(1, round(frames[0].width * scale)), max(1, round(frames[0].height * scale)))
            frames = [frame.resize(size, Image.Resampling.LANCZOS) for frame in frames]

        bar_height = max(34, round(frames[0].width * .038))
        labelled: list[Image.Image] = []
        for frame, label, accent in zip(
            frames,
            ("ORIGINAL PHOTO", "NMM LIGHT GUIDE"),
            ((225, 225, 218), (217, 255, 67)),
        ):
            composed = Image.new("RGB", (frame.width, frame.height + bar_height), (18, 18, 16))
            composed.paste(frame, (0, bar_height))
            draw = ImageDraw.Draw(composed)
            draw.rectangle((0, 0, 7, bar_height), fill=accent)
            draw.text((20, max(8, bar_height // 4)), label, fill=accent)
            labelled.append(composed)

        # Build one shared palette, so unchanged areas do not shimmer merely
        # because the two GIF frames selected different colours.
        palette_source = Image.new("RGB", (labelled[0].width, labelled[0].height * 2))
        palette_source.paste(labelled[0], (0, 0))
        palette_source.paste(labelled[1], (0, labelled[0].height))
        shared_palette = palette_source.quantize(colors=256, method=Image.Quantize.MEDIANCUT)
        gif_frames = [
            frame.quantize(palette=shared_palette, dither=Image.Dither.FLOYDSTEINBERG)
            for frame in labelled
        ]
        output = io.BytesIO()
        gif_frames[0].save(
            output,
            format="GIF",
            save_all=True,
            append_images=gif_frames[1:],
            loop=0,
            duration=[900, 1200],
            disposal=2,
            optimize=False,
        )
    except Exception as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return Response(
        content=output.getvalue(),
        media_type="image/gif",
        headers={"Content-Disposition": 'attachment; filename="nmm-before-after.gif"'},
    )


def _decode_export_frame(data: bytes) -> Image.Image:
    with warnings.catch_warnings():
        warnings.simplefilter("error", Image.DecompressionBombWarning)
        with Image.open(io.BytesIO(data)) as source:
            if source.width * source.height > MAX_EXPORT_PIXELS:
                raise ValueError("Export frame dimensions too large")
            return source.convert("RGB")


def _read_export_pair(original: UploadFile, nmm: UploadFile) -> list[Image.Image]:
    """Validate and normalize the two perfectly aligned export frames."""
    original_bytes = original.file.read(24 * 1024 * 1024 + 1)
    nmm_bytes = nmm.file.read(24 * 1024 * 1024 + 1)
    if len(original_bytes) > 24 * 1024 * 1024 or len(nmm_bytes) > 24 * 1024 * 1024:
        raise ValueError("Export frame is too large")
    frames = [_decode_export_frame(original_bytes), _decode_export_frame(nmm_bytes)]
    if frames[0].size != frames[1].size:
        raise ValueError("Export frames must have identical dimensions")
    if not frames[0].width or not frames[0].height:
        raise ValueError("Export frame is empty")
    if frames[0].width * frames[0].height > MAX_EXPORT_PIXELS:
        raise ValueError("Export frame dimensions are too large")

    # Keep the video inside the broadly compatible 1080p envelope and make
    # both dimensions even, as required by H.264 yuv420p.
    width, height = frames[0].size
    scale = min(1.0, 1080 / max(width, height))
    width = max(2, int(round(width * scale)) // 2 * 2)
    height = max(2, int(round(height * scale)) // 2 * 2)
    if frames[0].size != (width, height):
        frames = [frame.resize((width, height), Image.Resampling.LANCZOS) for frame in frames]
    return frames


def _ffmpeg_path() -> str:
    executable = shutil.which("ffmpeg")
    if executable:
        return executable
    try:
        import imageio_ffmpeg

        return imageio_ffmpeg.get_ffmpeg_exe()
    except Exception as exc:
        raise RuntimeError("FFmpeg is unavailable; MP4 export cannot start") from exc


@app.post("/api/export-mp4")
def export_mp4(
    original: UploadFile = File(...),
    nmm: UploadFile = File(...),
) -> Response:
    """Create a WeChat-friendly H.264 wipe comparison with aligned frames."""
    process: subprocess.Popen[bytes] | None = None
    try:
        original_frame, nmm_frame = _read_export_pair(original, nmm)
        width, height = original_frame.size
        fps = 24
        duration = 4.0
        frame_count = round(fps * duration)

        with tempfile.TemporaryDirectory(prefix="nmm-export-") as temp_dir:
            output_path = Path(temp_dir) / "nmm-wechat-comparison.mp4"
            command = [
                _ffmpeg_path(),
                "-hide_banner",
                "-loglevel",
                "error",
                "-f",
                "rawvideo",
                "-pix_fmt",
                "rgb24",
                "-s:v",
                f"{width}x{height}",
                "-r",
                str(fps),
                "-i",
                "-",
                "-an",
                "-c:v",
                "libx264",
                "-profile:v",
                "main",
                "-level",
                "4.0",
                "-pix_fmt",
                "yuv420p",
                "-preset",
                "medium",
                "-b:v",
                "2200k",
                "-maxrate",
                "2800k",
                "-bufsize",
                "4400k",
                "-movflags",
                "+faststart",
                "-y",
                str(output_path),
            ]
            process = subprocess.Popen(
                command,
                stdin=subprocess.PIPE,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.PIPE,
            )
            if process.stdin is None:
                raise RuntimeError("Unable to open the video encoder")

            divider_width = max(3, round(width * .004))
            for index in range(frame_count):
                time = index / fps
                divider_x: int | None = None
                if time < .8:
                    frame = original_frame
                elif time < 1.6:
                    progress = (time - .8) / .8
                    divider_x = round(width * (1 - progress))
                    frame = original_frame.copy()
                    if divider_x < width:
                        frame.paste(nmm_frame.crop((divider_x, 0, width, height)), (divider_x, 0))
                elif time < 2.6:
                    frame = nmm_frame
                elif time < 3.4:
                    progress = (time - 2.6) / .8
                    divider_x = round(width * progress)
                    frame = nmm_frame.copy()
                    if divider_x > 0:
                        frame.paste(original_frame.crop((0, 0, divider_x, height)), (0, 0))
                else:
                    frame = original_frame

                if divider_x is not None:
                    frame = frame.copy()
                    draw = ImageDraw.Draw(frame)
                    left = max(0, min(width - divider_width, divider_x - divider_width // 2))
                    draw.rectangle(
                        (left, 0, left + divider_width, height),
                        fill=(217, 255, 67),
                    )
                process.stdin.write(frame.tobytes())

            process.stdin.close()
            stderr = process.stderr.read().decode("utf-8", errors="replace") if process.stderr else ""
            return_code = process.wait(timeout=120)
            if return_code != 0 or not output_path.exists():
                raise RuntimeError(stderr.strip() or "Video encoder failed")
            output = output_path.read_bytes()
            process = None
    except Exception as exc:
        if process is not None and process.poll() is None:
            process.kill()
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    return Response(
        content=output,
        media_type="video/mp4",
        headers={
            "Content-Disposition": 'attachment; filename="nmm-wechat-comparison.mp4"',
            "Cache-Control": "no-store",
        },
    )


@app.get("/")
@app.get("/index.html")
def frontend_index() -> FileResponse:
    return FileResponse(ROOT / "index.html")


@app.get("/app.js")
def frontend_javascript() -> FileResponse:
    return FileResponse(ROOT / "app.js", media_type="application/javascript")


@app.get("/styles.css")
def frontend_styles() -> FileResponse:
    return FileResponse(ROOT / "styles.css", media_type="text/css")


app.mount("/output", StaticFiles(directory=OUTPUT_ROOT), name="output")
