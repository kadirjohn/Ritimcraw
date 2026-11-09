# third_party manifest

Acquired 2026-11-10 (network available this session). All paths relative to repo root.

| path | version / commit | sha256 | license | source URL |
|---|---|---|---|---|
| third_party/motioncam-decoder | commit b931ec0e2466c8217c49f13df925531b78f76e33 (2026-10-15, main HEAD) | — (git clone, .git removed) | Apache-2.0 | https://github.com/mirsadm/motioncam-decoder |
| third_party/doctest-2.4.12/doctest.h | 2.4.12 | 94029a7d32da24a56249658147dbd2b33ff0b9ed665295cbbaf19aafff5b0ced | MIT | https://raw.githubusercontent.com/doctest/doctest/v2.4.12/doctest/doctest.h |
| third_party/xiaomi-milog/Mi-Log_Whitepaper.pdf | 2025-02-25 | d80d9a237e7602d5896092bb8e935095dd5d9d6fd46aca60a57470846d92c340 | Xiaomi Mi-Log developer pack (redistribution per pack terms) | owner-provided (STEPS/media/test/) |
| third_party/xiaomi-milog/Mi-Log_to_ACES_AP0.dctl | 2025-03-12 | 00103d1f7c5de33a4d0cf1d81bc02dd12d2f068457bf947c37498e78a0ef9ea2 | Xiaomi Mi-Log developer pack | owner-provided (STEPS/media/test/) |
| third_party/xiaomi-milog/Mi-LogTo709_3DLUT.cube | 2025-02-28 (65³ LUT_3D_SIZE) | 35c610b97de85666c184a7fd5a7315ce5c4d28644262d7a761b4a07c4fde4069 | Xiaomi Mi-Log developer pack | owner-provided (STEPS/media/test/) |
| third_party/xiaomi-milog/Recommended_ACES_Workflow_for_Mi_Log.pdf | 2025-02-25 | e81d8c57c446e9f44683c57a5630fb7380a7a3c09842ff33e9bc11793718c1cb | Xiaomi Mi-Log developer pack | owner-provided (STEPS/media/test/) |

## Still missing (recorded per owner instruction)

| name | version | purpose | source |
|---|---|---|---|
| exiftool | latest | DNG output verification | https://exiftool.org (ffprobe already present via homebrew) |
| Material Symbols SVGs | latest | app icons (vendored vector drawables) | https://fonts.google.com/icons (androidx compose material-icons is already in the Gradle cache as fallback) |

## Installed by owner during the session (2026-11-10)

| name | version | note |
|---|---|---|
| Android NDK | 30.0.16248370 | newer than the readiness report's 28.2 recommendation; 16 KB page alignment supported — pin ndkVersion to this |
| SDK CMake | 4.1.2 | newer than the 3.22.1 recommendation; AGP 8.13 compatible |
