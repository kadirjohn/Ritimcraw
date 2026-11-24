# E0b — encoded (Mi-Log / MP4) GPU decode: tasarım notu (E0b-1)

Durum: tasarım, 2026-11-25. Kod yok. Gerekçe ve rehber: `docs/ENCODED_EXPORT_PLAN.md` §4.
Gerekçe sayısı (debug, cihaz, 4K30 P010 fikstür): CPU yolu kare başına ~1,05 s; 623 karelik klip ~11 dk; **gerçek zamandan ~65 kat yavaş**.

## 1. Bugünkü yol (kodda doğrulandı)
`EncodedAdapters` MediaCodec'ten `getOutputImage` ile Image alır, düzlemleri CPU'da NV12/I420 (veya P010) bayt dizilerine
kopyalar; `nativeEncodedDevelop` (jni_bridge.cpp ~6063) bunları `rawedit::developEncoded`'a verir:
sınırlı/tam aralık YUV → RGB → transfer çözme (Mi-Log / Rec709 / sRGB / Linear) → birincil dönüşümü → WB/temp/tint/pozlama →
sharpness/detail/tonemap (+gürültü giderme) → isteğe bağlı LUT → v2 düğüm zinciri (CPU, F1-6, K11) → `linearToRec709` → 8-bit ARGB.
Renk parametrelerinin tek kaynağı `EncodedColorSpec` (matrix, range, transfer, primaries, bit derinliği; `nativeMatrix/Transfer/Primaries`).

## 2. Hedef yol
`MediaCodec` → **`ImageReader`** (YUV_420_888 / P010) → düzlemler GL dokusu → GLES develop zinciri.
**OES ana yol değil:** OES'te YUV→RGB'yi sürücü yapar; matrix/range belirsiz, P010'da 10-bit kaybolabilir. OES yalnızca ileride,
parity kanıtlanırsa preview için ayrı bir seçenek olabilir.

- 8-bit: Y → `GL_R8`, CbCr → `GL_RG8` (yarım çözünürlük). P010: Y → `GL_R16`, CbCr → `GL_RG16` (10 bit değer 16-bit sözcüğün üst bitlerinde, alt 6 bit sıfır
  [ImageFormat.YCBCR_P010 tanımı]; **kesin ölçek E0b-2'de CPU oracle ile sabitlenir**, burada tahmin edilmez). `GL_EXT_YUV_target` varsa
  alternatif; yoksa yukarıdaki iki-doku yolu varsayılan.
- Range / matrix / transfer / primaries aynı shader'da, **`EncodedColorSpec` uniform'larıyla**; spec tek kaynaktır, shader'a ikinci bir
  kural kopyalanmaz. HDR transferleri (HLG/PQ) bugün CPU'da Rec709'a yaklaştırılıyor (`approximations`); GPU aynısını yapar, daha iyisini değil.
- Yükleme: `AHardwareBuffer`/`glTexSubImage2D`; ilk sürüm `glTexSubImage2D` (basit, ölçülebilir). Kopya sayısı E0b-5'te ölçülür.

## 3. GLES session'a etkisi
- Bugünkü pass 1, `GL_R16UI` CFA dokusunu RGBA32F eke geliştirir (`GlesPipeline.cpp:790`, `GpuDevelopPipeline`). Encoded için **yeni bir
  pass-1 varyantı** eklenir: CFA yerine YUV dokularını okur, çıktısı aynı RGBA32F ara görüntü. Sonraki pass'ler (sharpness/detail,
  NR, NODES, TAIL, store) **değişmeden** çalışır; warp encoded'da yok (CPU yolundaki gibi) ve kapalı kalır.
- `ChainSlots` (ping-pong slot planlayıcı, saf) pass-1 girdisinin ne olduğunu bilmez; slot planı **aynı** kalır. Çakışma riski: pass-1
  giriş tipi `GpuDevelopPipeline::Impl` ve request yapısında (append-only kuralı) yeni alanlar ister; bu yüzden E0b-2 yalnızca
  **test + yeni shader + yeni giriş yolu**, mevcut MCRAW yolunun çağrı sırası dokunulmaz (v1 bit-aynılık kilidi).
- **F1-6'nın bıraktığı develop-session node kapıları** (v2 zincir GPU'da MCRAW için bağlı; encoded'da CPU'da çalışıyor): K11 gereği
  node'lar encoded'da da açık. GPU encoded yolu açıldığında NODES pass'i aynı kapıdan geçer; kapı zaten pass-1'den bağımsız olduğundan
  ek mantık gerekmez, yalnızca **encoded + node zinciri** parity vakası eklenir.

## 4. Adımlar (her biri tek başına merge edilebilir)
- **E0b-2** YUV yükleme + shader + **headless parity**. Oracle: `developEncoded` (CPU). Eşik: `GradeParitySweep` ile aynı
  (`maxDiffLsb <= 1`, `mismatch*10000 <= samples`); **eşik gevşetilmez**. 8-bit ve P010 fikstürlerinin ikisi de kapsanır. Yalnızca test + yeni yol; export'a bağlanmaz.
- **E0b-3** export bağlama: `EncodedMp4Session` GPU yolu varsa onu kullanır, yoksa CPU'ya dürüst geri dönüş; hangi yol kullanıldı
  trace'te ve export raporunda yazar. Parity E0b-2'de geçmediyse bu adım yapılmaz.
- **E0b-4** preview bağlama (`EncodedPreviewSession`).
- **E0b-5** ölçüm: aynı 4K30 P010 fikstürüyle cihazda kare başına süre, önce/sonra tablosu (yönü açık: "gerçek zamandan N kat yavaş/hızlı").
- **10-bit encoded export:** yalnızca P010 parity'si geçerse, ayrı commit'te, `tenBitContext` kuralı güncellenerek; geçmezse kapalı ve sebebi raporda.

## 5. Riskler / bilinmeyenler
- Cihazda `ImageReader`'ın P010 desteği ve düzlem sırası (Image formatı 54, 2 düzlem; ölçüm koşucusunun P010 hatası bunun ispatı:
  `COLOR_FormatYUVP010` ile yapılandırılmayınca 8-bit Image geliyordu). E0b-2 ilk iş bunu cihazda loglar.
- Satır aralığı (stride) ve `GL_UNPACK_ROW_LENGTH` (GLES 3.0'da var) ile kopyasız yükleme mümkün; kopya sayısı ölçülür.
- Parity beklenmedik büyük çıkarsa (ör. 10-bit MSB ölçeği, birincil dönüşüm): bulgular raporlanır, eşik değişmez, export'a bağlanmaz.
