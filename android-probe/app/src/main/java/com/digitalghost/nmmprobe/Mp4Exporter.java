package com.digitalghost.nmmprobe;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.nio.ByteBuffer;

/** Offline H.264 wipe export compatible with WeChat and iOS Photos. */
final class Mp4Exporter {
    private static final int FPS = 24;
    private static final int FRAME_COUNT = 96;

    private Mp4Exporter() { }

    static void encode(Bitmap original, Bitmap nmm, File output) throws Exception {
        int sourceWidth = original.getWidth();
        int sourceHeight = original.getHeight();
        float scale = Math.min(1f, 1080f / Math.max(sourceWidth, sourceHeight));
        int width = Math.max(2, Math.round(sourceWidth * scale) / 2 * 2);
        int height = Math.max(2, Math.round(sourceHeight * scale) / 2 * 2);
        Bitmap originalScaled = Bitmap.createScaledBitmap(original, width, height, true);
        Bitmap nmmScaled = Bitmap.createScaledBitmap(nmm, width, height, true);

        MediaCodec codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        int colorFormat = chooseColorFormat(codec);
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 2_400_000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileMain);

        MediaMuxer muxer = null;
        Bitmap frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[width * height];
        byte[] yuv = new byte[width * height * 3 / 2];
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            EncoderState state = new EncoderState();
            Paint dividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            dividerPaint.setColor(Color.rgb(217, 255, 67));
            dividerPaint.setStrokeWidth(Math.max(3f, width * .004f));

            for (int index = 0; index < FRAME_COUNT; index++) {
                drawFrame(frame, originalScaled, nmmScaled, dividerPaint, index);
                frame.getPixels(pixels, 0, width, 0, 0, width, height);
                argbToYuv420(pixels, yuv, width, height,
                        colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar);
                queue(codec, yuv, index * 1_000_000L / FPS, false);
                drain(codec, muxer, state, false);
            }
            queue(codec, new byte[0], FRAME_COUNT * 1_000_000L / FPS, true);
            drain(codec, muxer, state, true);
            if (!state.started) throw new IllegalStateException("视频编码器没有产生有效画面");
        } finally {
            try { codec.stop(); } catch (Throwable ignored) { }
            codec.release();
            if (muxer != null) {
                try { muxer.stop(); } catch (Throwable ignored) { }
                muxer.release();
            }
            frame.recycle();
            if (originalScaled != original) originalScaled.recycle();
            if (nmmScaled != nmm) nmmScaled.recycle();
        }
    }

    private static int chooseColorFormat(MediaCodec codec) {
        int flexible = -1;
        for (int value : codec.getCodecInfo().getCapabilitiesForType(
                MediaFormat.MIMETYPE_VIDEO_AVC).colorFormats) {
            if (value == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) return value;
            if (value == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) flexible = value;
            if (value == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible && flexible < 0) {
                flexible = value;
            }
        }
        if (flexible < 0) throw new IllegalStateException("设备没有可用的 H.264 YUV420 编码器");
        return flexible;
    }

    private static void drawFrame(Bitmap target, Bitmap original, Bitmap nmm,
                                  Paint dividerPaint, int index) {
        float time = index / (float) FPS;
        Canvas canvas = new Canvas(target);
        int width = target.getWidth();
        int height = target.getHeight();
        int divider = -1;
        if (time < .8f) {
            canvas.drawBitmap(original, 0, 0, null);
        } else if (time < 1.6f) {
            float progress = (time - .8f) / .8f;
            divider = Math.round(width * (1f - progress));
            canvas.drawBitmap(original, 0, 0, null);
            canvas.save();
            canvas.clipRect(divider, 0, width, height);
            canvas.drawBitmap(nmm, 0, 0, null);
            canvas.restore();
        } else if (time < 2.6f) {
            canvas.drawBitmap(nmm, 0, 0, null);
        } else if (time < 3.4f) {
            float progress = (time - 2.6f) / .8f;
            divider = Math.round(width * progress);
            canvas.drawBitmap(nmm, 0, 0, null);
            canvas.save();
            canvas.clipRect(0, 0, divider, height);
            canvas.drawBitmap(original, 0, 0, null);
            canvas.restore();
        } else {
            canvas.drawBitmap(original, 0, 0, null);
        }
        if (divider >= 0 && divider <= width) {
            canvas.drawLine(divider, 0, divider, height, dividerPaint);
        }
    }

    private static void queue(MediaCodec codec, byte[] data, long presentationTimeUs,
                              boolean end) throws Exception {
        while (true) {
            int index = codec.dequeueInputBuffer(20_000);
            if (index < 0) continue;
            ByteBuffer buffer = codec.getInputBuffer(index);
            if (buffer == null) throw new IllegalStateException("无法取得视频编码缓冲区");
            buffer.clear();
            if (data.length > buffer.remaining()) throw new IllegalStateException("视频帧超过编码缓冲区容量");
            buffer.put(data);
            codec.queueInputBuffer(index, 0, data.length, presentationTimeUs,
                    end ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
            return;
        }
    }

    private static void drain(MediaCodec codec, MediaMuxer muxer, EncoderState state,
                              boolean untilEnd) throws Exception {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int idle = 0;
        while (true) {
            int index = codec.dequeueOutputBuffer(info, untilEnd ? 20_000 : 0);
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!untilEnd || ++idle > 500) return;
                continue;
            }
            idle = 0;
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (state.started) throw new IllegalStateException("视频格式重复变化");
                state.track = muxer.addTrack(codec.getOutputFormat());
                muxer.start();
                state.started = true;
                continue;
            }
            if (index < 0) continue;
            ByteBuffer output = codec.getOutputBuffer(index);
            if (output == null) throw new IllegalStateException("无法取得已编码视频数据");
            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
            if (info.size > 0) {
                if (!state.started) throw new IllegalStateException("视频容器尚未初始化");
                output.position(info.offset);
                output.limit(info.offset + info.size);
                muxer.writeSampleData(state.track, output, info);
            }
            boolean ended = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            codec.releaseOutputBuffer(index, false);
            if (ended || !untilEnd) return;
        }
    }

    private static void argbToYuv420(int[] pixels, byte[] output, int width, int height,
                                     boolean semiPlanar) {
        int frameSize = width * height;
        int yIndex = 0;
        int uIndex = frameSize;
        int vIndex = semiPlanar ? frameSize + 1 : frameSize + frameSize / 4;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int color = pixels[y * width + x];
                int r = Color.red(color);
                int g = Color.green(color);
                int b = Color.blue(color);
                int yy = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                int uu = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                int vv = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                output[yIndex++] = (byte) clamp(yy);
                if ((y & 1) == 0 && (x & 1) == 0) {
                    if (semiPlanar) {
                        output[uIndex] = (byte) clamp(uu);
                        output[vIndex] = (byte) clamp(vv);
                        uIndex += 2;
                        vIndex += 2;
                    } else {
                        output[uIndex++] = (byte) clamp(uu);
                        output[vIndex++] = (byte) clamp(vv);
                    }
                }
            }
        }
    }

    private static int clamp(int value) { return Math.max(0, Math.min(255, value)); }

    private static final class EncoderState {
        int track = -1;
        boolean started;
    }
}
