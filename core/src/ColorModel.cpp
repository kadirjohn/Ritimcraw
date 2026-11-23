// rawedit color model v2 — CPU node reference (F1-1). See ColorModel.h.
// The node arithmetic is a port of Klipp's GradeNode (engine/Grade.kt:
// computeParams / apply / logCurve / primaries / Curve.table), N1 subset.

#include <rawedit/ColorModel.h>

#include <algorithm>
#include <cmath>
#include <cstring>
#include <memory>

#include <rawedit/Develop.h>
#include <rawedit/Lut.h>

namespace rawedit {

namespace {

constexpr float kPi = 3.14159265358979323846f;
// Rec.709 luma on the node's working values (see header note).
constexpr float kNodeLuma[3] = {0.2126f, 0.7152f, 0.0722f};

struct Mats {
    float to709[9];
    float toDwg[9];
    Mats() {
        gamutToGamut(GamutId::Rec709, GamutId::DaVinciWideGamut, toDwg);
        gamutToGamut(GamutId::DaVinciWideGamut, GamutId::Rec709, to709);
    }
};
const Mats& mats() {
    static const Mats m;
    return m;
}

inline void mul3(const float m[9], const float in[3], float out[3]) {
    const float x = in[0], y = in[1], z = in[2];
    out[0] = m[0] * x + m[1] * y + m[2] * z;
    out[1] = m[3] * x + m[4] * y + m[5] * z;
    out[2] = m[6] * x + m[7] * y + m[8] * z;
}

// ---- wheel / primaries (Klipp Wheel.rgb, GradeNode.primaries) ----
void wheelRgb(const NodeWheel& w, float out[3]) {
    const float m = std::min(1.0f, std::hypot(w.x, w.y));
    if (m < 1e-6f) {
        out[0] = out[1] = out[2] = 0.0f;
        return;
    }
    const float th = std::atan2(w.y, w.x);
    const float k = m * 2.0f / 3.0f;
    out[0] = k * std::cos(th);
    out[1] = k * std::cos(th - 2.0f * kPi / 3.0f);
    out[2] = k * std::cos(th + 2.0f * kPi / 3.0f);
}

void primaries(float lift, float gain, float gamma, float& black, float& slope,
               float& expo) {
    const float d = std::max(1.0f + lift * (gain - 1.0f), 0.05f);
    expo = gamma >= 0.0f ? 1.0f / (1.0f + gamma) : 1.0f - gamma;
    black = gain * lift / d;
    slope = gain * (1.0f - lift) / d;
}

// ---- table sampling (Klipp sample / sampleExt / curveSample) ----
float sample256(const float* t, float x) {
    const float p = std::min(std::max(x, 0.0f), 1.0f) * 255.0f;
    const int i = std::min(static_cast<int>(p), 254);
    const float f = p - static_cast<float>(i);
    return t[i] + (t[i + 1] - t[i]) * f;
}

float sampleExt256(const float* t, float x) {
    if (x < 0.0f)
        return t[0] + x * (t[1] - t[0]) * 255.0f;
    if (x > 1.0f)
        return t[255] + (x - 1.0f) * (t[255] - t[254]) * 255.0f;
    return sample256(t, x);
}

// Everything a node needs per pixel, computed once per node.
struct Compiled {
    bool lutActive = false;
    float lift[3], gain[3], offset[3], gammaExp[3];
    bool hasY = false;
    float ySide[3] = {0, 1, 1};
    float contrast, pivot;
    float wb[3];
    float sat;
    float hueMat[9];
    float mix;
    std::vector<float> curves; // 3 * kNodeCurveN, empty = none
    // node LUT
    const Lut* lut = nullptr;
    CompiledLutBinding binding{};
    float lutMix = 1.0f;
};

Compiled compile(const GradeNode& n) {
    Compiled c;
    float l[3], g[3], gn[3], o[3];
    wheelRgb(n.lift, l);
    wheelRgb(n.gamma, g);
    wheelRgb(n.gain, gn);
    wheelRgb(n.offset, o);
    for (int i = 0; i < 3; ++i) {
        float a, b, e;
        primaries(l[i] * 0.25f + n.lift.master * 0.5f,
                  std::max(1.0f + gn[i] * 0.5f + n.gain.master, 0.0f),
                  g[i] * 0.5f + n.gamma.master, a, b, e);
        c.lift[i] = a;
        c.gain[i] = b;
        c.gammaExp[i] = e;
        c.offset[i] = o[i] * 0.2f + n.offset.master * 0.25f;
    }
    {
        float a, b, e;
        primaries(n.lift.master * 0.5f, std::max(1.0f + n.gain.master, 0.0f),
                  n.gamma.master, a, b, e);
        c.ySide[0] = a;
        c.ySide[1] = b;
        c.ySide[2] = e;
        // Klipp always runs the luminance side; with all masters neutral it
        // is (0, 1, 1) and a no-op by construction (kept to stay a port).
        c.hasY = true;
    }
    c.contrast = n.contrast;
    c.pivot = n.pivot;
    c.wb[0] = 1.0f + 0.18f * n.temp;
    c.wb[1] = 1.0f - 0.12f * n.tint;
    c.wb[2] = 1.0f - 0.18f * n.temp;
    const float a = n.hue * kPi / 180.0f;
    const float cs = std::cos(a), sn = std::sin(a);
    const float k = 1.0f / 3.0f, sq = std::sqrt(k);
    const float h[9] = {
        cs + (1 - cs) * k,       k * (1 - cs) - sq * sn, k * (1 - cs) + sq * sn,
        k * (1 - cs) + sq * sn,  cs + k * (1 - cs),      k * (1 - cs) - sq * sn,
        k * (1 - cs) - sq * sn,  k * (1 - cs) + sq * sn, cs + k * (1 - cs)};
    std::memcpy(c.hueMat, h, sizeof(h));
    c.sat = n.sat;
    c.mix = std::min(std::max(n.mix, 0.0f), 1.0f);

    if (n.hasCurves() || n.hasLog()) {
        const std::vector<float> y = nodeCurveTable(n.curveY);
        const std::vector<float> ch[3] = {nodeCurveTable(n.curveR),
                                          nodeCurveTable(n.curveG),
                                          nodeCurveTable(n.curveB)};
        float sh[3], md[3], hi[3];
        wheelRgb(n.logShadow, sh);
        wheelRgb(n.logMid, md);
        wheelRgb(n.logHi, hi);
        const bool hasLog = n.hasLog();
        c.curves.assign(3 * static_cast<size_t>(kNodeCurveN), 0.0f);
        for (int cI = 0; cI < 3; ++cI) {
            std::vector<float> lg;
            if (hasLog) {
                lg = nodeLogCurve(sh[cI] * 0.25f + n.logShadow.master * 0.5f,
                                  md[cI] * 0.25f + n.logMid.master * 0.5f,
                                  hi[cI] * 0.25f + n.logHi.master * 0.5f,
                                  n.logLow, n.logHigh);
            }
            for (int i = 0; i < kNodeCurveN; ++i) {
                const float x = hasLog ? lg[static_cast<size_t>(i)] : nodeCurveX(i);
                c.curves[static_cast<size_t>(cI) * kNodeCurveN + i] =
                    sampleExt256(ch[cI].data(), sampleExt256(y.data(), x));
            }
        }
    }
    if (n.hasLut()) {
        c.lutActive = true;
        c.lut = n.lut;
        c.binding = compileLutBinding(n.lutInterp);
        c.lutMix = std::min(std::max(n.lutMix, 0.0f), 1.0f);
    }
    return c;
}

// One DI pixel through one compiled node (Klipp GradeNode.apply, N1).
void applyCompiled(const Compiled& p, float rgb[3]) {
    const float inR = rgb[0], inG = rgb[1], inB = rgb[2];
    const float yIn = kNodeLuma[0] * rgb[0] + kNodeLuma[1] * rgb[1] + kNodeLuma[2] * rgb[2];
    for (int i = 0; i < 3; ++i) {
        const float c = p.lift[i] + p.gain[i] * rgb[i] + p.offset[i];
        rgb[i] = p.gammaExp[i] == 1.0f ? c : std::pow(std::max(c, 0.0f), p.gammaExp[i]);
    }
    if (p.hasY) {
        float yv = p.ySide[0] + p.ySide[1] * yIn +
                   kNodeLuma[0] * p.offset[0] + kNodeLuma[1] * p.offset[1] +
                   kNodeLuma[2] * p.offset[2];
        if (p.ySide[2] != 1.0f)
            yv = std::pow(std::max(yv, 0.0f), p.ySide[2]);
        const float d = yv - (kNodeLuma[0] * rgb[0] + kNodeLuma[1] * rgb[1] +
                              kNodeLuma[2] * rgb[2]);
        for (int i = 0; i < 3; ++i)
            rgb[i] += d;
    }
    for (int i = 0; i < 3; ++i)
        rgb[i] = ((rgb[i] - p.pivot) * p.contrast + p.pivot) * p.wb[i];
    const float r = rgb[0], g = rgb[1], b = rgb[2];
    const float hr = p.hueMat[0] * r + p.hueMat[1] * g + p.hueMat[2] * b;
    const float hg = p.hueMat[3] * r + p.hueMat[4] * g + p.hueMat[5] * b;
    const float hb = p.hueMat[6] * r + p.hueMat[7] * g + p.hueMat[8] * b;
    const float luma = kNodeLuma[0] * hr + kNodeLuma[1] * hg + kNodeLuma[2] * hb;
    // not clamped: values below black and above white reach the curves
    rgb[0] = luma + (hr - luma) * p.sat;
    rgb[1] = luma + (hg - luma) * p.sat;
    rgb[2] = luma + (hb - luma) * p.sat;
    if (!p.curves.empty()) {
        for (int i = 0; i < 3; ++i)
            rgb[i] = nodeCurveSample(&p.curves[static_cast<size_t>(i) * kNodeCurveN], rgb[i]);
    }
    if (p.lutActive) {
        float lin[3], l709[3], out709[3], outDwg[3], outDi[3];
        // DI -> DWG linear -> linear 709 (the managed binding's working space)
        for (int i = 0; i < 3; ++i)
            lin[i] = decodeTransfer(rgb[i], TransferId::DaVinciIntermediate);
        mul3(mats().to709, lin, l709);
        evaluateManagedLutPixel(*p.lut, p.binding, l709, out709);
        mul3(mats().toDwg, out709, outDwg);
        for (int i = 0; i < 3; ++i)
            outDi[i] = encodeTransfer(outDwg[i], TransferId::DaVinciIntermediate);
        for (int i = 0; i < 3; ++i)
            rgb[i] = rgb[i] + (outDi[i] - rgb[i]) * p.lutMix;
    }
    rgb[0] = inR + (rgb[0] - inR) * p.mix;
    rgb[1] = inG + (rgb[1] - inG) * p.mix;
    rgb[2] = inB + (rgb[2] - inB) * p.mix;
}

} // namespace

// ---- DI / DWG glue ----

void rec709ToDwgMatrix(float out[9]) { std::memcpy(out, mats().toDwg, sizeof(float) * 9); }
void dwgToRec709Matrix(float out[9]) { std::memcpy(out, mats().to709, sizeof(float) * 9); }

void linear709ToDwg(const float in[3], float out[3]) { mul3(mats().toDwg, in, out); }
void dwgToLinear709(const float in[3], float out[3]) { mul3(mats().to709, in, out); }

void linear709ToDi(const float in[3], float out[3]) {
    float dwg[3];
    mul3(mats().toDwg, in, dwg);
    for (int i = 0; i < 3; ++i)
        out[i] = encodeTransfer(dwg[i], TransferId::DaVinciIntermediate);
}

void diToLinear709(const float in[3], float out[3]) {
    float dwg[3];
    for (int i = 0; i < 3; ++i)
        dwg[i] = decodeTransfer(in[i], TransferId::DaVinciIntermediate);
    mul3(mats().to709, dwg, out);
}

// ---- curves ----

float nodeCurveX(int i) {
    return kNodeCurveLo + static_cast<float>(i) * (kNodeCurveHi - kNodeCurveLo) /
                              static_cast<float>(kNodeCurveN - 1);
}

float nodeCurveSample(const float* t, float x) {
    const float p = (x - kNodeCurveLo) / (kNodeCurveHi - kNodeCurveLo) *
                    static_cast<float>(kNodeCurveN - 1);
    const int i = std::min(std::max(static_cast<int>(std::floor(p)), 0), kNodeCurveN - 2);
    const float d0 = t[i] - nodeCurveX(i);
    const float d1 = t[i + 1] - nodeCurveX(i + 1);
    return x + d0 + (d1 - d0) * (p - static_cast<float>(i));
}

std::vector<float> nodeCurveTable(const NodeCurve& c) {
    std::vector<std::pair<float, float>> p = c.points;
    std::stable_sort(p.begin(), p.end(),
                     [](const auto& a, const auto& b) { return a.first < b.first; });
    const size_t n = p.size();
    std::vector<float> out(256);
    if (n < 2) {
        for (int i = 0; i < 256; ++i)
            out[static_cast<size_t>(i)] = static_cast<float>(i) / 255.0f;
        return out;
    }
    std::vector<float> xs(n), ys(n), d(n - 1), m(n);
    for (size_t i = 0; i < n; ++i) {
        xs[i] = p[i].first;
        ys[i] = p[i].second;
    }
    for (size_t i = 0; i + 1 < n; ++i)
        d[i] = (ys[i + 1] - ys[i]) / std::max(1e-6f, xs[i + 1] - xs[i]);
    m[0] = d[0];
    m[n - 1] = d[n - 2];
    for (size_t i = 1; i + 1 < n; ++i)
        m[i] = (d[i - 1] * d[i] <= 0.0f) ? 0.0f : (d[i - 1] + d[i]) / 2.0f;
    for (size_t i = 0; i + 1 < n; ++i) {
        if (d[i] == 0.0f) {
            m[i] = 0.0f;
            m[i + 1] = 0.0f;
            continue;
        }
        const float a = m[i] / d[i], b = m[i + 1] / d[i];
        const float s = a * a + b * b;
        if (s > 9.0f) {
            const float t = 3.0f / std::sqrt(s);
            m[i] = t * a * d[i];
            m[i + 1] = t * b * d[i];
        }
    }
    for (int k = 0; k < 256; ++k) {
        const float x = static_cast<float>(k) / 255.0f;
        float v;
        if (x <= xs[0]) {
            v = ys[0];
        } else if (x >= xs[n - 1]) {
            v = ys[n - 1];
        } else {
            size_t i = 0;
            while (i < n - 2 && x > xs[i + 1])
                ++i;
            const float h = xs[i + 1] - xs[i];
            const float t = h > 1e-6f ? (x - xs[i]) / h : 0.0f;
            const float t2 = t * t, t3 = t2 * t;
            v = (2 * t3 - 3 * t2 + 1) * ys[i] + (t3 - 2 * t2 + t) * h * m[i] +
                (-2 * t3 + 3 * t2) * ys[i + 1] + (t3 - t2) * h * m[i + 1];
        }
        out[static_cast<size_t>(k)] =
            std::isnan(v) ? x : std::min(std::max(v, 0.0f), 1.0f);
    }
    return out;
}

// Klipp GradeNode.logCurve (double precision inside, as the original).
std::vector<float> nodeLogCurve(float shadow, float mid, float high, float low,
                                float top) {
    constexpr double kLogMidPow = 1.25;
    constexpr double kLogSoft = 0.063f;
    const double x0 = kNodeCurveLo - 0.25;
    const double dx = 1.5 / 3072;
    const int n = static_cast<int>(std::lround((kNodeCurveHi + 0.25 - x0) / dx)) + 1;
    const double ks = shadow >= 0.0f ? shadow : shadow / low;
    const double kh = high >= 0.0f ? high / (1.0f - top) : high;
    const double m = std::min(std::max(mid, -0.995f), 0.995f);
    const double g = std::pow((1 - m) / (1 + m), kLogMidPow);
    const double lo = low, hi = top;
    auto d = [&](double x) {
        if (x < lo)
            return ks * (lo - x);
        if (x > hi)
            return kh * (x - hi);
        return lo + (hi - lo) * std::pow((x - lo) / (hi - lo), g) - x;
    };
    std::vector<double> c(static_cast<size_t>(n), 0.0);
    double prev = d(x0);
    for (int i = 1; i < n; ++i) {
        const double cur = d(x0 + i * dx);
        c[static_cast<size_t>(i)] = c[static_cast<size_t>(i) - 1] + (prev + cur) / 2 * dx;
        prev = cur;
    }
    auto at = [&](double x) {
        const double p = std::min(std::max((x - x0) / dx, 0.0), n - 1.0);
        const int i = std::min(static_cast<int>(p), n - 2);
        return c[static_cast<size_t>(i)] +
               (c[static_cast<size_t>(i) + 1] - c[static_cast<size_t>(i)]) * (p - i);
    };
    std::vector<float> out(static_cast<size_t>(kNodeCurveN));
    for (int i = 0; i < kNodeCurveN; ++i) {
        const double x = nodeCurveX(i);
        out[static_cast<size_t>(i)] =
            static_cast<float>(x + (at(x + kLogSoft) - at(x - kLogSoft)) / (2 * kLogSoft));
    }
    return out;
}

// ---- node chain ----

bool GradeNode::isNeutral() const {
    return !enabled || mix <= 0.0001f ||
           (lift.isNeutral() && gamma.isNeutral() && gain.isNeutral() &&
            offset.isNeutral() && contrast == 1.0f && sat == 1.0f && hue == 0.0f &&
            temp == 0.0f && tint == 0.0f && !hasCurves() && !hasLog() && !hasLut());
}

std::string validateNodeChain(const GradeNode* nodes, size_t n) {
    if (n > kMaxNodes)
        return "node chain longer than kMaxNodes";
    size_t luts = 0;
    for (size_t i = 0; i < n; ++i)
        if (nodes[i].enabled && nodes[i].hasLut())
            ++luts;
    if (luts > kMaxNodeLuts)
        return "more than kMaxNodeLuts nodes with a LUT";
    return std::string();
}

void applyNodeDi(const GradeNode& node, float di[3]) {
    if (node.isNeutral())
        return;
    applyCompiled(compile(node), di);
}

bool applyNodes(ImageF& img, const GradeNode* nodes, size_t n, NodesOutput out) {
    if (!validateNodeChain(nodes, n).empty())
        return false;
    const size_t pixels = static_cast<size_t>(img.w) * img.h;
    if (img.rgb.size() != pixels * 3)
        return true;

    std::vector<Compiled> chain;
    for (size_t i = 0; i < n; ++i)
        if (!nodes[i].isNeutral())
            chain.push_back(compile(nodes[i]));

    if (chain.empty()) {
        if (out == NodesOutput::Rec709Linear)
            return true; // nodes=[] / all neutral: bit-identical, no work
        for (size_t p = 0; p < pixels; ++p) {
            float* px = &img.rgb[p * 3];
            float o[3];
            linear709ToDwg(px, o);
            px[0] = o[0]; px[1] = o[1]; px[2] = o[2];
        }
        return true;
    }

    for (size_t p = 0; p < pixels; ++p) {
        float* px = &img.rgb[p * 3];
        float di[3];
        linear709ToDi(px, di);
        for (const Compiled& c : chain)
            applyCompiled(c, di);
        float dwg[3];
        for (int i = 0; i < 3; ++i)
            dwg[i] = decodeTransfer(di[i], TransferId::DaVinciIntermediate);
        if (out == NodesOutput::DwgLinear) {
            px[0] = dwg[0]; px[1] = dwg[1]; px[2] = dwg[2];
        } else {
            float o[3];
            dwgToLinear709(dwg, o);
            px[0] = o[0]; px[1] = o[1]; px[2] = o[2];
        }
    }
    return true;
}

} // namespace rawedit
