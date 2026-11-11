// Stabilization Phase 5 implementation — see Warp.h for the contract
// (the composite map, the placement decision, the fast paths). Plain
// CPU math, no GPU, no UI.

#include <rawedit/Warp.h>

#include <cmath>

namespace rawedit {

namespace {

// Inverts a 3x3 through the adjugate. Returns false when the matrix is
// (numerically) singular; out is untouched then. Same body as
// Stabilization.cpp's helper (whose copy is anonymous-namespace there,
// so it is repeated here verbatim rather than exposed an internal
// header for one caller).
bool matInv(const double m[9], double out[9]) {
    const double c00 = m[4] * m[8] - m[5] * m[7];
    const double c01 = m[5] * m[6] - m[3] * m[8];
    const double c02 = m[3] * m[7] - m[4] * m[6];
    const double det = m[0] * c00 + m[1] * c01 + m[2] * c02;
    if (!std::isfinite(det) || std::fabs(det) < 1e-12)
        return false;
    const double inv = 1.0 / det;
    out[0] = c00 * inv;
    out[1] = (m[2] * m[7] - m[1] * m[8]) * inv;
    out[2] = (m[1] * m[5] - m[2] * m[4]) * inv;
    out[3] = c01 * inv;
    out[4] = (m[0] * m[8] - m[2] * m[6]) * inv;
    out[5] = (m[2] * m[3] - m[0] * m[5]) * inv;
    out[6] = c02 * inv;
    out[7] = (m[1] * m[6] - m[0] * m[7]) * inv;
    out[8] = (m[0] * m[4] - m[1] * m[3]) * inv;
    return true;
}

// Homogeneous point map: out = H * (x, y, 1), perspective-divided. Same
// body as Stabilization.cpp's helper (see matInv's comment).
bool mapPoint(const double h[9], double x, double y, double& ox, double& oy) {
    const double u = h[0] * x + h[1] * y + h[2];
    const double v = h[3] * x + h[4] * y + h[5];
    const double w = h[6] * x + h[7] * y + h[8];
    if (!std::isfinite(w) || std::fabs(w) < 1e-12)
        return false;
    ox = u / w;
    oy = v / w;
    return std::isfinite(ox) && std::isfinite(oy);
}

} // namespace

ImageF warpFrame(const ImageF& src, const double h[9], double zoom,
                 double cx, double cy) {
    if (src.w == 0 || src.h == 0 ||
        src.rgb.size() < static_cast<size_t>(src.w) * src.h * 3u)
        return ImageF{};

    bool identity = true;
    for (int i = 0; i < 9; ++i) {
        const double want = i == 0 || i == 4 || i == 8 ? 1.0 : 0.0;
        if (h[i] != want) {
            identity = false;
            break;
        }
    }
    if (identity && zoom == 1.0)
        return src; // byte-identical: no copy, no resample

    if (!std::isfinite(zoom))
        return src; // honest no-op on a broken plan
    if (zoom < 1.0)
        zoom = 1.0; // a warp may not magnify the frame

    double hInv[9];
    if (!matInv(h, hInv))
        return src; // degenerate plan: an honest no-op, never garbage

    ImageF out;
    out.w = src.w;
    out.h = src.h;
    out.rgb.assign(static_cast<size_t>(src.w) * src.h * 3u, 0.0f);

    const double wD = static_cast<double>(src.w);
    const double hD = static_cast<double>(src.h);
    const double maxU = wD - 1.0;
    const double maxV = hD - 1.0;
    const size_t stride = static_cast<size_t>(src.w) * 3u;

    // p_src = H^-1 * ( c + (p_out - c) / Z ). The scale-about-c map S is
    // q.x = cx + (x - cx)/Z, q.y = cy + (y - cy)/Z, so the composite
    // M = Hinv * S is built once per frame and every output pixel is one
    // homogeneous tap through M (six multiplies) plus one bilinear tap.
    const double s = 1.0 / zoom;
    double m[9];
    for (int i = 0; i < 3; ++i) {
        m[i * 3 + 0] = hInv[i * 3 + 0] * s;
        m[i * 3 + 1] = hInv[i * 3 + 1] * s;
        m[i * 3 + 2] = hInv[i * 3 + 0] * (cx - cx * s)
                     + hInv[i * 3 + 1] * (cy - cy * s)
                     + hInv[i * 3 + 2];
    }

    for (uint32_t y = 0; y < src.h; ++y) {
        for (uint32_t x = 0; x < src.w; ++x) {
            double fx = 0.0, fy = 0.0;
            if (!mapPoint(m, static_cast<double>(x), static_cast<double>(y),
                          fx, fy)) {
                // Unmappable output pixel (behind the plane, or a
                // non-finite landing point): clamp to the corner, the
                // documented edge-clamp behavior. mapPoint leaves the
                // outputs untouched on its early return and may leave
                // them non-finite on the late one, so both are reset.
                fx = 0.0;
                fy = 0.0;
            }
            if (fx < 0.0) fx = 0.0;
            if (fx > maxU) fx = maxU;
            if (fy < 0.0) fy = 0.0;
            if (fy > maxV) fy = maxV;
            const uint32_t x0 = static_cast<uint32_t>(fx);
            const uint32_t y0 = static_cast<uint32_t>(fy);
            const uint32_t x1 = x0 + 1 < src.w ? x0 + 1 : x0;
            const uint32_t y1 = y0 + 1 < src.h ? y0 + 1 : y0;
            const double ax = fx - static_cast<double>(x0);
            const double ay = fy - static_cast<double>(y0);
            const float* p00 = &src.rgb[static_cast<size_t>(y0) * stride + static_cast<size_t>(x0) * 3u];
            const float* p01 = &src.rgb[static_cast<size_t>(y0) * stride + static_cast<size_t>(x1) * 3u];
            const float* p10 = &src.rgb[static_cast<size_t>(y1) * stride + static_cast<size_t>(x0) * 3u];
            const float* p11 = &src.rgb[static_cast<size_t>(y1) * stride + static_cast<size_t>(x1) * 3u];
            float* dst = &out.rgb[static_cast<size_t>(y) * stride + static_cast<size_t>(x) * 3u];
            for (int c = 0; c < 3; ++c) {
                const double top = p00[c] + (p01[c] - p00[c]) * ax;
                const double bot = p10[c] + (p11[c] - p10[c]) * ax;
                dst[c] = static_cast<float>(top + (bot - top) * ay);
            }
        }
    }
    return out;
}

} // namespace rawedit
