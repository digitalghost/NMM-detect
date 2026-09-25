package com.digitalghost.nmmprobe;

/** Pixel coordinates shared by model padding, resize and the geometry tests. */
final class DepthCoordinates {
    static float[] unpadCamera(float[] input, int left, int top, int w, int h, int targetW, int targetH) {
        float[] camera = input.clone();
        float sx = targetW / (float)w, sy = targetH / (float)h;
        camera[0] *= sx; camera[1] *= sx; camera[2] = (camera[2] - left) * sx;
        camera[3] *= sy; camera[4] *= sy; camera[5] = (camera[5] - top) * sy;
        return camera;
    }

    static float[] unpadDepth(float[] input, int w, int h, int modelW, int modelH,
                             int left, int top, int contentW, int contentH, int targetW, int targetH) {
        float[] output = new float[targetW * targetH];
        float minX=left*w/(float)modelW, maxX=Math.max(minX,(left+contentW)*w/(float)modelW-1);
        float minY=top*h/(float)modelH, maxY=Math.max(minY,(top+contentH)*h/(float)modelH-1);
        for (int y = 0; y < targetH; y++) {
            float py = Math.max(0,Math.min(h-1,Math.max(minY, Math.min(maxY, (top+(y+.5f)*contentH/targetH)*h/modelH-.5f))));
            int y0 = (int)py, y1 = Math.min(h-1,y0+1); float fy = py-y0;
            for (int x = 0; x < targetW; x++) {
                float px = Math.max(0,Math.min(w-1,Math.max(minX, Math.min(maxX, (left+(x+.5f)*contentW/targetW)*w/modelW-.5f))));
                int x0 = (int)px, x1 = Math.min(w-1,x0+1); float fx = px-x0;
                float a = input[y0*w+x0]*(1-fx)+input[y0*w+x1]*fx;
                float b = input[y1*w+x0]*(1-fx)+input[y1*w+x1]*fx;
                output[y*targetW+x]=a*(1-fy)+b*fy;
            }
        }
        return output;
    }
}
