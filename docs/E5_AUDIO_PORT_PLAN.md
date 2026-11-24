# E5 — Ses export + loudness: filmcraft audio-dsp crate'inin saf C++ port tarama planı

E5 gece görevi, 2026-11-24. Bu belge bir PLAN'dır; üretim kodu yok.
Kaynak: `~/projects/filmcraft-android` (crate `crates/audio-dsp`,
görev sırasında main = `c41b58467e`, sadece OKUNDU — Rust tarafına
dokunulmadı). Hedef: KLIPP·FILMCRAFT-PLAN §7.3 (kontroller), §8.4 (ses
efektleri), §13.9 (loudness saha ölçümleri) maddelerinin E5 paketi —
ses export, loudness normalizasyonu, true-peak ve (E5-C) timeline ses
efektleri — için hangi Rust modüllerinin hangi sırayla, hangi test
vektörleriyle, kaç satırla **saf C++'a (core/) portlanacağı**.

Bağlayıcı karar (sahip, D1): Rust bağlanmaz; gereken her şey saf
C++'a portlanır. FilmCraft test vektörleri doctest'e taşınır.

Doğrulama disiplini: her iddia ya **doğrulandı** (kod satırı okundu,
`wc -l` ölçüldü, komut çalıştı) ya **doğrulanmadı** (ölçüm veya
araştırma gerektirir) etiketini taşır. Satır sayıları tahmin değil,
ölçümdür (ölçüm ortamı: filmcraft-android working tree, 2026-11-24).

Ortam: worktree branch `e5-audio-port-plan`, HEAD = main `fd749d0`.
Build kapısı: `bash tools/build-desktop.sh --no-tests` YEŞİL — tarama
öncesi ve sonra çalıştırıldı (docs-only değişiklik; tam `--tests`
sürümü bu belge için çalıştırılmadı çünkü belge hiçbir kaynağı
değiştirmiyor; `git status` clean).

Tüm sınıf/dosya adresleri göreli verildi; iki kök var:
(a) `crates/audio-dsp/src/...` = filmcraft-android'deki Rust kaynağı,
(b) `app/...` veya `core/...` = bu repo (Ritimcraw).

---

## 0. Özet + port sırası önerisi

Önerilen port sırası — bağımlılık + kullanıcı değeri:

```
E5-A  Loudness ölçer + true-peak + normalize halkası   → export "Sosyal" preset
  │   kaynak: loudness.rs (446 kod satırı) + oversample.rs (123)
  │   + biquad.rs'den K-ağırlık için gereken kesit (Coeffs + Biquad
  │   tick, ~80 satır; dosya küçük olduğundan tamamı portlanır:
  │   146 satır)
  ▼
E5-B  Resampler + remix/channels (downmix) kesiti       → E1'de dondurulmuş
  │   ayarlar açılır (örnekleme hızı + mono/stereo)
  │   kaynak: resample.rs (93) + channels.rs kesiti (~120)
  ▼
E5-C  Biquad EQ + compressor/gate/limiter + ducking     → timeline ses efektleri
      kaynak: dynamics.rs (414) + ducking.rs (134) + smooth.rs (72)
      + AudioEffect/ParamSpec iskeleti (~80 kesit)
```

Neden bu sıra:

1. **E5-A en alt katman ve değeri en yüksek.** §7.3'ün E1'de
   dondurduğu tek ses maddesi loudness normalizasyonu; sosyal-medya
   export'un göze çarpan tek farkı bu (§13.9: "Sosyal preset: −14
   LUFS-I, en fazla −1 dBTP"). `loudness.rs` crate'in en bağımsız
   modülü: dış bağımlılığı yalnız `biquad.rs` (K-ağırlık) ve
   `oversample.rs` (true-peak) — `fft.rs` YOK, thread/rayon YOK
   (doğrulandı, grep). Ölçer sabit bellekle sonsuz uzunlukta program
   işler (crate README + kod: 0.01 LU histogram, 9000 bin).
2. **E5-B E1'in ikinci dondurmasını açar:** E1 için örnek hızı ve
   mono/stereo "kaynağı izler, read-only" kararı verilmişti (
   `ExportSettingsPanel.kt:66-68`, "owner decision 2026-11-23" —
   doğrulandı). E5'in şartnamesi (§7.3) bu ikisini serbest bırakır:
   "44.1 veya 48 kHz, ... mono/stereo". `resample.rs`'in Rust
   karşılığı `StreamResampler` zaten TIMELINE_AUDIO_PLAN §2.1'in
   sözünü verdiği "streaming, block-invariant rate conversion"
   çekirdeğidir ve Ritimcraw'ın `AudioMixer.Kernel.LINEAR`'ı tam bu
   kontratı bekliyor (doğrulandı: `AudioMixer.kt:29-34`, "The final
   kernel (linear vs windowed-sinc) is the plan's §6.3 item 1
   listening decision").
3. **E5-C en geniş yüzey, export'a kritik değil.** AudioEffect
   iskeleti + ParamSpec sistemi + dört efekt (EQ, compressor, gate,
   ducking) burada. Şartname §8.4 bu maddeyi F4 ile ortak sayar
   ("SFX + temel ses efektleri"); E5-C sadece çekirdek DSP'yi verir,
   UI/keyframe tarafı F4 ile buluşur (§6). En sona bırakılır çünkü
   E5-A'nın ölçer halkası tek başına da şartnamedeki "loudness
   ölçer, true peak" maddesini karşılar ve limiter E5-A'da
   `Limiter` olarak zaten portlanmış olur.

Toplam özet tablosu (kod = test blokları düşülmüş `wc -l`; ölçüm
yöntemi: her dosyada `#[cfg(test)]` satırına kadar kod, sonrası test):

| Paket | Kaynak | Kod satırı | Test satırı (crate'ten taşınacak) |
|---|---|---|---|
| E5-A | loudness.rs + oversample.rs + biquad.rs (tam) | 446 + 123 + 146 = **715** | 247 + 23 + 85 = **355** |
| E5-B | resample.rs + channels.rs kesit (Mono/Stereo downmix) | 93 + ~120 = **~213** | 80 + ~70 = **~150** |
| E5-C | dynamics.rs + ducking.rs + smooth.rs + iskelet kesiti | 414 + 134 + 72 + ~80 = **~700** | 144 + 95 + 33 + ~40 = **~312** |
| **Toplam** | | **~1630** | **~817** |

Kapsam dışı (§3.8'de gerekçeli): design.rs 757, remix.rs 905,
sync.rs 516, fft.rs 115, effects/ altında seçilmeyen ~7000 satır.

---

## 1. Mevcut durum: Ritimcraw'da ses bugün ne yapıyor (kod adresleriyle)

Tüm alıntılar bu worktree'de HEAD `fd749d0`'da okundu (**doğrulandı**).

### 1.1 PCM üretimi: native decoder'lar, 16-bit interleaved

- **MCRAW (motioncam) klipleri:** `core/src/Source.cpp:494` —
  `Source::readAudio()` tüm ses chunk'larını birleştirip
  **interleaved 16-bit PCM** döndürür; `core/include/rawedit/Source.h:
  72-75` `AudioInfo{sampleRate, channels}` (ses yoksa 0/0), `:182`
  sözleşme ("All audio chunks concatenated, interleaved per channel,
  16-bit PCM"). Decoder tarafı doğrulandı:
  `third_party/motioncam-decoder/lib/Decoder.cpp:42-71` her chunk'ı
  `std::vector<int16_t>` olarak okur (ham veri); `Container.hpp:48-51`
  içinde `AUDIO_DATA_F32 = 7` tip TANIMLI ama `Decoder.cpp:434` F32
  öğelerini skip ediyor — bugün tek yol 16-bit. (F32'nin hangi
  MotionCam sürümlerinde yazıldığı **doğrulanmadı**, §9.)
- **Encoded (MP4) klipleri:** `Mp4Exporter.kt:164-165, 277` —
  `audioInfo`/`readAudio` arayüzü; `readAudio` → `NativeSource.
  jniReadAudio`. AAC decode edilmiş PCM (interleaved 16-bit);
  ses yoksa null → video-only dosya.
- **Timeline playback (önizleme):** `engine/EditorAudio.kt:140-141`
  — aynı sözleşme ("readAudio contract: ... interleaved per channel,
  16-bit"); `EditorAudio.kt:73-80` AudioTrack MODE_STREAM, 16-bit PCM.
  Timeline ekranı hala TEK klip çalar: `TimelineScreen.kt:638`
  `EditorAudioPlayback`, playhead'in altındaki klibin kendi hızında.

**Ara format gerçeği (E5 tasarımı için kritik):** decoder'dan AAC'e
kadar tek ara format var: **16-bit interleaved ShortArray**. FilmCraft
crate'i ise **planar f32** işler. Port modülleri planar f32 alacak;
16-bit interleaved ↔ planar f32 dönüşümü port paketinin bir parçası
olarak tanımlanmalı (§8.3'te analiz).

### 1.2 Mikser (`.mixer`): var, saf, ama runtime consumer'ı yok

- **`data/timeline/AudioMixer.kt` (243 satır)** — TIMELINE_AUDIO_PLAN
  §5.2 opsiyon A'nın "pure mix pass reference"ı (doğrulandı): 
  `renderBlock(plan, master, startMixSample, frames, sources, kernel)`
  → interleaved **stereo 16-bit** `MixBlock` + AUD-07 sayaçları:
  `peakLeft/peakRight` (satüre SONRASI blok tepe), `clippedSamples`
  (satüre ÖNCESİ |x|>1 sayımı — klip göstergesi), `sumSquares` (RMS
  için). Kanal politikası: `PcmSource` (rate/channels/frameCount/
  sample(frame, ch)); mono → L=R kopya; >2 kanal → ilk iki kanal
  (`AudioMixer.kt:49-52`, "never a guessed downmix matrix" — E5-B'nin
  `channels.rs` port kesitinin bağlanacağı yer). Çapraz-hız çekirdeği
  `Kernel.{NEAREST, LINEAR}` — lineer interpolasyon implement
  (`AudioMixer.kt:160-179`).
- **`data/timeline/AudioPlan.kt` (526 satır)** — placement saf modeli:
  `mixPlan()` → `AudioMixPlan` (placed clip'ler, mix rate,
  totalMixSamples), `ClipAudioState{gainDb, pan, muted, fadeInMs,
  fadeOutMs}`, `MasterAudioState{gainDb, muted}`, `clipGains` (gain →
  pan → fade zinciri), `saturate` (±1.0 hard clip),
  `DEFAULT_MIX_RATE_HZ = 48000` (`AudioPlan.kt:59`).
- **Tüketim (grep, doğrulandı):** üretim kodunda `AudioMixer` çağıran
  TEK yer yok — yalnız `app/src/test/java/com/ritim/ritimcraw/data/
  timeline/AudioMixerTest.kt` sınıyor. Timeline playback tek-klip
  (yukarıda), timeline export video-only (aşağıda).

### 1.3 AAC export zinciri (E1 sonrası)

- **Yazıcı:** `export/MediaCodecMp4Writer.kt` (1287 satır). AAC
  bacağı: `MediaCodecMp4Writer.kt:221` — `configure(MIME_AAC,
  audioFormat(sampleRate, channels, p.audioKbps))`; `audioFormat`
  `ExportFormats.audio(rate, ch, kbps)`'i MediaFormat'e çevirir
  (bitrate `KEY_BIT_RATE`, 64 KB `KEY_MAX_INPUT_SIZE` — eski 682-683
  satırdaki 128_000 sabiti E1'le plana taşındı: `ExportSettings.kt:
  79` `audioKbps`, seçenekler `AUDIO_KBPS = [128, 192, 256, 320]`,
  `ExportSettings.kt:82`). `start(out, w, h, fps, sampleRate,
  channels)`; 0/0 → video-only. Ses besleme sözleşmesi:
  `writeAudioBlock(ptsUs, pcm)` (`MediaCodecMp4Writer.kt:920` civarı,
  arayüz `Mp4Exporter.kt:732-733`).
- **Besleme zinciri (per-klip export):** `Mp4Exporter.kt:1368-1386` —
  kaynağın `audioInfo`'su okunur (kendi hızı + kanalı), `readAudio`
  tüm PCM'i alır, `chunk(pcm, channels)` 1024-frame'lik AAC bloklarına
  böler (son blok kısa; `Mp4Exporter.kt:2277-2290`), pts üretimi
  `PtsTimeline.audioBlockPts(blockIndex, AAC_BLOCK_FRAMES, sampleRate)`
  (`PtsTimeline.kt:61-62` — "sample k at k * 1e6 / rate us", video ile
  ortak saat); `Mp4Exporter.kt:2122` `writer.writeAudioBlock(...)`.
  Interleaving pts'e göre stabil sıralı (eşitlikte video önce).
- **Timeline export:** `TimelineMp4Exporter.kt:253-257` — plan
  `audioSkipped` ise stage log "timeline audio: skipped (v1 exports
  the video track only)" yazar, writer video-only start edilir. Yani
  TIMELINE export'unda ses YOK; per-klip MP4 export'unda ses ham
  kaynaktan akar (mix/gain/limiter yok — tek kaynak, tek klip).
- **E1 sonrası ayarlar:** `ExportSettings.kt:64-83` — `audioKbps`
  ayar; **örnekleme hızı ve kanal sayısı ayara bağlı DEĞİL**:
  `Mp4Exporter.kt:1372-1386` kaynağın kendi rate/channels'ini okur,
  export sheet'te "Audio format" satırı READ-ONLY
  (`ExportSettingsPanel.kt:413-414`; gerekçe `:66-68`: "Frame rate,
  sample rate and channel count are read-only on purpose (owner
  decision 2026-11-23)"). E5-B tam bu iki dondurmayı açar.
- **Loudness/true-peak:** hiçbir yerde yok (grep: LUFS / BS.1770 /
  dbtp geçmiyor — doğrulandı).

### 1.4 AUD-07 ölçümleri bugün ne veriyor

`AudioMixer.MixBlock` zaten sample-peak (satüre sonrası) ve clipping
sayacı (satüre öncesi |x| > 1) döndürüyor. Eksik olan: **K-ağırlıklı
LUFS-I ve true-peak** — E5-A'nın işleri. E5-A MixBlock'un mevcut
sayaçlarını değiştirmez; yanına integrated-LUFS/TP ölçümü ekler (§4).

---

## 2. TIMELINE_AUDIO_PLAN.md ile ilişki

`docs/TIMELINE_AUDIO_PLAN.md` (605 satır) baştan sona okundu
(**doğrulandı**). Ayrım ve örtüşmeler:

| Konu | TIMELINE_AUDIO_PLAN (C1) | E5 (bu plan) |
|---|---|---|
| Placement matematiği (source↔mix pencere, sampleAt, split rejoin) | §2 — model + 35 test implement edildi | Kullanır, değiştirmez |
| Gain/pan/mute/fade zinciri + hard saturate | §3 — `clipGains`/`mixSum` implement | Kullanır. Pan kanunu (constant-power) E5-B channels port'uyla AYNI felsefe (güç korunumu) ama farklı fonksiyonlar — çakışmaz |
| Mix pass kontratı (renderBlock, PcmSource, 16-bit stereo out) | §5.2-A — AudioMixer implement (2026-11-23) | Kullanır; E5-A ölçüm halkası bu döngünün üstünde (§4) |
| Resampler kernel kararı (linear vs windowed-sinc) | §2.1 + §6.3-1: "LINEAR ilk wired sürüm; windowed-sinc ABX isterse"; karar sahibin | E5-B Rust `StreamResampler`'ı (LINEAR) getirir; seçim `Kernel` enum'unda kalır, default LINEAR — planla uyumlu |
| AUD-07 ölçümleri (peak/RMS/clipping) | §3.3 — MixBlock'ta implement | E5-A bunu LUFS-I/TP ile tamamlar |
| Export sözleşmesi (writer tek stereo stream, "dumb pipe") | §5.1 | E5-A normalize/limiter halkası writer'dan ÖNCE, mix pass'te — writer'a dokunmaz |
| Playback çoklu-klip mix motoru | §4.3: "v1 tek-klip kalır" — sonraki dalga | E5 kapsamı dışı; E5-B resampler'ı playback tarafı da kullanacak |
| Pan/mute kalıcılığı | §3.1 + §6.3-4: 2026-11-23'te host-test'li (pan/muted/master alanları modelde; "No UI control yet") | E5 UI bölümü (§6) bu state'i esas alır |

**Örtüşme/çakışma bulguları (önemli sırayla):**

1. **ÖRTÜŞME (E5-B, resampler):** TIMELINE_AUDIO_PLAN §2.1 "wired
   release nearest-sample ile gelmez" diyor ve AudioMixer LINEAR'ı zaten
   implement etti. `StreamResampler` port'u bu kararı değiştirmez;
   `Kernel` enum'una (NEAREST/LINEAR) ileride `SINC` eklemenin yolu
   açılır ama default LINEAR kalır; §6.3-1'in "dinleme kararı owner'ın"
   hükmü durur (plansız değişiklik önerilmiyor).
2. **ÇAKIŞMA GÖRÜNÜMÜ (E5-A, limiter):** TIMELINE_AUDIO_PLAN §3.3 "no
   soft limiter runs (v1)" diyor — bu mix pass'in KENDİSİ için (v1
   şerhi). E5-A'nın normalize→limiter halkası mix'ten SONRAKİ export
   aşamasında (§4) — çelişki değil, ama E5-A implementasyonu
   TIMELINE_AUDIO_PLAN §3.3'e "(v1; E5-A export halkası sonda)"
   şeklinde docs senkronu düşmelidir. Burada işaretlendi.
3. **ÖRTÜŞME (E5-A, ölçüm kapsamı):** §13.9 "Ölçüm son miks üzerinden"
   — E5-A ölçer halkası AudioMixer çıktısı üzerinden akar. Inline mı
   ayrı pass mi analizi §4.1'de.
4. **DOKUNULMAYANLAR:** E5 placement modeline (AudioPlan), undo
   entegrasyonuna (§3.4), bound-vs-independent tasarıma (§4) dokunmaz.
   Preview mix motoru (§5.3'ün engine/ adayı) E5 sonrası dalga.

---

## 3. Modül modül port tablosu

Notasyon: "kod" = `wc -l` toplamı eksi `#[cfg(test)]` blokları;
"test" = o bloklar. Dış bağımlılıklarda çevrimdışı kural: rayon
kullanılmaz; paralellik gerekirse `rawedit::parallelForRows` deseni
(`core/include/rawedit/Parallel.h`, `core/src/Parallel.cpp` —
doğrulandı: process-genişlik leaky singleton havuz, min(4, hw_concurrency)
worker, bit-eşit serial fallback). İncelenen crate'te rayon/thread
kullanımı YOK (grep, doğrulandı; `src/effects.rs:276`'daki
`std::thread::scope` yalnız TEST içinde, Efekt listesini izole
çalıştırmak için — porta taşınmaz). Crate'in Cargo.toml
`[dependencies]`'i boş: "Intentionally dependency-free (layer L1)"
— C++ port'u third_party bağımlılığı istemez; doctest zaten
`third_party/doctest-2.4.12`'de.

### 3.1 BS.1770-4 loudness ölçer (E5-A) — `src/loudness.rs`

| özellik | değer |
|---|---|
| Dosya | 693 satır toplam; **446 kod / 247 test** (test modu 447. satırda başlar; sınırdaki 446-447 satır kovalaması ölçümle) |
| Dış bağımlılık | `biquad.rs` (Coeffs + `Biquad::tick` — K-ağırlığın iki evresi), `oversample.rs` (`PolyphaseBank` + `Interpolator` — true-peak). `fft.rs` YOK, thread YOK. Tamamen saf matematik; her şey f64 içinde, giriş f32. |
| Gating | 400 ms blok / 100 ms adım (%75 örtüşme — `M_SUBBLOCKS=4`/`ST_SUBBLOCKS=30` sabitleri, kod 26-28); mutlak kapı −70 LUFS, göreli kapı −10 LU (integrated); LRA (EBU Tech 3342): −70/−20 kapılar, 10.–95. yüzdelik. |
| Portlanacak semboller | `energy_to_lufs`, `k_prefilter(sample_rate)`, `k_rlb(sample_rate)` (bilinear transform; 48 kHz'de BS.1770 katsayı tablosunu 1e-8 içinde üretir — testi var), `default_channel_weights(channels)` (stereo [1,1], 5.1 [1,1,1,0,1.41,1.41] — LFE hariç, surround +1.5 dB), `Histogram` (0.01 LU bin, HIST_MIN −70, HIST_MAX +20; count+energy — sabit bellek), `LoudnessMeter` (`new/process/process_interleaved/momentary/short_term/max_momentary/max_short_term/integrated/relative_threshold/loudness_range/sample_peak(_channel)/true_peak(_channel/_dbtp)/summary/reset/set_channel_weights`), `LoudnessSummary`, `measure()`, `normalize_gain_db`, `normalize_gain_db_peak_limited`, `k_weighting_power` (test/analitik araç). |
| Test vektörleri (crate'ten doctest'e taşınacak; 14 test fonksiyonu — sayım grep ile doğrulandı) | (1) K-ağırlık katsayıları 48 kHz tabloyla 1e-8; (2) K-ağırlık şekli örnek-hızı bağımsız (32/44.1/48/96/192 kHz; 1 kHz'de +0.69 dB, 10 kHz'de ~+4 dB, 20 Hz'de yüksek kesim); (3) Tech 3341 vaka 1-2: stereo sinüs 20 sn −23/−33 dBFS, I/M/S hepsi hedefte ±0.1 (44.1/48/96 kHz, 997 Hz @44.1); (4) interleaved == planar (1e-9); (5) Tech 3341 vaka 3-4: gating senaryoları → tam −23.0 LUFS; (6) vaka 5: göreli kapı; (7) sessizlik ve −70 altı → I = −∞; (8) Tech 3342 LRA 4 senaryo; (9) log-sweep 20 Hz→20 kHz, analitik K-ağırlık beklentisiyle; (10) surround ağırlıkları: LFE kanalı −∞, Ls kanalı ön kanaldan +1.5 dB; (11) intersample true-peak: fs/4 @45° (örnek tepe −3.01 dBFS, TP 0 dBFS) ve 11.025 kHz @44.1 off-grid — EBU Tech 3341 tolerans bandı [+0.2/−0.4 dB]; (12) reset; (13) normalizeGain helper'ları (−30→−23 = +7 dB; peak-limited varyant; kazanç uygulaması hedefe oturur ±0.02 LU); (14) 1237'lik tuhaf bloklarla besleme == tek blok (bit-eşit) + mono ölçümün dual-mono +3 dB'siz analitik değeri. |
| Oracle testi (`tests/loudness_oracle.rs`, 285 satır) | ffmpeg `ebur128` karşılaştırması (sine_997, pink [Paul Kellet], speech_like [hece üreticisi], gated_stereo, intersample_peak, speech_mono_44k, pink_noise_96k). Toleranslar README'de: M/S ±0.0005 LU, I ±0.007 LU, LRA ±0.04 LU, TP ±0.045 dB. **Taşıma stratejisi:** oracle testi doctest'e doğrudan ölçüde TAŞINMAZ (ffmpeg şart değil); sinyal üreticileri (sine/pink/speech — oracle dosyasında ~80 satır) test util olarak taşınır ve beklenti değerleri SABİT vektör olarak gömülür (ölçülen değerler oracle'daki sapmalarla değil, mutlak toleranslarla assert). Masaüstünde ffmpeg varsa (bu makinede `/opt/homebrew/bin/ffmpeg` 8.1.1 — doğrulandı) opsiyonel ikinci bir kontrol testi `RAWEDIT_FFMPEG=1` kapısıyla eklenebilir (`RAWEDIT_REALCLIP` deseni). Cihaz build'inde ffmpeg yok — kapı zaten gerekli. |
| True-peak stratejisi (görevin "4x oversample — pahalıysa 4x FIR alternatifi" maddesi) | `true_peak_factor(sr)`: <88.2 kHz → 4×, <176.4 kHz → 2×, aksi 1× (BS.1770-4 Annex 2'ye uygun). Bu zaten 4× FIR (polyphase Kaiser-sinc, 16 tap/faz, beta=8, faz-normalli DC birim kazanç). Maliyet: kanal başına ~64 MAC/giriş örneği; 48 kHz stereo ≈ 6.1 M MAC/s (§8.1'de cihaz ölçüm planı). Ucuz alternatif 2× + parabolik tepeler BS.1770-4'ün "≥4×" şartını ve EBU Tech 3341'in TP toleransını riske atabilir —-port TESTİ PARAMETRELİ yazar (4× default, ölçümle kanıtlanırsa "hızlı 2×" knob). |
| Hedef C++ düzeni | `core/include/rawedit/Loudness.h` + `core/src/Loudness.cpp` + `core/tests/test_loudness.cpp` — Color.h/Develop.h deseni; CMakeLists'e `test_loudness` execu ve `add_test` satırı, `tools/build-desktop.sh`'e derleme+çalıştırma blokları (mevcut desen, dosya başına iki blok). |
| Boyut | **M** |

### 3.2 Oversample — true-peak altyapı (E5-A) — `src/oversample.rs`

| özellik | değer |
|---|---|
| Dosya | 144 satır; **123 kod / 21 test** (test 122'de başlar) |
| Dış bağımlılık | Hiçbir şey (bessel_i0 serisi crate içinde: 50 terim, term < sum·1e-17 erken durur) |
| Portlanacak semboller | `TAPS_PER_PHASE = 16`, `true_peak_factor(sample_rate)`, `bessel_i0`, `PolyphaseBank::new(factor) / factor() / delay()`, `Interpolator::reset / push_peak(bank, x)` (çift-yazımlı ring — `TAPS` uzunluğunda bitişik pencere her örnek için hazır; örnek başına `factor` faz çarpımı, maks al) |
| Test vektörleri | (1) Her faz DC birim kazanç (toplam 1e-5 içinde); fs/4 sinüs @45° faz → interpolasyon tepe 1.0 ± 0.02. (2) loudness intersample testi bu bankayı dolaylı sınar (3.1'in 11 no'lu testi). |
| Hedef | `core/include/rawedit/Oversample.h` + `core/src/Oversample.cpp` — E5-C'nin limiter'ı da kullanacağından ayrı dosya (Loudness.cpp'e gömmek yerine) |
| Boyut | **S** |

### 3.3 Biquad (E5-A K-ağırlık + E5-C EQ temeli) — `src/biquad.rs`

| özellik | değer |
|---|---|
| Dosya | 231 satır; **146 kod / 85 test** (test 148'de başlar) |
| Dış bağımlılık | Yalnız `crate::flush` (lib.rs:229-231: \|x\| < 1e-30 → 0; denormal koruması — CPU FTZ bayrağı gerektirmez; C++ porta da özdeş taşınır çünkü Android'de FTZ süreci garanti etmiyor) |
| Portlanacak semboller | `FilterType` (Peaking/LowShelf/HighShelf/LowPass/HighPass/Notch/BandPass/AllPass/Cut), `Coeffs{b0..a2, design, magnitude, magnitude_db, IDENTITY}`, `Biquad` TDF-II durum (`tick`, `process`, `reset`, `is_clear`) |
| design.rs saf mı? (görevin sorusu) | **Evet — biquad tasarımı tamamen saf.** `Coeffs::design` RBJ "Audio EQ Cookbook" kapalı formülleri: `w0 = 2πf/sr`, `alpha = sin(w0)/(2q)`, `A = 10^(g/40)`; peaking `alpha·A / alpha/A`; shelf'ler `k = 2·sqrt(A)·alpha`; LP/HP/notch/BP/AP klasik; Cut tipi `1 + (g−1)·BP` (sabit bant genişliği). Tablo yok, iterasyon yok, dış kütüphane yok; her şey f64. (757 satırlık `design.rs` — Butterworth/Bessel/Chebyshev/elliptik — E5'in üç etkisi bunu KULLANMIYOR; kapsam dışı §3.8.) |
| Test vektörleri (6 test) | (1) Peaking merkez kazancı analitik + ölçülmüş (Goertzel tarzı `tone_amplitude` ölçümü, geçici atlanır, ±0.05 dB); (2) LP/HP −3.0103 dB kesimde + eğimler (12 dB/okt); (3) notch derinliği 1e-6 @60 Hz Q10; (4) shelf'ler: 10 Hz'de tam kazanç, kesimde yarısı, ötesinde düz; (5) BP/AP özşıklık; (6) **2.000.000 sessiz tick sonrası durum biteşik sıfır — denormal korumasının kanıtı.** Ölçüm aracı `tone_amplitude` da test util'e taşınır. |
| Hedef | `core/include/rawedit/Biquad.h` + `core/src/Biquad.cpp` |
| Boyut | **S** |

### 3.4 Compressor / Gate / Limiter (E5-C; Limiter E5-A'da da) — `src/effects/dynamics.rs`

| özellik | değer |
|---|---|
| Dosya | 558 satır; **414 kod / 144 test** (`#[cfg(test)]` 415'te) |
| Dış bağımlılık | `oversample.rs` (limiter'ın opsiyonel TP dedektörü — aynı PolyphaseBank/Interpolator), lib.rs paylaşımları (ParamValues, Smoothed, `db_to_gain`, `block_len`, `param_plumbing!` makrosu — C++'ta bunun karşılığı iskelet §3.6), `VecDeque` (sliding-min için; C++'ta boyut-sınırlı ring yazar — her işaret 64-bit sayaç, doğrulandı `Limiter.t: u64`) |
| Portlanacak semboller | `compressor_curve(x, thr, ratio, knee)` (yumuşak diz quadratic), `expander_curve(x, thr, ratio, range)`, `Compressor` (peak/RMS detektör; stereo-linked: en yüksek kanal tek ortak kazancı sürer; attack/release one-pole kazanç-dB düzleminde; make-up Smoothed; RMS zaman sabiti 10 ms sert kod), `Gate` (aşağı-expander; hold sayacı; range dB sınırı), **`Limiter`** (5 ms look-ahead `LOOKAHEAD_MS`, sliding minimum monotonic deque ile look pencereli kazanç, ardından one-pole release, ardından moving-average tam look uzunluğunda kazanç yumuşatma, final clamp ceiling'e — "ceiling guaranteed"; TP modu detektör gecikmesi `bank.delay()`; `latency() = look + det_delay` raporu) |
| Test vektörleri (7 test) | (1) Statik compressor eğrisi: diz kenarlarında süreklilik, orta nokta sert eğrinin altında, 800 noktada monotonik; (2) sabit (DC) seviye girişte steady-state curve eşleşmesi ±0.05 dB (peak/RMS detektörler DC'de aynı; L = −R korunur); (3) attack zaman sabiti: 1τ'da kazanç düşüşünün %63.2'si; (4) gate: thr altına düşünce hold süresince açık, sonra release ile expander_curve'a oturur; (5) **limiter asla ceiling aşmaz**: 200 rastgele boyutlu blok, her bloktan sonra anında assert \|y\| ≤ ceiling + 1e-7; impulslar; TP modunda LoudnessMeter ile doğrulanan TP < −1 + 0.6 dBTP; (6) ceiling altında şeffaflık: rapor latency kadar kaydırılmış giriş ile çıkış 1e-6'dan yakın eşleşir; (7) 1.0 genlikli 200 Hz sinüs −6 dB ceiling'e oturur (kuyruk 0.3 dB tolerans). |
| Hedef | `core/include/rawedit/Dynamics.h` + `core/src/Dynamics.cpp` |
| Boyut | **M** |

### 3.5 Otomatik ducking (E5-C) — `src/ducking.rs`

| özellik | değer |
|---|---|
| Dosya | 229 satır; **134 kod / 95 test** (test 136'da) |
| Dış bağımlılık | **YOK — tamamen saf fonksiyonlar** (fft yok, thread yok; sadece Vec allocasyonları) |
| Portlanacak semboller | `FLOOR_DB = −120`, `envelope_db(channels, sr, hop_s, window_s)` (kanal-ortalamalı güç prefix-sum'u; kenarlarda kırpılmış pencere), `activity(env_db, hop_s, threshold, min_on_s, bridge_s)` (blip atma + gap köprüleme; hop µs yuvarlaması), `sensitivity_threshold_db(s)` (0..10 → −20 − 4·s dBFS), `duck_keyframes(regions, clip_start, clip_end, base_db, reduce_db, fade_s)` (rampaları çakışan bölgeleri merge; clip kenarlarına clamp; kesilen rampaya kenarda keyframe; zamanları kesin artan) |
| Test vektörleri (5 test) | (1) Zarf seviyeleri: 1 sn sessizlik + ±0.1 kare dalga (−20 dBFS RMS) → 200 zarf noktası, sessiz FLOOR, sinyal −20, pencere kenarı arada; (2) activity: 0.5–1.5 sn konuşma köprülü, 2.00–2.03 blip atılır; sonda açık bölge kapanır; (3) sensitivity eşlemesi (5 → −40; 0 → −20; clamp 0..10); (4) keyframe basic/merge: rampalar çakışınca duck kalır, ayrıksa iki grup, zamanlar kesin artan; (5) **kenar kasaları:** bölge clip'ten önce → başta tam duck; rampa yarıda → kenarda rampa değeri; bölge sona taşar → duck bitiş kenarına; fade 0 bile kesin artan; clip dışı → boş. |
| Kullanım bağlamı (doğrulandı) | FilmCraft engine `essential_sound.rs:405-406` zinciri: trigger sinyalden `envelope_db` → `activity` (threshold = sensitivity map) → hedef klipe `duck_keyframes` yazımı. Ritimcraw E5-C'de aynı zincir: trigger = konuşma/music kanalı zarfı, hedef = music-klip volume keyframe'leri (F4 satıhıyla buluşur, §6). |
| Hedef | `core/include/rawedit/Ducking.h` + `core/src/Ducking.cpp` |
| Boyut | **S** |

### 3.6 AudioEffect iskeleti (E5-C) — `src/lib.rs` + `src/effects.rs` + `src/smooth.rs` kesitleri

| özellik | değer |
|---|---|
| Kaynak satırlar | `lib.rs` (292; seçilen kesitler), `effects.rs` (327; kayıtçi + ortak testler), `smooth.rs` (105; **72 kod / 33 test**) |
| Portlanacak kesit (~80 satır) | `Unit` enum (dB, Hz, ms, %, Q, ratio, toggle, choice...), `ParamSpec` (id/min/max/default/unit/log-hint/choices + `sanitize`), `ParamValues` (set/get/v/on/idx), `db_to_gain`/`gain_to_db`, `flush`/`flush32` (zaten §3.3'te), `Smoothed` (lineer ramp; set/snap/set_immediate/tick/is_smoothing) — tamamı. `AudioEffect` trait'inin C++ karşılığı: küçük soyut temel sınıf (id/params/setParam/param/reset/process/latency/responseDb/transferDb) — Rust'taki `param_plumbing!` makrosu C++'ta elle yazılmış inline metotlara dönüşür (birebir davranış) ya da kalıpsız elle (öneri: elle — 3-4 efekt için makro şart değil). Rust REGISTRY + `effects()` fonksiyonu E5-C'de gerekmez (4 efekt elle kurulur; dinamik fabrika ileride). |
| Test vektörleri | smooth.rs'in 33 satırlık test bloğu + effects.rs'in ortak blok-bağımsızlık testleri (E5-C portunun DoĞRULAMA iskeletini oluşturur: her efekt için "tek büyük blok == rastgele küçük bloklar" — Compressor/Gate/Limiter/EQ için bunu crate'te dynamics/eq testleri zaten kapsıyor; effects.rs'teki jenerik sürüm portta test helper olarak yeniden yazılır). |
| Boyut | **S** |

### 3.7 Resampler (E5-B) — `src/resample.rs`

| özellik | değer |
|---|---|
| Dosya | 173 satır; **93 kod / 80 test** (test 95'te) |
| Dış bağımlılık | YOK (i128 aritmetiği + f32 lineer interpolasyon) |
| Algoritma (görevin "sinc/linear?" sorusu) | **LINEAR.** Her çıkış karesinin kaynak konumu tam rasyonel: `out × from / to` (i128 `div_euclid`/`rem_euclid` ile) — faz blok sınırlarından bağımsız, sıfır faz sıçraması. Kaynak kareleri bitişik, örtüşmeyen span'lerle pull'lanır (mixin efekt durum sürekliliği görür). Çıkış, kaynağı 1 örnek geriden okur (≈21 µs @48 kHz) — geleceğe bakmadan interpolasyon. 44.1↔48 ve keyfi çiftler destekli; to=0 veya from=0 güvenli (1 Hz'e kelepçelenir). NEAREST yok (o AudioMixer tarafının v1 stand-in'iydi). |
| Portlanacaklar | `StreamResampler::new(from, to)`, `rates()`, `process(out, n, pull)` (pull callback: `Fn(i64 start, usize frames) -> Vec<Vec<f32>>` planar kaynak), `MAX_RESAMPLE_SOURCE_SPAN`, `source_index`. Rust i128 → C++ `__int128` (NDK clang destekli; taşma güvenliği için 53-bit sınır + fallback testi eklenir). Callback C++'ta std::function (Parallel.h'deki `std::function` deseni zaten var). |
| Test vektörleri (4 test) | (1) **Blok-bağımsızlık:** 4 oran çifti (48→44.1, 44.1→48, 48→96, 96→48) × {[20000], [512], [333], [1,7,512,64]} — çıktı BİT-EŞİT eşleşir; (2) 1 kHz ton 48→44.1: lineer interpolasyonun hata payı içinde aynı ton (~3e-3 örnek hatası, bir kaynak örneği gecikme); (3) pull çağrıları bitişik (ardışık pull'ların start + frames zinciri); (4) seek sıfırdan başlar + hostile girdi: eksik kanal, eksik örnek, 0 oran → çıkış sonludur, bloklar boş döner. |
| Hedef | `core/include/rawedit/Resample.h` + `core/src/Resample.cpp` |
| Boyut | **S** |

### 3.8 remix / channels (E5-B kesit) — `src/channels.rs`

| özellik | değer |
|---|---|
| Dosya | 426 satır; **294 kod / 132 test** (test 296'da) |
| Portlanacak KESİT (~120 kod satırı) | Ritimcraw mono/stereo dışı kanal GÖRMÜYOR (doğrulandı: AudioMixer.kt:49-52 mono → L=R duplicate, >2 → ilk ikisi). Full 5.1 seti (Mixdown enum'u, Pan51, point_gains, offsets) E5 kapsamı DIŞI. Kesit: `Layout{Mono, Stereo}` (Surround51 enumda kalır ama kullanılmaz — ileriye dönük ipucu), `K = 1/√2`, `matrix(from, to, mixdown)` — tek anlamlı satırlar: 2.0→1.0 `[K, K]`; 1.0→2.0 `[1; 1]` (satır başına, yani her iki kanala unity); identity; `apply_matrix` (sıfır kazanç atlanır); `convert`/`convert_owned`. |
| ITU gücü koruyor mu? (görevin sorusu) | **Downmix evet — kanonik BS.775:** stereo→mono `[k, k]`, k = 1/√2 ≈ −3.01 dB. Bağımsız L=R=x için mono = 2k·x = √2·x → güç (2k)²·x²/... RMS √2·x; yani güç korunmuş durumdadır: ortalama bağımsız stereo sinyalde sum konservatif, fazda zıt (L=−R) sinyalde sessizlik — bilinçli sum-and-drop davranışı (BS.775'in ta kendisi; testte katsayılar bit-eşit sabitlenmiş). **Upmix bilinçli +3 dB:** mono→stereo `[1; 1]` = duble-mono → BS.1770 ölçümünde bilinen +3 dB; bu filmcraft'ta KASITLI (mono klipler her yerde çift kanala kopyalanır — AudioMixer'daki mevcut davranışla aynı). Gain-power korunumu pan için (Pan51) kapsam dışı. Port testi: katsayı bit-eşitliği + örnek-başı matris uygulaması. |
| Test vektörleri (kesit için) | `bs775_downmix_coefficients_are_exact`'in 1.0↔2.0 satırları (5.1 satırları kapsam dışı), `upmix_places_on_front_speakers`'ın mono/stereo kısımları (5.1 panner kısmı hariç), `convert_applies_the_matrix_per_sample`. |
| Hedef | `core/include/rawedit/Channels.h` + `core/src/Channels.cpp` |
| Boyut | **S** |

### 3.9 Kapsam dışına atılanlar (gerekçeli)

| Modül | wc -l | Dışarıda kalış gerekçesi |
|---|---|---|
| `src/design.rs` | 757 (648/109) | Butterworth/Bessel/Chebyshev/elliptik "Scientific Filter" tasarımı — Premiere-parite setinden. E5'in üç etkisi (RBJ biquad, dynamics, ducking) bunu kullanmıyor (grep: eq.rs/dynamics.rs `design::`ye referans vermiyor — doğrulandı). İleride Premiere-parite dalgasında. |
| `src/remix.rs` | 905 | Müzik beat-grid remix (tempo/DP beat takip, chroma benzerlik, joint planlama, crossfade render). Ritimcraw şartnamesinde yok (§8.4 listesinde de yok); fft'ye büyük bağımlılık. |
| `src/sync.rs` | 516 (309/207) | GCC-PHAT kayıt senkronizasyonu (görev metninde de kapsam dışı deniyor): Ritimcraw'da karşılığı `core/src/OpticalSync.cpp` — kendi çözümü zaten Phase 3-4'te implement. |
| `src/effects/essential.rs` | 764 (508/256) | DeEsser/DeReverb/SpeechEnhance/StereoWidth — §8.4'te "(Opsiyonel) Konuşma netleştirme ve gürültü azaltma ... sonraya bırakılır" sahibin net kararı. |
| `src/effects/{restoration, stft}.rs`, `src/fft.rs` | restoration 334 (263/71) + stft 99 + fft 115 (86/29) | DeHum/DeNoise ve STFT iş_frame — aynı §8.4 "sonraya" hükmü. fft.rs yalnız bunlarla ve remix/sync'le gerekiyor (E5 seçimi hiçbirine dokunmuyor — doğrulandı: seçili modüllerin import'ları fft içermiyor). |
| `src/effects/{reverbs, time, modulation, multiband, special, pitch, declick, filters, premiere_tests, util, channel, restoration}.rs` + `effects.rs` kayıtçisi | ~6800 | Şartnamede OLMAYAN efekt seti (reverb, delay, chorus, multiband, distortion, pitch...). E5-C net dörtlüsü dışında. special.rs:876'daki `LoudnessMeterFx` bir efekt-SARMALAYICI — E5-A'nın C++ API'si doğrudan ölçeri verir; sarmalayıcıya gerek yok. `channel.rs` (Amplify/Balance/Route) — Ritimcraw'da karşılığı zaten VAR: `AudioPlan.clipGains`/`mixSum`/`saturate` (TIMELINE_AUDIO_PLAN §3); gain/pan kopyası gereksiz duplication. |
| `tests/loudness_oracle.rs` | 285 | ffmpeg bağımlı oracle — doctest'e sinyal üreticileri + sabit beklentiler olarak taşınır (§3.1); ffmpeg'i test kapısına bağlamak zorunlu değil. |

---

## 4. Export entegrasyon noktaları

### 4.1 Zincir: ölçüm → kazanç → limiter → doğrulama (iki-geçişli önerisi)

§13.9 hükmü: "Ölçüm son miks üzerinden, true peak kontrolü limiter
sonrası yapılır." Mix çıkışından AAC'e önerilen yol:

```
AudioMixer.renderBlock çevrimi                          [mevcut]
  (ClipAudioState: gain→pan→fade, sum, master, saturate)
  │  16-bit interleaved bloklar (MixBlock)
  ▼
[PASS 1] planar f32'e dönüştür + LoudnessMeter besle → I (LUFS-I), TP, SP
  │  g = normalize_gain_db_peak_limited(I, hedef, TP, TP_sınırı)
  │  (TP'yi aşan hedef için g kırpılır — loudness feda, peak korunur;
  │   limiter bu feda'yı geri kazanmayı dener)
  ▼
[PASS 2] g uygula + Limiter(ceiling = TP hedefi, look-ahead 5 ms, TP dedektörü açık)
  ▼
[PASS 3] hızlı doğrulama ölçümü I', TP' — limitli sapma varsa
         g'yi düzelt ve TEKRAR (≤ 2 iterasyon önerisi; sabit tekrar,
         yakınsaklık testi doctest'te)
  ▼
16-bit'e dönüştür + writeAudioBlock (AAC) — pts AYNI (blok başına
  k·1e6/rate µs kuralı değişmez; süre/pts etkisi YOK — görevin
  "pts etkisi yok" maddesinin teyidi)
```

**İki-geçişli mi (export öncesi ölçüm pass'i) tek-geçişli mi?**
Trade-off analizi:

- Normal + limiter DOĞRUSAL OLMAYAN zincir: tek geçişte (ölçmeden
  önce adaptive kazanç) hedef I'ya ancak yaklaşılır; limiter devreye
  girerse gerçekleşen I öngörülemez şekilde hedefin altında kalır.
  İki geçiş (ölç + uygula + doğrula) DETERMİNİSTİK hedef verir, ki
  preset sözü budur ("−14 LUFS-I").
- Tek-geçişli gerçek-zamanlı normalize yalnız oynatmada anlamlı
  (streaming bazen ihtiyaç olur); export zaten offline, iki-geçiş
  bedava doğruluk.
- Bellek: iki geçiş, tam miks PCM'ini iki kez OkUMALI. Mevcut
  per-klip export zaten tüm PCM'i bellekte tutuyor (`readAudio` →
  tek ShortArray; Mp4Exporter.kt:1380-1384) — E5-A v1'i ölçümü bu
  mevcut bellek üzerinde yapar: yeni büyük bellek YOK; pass 2 yalnız
  `g × clamp` uygulayıp geri yazar (in-place, tek ek kopya f32
  blok-başı tampon). Export süresine ek: ~2 ek herbir örnek
  geçişi (ölçüm K-filtreleri + TP interpolasyonu — §8.1 maliyeti).
- Inline (S6 tarzı ayrı "measurement" ekranı) mi? Hayır — E5-A
  ölçümü export ZAMANINDA çalışır; kullanıcıya gösterge olarak
  sonucu raporda yazmak yeterli (ExportReport'a "audioLufs"/"
  audioDbtp" alanları önerisi). Ayrı bir "miks ölç" aracı ileride
  timeline'a konabilir ama E5'in şartnamesi değil.

**16-bit ara formatın etkisi:** mix blokları zaten 16-bit
quantize edilmiş; ölçüm bu girdi üzerinden yapılırsa "ne
export edilirse o ölçülür" dürüstlüğü korunur. f32 alanda gain
uygulayıp yeniden 16-bit'e dönerken ikinci quantize gürültüsü
(~±0.5 LSB ≈ 0.001 LU düzeyi) ihmal edilebilir (§8.3).

### 4.2 Nereye girer (kod adresleri)

- **Per-klip (Mp4Exporter):** `Mp4Exporter.kt:1372-1386` blok
  listesi üretilmeden HEMEN önce: ölçüm `pcm` üzerinde, `g` hesabı,
  sonra `chunk()`'a girmeden bloklara `g`+limiter uygulaması (ya da
  blok iterasyonunda). Tek PCM bellekte olduğundan en ucuz yerleşim.
  `audioPts` aynı kalır (blok sayısı ve süre değişmez).
- **Timeline (TimelineMp4Exporter):** video yürüyüşünden sonra,
  `writer.finalize`'dan önce yeni bir "audio finalize" aşaması olarak:
  AudioMixPlan + AudioMixer.renderBlock zaten mevcut sırayı veriyor;
  E5-A bu döngünün dışına ölçer halkası ekler (renderBlock çıktıları
  bir PcmSource'a yazılır → ölç → kazanç uygula → yazıcıya ver).
  TIMELINE_AUDIO_PLAN §5.3'ün "export pass önce, playback sonra"
  önerisiyle uyumlu; writer'a DOKUNULMAZ (§5.1 "dumb pipe").
- **Ayarlar:** `ExportSettings.kt`'e eklenecek alanlar (wire v=2;
  parser zaten unknown-version'u "reported, never guessed" diyor —
  ExportSettingsWire deseni): `loudnessTargetLufs: Float?` (null =
  kapalı → mevcut davranış bit-eşit), `truePeakDbtp: Float = −1.0`.
  `ExportPreset`: `SOCIAL` (§7.4 zaten öngörüyor: "Sosyal (dikey,
  −14 LUFS, H.264/HEVC)") — loudnessTarget = −14, TP = −1, dikey
  1080×1920; değiştiren alan CUSTOM'a düşer (mevcut kural).

### 4.3 Ses pts etkisi

Görevin maddesi doğrulandı: zincir yalnız örnek DEĞERLERİNİ değiştirir
(ve blok sınırlarını değil); `PtsTimeline.audioBlockPts` k =
k·1e6/rate µs kuralı ve AAC blok boyutu (1024 frame) aynı kalır →
pts zinciri ve video-ses hizalaması etkilenmez. AAC encoder'ı (MediaCodec) finalized PCM'i görür; bitrate ayarı ayrı (audioKbps) ve E5
tarafından değişmez.

---

## 5. Preset'ler (sahip §13.9)

| Preset | Hedef I (LUFS-I) | TP üst sınırı (dBTP) | Not |
|---|---|---|---|
| Sosyal | −14.0 | −1.0 | §13.9 saha: YouTube ≈ −14; Reels medyan −14.1; TikTok medyan −21.2 (dağınık). Adlandırma "platform zorunluluğu" DEĞİL — uygulamanın kendi hedefi ("Sosyal video için optimize edilen hedef"; platform adları UI'da iddia olarak kullanılmaz). |
| Podcast | −16.0 | −1.0 (öneri; §7.3'te TP belirtilmemiş) | §13.9/§7.3: −16. TP sınırı §9'da online sorusu. |
| Yayın (EBU R128) | −23.0 | −1.0 (öneri) | §13.9: "Yayın EBU R128 −23". R128'in TP/S/M üst limitleri (ör. max S) v1'de ölçülür ama zorlanmaz — belge notu. |
| Kapalı | — | — | Mevcut davranış: ham miks, yalnız ±1.0 saturate. Varsayılan (geriye uyumluluk: eski ayarların davranışı değişmez). |

Adlandırma ilkesi (§13.9 hükmü): preset adı uygulamanın HEDEFİ olarak
konur; "YouTube/Reels şartı" şeklinde platform iddiası metinlerde
yapılmaz (TR/EN string'ler buna göre yazılır).

---

## 6. UI püşkeleşmesi (kapsam tasviri; kod yok)

**Export ayarları — ses bölümü (E5-A/E5-B; `ExportSettingsPanel.kt`
büyür, mevcut yapı: HelpTitle + ChipRow + ReadOnlyRow):**

1. AAC kbps: mevcut [128/192/256/320] chips — değişmez.
2. Örnekleme hızı (E5-B): mevcut read-only "Audio format" satırı →
   [44.1 kHz, 48 kHz] chips. Default: kaynak hızı (per-klip) /
   DEFAULT_MIX_RATE_HZ=48k (timeline; TIMELINE_AUDIO_PLAN §2.1
   kararı korunur — sorun olmadan 48 k passthrough). Kaynak hızı ≠
   seçim ise satırda "resample edilir" bilgisi gösterilir.
3. Mono/Stereo (E5-B): [Stereo, Mono] chips; default kaynak. Downmix
   BS.775 `[k, k]`; mono→stereo `[1; 1]` duble (bilinçli +3 dB).
4. Loudness hedefi (E5-A): chips [Kapalı, Sosyal −14, Podcast −16,
   Yayın −23] + Custom seçiminde LUFS girişi (−36..−5 güvenli aralık
   önerisi) + TP sınırı (−3..0 dBTP aralığı, default −1.0).

**Per-klip ses — timeline tarafı (E5-C + F4):**

- Mevcut: `ui/timeline/AudioSettingsSheet.kt` (366 satır) — gain
  dB, fade in/out ms (AUD-03). Pan/mute alanları MODELDE var
  (TIMELINE_AUDIO_PLAN §6.3-4, 2026-11-23) ama UI kontrolü yok.
- E5-C'in önerisi: AudioSettingsSheet'e (a) pan + mute kontrolleri
  (model hazır; tek eksik UI), (b) "Efektler" bölümü — ParamSpec
  listesinden UI üretimi (crate'in tasarım felsefesi: README,
  "ParamSpec ... so UIs can be generated"; Cargo.toml üst notu).
  İlk dalga: compressor (threshold/ratio/knee/att/rel/makeup) ve
  basit EQ (SimpleEq 3 bant: low/mid/high dB). Gate/limiter ikinci
  dalga.
- **Kimin paketi (görevin sorusu):** per-klip gain slider F4'ün
  timeline paketi (§8.4 "Timeline'daki kliplerde ses seviyesi çizgisi
  ve keyframe'ler"); E5-C DSP çekirdeğini ve ParamSpec metadata'yı
  verir, keyframe SATIRI/çizimi F4'ündür. Ducking (E5-C) ürettiği
  keyframe'leri F4'ün volume-otomasyon formatına YAZAR — interface:
  `duck_keyframes`'in `(time_s, level_db)` listesi (doğrulandı:
  crate fonksiyon imzası aynen bu). F4↔E5-C sözleşmesi tek satır
  tiptir; implementasyon sırası F4'den bağımsız (E5-C önce biter,
  F4 satıhını kendi paketinde açar).

---

## 7. Paket kırılımı

### E5-A — Loudness ölçer + true-peak + normalize halkası → "Sosyal" preset

- **Dosyalar (C++):** `core/include/rawedit/Loudness.h` +
  `core/src/Loudness.cpp` + `core/tests/test_loudness.cpp`;
  `core/include/rawedit/Oversample.h` + `core/src/Oversample.cpp`;
  `core/include/rawedit/Biquad.h` + `core/src/Biquad.cpp` (K-ağırlık
  için gerekli; EQ daha sonra genişletir). CMakeLists.txt'e üç
  `add_executable` + `add_test` blokları ve `rawedit` kütüphanesine
  `Loudness.cpp/Oversample.cpp/Biquad.cpp` kaynak satırları;
  `tools/build-desktop.sh`'e derleme + çalıştırma blokları.
- **Doğrulama:** `bash tools/build-desktop.sh` tam sürümü YEŞİL olmalı
  (desktop doctest'leri).
- **JNI köprüsü (öneri, D1'e sadık):** `jni_bridge.cpp`'ye ince
  fonksiyonlar: `loudnessMeasure(pcm interleaved f32 veya i16 +
  rate/ch → {I, TP, SP})` ve `audioFinalize(pcm, targetLufs, tpMax) →
  {pcm, I', TP', iterations}`. Alternatif v1 basitleştirmesi: ölçer
  JVM tarafında native çağrıyla blok blok beslenir (AudioMixer loop'un
  içinden tek satır) — JNI yüzeyini minimize eder; seçenek render
  katmanı yazılırken sahibin onayına.
- **Kotlin tarafı:** TimelineMp4Exporter + Mp4Exporter'a finalized
  ses aşaması; ExportSettings'e `loudnessTargetLufs`/`truePeakDbtp`
  (wire v2); ExportPreset'e `SOCIAL`; ExportSettingsPanel'a hedef
  chips; ExportReport'a ölçüm alanları.
- **Testler:** crate'ten loudness 14 + oversample 1 + biquad 6
  testi doctest'e; oracle sinyal üreticileri (sine/pink/speech ~80
  satır test util) + sabit beklentiler; opsiyonel RAWEDIT_FFMPEG
  oracle kapısı; iki-geçişli halkanın yakınsaklık testi (normalize
  OFF → eski export BİT-EŞİT testi: mevcut davranışın korunduğunun kanıtı).
- **Boyut: M.** Bağımlılık sırası: Biquad → Oversample → Loudness →
  normalize halkası (Rust'ta: loudness biquad+oversample'ı kullanır;
  halka üstte) → export bağlama + preset.

### E5-B — Resampler + downmix → E1'in dondurduğu ayarlar açılır

- **Dosyalar:** `core/include/rawedit/Resample.h` + `core/src/
  Resample.cpp` + `core/tests/test_resample.cpp`;
  `core/include/rawedit/Channels.h` + `core/src/Channels.cpp` +
  `core/tests/test_channels.cpp` (kesit testleri).
- **Kotlin tarafı:** `ExportSettings.sampleRateHz: Int?=null` +
  `channels: Int?=null` (null = kaynak; omit-when-default kalıcılık);
  ExportSettingsPanel'de read-only satırların chips'e dönüşmesi;
  per-klip export'ta kaynak hızı ≠ seçim ise Resample.cpp
  (`StreamResampler`) devrede; timeline export'ta mix rate =
  seçim (AudioPlan.DEFAULT_MIX_RATE_HZ yerine askıya alınır).
  AudioMixer.Kernel LINEAR kalır; StreamResampler portunun getirisi:
  export'ta pull-based (tek geçiş kaynak→mix) yerine **push
  kullanımı** — exporter AudioMixer.renderBlock'u çağırmaya devam
  eder, renderBlock içindeki pull matematiği aynı lineer interpol.
  Port edilen StreamResampler playback tarafında (cihaz hızı ≠ mix
  hızı) kullanılacaktır — playback dalgası.
- **Testler:** crate'ten resample 4 + channels kesit 3 testi;
  downmix sonrası LUFS ölçüm testi (mono→stereo +3 dB bilinçli;
  stereo→mono −3.01 dB katsayı) E5-A ölçeriyle çapraz doğrulanır.
- **Boyut: S-M.** Bağımlılık: E5-A'dan bağımsız ama ÖLÇEREB
  E5-A'dan sonra (downmix/hız seçimi sonrası ölçüm hedefi "final
  hattın" hedefi olmalı — seçim değişirse I değişir; sıralama
  doğal).

### E5-C — Biquad EQ + compressor/gate/limiter + ducking → timeline ses efektleri

- **Dosyalar:** `core/include/rawedit/Dynamics.h` + `core/src/
  Dynamics.cpp` + `core/tests/test_dynamics.cpp`;
  `core/include/rawedit/Ducking.h` + `core/src/Ducking.cpp` +
  `core/tests/test_ducking.cpp`; `core/include/rawedit/Smooth.h`
  (72 satır — header-only); iskelet: `core/include/rawedit/AudioFx.h`
  (Unit/ParamSpec/Smoothed/db_to_gain/flush + soyut AudioEffect —
  tek header). Biquad'ın tam kullanımı (FilterType'ların hepsi)
  bu paketle açılır (E5-A'dan genişletilir).
- **Efekt CPU zinciri (öneri):** gain → **efektler** → pan → fade →
  sum (TIMELINE_AUDIO_PLAN §3.3 zincirinin önüne efektler;
  belgesel senkron). Efektler klip-başına (source decode edilmiş
  pencerede uygulanır) — mix pass'te `renderBlock` içinde clip
  iterasyonunda uygulanır. Efekt state'i clip'e bağlı olarak
  plan'da taşınır (kırpılma/seek durumunda sıfırlama kuralı —
  TIMELINE_AUDIO_PLAN pull kontratı bozulmaz: argümanlı seek
  efektleri reset'ler).
- **Testler:** dynamics 7 + ducking 5 + smooth 33 satır blok +
  efek başına blok-bağımsızlık (tek-klip kullanım).
- **Boyut: M.** Bağımlılık: E5-A (biquad/oversample) hazır; F4
  SFX/keyframe tarafıyla buluşma tek imza (keyframe listesi).

---

## 8. Riskler

### 8.1 True-peak oversample maliyeti (görev maddesi; öneri: cihaz ölçümü)

- Teorik maliyet (hesap, doğrulandı): 4×, 16 tap/faz → kanal başına
  `factor × 16 MAC = 64 MAC/giriş örneği`; 48 kHz stereo ölçüm
  pass'inde ~6.1 M MAC/s; modern telefon (SD8 Elite) için önemsiz
  düzey. Ölçüm + limiter birlikte iki geçişte ~4x bu değer.
- **Asıl risk süre değil, skopa eklenmesi:** AAC encode ile aynı
  export iş parçacığında çalıştığında export duvar süresine ek
  (uzun videolarda kullanıcı gözlemi). Ölçüm planı (cihaz; S?
  ölçüm adayı): 10 dakikalık stereo miks + AAC export üç
  konfigürasyon: (a) E1 hattı (ölçüm yok), (b) + iki-geçiş ölçüm +
  normalize kapalı, (c) + normalize açık. `ExportReport.wallMs`
  farkları rapora yazılır (cihaz: Xiaomi 15 Ultra, §13.13 build
  kimliğiyle). E5-A'ya, bir `audioMeasureMs` rapor alanı önerilir —
  ölçüm cihaza inince gerçek değer görünür.
- **4× FIR alternatifi değerlendirmesi (görevin sorusu):**
  Rust zaten 4× polyphase FIR (Kaiser-sinc) — task'taki "4x
  oversample pahalıysa 4x FIR alternatifi" formülasyonu
  yorumlanmıştır aslında: maliyet zaten FIR tabanlı ve düşük; spekülatif
  ucuzlatma (2× + parabolik tepe) BS.1770-4'ün ≥4× şartını
  gevşetir ve EBU Tech 3341 TP tolerans bandını (+0.2/−0.4 dB)
  riske atar. Karar: **4× default; test parametreli (faktör
  constructor argümanı); ölçüm 2×'in yeterli olduğunu gösterirse
  "hızlı TP" knob'u** değerlendirilir — veri sahibi kararına.

### 8.2 BS.1770 LFE/kanal ağırlıkları

- `default_channel_weights(6)` = [1, 1, 1, **0**, 1.41, 1.41] (LFE
  hariç; surround +1.5 dB) — doğrulandı (kod 71-78). Test
  `surround_weights` 5.1 için bunu sabitler. Ritimcraw v1
  stereo/mono olduğundan pratik risk yok; port ağırlık vector'ü
  PARAMETRE olarak taşır (`set_channel_weights` da port) — 5.1
  geleceğinde enum'un unutulma riskine karşı doctest'te 5.1 ağırlık
  testi de taşınır (test 3.1'in 10 no'lu maddesi).
- Mono ölçümün dual-mono +3 dB kuralı (crate: mono tek kanal ölçülür,
  karşılıklı iki kanala kopyalanınca LUFS +3 artar): E5-B'nin
  [1;1] upmix'i ile etkileşim — mono kaynaktan stereo export'ta I
  ölçülen +3 dB'yi içerir; normalize hedefe oturur, kullanıcıya
  sürpriz yok (ölçüm son miks üzerinden olduğundan §4.1 zinciri
  güvenli).

### 8.3 float vs int PCM ara formatları (görevin sorusu: mevcut AudioMixer formatı ne?)

- **Mevcut format (doğrulandı):** AudioMixer dahil tüm yol
  **16-bit interleaved ShortArray**: decoder/NativeSource int16 →
  AudioMixer (içte float hesap, double akümülatör, `toPcm` ile
  16-bit'e dönüş — `AudioMixer.kt:241` `(x × 32768f).roundToInt()
  .coerceIn(-32768, 32767)`) → AAC writer 16-bit PCM alır.
  FilmCraft crate'i **planar f32** bekler.
- **Port kuralı (öneri):** port edilen modüller planar f32 API
  sunar (`std::vector<float>` kanal başına ya da `float* chans[]
  + n`); JNI sınırında i16↔f32 dönüşümü tek noktada (norm /32768 —
  AudioMixer'daki `norm` ile aynı; toPcm kullanıcıları). Dosya
  format transferleri bit-eşit kalmalı: yardımcı fonksiyonlar
  port paketinde `Core/AudioConvert.h` altında tek yerde.
- **Bit-eşitlik testi (şart):** normalize kapalıyken eski
  export'la BİT-EŞİT PCM (quantize çözümlemesi aynı: roundToInt +
  clamp zinciri korunur). Bu bir doctest vektörüdür (E5-A
  checklist'i).
- Ek gürültü: f32 alanda g uygulayıp yeniden quantize → ±0.5 LSB
  (~0.001 LU) — hedefe oturma toleransı zaten ±0.1 LU'nin
  içinde.

### 8.4 Rolling-off vs clipping (görev maddesi)

- Normalize pozitif gain (sessiz miks + hedef daha yüksek) → TP
  sınırına kadar serbest, sonrası limiter: gain kırpma
  (`normalize_gain_db_peak_limited`) + limiter'ın look-ahead'ı.
  Kombinasyon: hedef I'ya varmak için limiter gerekenden fazlaysa
  I hedefin altında kalır — pass 3 doğrulaması bunu rapor eder
  (köşe senaryosu: tepe yoğun müzik + çok yüksek hedef I;
  kalıcı bir çözüm yok: TP korunur, I feda — §3.1'deki
  `normalize_gain_db_peak_limited` semantiği zaten bu).
- Negatif gain (yüksek miks + düşük hedef) → limiter etkisiz,
  temiz düşüş — test vektörü "loud mix'e negatif gain".
- Saturate önce mi sonra mı: mix'in kendi ±1.0 saturate'i ŞİMDİLİK
  kalır (MixBlock.clip count AUD-07); E5-A halkası bunun PEŞİNE
  ölçer — yani export zamanı zinciri: mix → (16-bit) → ölç/g/limiter
  → AAC. Mix sırasında değerler zaten ±1'in üstünde olamaz; normal
  gain ↑ yalnızca hedef TP içi makul aralıkta (−14 LUFS hedef,
  −1 dBTP tavan) taşma yaratmaz tipik müzikte; limiter yine de
  garanti olarak duruyor (5 ms look-ahead, TP dedektörlü).

---

## 9. Doğrulanamayanlar + online araştırma ajanına sorular

**Bu tarama içinde doğrulanamayanlar:**

1. MotionCam konteynerindeki `AUDIO_DATA_F32 = 7` tipinin hangi
   uygulama/cihaz sürümlerinde yazıldığı ve ritimcraw'ın kaynak
   kliplerinde sesin hiç f32 olup olmadığı — decoder 16-bit yolunu
   kullanıyor (doğrulandı); varsa vaka belgelenmeli.
2. Xiaomi 15 Ultra'da iki-geçişli ölçümün export süresine etkisi
   (§8.1'in ölçüm planı).
3. AAC encoder'ın (MediaCodec, cihaza özgü) mono encode öncesi
   herhangi bir otomatik kazanç normalizasyonu yapıp yapmadığı —
   platform davranışı, koddan okunamaz.
4. Podcast −16 için yaygın TP üst sınırı — §5'te −1.0 önerildi;
   birincil kaynak teyidi gerekli.

**Online araştırma ajanına sorular:**

1. **Android AudioTrack/MediaCodec AAC hattı UV/mono eşlemesi:**
   AAC-LC profilinde cihaz encoder'larının mono kanal
   konfigürasyonu davranışı; encoder'ın girişte pre-gain/DC/high-pass uygulaması var mı (bazı cihaz AAC encoder'larında raporlanmış).
   Normalize halkasının encoder'dan ÖNCE olması bu riski kapatır
   ama cihaz encoder'ın ölçülen içeriği DEĞİŞTİRMESİ (ör. sessiz-baş etkisi) durumunda "ölçüm WYSIWYG olmaz" senaryosu: ölçüm
   DECODED EXPORT dosyası üzerinden mi doğrulanmalı (şart değil;
   ama bilmek iyi olur).
2. **BS.1770-4 vs BS.1770-5 farkı:** 2023'te yayınlanan -5'in
   değişiklikleri (görev sorusu; özellikle surround kanal ağırlıkları ve LRA tanımındaki değişiklikler) — E5-A -4'ü
   portluyor; -5 farkları stereo v1'de etkisiz olmalı ama not
   edilmeli.
3. **Mevcut saf C++ BS.1770 referans implementasyonları:**
   libebur128 (MIT, C — en yaygın; Rust crate'ten bağımsız
   clean-room doğrulama kaynağı olarak desktop testlerinde
   kullanılabilir mi; lisans uyumu), kx.rlib, pyloudnorm (Python,
   referans amaçlı). Ritimcraw'ın clean-room durumu: FilmCraft
   crate'i clean-room (README: "Clean-room: written from the public
   standards"; doğrulandı) — port da clean-room sayılır; libebur128
   karşılaştırması ancak TEST amaçlı ve lisans zincirine
   girmez (test dosyası bağımsız, produktime ikili etmez). Öneri:
   masaüstünde `RAWEDIT_FFMPEG` yerine `RAWEDIT_EBUR128_REF` kapı
   libebur128 + ffmpeg'e karşı çapraz üçgen doğrulama.
4. LUFS/LRA ölçümünde 44.1 kHz kaynakların K-ağırlık türevi için
   herhangi bir referans noktası farkı olduğu raporlandı mı
   (crate testi sr bağımsızlığını sabitliyor; oracle da 44.1'i
   kapsıyor — ek araştırma muhtemelen gereksiz).
5. Sosyal platform saha ölçümlerinin (§13.9) güncelliği: TikTok
   medyan −21.2 → "TikTok hedefi −21" preset gerekliliği; YouTube
   Music/Others değişimleri. (Preset setine bir "TikTok" adayı
   eklensin mi — sahibin kararı.)

---

## 10. Ölçüm özeti (taahhüt tablosu)

Satır sayıları `wc -l` (filmcraft-android working tree, 2026-11-24;
kod/test ayrımı `#[cfg(test)]` satır konumundan ölçüldü):

```
crates/audio-dsp/src/loudness.rs       693  (kod 446 / test 247; test modu 447'de başlar)
crates/audio-dsp/src/oversample.rs     144  (kod 123 / test 21;  test modu 122'de)
crates/audio-dsp/src/biquad.rs         231  (kod 146 / test 85;  test modu 148'de)
crates/audio-dsp/src/resample.rs       173  (kod  93 / test 80;  test modu 95'te)
crates/audio-dsp/src/channels.rs       426  (kod 294 / test 132; test modu 296'da)
crates/audio-dsp/src/ducking.rs        229  (kod 134 / test 95;  test modu 136'da)
crates/audio-dsp/src/smooth.rs         105  (kod  72 / test 33;  test modu 73'te)
crates/audio-dsp/src/fft.rs            115  (kod  86 / test 29)  [kapsam dışı]
crates/audio-dsp/src/design.rs         757  (kod 648 / test 109) [kapsam dışı]
crates/audio-dsp/src/remix.rs          905                        [kapsam dışı]
crates/audio-dsp/src/sync.rs           516  (kod 309 / test 207)  [kapsam dışı]
crates/audio-dsp/src/effects.rs        327  (kod ~163; kayıtçı + jenerik testler)
crates/audio-dsp/src/effects/dynamics.rs 558 (kod 414 / test 144; test modu 415'te)
crates/audio-dsp/src/effects/eq.rs     564  (kod 374 / test 190; ilk test modu 375'te)
crates/audio-dsp/src/effects/essential.rs 764 (kod 508 / test 256) [kapsam dışı]
crates/audio-dsp/src/effects/{restoration(334), stft(99), util(246), channel(340),
  reverbs(474), time(529), modulation(554), multiband(621), pitch(252),
  declick(235), filters(702), special(952), premiere_tests(553)}.rs  [kapsam dışı]
crates/audio-dsp/tests/loudness_oracle.rs  285  (ffmpeg oracle; §3.1 taşıma stratejisi)

Ritimcraw tarafı (bu repo, worktree HEAD fd749d0):
app/src/main/java/com/ritim/ritimcraw/data/timeline/AudioMixer.kt   243
app/src/main/java/com/ritim/ritimcraw/data/timeline/AudioPlan.kt    526
app/src/main/java/com/ritim/ritimcraw/data/audio/AudioTrackOps.kt   323
app/src/main/java/com/ritim/ritimcraw/data/audio/AudioTrackModel.kt 222
app/src/main/java/com/ritim/ritimcraw/engine/EditorAudio.kt         684
app/src/main/java/com/ritim/ritimcraw/export/MediaCodecMp4Writer.kt 1287
app/src/main/java/com/ritim/ritimcraw/export/Mp4Exporter.kt         2541
app/src/main/java/com/ritim/ritimcraw/export/ExportSettings.kt        208
app/src/main/java/com/ritim/ritimcraw/export/ExportPlan.kt             406
app/src/main/java/com/ritim/ritimcraw/export/TimelineMp4Exporter.kt    646
app/src/main/java/com/ritim/ritimcraw/export/PtsTimeline.kt             113
app/src/main/java/com/ritim/ritimcraw/ui/timeline/AudioSettingsSheet.kt 366
docs/TIMELINE_AUDIO_PLAN.md                                            605
```

Build kapısı: `bash tools/build-desktop.sh --no-tests` YEŞİL (tarama
öncesi ve sonrası; docs-only). Kaynak provanans: filmcraft-android
main `c41b58467e` (working tree HEAD ref'i, okundu; bu repo dışında
commit edilmedi).
