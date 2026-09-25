package com.digitalghost.nmmprobe;

import java.util.Arrays;

/** CPU counterpart of backend/nmm_shader.py. Depth stays in model units. */
final class PerspectiveGeometry {
    private PerspectiveGeometry() { }

    static float[] normals(float[] depth, float[] mask, int width, int height,
                           float[] camera, boolean detail) {
        int count = width * height;
        if (width < 2 || height < 2 || depth.length != count || mask.length != count)
            throw new IllegalArgumentException("Invalid geometry dimensions");
        float[] valid = new float[count], values = new float[count], samples = new float[count];
        int used = 0;
        for (int i = 0; i < count; i++) if (mask[i] > .1f && Float.isFinite(depth[i]) && depth[i] > 1e-6f) {
            valid[i] = 1; values[i] = depth[i]; samples[used++] = depth[i];
        }
        if (used < 10) throw new IllegalArgumentException("主体深度无效或过小");
        Arrays.sort(samples, 0, used);
        float span = percentile(samples, used, .98f) - percentile(samples, used, .02f);
        float sigma = detail ? clip(Math.max(width, height) / 1800f, .38f, .72f)
                : clip(Math.max(width, height) / 420f, 1.15f, 3.4f);
        float[] smooth = maskedSmooth(values, valid, width, height, detail ? sigma : Math.max(.8f, sigma * .42f));
        if (!detail) {
            float[] coarse = maskedSmooth(values, valid, width, height, sigma);
            for (int i = 0; i < count; i++) smooth[i] = smooth[i] * .58f + coarse[i] * .42f;
        }
        smooth = bilateral(smooth, width, height,
                Math.max(span * (detail ? .018f : .045f), detail ? 1e-6f : 1e-5f),
                detail ? Math.max(.65f, sigma) : Math.max(1.25f, sigma * 1.05f));
        float fx = .9f * Math.max(width, height), fy = fx;
        float cx = (width - 1) * .5f, cy = (height - 1) * .5f;
        if (camera != null) {
            if (camera.length != 9) throw new IllegalArgumentException("Invalid camera matrix");
            for (float value : camera) if (!Float.isFinite(value)) throw new IllegalArgumentException("Invalid camera value");
            fx = camera[0]; fy = camera[4]; cx = camera[2]; cy = camera[5];
            if (fx <= 0 || fy <= 0) throw new IllegalArgumentException("Invalid focal length");
        }
        float[] result = new float[count * 3];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int xm = Math.max(0, x - 1), xp = Math.min(width - 1, x + 1);
            int ym = Math.max(0, y - 1), yp = Math.min(height - 1, y + 1);
            float zl = smooth[y * width + xm], zr = smooth[y * width + xp];
            float zt = smooth[ym * width + x], zb = smooth[yp * width + x];
            float ax = ((xp - cx) * zr - (xm - cx) * zl) / fx / (xp - xm);
            float ay = (y - cy) * (zr - zl) / fy / (xp - xm);
            float az = (zr - zl) / (xp - xm);
            float bx = (x - cx) * (zb - zt) / fx / (yp - ym);
            float by = ((yp - cy) * zb - (ym - cy) * zt) / fy / (yp - ym);
            float bz = (zb - zt) / (yp - ym);
            float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
            float length = Math.max(1e-6f, (float)Math.sqrt(nx * nx + ny * ny + nz * nz));
            if (nz < 0) length = -length;
            int p = (y * width + x) * 3;
            result[p] = nx / length; result[p + 1] = ny / length; result[p + 2] = nz / length;
        }
        float normalSigma = detail ? .28f : Math.max(.65f, sigma * .38f);
        float[] weights = gaussian(valid, width, height, normalSigma);
        for (int channel = 0; channel < 3; channel++) {
            for (int i = 0; i < count; i++) values[i] = result[i * 3 + channel] * valid[i];
            float[] filtered = gaussian(values, width, height, normalSigma);
            for (int i = 0; i < count; i++) result[i * 3 + channel] = filtered[i] / Math.max(weights[i], 1e-6f);
        }
        for (int i = 0; i < count; i++) {
            int p = i * 3;
            float length = (float)Math.sqrt(result[p]*result[p] + result[p+1]*result[p+1] + result[p+2]*result[p+2]);
            if (mask[i] <= .1f || !Float.isFinite(length) || length < 1e-6f) {
                result[p] = result[p+1] = 0; result[p+2] = 1;
            } else for (int c = 0; c < 3; c++) result[p+c] /= length;
        }
        return result;
    }

    private static float percentile(float[] sorted, int count, float fraction) {
        float position = (count - 1) * fraction;
        int lo = (int)position, hi = Math.min(count - 1, lo + 1);
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (position - lo);
    }

    private static float[] maskedSmooth(float[] values, float[] mask, int w, int h, float sigma) {
        float[] result = gaussian(values, w, h, sigma), weights = gaussian(mask, w, h, sigma);
        for (int i = 0; i < result.length; i++) result[i] /= Math.max(weights[i], 1e-6f);
        return result;
    }

    static float[] gaussian(float[] input, int w, int h, float sigma) {
        int radius = (int)(4 * sigma + .5f);
        float[] kernel = new float[2 * radius + 1];
        double sum = 0;
        for (int k = -radius; k <= radius; k++) { kernel[k+radius] = (float)Math.exp(-.5*k*k/(sigma*sigma)); sum += kernel[k+radius]; }
        for (int k = 0; k < kernel.length; k++) kernel[k] /= sum;
        float[] temporary = new float[input.length], output = new float[input.length];
        // scipy gaussian_filter uses half-sample symmetric reflection, axis 0 first.
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            double value = 0;
            for (int k = -radius; k <= radius; k++) value += input[reflect(y+k,h,false)*w+x]*kernel[k+radius];
            temporary[y*w+x] = (float)value;
        }
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            double value = 0;
            for (int k = -radius; k <= radius; k++) value += temporary[y*w+reflect(x+k,w,false)]*kernel[k+radius];
            output[y*w+x] = (float)value;
        }
        return output;
    }

    private static float[] bilateral(float[] input, int w, int h, float color, float space) {
        int radius = Math.max(1, Math.round(space * 1.5f));
        float[] output = new float[input.length];
        int size = (2*radius+1)*(2*radius+1), used = 0;
        int[] dx = new int[size], dy = new int[size]; float[] spatial = new float[size];
        for (int y = -radius; y <= radius; y++) for (int x = -radius; x <= radius; x++) if (x*x+y*y <= radius*radius) {
            dx[used]=x;dy[used]=y;spatial[used++]=(float)Math.exp(-.5*(x*x+y*y)/(space*space));
        }
        // Lookup table avoids an exp() per pixel/neighbour. Error is below PNG quantization.
        float[] range = new float[4097];
        for (int k=0;k<range.length;k++) range[k]=(float)Math.exp(-.5*Math.pow(k*8.0/(range.length-1),2));
        for (int y=0;y<h;y++) for (int x=0;x<w;x++) {
            float center=input[y*w+x]; double sum=0, weight=0;
            for (int k=0;k<used;k++) {
                float sample=input[reflect(y+dy[k],h,true)*w+reflect(x+dx[k],w,true)];
                float position=Math.min(4096,Math.abs(sample-center)/color*512);
                int lo=(int)position, hi=Math.min(4096,lo+1);
                float factor=spatial[k]*(range[lo]+(range[hi]-range[lo])*(position-lo));
                sum+=sample*factor;weight+=factor;
            }
            output[y*w+x]=(float)(sum/Math.max(weight,1e-12));
        }
        return output;
    }

    private static int reflect(int p, int size, boolean whole) {
        if (size == 1) return 0;
        while (p < 0 || p >= size) p = p < 0 ? -p-(whole?0:1) : 2*size-p-(whole?2:1);
        return p;
    }
    private static float clip(float v,float lo,float hi) { return Math.max(lo,Math.min(hi,v)); }
}
