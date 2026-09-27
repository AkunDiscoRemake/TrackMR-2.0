// TrackMR vision kernels: camera YUV_420_888 -> RGBA crop/resize for the hand landmarker.
// Single pass, fixed point, no heap allocation per frame. Writes straight into the
// destination Bitmap (AndroidBitmap_lockPixels) so there is no Java-side copy.
#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <cmath>
#include <cstdint>
#include <vector>

#define LOG_TAG "TrackMR-Vision"

namespace {

struct Lut {
    float gamma = -1.f;
    uint8_t table[256];
    void build(float g) {
        if (std::fabs(g - gamma) < 0.02f) return;
        gamma = g;
        for (int i = 0; i < 256; ++i) {
            float v = std::pow(i / 255.f, g) * 255.f;
            table[i] = (uint8_t) (v > 255.f ? 255 : (v < 0.f ? 0 : v));
        }
    }
};

thread_local Lut gLut;
thread_local std::vector<int> gXs;

inline uint8_t clamp8(int v) { return (uint8_t) (v < 0 ? 0 : (v > 255 ? 255 : v)); }

}  // namespace

extern "C" JNIEXPORT jfloat JNICALL
Java_com_trackmr_hands_VisionNative_yuvToBitmap(
        JNIEnv *env, jclass, jobject yBuf, jobject uBuf, jobject vBuf,
        jint yStride, jint uvStride, jint uvPixelStride, jint srcW, jint srcH,
        jint roiX, jint roiY, jint roiW, jint roiH, jobject bitmap, jboolean lowLightBoost) {
    const uint8_t *yp = (const uint8_t *) env->GetDirectBufferAddress(yBuf);
    const uint8_t *up = (const uint8_t *) env->GetDirectBufferAddress(uBuf);
    const uint8_t *vp = (const uint8_t *) env->GetDirectBufferAddress(vBuf);
    if (!yp || !up || !vp) return -1.f;

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return -1.f;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return -1.f;
    const int dstW = (int) info.width, dstH = (int) info.height;

    if (roiX < 0) roiX = 0;
    if (roiY < 0) roiY = 0;
    if (roiX + roiW > srcW) roiW = srcW - roiX;
    if (roiY + roiH > srcH) roiH = srcH - roiY;
    if (roiW <= 1 || roiH <= 1) return -1.f;

    // 1) Mean luma of the ROI on a sparse grid (~1/64 of the pixels).
    uint32_t sum = 0, cnt = 0;
    for (int y = roiY; y < roiY + roiH; y += 8) {
        const uint8_t *row = yp + y * yStride;
        for (int x = roiX; x < roiX + roiW; x += 8) { sum += row[x]; ++cnt; }
    }
    const float mean = cnt ? (float) sum / cnt : 128.f;

    // 2) Adaptive gamma for dim scenes keeps landmark detection stable in poor light.
    bool useLut = false;
    if (lowLightBoost && mean < 95.f) {
        float m = mean < 8.f ? 8.f : mean;
        float g = std::log(0.42f) / std::log(m / 255.f);  // maps mean -> ~42% grey
        if (g < 0.35f) g = 0.35f;
        if (g < 0.97f) { gLut.build(g); useLut = true; }
    }

    void *pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return -1.f;

    // 3) Nearest-neighbour resample of the ROI into the bitmap (letterboxed, aspect kept).
    const float scale = std::fmin((float) dstW / roiW, (float) dstH / roiH);
    const int outW = (int) (roiW * scale), outH = (int) (roiH * scale);
    const int offX = (dstW - outW) / 2, offY = (dstH - outH) / 2;
    const int stepX = (int) ((roiW << 16) / (outW > 0 ? outW : 1));
    const int stepY = (int) ((roiH << 16) / (outH > 0 ? outH : 1));

    if ((int) gXs.size() < dstW) gXs.resize(dstW);
    for (int x = 0; x < outW; ++x) gXs[x] = roiX + ((x * stepX) >> 16);

    uint8_t *dst = (uint8_t *) pixels;
    const int dstStride = (int) info.stride;
    for (int y = 0; y < dstH; ++y) {
        uint32_t *out = (uint32_t *) (dst + y * dstStride);
        if (y < offY || y >= offY + outH) {
            for (int x = 0; x < dstW; ++x) out[x] = 0xFF000000u;
            continue;
        }
        const int sy = roiY + (((y - offY) * stepY) >> 16);
        const uint8_t *yr = yp + sy * yStride;
        const uint8_t *ur = up + (sy >> 1) * uvStride;
        const uint8_t *vr = vp + (sy >> 1) * uvStride;
        for (int x = 0; x < offX; ++x) out[x] = 0xFF000000u;
        for (int x = 0; x < outW; ++x) {
            const int sx = gXs[x];
            int Y = yr[sx];
            if (useLut) Y = gLut.table[Y];
            const int ci = (sx >> 1) * uvPixelStride;
            const int U = ur[ci] - 128;
            const int V = vr[ci] - 128;
            const uint8_t R = clamp8(Y + ((359 * V) >> 8));
            const uint8_t G = clamp8(Y - ((88 * U + 183 * V) >> 8));
            const uint8_t B = clamp8(Y + ((454 * U) >> 8));
            out[offX + x] = 0xFF000000u | ((uint32_t) B << 16) | ((uint32_t) G << 8) | R;
        }
        for (int x = offX + outW; x < dstW; ++x) out[x] = 0xFF000000u;
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return mean;
}
