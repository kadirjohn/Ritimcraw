// OpenDRT v1.1.0 — C++ reference (F1-4). See OpenDrt.h.
//
// Provenance: this file is a line-by-line port of Klipp's Kotlin port
// (engine/OpenDrt.kt), itself a port of Jed Smith's OpenDRT.dctl v1.1.0
// (https://github.com/jedypod/open-display-transform, GPL-3.0-or-later).
// The look/tonescale preset tables and the matrices below were converted
// from the Kotlin source by a script (text transform, no retyping); the
// algorithm below follows the Kotlin operation order. It was NOT compared
// against the original DCTL by this repository (the DCTL is not vendored):
// the only independent evidence is the reference vectors Klipp's own test
// suite carries (see core/tests/test_opendrt.cpp for how they are used and
// what they do and do not prove).

#include <rawedit/OpenDrt.h>

#include <algorithm>
#include <cmath>

#include <rawedit/Develop.h>

namespace rawedit {

namespace {

// ---- matrices (OpenDRT.dctl constants, row-major) ----
constexpr float kDWG_TO_XYZ[9] = {0.700622392093671609f, 0.148774815123196763f, 0.101058719834803246f, 0.274118510906649016f, 0.873631895940436665f, -0.147750406847085763f, -0.0989629128832311411f, -0.137895325075543307f, 1.32591598871865268f};
constexpr float kP3D65_TO_XYZ[9] = {0.486570948648216151f, 0.265667693169093f, 0.198217285234362467f, 0.228974564069748754f, 0.691738521836506193f, 0.079286914093744984f, -4.00000000000000029e-17f, 0.0451133818589026167f, 1.04394436890097575f};
constexpr float kXYZ_TO_P3D65[9] = {2.49349691194142542f, -0.93138361791912383f, -0.402710784450716841f, -0.829488969561574696f, 1.76266406031834655f, 0.0236246858419435941f, 0.0358458302437844531f, -0.0761723892680418041f, 0.956884524007687309f};
constexpr float kXYZ_TO_REC709[9] = {3.24096994190452348f, -1.53738317757009435f, -0.498610760293003552f, -0.969243636280879506f, 1.87596750150771996f, 0.0415550574071755843f, 0.0556300796969936354f, -0.20397695888897649f, 1.05697151424287816f};
constexpr float kCWP_NORM_709[6] = {0.744192699063f, 0.873470832146f, 1.0f, 0.955936992163f, 0.905671332781f, 0.850004385027f};
constexpr float kCWP_NORM_P3[6] = {0.762687057298f, 0.884054083328f, 1.0f, 0.964320186739f, 0.923076518860f, 0.876572837784f};
constexpr float kP3_TO_REC2020[9] = {0.753833034361722221f, 0.198597369052616435f, 0.0475695965856618441f, 0.0457438489653582137f, 0.9417772198116936f, 0.0124789312229481135f, -0.0012103403545183941f, 0.0176017173010899926f, 0.983608623053428777f};
// D65 -> D93, D75, (D65 = none), D60, D55, D50 (CAT02), row-major
constexpr float kCatD65[6][9] = {
    {0.95703423023223877f, -0.0247171502560377121f, 0.0624028593301773071f, -0.0179296955466270447f, 0.990019857883453369f, 0.0248119533061981201f, 0.00127589143812656403f, 0.00427919067442417058f, 1.29345715045928955f},
    {0.981001079082489014f, -0.0116619253531098366f, 0.0265614092350006104f, -0.00843488052487373352f, 0.996506094932556152f, 0.0105696544051170349f, 0.000552809564396739006f, 0.00179840810596942902f, 1.12374722957611084f},
    {1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f}, // D65: unused (Klipp: null)
    {1.01182246208190918f, 0.00778879318386316299f, -0.0157783031463623047f, 0.00561682833358645439f, 1.00150644779205322f, -0.00628517568111419678f, -0.000335735734552145004f, -0.0010509500280022619f, 0.927366673946380615f},
    {1.02585089206695557f, 0.0179439820349216461f, -0.0332137793302536011f, 0.0129133854061365128f, 1.00214779376983643f, -0.0132421031594276428f, -0.000719940289855003032f, -0.00218106806278228803f, 0.84868013858795166f},
    {1.04257404804229736f, 0.03089117631316185f, -0.052812620997428894f, 0.0221935361623764038f, 1.00185668468475342f, -0.0210737623274326324f, -0.00116488314233720303f, -0.00342052709311246915f, 0.761789083480834961f},
};


constexpr float kSqrt3 = 1.7320508f;
constexpr float kPi = 3.14159265358979323846f;

inline float spowf(float a, float b) { return a <= 0.0f ? a : std::pow(a, b); }
inline float sdivf(float a, float b) { return b == 0.0f ? 0.0f : a / b; }
inline float exp2f_(float x) { return std::pow(2.0f, x); }

inline void mul(const float* m, float v[3]) {
    const float x = v[0], y = v[1], z = v[2];
    v[0] = m[0] * x + m[1] * y + m[2] * z;
    v[1] = m[3] * x + m[4] * y + m[5] * z;
    v[2] = m[6] * x + m[7] * y + m[8] * z;
}

// ---- presets (Klipp P class) ----
struct P {
    float tn_con = 0, tn_sh = 0, tn_toe = 0, tn_off = 0;
    bool tn_hcon_enable = false; float tn_hcon = 0, tn_hcon_pv = 0, tn_hcon_st = 0;
    bool tn_lcon_enable = false; float tn_lcon = 0, tn_lcon_w = 0;
    int cwp = 2; float cwp_lm = 0, rs_sa = 0, rs_rw = 0, rs_bw = 0;
    bool pt_enable = true; float pt_lml = 0, pt_lml_r = 0, pt_lml_g = 0, pt_lml_b = 0, pt_lmh = 0, pt_lmh_r = 0, pt_lmh_b = 0;
    bool ptl_enable = true; float ptl_c = 0, ptl_m = 0, ptl_y = 0;
    bool ptm_enable = true; float ptm_low = 0, ptm_low_rng = 0, ptm_low_st = 0, ptm_high = 0, ptm_high_rng = 0, ptm_high_st = 0;
    bool brl_enable = true; float brl = 0, brl_r = 0, brl_g = 0, brl_b = 0, brl_rng = 0, brl_st = 0;
    bool brlp_enable = true; float brlp = 0, brlp_r = 0, brlp_g = 0, brlp_b = 0;
    bool hc_enable = true; float hc_r = 0, hc_r_rng = 0;
    bool hs_rgb_enable = true; float hs_r = 0, hs_r_rng = 0, hs_g = 0, hs_g_rng = 0, hs_b = 0, hs_b_rng = 0;
    bool hs_cmy_enable = true; float hs_c = 0, hs_c_rng = 0, hs_m = 0, hs_m_rng = 0, hs_y = 0, hs_y_rng = 0;

    void ts(float con, float sh, float toe, float off, bool hconOn, float hcon,
            float hconPv, float hconSt, bool lconOn, float lcon, float lconW) {
        tn_con = con; tn_sh = sh; tn_toe = toe; tn_off = off;
        tn_hcon_enable = hconOn; tn_hcon = hcon; tn_hcon_pv = hconPv; tn_hcon_st = hconSt;
        tn_lcon_enable = lconOn; tn_lcon = lcon; tn_lcon_w = lconW;
    }

    void preset(int look, int tonescale, int whiteSel, float whiteLimit) {
        switch (look) {
        case 1: { // Arriba
            ts(1.05f, 0.5f, 0.1f, 0.01f, false, 0.0f, 1.0f, 4.0f, true, 1.5f, 0.2f);
            cwp = 2; cwp_lm = 0.25f; rs_sa = 0.35f; rs_rw = 0.25f; rs_bw = 0.55f;
            pt_enable = true; pt_lml = 0.25f; pt_lml_r = 0.45f; pt_lml_g = 0.0f; pt_lml_b = 0.1f; pt_lmh = 0.25f; pt_lmh_r = 0.25f; pt_lmh_b = 0.0f;
            ptl_enable = true; ptl_c = 0.06f; ptl_m = 0.08f; ptl_y = 0.06f;
            ptm_enable = true; ptm_low = 1.0f; ptm_low_rng = 0.4f; ptm_low_st = 0.5f; ptm_high = -0.8f; ptm_high_rng = 0.66f; ptm_high_st = 0.6f;
            brl_enable = true; brl = 0.0f; brl_r = -2.5f; brl_g = -1.5f; brl_b = -1.5f; brl_rng = 0.5f; brl_st = 0.35f;
            brlp_enable = true; brlp = 0.0f; brlp_r = -1.7f; brlp_g = -2.0f; brlp_b = -0.5f;
            hc_enable = true; hc_r = 1.0f; hc_r_rng = 0.3f;
            hs_rgb_enable = true; hs_r = 0.6f; hs_r_rng = 0.8f; hs_g = 0.35f; hs_g_rng = 1.0f; hs_b = 0.66f; hs_b_rng = 1.0f;
            hs_cmy_enable = true; hs_c = 0.15f; hs_c_rng = 1.0f; hs_m = 0.0f; hs_m_rng = 1.0f; hs_y = 0.0f; hs_y_rng = 1.0f;
            break;
        }
        case 2: { // Sylvan
            ts(1.6f, 0.5f, 0.01f, 0.01f, false, 0.0f, 1.0f, 4.0f, true, 0.25f, 0.75f);
            cwp = 2; cwp_lm = 0.25f; rs_sa = 0.25f; rs_rw = 0.25f; rs_bw = 0.55f;
            pt_enable = true; pt_lml = 0.15f; pt_lml_r = 0.5f; pt_lml_g = 0.15f; pt_lml_b = 0.1f; pt_lmh = 0.25f; pt_lmh_r = 0.15f; pt_lmh_b = 0.15f;
            ptl_enable = true; ptl_c = 0.05f; ptl_m = 0.08f; ptl_y = 0.05f;
            ptm_enable = true; ptm_low = 0.5f; ptm_low_rng = 0.5f; ptm_low_st = 0.5f; ptm_high = -0.8f; ptm_high_rng = 0.5f; ptm_high_st = 0.5f;
            brl_enable = true; brl = -1.0f; brl_r = -2.0f; brl_g = -2.0f; brl_b = 0.0f; brl_rng = 0.25f; brl_st = 0.25f;
            brlp_enable = true; brlp = -1.0f; brlp_r = -0.5f; brlp_g = -0.25f; brlp_b = -0.25f;
            hc_enable = true; hc_r = 1.0f; hc_r_rng = 0.4f;
            hs_rgb_enable = true; hs_r = 0.6f; hs_r_rng = 1.15f; hs_g = 0.8f; hs_g_rng = 1.25f; hs_b = 0.6f; hs_b_rng = 1.0f;
            hs_cmy_enable = true; hs_c = 0.25f; hs_c_rng = 0.25f; hs_m = 0.25f; hs_m_rng = 0.5f; hs_y = 0.35f; hs_y_rng = 0.5f;
            break;
        }
        case 3: { // Colorful
            ts(1.5f, 0.5f, 0.003f, 0.003f, false, 0.0f, 1.0f, 4.0f, true, 0.4f, 0.5f);
            cwp = 2; cwp_lm = 0.25f; rs_sa = 0.35f; rs_rw = 0.25f; rs_bw = 0.55f;
            pt_enable = true; pt_lml = 0.5f; pt_lml_r = 1.0f; pt_lml_g = 0.0f; pt_lml_b = 0.5f; pt_lmh = 0.15f; pt_lmh_r = 0.15f; pt_lmh_b = 0.15f;
            ptl_enable = true; ptl_c = 0.05f; ptl_m = 0.06f; ptl_y = 0.05f;
            ptm_enable = true; ptm_low = 0.8f; ptm_low_rng = 0.5f; ptm_low_st = 0.4f; ptm_high = -0.8f; ptm_high_rng = 0.4f; ptm_high_st = 0.4f;
            brl_enable = true; brl = 0.0f; brl_r = -1.25f; brl_g = -1.25f; brl_b = -0.25f; brl_rng = 0.3f; brl_st = 0.5f;
            brlp_enable = true; brlp = -0.5f; brlp_r = -1.25f; brlp_g = -1.25f; brlp_b = -0.5f;
            hc_enable = true; hc_r = 1.0f; hc_r_rng = 0.4f;
            hs_rgb_enable = true; hs_r = 0.5f; hs_r_rng = 0.8f; hs_g = 0.35f; hs_g_rng = 1.0f; hs_b = 0.5f; hs_b_rng = 1.0f;
            hs_cmy_enable = true; hs_c = 0.25f; hs_c_rng = 1.0f; hs_m = 0.0f; hs_m_rng = 1.0f; hs_y = 0.25f; hs_y_rng = 1.0f;
            break;
        }
        case 4: { // Aery
            ts(1.15f, 0.5f, 0.04f, 0.006f, false, 0.0f, 0.0f, 0.5f, true, 0.5f, 2.0f);
            cwp = 1; cwp_lm = 0.25f; rs_sa = 0.25f; rs_rw = 0.2f; rs_bw = 0.5f;
            pt_enable = true; pt_lml = 0.0f; pt_lml_r = 0.5f; pt_lml_g = 0.15f; pt_lml_b = 0.1f; pt_lmh = 0.0f; pt_lmh_r = 0.1f; pt_lmh_b = 0.0f;
            ptl_enable = true; ptl_c = 0.05f; ptl_m = 0.08f; ptl_y = 0.05f;
            ptm_enable = true; ptm_low = 0.8f; ptm_low_rng = 0.35f; ptm_low_st = 0.5f; ptm_high = -0.9f; ptm_high_rng = 0.5f; ptm_high_st = 0.3f;
            brl_enable = true; brl = -3.0f; brl_r = 0.0f; brl_g = 0.0f; brl_b = 1.0f; brl_rng = 0.8f; brl_st = 0.15f;
            brlp_enable = true; brlp = -1.0f; brlp_r = -1.0f; brlp_g = -1.0f; brlp_b = 0.0f;
            hc_enable = true; hc_r = 0.5f; hc_r_rng = 0.25f;
            hs_rgb_enable = true; hs_r = 0.6f; hs_r_rng = 1.0f; hs_g = 0.35f; hs_g_rng = 2.0f; hs_b = 0.5f; hs_b_rng = 1.5f;
            hs_cmy_enable = true; hs_c = 0.35f; hs_c_rng = 1.0f; hs_m = 0.25f; hs_m_rng = 1.0f; hs_y = 0.35f; hs_y_rng = 0.5f;
            break;
        }
        case 5: { // Dystopic
            ts(1.6f, 0.5f, 0.01f, 0.008f, true, 0.25f, 0.0f, 1.0f, true, 1.0f, 0.75f);
            cwp = 3; cwp_lm = 0.25f; rs_sa = 0.2f; rs_rw = 0.25f; rs_bw = 0.55f;
            pt_enable = true; pt_lml = 0.15f; pt_lml_r = 0.0f; pt_lml_g = 0.0f; pt_lml_b = 0.0f; pt_lmh = 0.0f; pt_lmh_r = 0.0f; pt_lmh_b = 0.0f;
            ptl_enable = true; ptl_c = 0.05f; ptl_m = 0.08f; ptl_y = 0.05f;
            ptm_enable = true; ptm_low = 0.25f; ptm_low_rng = 0.25f; ptm_low_st = 0.8f; ptm_high = -0.8f; ptm_high_rng = 0.6f; ptm_high_st = 0.25f;
            brl_enable = true; brl = -2.0f; brl_r = -2.0f; brl_g = -2.0f; brl_b = 0.0f; brl_rng = 0.35f; brl_st = 0.35f;
            brlp_enable = true; brlp = 0.0f; brlp_r = -1.0f; brlp_g = -1.0f; brlp_b = -1.0f;
            hc_enable = true; hc_r = 1.0f; hc_r_rng = 0.25f;
            hs_rgb_enable = true; hs_r = 0.7f; hs_r_rng = 1.33f; hs_g = 1.0f; hs_g_rng = 2.0f; hs_b = 0.75f; hs_b_rng = 2.0f;
            hs_cmy_enable = true; hs_c = 1.0f; hs_c_rng = 0.5f; hs_m = 1.0f; hs_m_rng = 1.0f; hs_y = 1.0f; hs_y_rng = 0.765f;
            break;
        }
        case 6: { // Umbra
            ts(1.8f, 0.5f, 0.001f, 0.015f, false, 0.0f, 1.0f, 4.0f, true, 1.0f, 1.0f);
            cwp = 5; cwp_lm = 0.25f; rs_sa = 0.35f; rs_rw = 0.25f; rs_bw = 0.55f;
            pt_enable = true; pt_lml = 0.0f; pt_lml_r = 0.5f; pt_lml_g = 0.0f; pt_lml_b = 0.15f; pt_lmh = 0.25f; pt_lmh_r = 0.25f; pt_lmh_b = 0.0f;
            ptl_enable = true; ptl_c = 0.05f; ptl_m = 0.06f; ptl_y = 0.05f;
            ptm_enable = true; ptm_low = 0.4f; ptm_low_rng = 0.35f; ptm_low_st = 0.66f; ptm_high = -0.6f; ptm_high_rng = 0.45f; ptm_high_st = 0.45f;
            brl_enable = true; brl = -2.0f; brl_r = -4.5f; brl_g = -3.0f; brl_b = -4.0f; brl_rng = 0.35f; brl_st = 0.3f;
            brlp_enable = true; brlp = 0.0f; brlp_r = -2.0f; brlp_g = -1.0f; brlp_b = -0.5f;
            hc_enable = true; hc_r = 1.0f; hc_r_rng = 0.35f;
            hs_rgb_enable = true; hs_r = 0.66f; hs_r_rng = 1.0f; hs_g = 0.5f; hs_g_rng = 2.0f; hs_b = 0.85f; hs_b_rng = 2.0f;
            hs_cmy_enable = true; hs_c = 0.0f; hs_c_rng = 1.0f; hs_m = 0.25f; hs_m_rng = 1.0f; hs_y = 0.66f; hs_y_rng = 0.66f;
            break;
        }
        default: { // Standard
            ts(1.66f, 0.5f, 0.003f, 0.005f, false, 0.0f, 1.0f, 4.0f, false, 0.0f, 0.5f);
            cwp = 2; cwp_lm = 0.25f; rs_sa = 0.35f; rs_rw = 0.25f; rs_bw = 0.55f;
            pt_enable = true; pt_lml = 0.25f; pt_lml_r = 0.5f; pt_lml_g = 0.0f; pt_lml_b = 0.1f; pt_lmh = 0.25f; pt_lmh_r = 0.5f; pt_lmh_b = 0.0f;
            ptl_enable = true; ptl_c = 0.06f; ptl_m = 0.08f; ptl_y = 0.06f;
            ptm_enable = true; ptm_low = 0.4f; ptm_low_rng = 0.25f; ptm_low_st = 0.5f; ptm_high = -0.8f; ptm_high_rng = 0.35f; ptm_high_st = 0.4f;
            brl_enable = true; brl = 0.0f; brl_r = -2.5f; brl_g = -1.5f; brl_b = -1.5f; brl_rng = 0.5f; brl_st = 0.35f;
            brlp_enable = true; brlp = -0.5f; brlp_r = -1.25f; brlp_g = -1.25f; brlp_b = -0.25f;
            hc_enable = true; hc_r = 1.0f; hc_r_rng = 0.3f;
            hs_rgb_enable = true; hs_r = 0.6f; hs_r_rng = 0.6f; hs_g = 0.35f; hs_g_rng = 1.0f; hs_b = 0.66f; hs_b_rng = 1.0f;
            hs_cmy_enable = true; hs_c = 0.25f; hs_c_rng = 1.0f; hs_m = 0.0f; hs_m_rng = 1.0f; hs_y = 0.0f; hs_y_rng = 1.0f;
            break;
        }

        }
        switch (tonescale) {
        case 1: ts(1.4f, 0.5f, 0.003f, 0.005f, false, 0.0f, 1.0f, 4.0f, false, 0.0f, 0.5f); break;
        case 2: ts(1.66f, 0.5f, 0.003f, 0.005f, false, 0.0f, 1.0f, 4.0f, false, 0.0f, 0.5f); break;
        case 3: ts(1.4f, 0.5f, 0.003f, 0.005f, false, 0.0f, 1.0f, 4.0f, true, 1.0f, 0.5f); break;
        case 4: ts(1.05f, 0.5f, 0.1f, 0.01f, false, 0.0f, 1.0f, 4.0f, true, 1.5f, 0.2f); break;
        case 5: ts(1.6f, 0.5f, 0.01f, 0.01f, false, 0.0f, 1.0f, 4.0f, true, 0.25f, 0.75f); break;
        case 6: ts(1.5f, 0.5f, 0.003f, 0.003f, false, 0.0f, 1.0f, 4.0f, true, 0.4f, 0.5f); break;
        case 7: ts(1.15f, 0.5f, 0.04f, 0.006f, false, 0.0f, 0.0f, 0.5f, true, 0.5f, 2.0f); break;
        case 8: ts(1.6f, 0.5f, 0.01f, 0.008f, true, 0.25f, 0.0f, 1.0f, true, 1.0f, 0.75f); break;
        case 9: ts(1.8f, 0.5f, 0.001f, 0.015f, false, 0.0f, 1.0f, 4.0f, true, 1.0f, 1.0f); break;
        case 10: ts(1.0f, 0.35f, 0.02f, 0.0f, true, 0.55f, 0.0f, 2.0f, true, 1.13f, 1.0f); break;
        case 11: ts(1.15f, 0.5f, 0.04f, 0.0f, false, 1.0f, 1.0f, 1.0f, false, 1.0f, 0.6f); break;
        case 12: ts(1.5f, 0.5f, 0.003f, 0.01f, true, 0.25f, 0.0f, 4.0f, true, 1.0f, 1.0f); break;
        case 13: ts(1.2f, 0.5f, 0.02f, 0.0f, false, 0.0f, 1.0f, 1.0f, false, 0.0f, 0.6f); break;

        default: break;
        }
        // creative white point: 0 = the look's, 1..6 = D93..D50 (internally 0..5)
        if (whiteSel != 0) {
            cwp_lm = whiteLimit;
            cwp = whiteSel - 1;
        }
    }
};

// ---- math ----
float compressHyperbolicPower(float x, float s, float p) { return spowf(x / (x + s), p); }
float compressToeQuadratic(float x, float toe, bool inv) {
    if (toe == 0.0f) return x;
    return !inv ? spowf(x, 2.0f) / (x + toe) : (x + std::sqrt(x * (4.0f * toe + x))) / 2.0f;
}
float compressToeCubic(float x, float m, float w, bool inv) {
    if (m == 1.0f) return x;
    const float x2 = x * x;
    if (!inv) return x * (x2 + m * w) / (x2 + w);
    const float p0 = x2 - 3.0f * m * w;
    const float p1 = 2.0f * x2 + 27.0f * w - 9.0f * m * w;
    const float p2 = std::pow(std::sqrt(x2 * p1 * p1 - 4.0f * p0 * p0 * p0) / 2.0f + x * p1 / 2.0f, 1.0f / 3.0f);
    return p0 / (3.0f * p2) + p2 / 3.0f + x / 3.0f;
}
float contrastHigh(float x, float p, float pv, float pvLx) {
    const float x0 = 0.18f * exp2f_(pv);
    if (x < x0 || p == 1.0f) return x;
    const float o = x0 - x0 / p;
    const float s0 = std::pow(x0, 1.0f - p) / p;
    const float x1 = x0 * exp2f_(pvLx);
    const float k1 = p * s0 * std::pow(x1, p) / x1;
    const float y1 = s0 * std::pow(x1, p) + o;
    return x > x1 ? k1 * (x - x1) + y1 : s0 * std::pow(x, p) + o;
}
float softplus(float x, float s) {
    if (x > 10.0f * s || s < 1e-4f) return x;
    return s * std::log(std::max(0.0f, 1.0f + std::exp(x / s)));
}
float gaussWindow(float x, float w) { return std::exp(-x * x / w); }
float hueOffset(float h, float o) { return std::fmod(h - o + kPi, 2.0f * kPi) - kPi; }

} // namespace

struct OpenDrt::Impl {
    P p;
    bool hdr;
    int eotf;
    float tsS, tsM2, tsP, tsDsc, tsS1, lconM, lconW, lconSc, hconP, sLp100;
    float tnLp;

    explicit Impl(const OpenDrtSpec& spec) {
        p.preset(spec.look, spec.tonescale, spec.cwp, spec.cwpLimit);
        hdr = spec.display == OpenDrtDisplay::Hlg1000;
        // HLG / 2.4 Rec.1886 / 2.2 sRGB display
        eotf = hdr ? 5 : (spec.display == OpenDrtDisplay::SdrRec1886 ? 2 : 1);
        const int tnSu = hdr ? 0 : (spec.display == OpenDrtDisplay::SdrRec1886 ? 1 : 2);
        tnLp = hdr ? 1000.0f : 100.0f;
        const float tnLg = spec.grey;
        const float tnGb = 0.13f;
        const float ptHdr = 0.5f;

        const float tsX1 = exp2f_(6.0f * p.tn_sh + 4.0f);
        const float tsY1 = tnLp / 100.0f;
        const float tsX0 = 0.18f + p.tn_off;
        const float tsY0 = tnLg / 100.0f * (1.0f + tnGb * (std::log(tsY1) / std::log(2.0f)));
        const float tsS0 = compressToeQuadratic(tsY0, p.tn_toe, true);
        tsP = p.tn_con / (1.0f + static_cast<float>(tnSu) * 0.05f);
        const float tsS10 = tsX0 * (std::pow(tsS0, -1.0f / p.tn_con) - 1.0f);
        const float tsM1 = tsY1 / std::pow(tsX1 / (tsX1 + tsS10), p.tn_con);
        tsM2 = compressToeQuadratic(tsM1, p.tn_toe, true);
        tsS = tsX0 * (std::pow(tsS0 / tsM2, -1.0f / p.tn_con) - 1.0f);
        tsDsc = eotf == 5 ? 0.1f : 100.0f / tnLp;
        const float ptCmpLf = ptHdr * std::min(1.0f, (tnLp - 100.0f) / 900.0f);
        sLp100 = tsX0 * (std::pow(tnLg / 100.0f, -1.0f / p.tn_con) - 1.0f);
        tsS1 = tsS * ptCmpLf + sLp100 * (1.0f - ptCmpLf);
        lconM = exp2f_(-p.tn_lcon);
        const float lw = p.tn_lcon_w / 4.0f;
        lconW = lw * lw;
        lconSc = p.tn_lcon_enable ? compressToeCubic(tsX0, lconM, lconW, true) / tsX0 : 1.0f;
        hconP = exp2f_(p.tn_hcon);
    }

    // OpenDRT's eotf_hlg (inverse, 1000 nits): display light (1 = 1000 nits) -> HLG code values.
    static void hlgInverseEotf(float rgb[3]) {
        const float yd = 0.2627f * rgb[0] + 0.6780f * rgb[1] + 0.0593f * rgb[2];
        const float k = spowf(yd, (1.0f - 1.2f) / 1.2f);
        for (int i = 0; i < 3; ++i) {
            const float x = rgb[i] * k;
            rgb[i] = x <= 1.0f / 12.0f ? std::sqrt(3.0f * x)
                                       : 0.17883277f * std::log(12.0f * x - 0.28466892f) + 0.55991073f;
        }
    }

    // P3-D65 -> the display's Rec.709 (HDR: P3-D65) with the creative white point.
    void displayWhite(float rgb[3], float tsn) const {
        mul(kP3D65_TO_XYZ, rgb);
        const float n0 = rgb[0], n1 = rgb[1], n2 = rgb[2];
        const float f = std::pow(tsn, 2.0f * p.cwp_lm);
        if (p.cwp >= 0 && p.cwp < 6 && p.cwp != 2)
            mul(kCatD65[p.cwp], rgb);
        rgb[0] = rgb[0] * f + n0 * (1.0f - f);
        rgb[1] = rgb[1] * f + n1 * (1.0f - f);
        rgb[2] = rgb[2] * f + n2 * (1.0f - f);
        mul(hdr ? kXYZ_TO_P3D65 : kXYZ_TO_REC709, rgb);
        const float* normTab = hdr ? kCWP_NORM_P3 : kCWP_NORM_709;
        const float norm = (p.cwp >= 0 && p.cwp < 6) ? normTab[p.cwp] : 1.0f;
        const float k = norm * f + 1.0f - f;
        for (int i = 0; i < 3; ++i) rgb[i] *= k;
    }

    void applyXyz(float rgb[3]) const {
        mul(kXYZ_TO_P3D65, rgb);

        // rendering space
        const float rsW0 = p.rs_rw, rsW1 = 1.0f - p.rs_rw - p.rs_bw, rsW2 = p.rs_bw;
        float satL = rgb[0] * rsW0 + rgb[1] * rsW1 + rgb[2] * rsW2;
        for (int i = 0; i < 3; ++i) rgb[i] = satL * p.rs_sa + rgb[i] * (1.0f - p.rs_sa) + p.tn_off;

        float tsn = std::sqrt(std::max(0.0f, rgb[0] * rgb[0] + rgb[1] * rgb[1] + rgb[2] * rgb[2])) / kSqrt3;
        for (int i = 0; i < 3; ++i) rgb[i] = sdivf(rgb[i], tsn);

        const float oppX = rgb[0] - rgb[2];
        const float oppY = rgb[1] - (rgb[0] + rgb[2]) / 2.0f;
        float achD = std::sqrt(std::max(0.0f, oppX * oppX + oppY * oppY)) / 2.0f;
        achD = 1.25f * compressToeQuadratic(achD, 0.25f, false);
        const float hue = std::fmod(std::atan2(oppX, oppY) + kPi + 1.10714931f, 2.0f * kPi);

        const float haR = gaussWindow(hueOffset(hue, 0.1f), 0.66f);
        const float haG = gaussWindow(hueOffset(hue, 4.3f), 0.66f);
        const float haB = gaussWindow(hueOffset(hue, 2.3f), 0.66f);
        const float hsR = gaussWindow(hueOffset(hue, -0.4f), 0.66f);
        const float hsG = haG;
        const float hsB = gaussWindow(hueOffset(hue, 2.5f), 0.66f);
        const float cmyC = gaussWindow(hueOffset(hue, 3.3f), 0.5f);
        const float cmyM = gaussWindow(hueOffset(hue, 1.3f), 0.5f);
        const float cmyY = gaussWindow(hueOffset(hue, -1.15f), 0.5f);

        if (p.brl_enable) {
            const float tsf = std::pow(tsn / (tsn + 1.0f), 1.0f - p.brl_rng);
            const float exf = (p.brl + p.brl_r * haR + p.brl_g * haG + p.brl_b * haB) *
                              std::pow(achD, 1.0f / p.brl_st);
            tsn *= exf2(exf * (exf < 0.0f ? tsf : 1.0f - tsf));
        }
        if (p.tn_lcon_enable) {
            tsn *= lconSc;
            tsn = compressToeCubic(tsn, lconM, lconW, false);
        }
        if (p.tn_hcon_enable) tsn = contrastHigh(tsn, hconP, p.tn_hcon_pv, p.tn_hcon_st);

        const float tsnPt = compressHyperbolicPower(tsn, tsS1, tsP);
        const float tsnConst = compressHyperbolicPower(tsn, sLp100, tsP);
        tsn = compressHyperbolicPower(tsn, tsS, tsP);

        if (p.hc_enable) {
            float hcTs = 1.0f - tsnConst;
            float hcC = hcTs * (1.0f - achD) + achD * (1.0f - hcTs);
            hcC *= achD * haR;
            hcTs = std::pow(hcTs, 1.0f / p.hc_r_rng);
            const float hcF = p.hc_r * (hcC - 2.0f * hcC * hcTs) + 1.0f;
            rgb[1] *= hcF;
            rgb[2] *= hcF;
        }
        if (p.hs_rgb_enable) {
            const float x = hsR * achD * std::pow(tsnPt, 1.0f / p.hs_r_rng) * p.hs_r;
            const float y = hsG * achD * std::pow(tsnPt, 1.0f / p.hs_g_rng) * -p.hs_g;
            const float z = hsB * achD * std::pow(tsnPt, 1.0f / p.hs_b_rng) * -p.hs_b;
            rgb[0] += z - y; rgb[1] += x - z; rgb[2] += y - x;
        }
        if (p.hs_cmy_enable) {
            const float c = 1.0f - tsnPt;
            const float x = cmyC * achD * std::pow(c, 1.0f / p.hs_c_rng) * -p.hs_c;
            const float y = cmyM * achD * std::pow(c, 1.0f / p.hs_m_rng) * p.hs_m;
            const float z = cmyY * achD * std::pow(c, 1.0f / p.hs_y_rng) * p.hs_y;
            rgb[0] += z - y; rgb[1] += x - z; rgb[2] += y - x;
        }

        // purity compression
        const float ptLmlP = 1.0f + 4.0f * (1.0f - tsnPt) *
                             (p.pt_lml + p.pt_lml_r * hsR + p.pt_lml_g * hsG + p.pt_lml_b * hsB);
        float ptf = 1.0f - std::pow(tsnPt, ptLmlP);
        const float ptLmhP = (1.0f - achD * (p.pt_lmh_r * hsR + p.pt_lmh_b * hsB)) *
                             (1.0f - p.pt_lmh * achD);
        ptf = std::pow(ptf, ptLmhP);
        if (p.ptm_enable) {
            const float low = (p.ptm_low_st == 0.0f || p.ptm_low_rng == 0.0f) ? 1.0f
                : 1.0f + p.ptm_low * std::exp(-2.0f * achD * achD / p.ptm_low_st) *
                          std::pow(1.0f - tsnConst, 1.0f / p.ptm_low_rng);
            const float high = (p.ptm_high_st == 0.0f || p.ptm_high_rng == 0.0f) ? 1.0f
                : 1.0f + p.ptm_high * std::exp(-2.0f * achD * achD / p.ptm_high_st) *
                          std::pow(tsnPt, 1.0f / (4.0f * p.ptm_high_rng));
            ptf *= low * high;
        }
        for (int i = 0; i < 3; ++i) rgb[i] = rgb[i] * ptf + 1.0f - ptf;

        // back from the rendering space
        satL = rgb[0] * rsW0 + rgb[1] * rsW1 + rgb[2] * rsW2;
        for (int i = 0; i < 3; ++i) rgb[i] = (satL * p.rs_sa - rgb[i]) / (p.rs_sa - 1.0f);

        displayWhite(rgb, tsnConst);

        if (p.brlp_enable) {
            const float bx = rgb[0] - rgb[2];
            const float by = rgb[1] - (rgb[0] + rgb[2]) / 2.0f;
            float bAch = std::sqrt(std::max(0.0f, bx * bx + by * by)) / 4.0f;
            bAch = 1.1f * (bAch * bAch / (bAch + 0.1f));
            const float m = p.brlp + p.brlp_r * achD * haR + p.brlp_g * achD * haG + p.brlp_b * achD * haB;
            const float ex = exp2f_(m * bAch * tsn);
            for (int i = 0; i < 3; ++i) rgb[i] *= ex;
        }
        if (p.ptl_enable) {
            rgb[0] = softplus(rgb[0], p.ptl_c);
            rgb[1] = softplus(rgb[1], p.ptl_m);
            rgb[2] = softplus(rgb[2], p.ptl_y);
        }

        tsn *= tsM2;
        tsn = compressToeQuadratic(tsn, p.tn_toe, false);
        tsn *= tsDsc;
        if (hdr) {
            // Rec.2020 (P3 limited), clamped, then the inverse HLG EOTF
            for (int i = 0; i < 3; ++i) rgb[i] = std::max(0.0f, rgb[i] * tsn);
            mul(kP3_TO_REC2020, rgb);
            for (int i = 0; i < 3; ++i) rgb[i] = std::min(std::max(rgb[i], 0.0f), 1.0f);
            hlgInverseEotf(rgb);
            return;
        }
        const float ep = 2.0f + static_cast<float>(eotf) * 0.2f;
        for (int i = 0; i < 3; ++i)
            rgb[i] = spowf(std::min(std::max(rgb[i] * tsn, 0.0f), 1.0f), 1.0f / ep);
    }

    static float exf2(float x) { return exp2f_(x); }
};

OpenDrt::OpenDrt(const OpenDrtSpec& spec) : impl_(new Impl(spec)) {}
OpenDrt::~OpenDrt() { delete impl_; }

void OpenDrt::applyXyz(float rgb[3]) const { impl_->applyXyz(rgb); }

void OpenDrt::applyDwgLinear(float rgb[3]) const {
    mul(kDWG_TO_XYZ, rgb);
    impl_->applyXyz(rgb);
}

void applyOpenDrt(ImageF& img, const OpenDrtSpec& spec) {
    const OpenDrt t(spec);
    const size_t pixels = static_cast<size_t>(img.w) * img.h;
    if (img.rgb.size() != pixels * 3)
        return;
    for (size_t p = 0; p < pixels; ++p) {
        float* px = &img.rgb[p * 3];
        t.applyDwgLinear(px);
        for (int c = 0; c < 3; ++c)
            if (std::isnan(px[c]))
                px[c] = 0.0f;
    }
}

} // namespace rawedit
