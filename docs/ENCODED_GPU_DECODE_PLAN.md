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
- Cihazda `ImageReader`'ın P010 desteği ve düzlem sırası (Image formatı 54, 2 düzlem) **doğrulanmadı**. İlgili ipucu: codec ölçümünün
  10-bit satırları 0 bayt verdi; en olası sebep (HİPOTEZ, cihazda henüz denenmedi) satırların P010 yerine 8-bit YUV420Flexible
  girdi istemesiydi ve düzeltme aynı gün yapıldı. E0b-2'nin ilk işi, P010 `ImageReader`/`getOutputImage` için Image formatı, düzlem
  sayısı ve stride'ı cihazda loglamak.
- Satır aralığı (stride) ve `GL_UNPACK_ROW_LENGTH` (GLES 3.0'da var) ile kopyasız yükleme mümkün; kopya sayısı ölçülür.
- Parity beklenmedik büyük çıkarsa (ör. 10-bit MSB ölçeği, birincil dönüşüm): bulgular raporlanır, eşik değişmez, export'a bağlanmaz.

## 6. REVİZYON 2 (2026-11-25, sahip yönergesi): iki yol, küçült-sonra-işle, proxy ayrı iş

Sahip, Klipp'in (`~/projects/klipp`) kaynağını inceledi; aşağıdaki iddialar Klipp kodunda DOĞRULANDI:
`engine/VideoPipeline.kt` `previewShort = 1080` ("playback downscales the footage to it before the nodes, film and look are computed
(≈ 4× less work per stage for 4K at 1080) ... Export ... always compute at full resolution from the originals");
`engine/Proxies.kt` (arka planda donanım encoder ile hafif, sık anahtar kareli kopya; oynatma/sarma proxy'den, export orijinalden;
varsayılan kısa kenar 1080; 10-bit kaynakta HEVC Main10, 8-bit'te H.264; "yeni proxy bitene kadar eskisi kullanılır").

**Alınmayacaklar:** Media3'ün tamamı (kendi GLES hattımız + CPU ile parity testlerimiz var; geçmek onları baştan yazmak olur);
`KlippPrecision` (Media3'ün iç texture havuzlarına RGBA16F yaması; biz Media3 kullanmıyoruz ve kendi develop zincirimiz zaten RGBA32F,
yani uygulanamaz). Lisans notu: Klipp bütünüyle GPL-3.0-only; yalnızca `KlippPrecision.java`'nın başlığı Apache-2.0. Klipp kodu birebir
kopyalanmaz, yalnızca mimari alınır; porta çıkan her şey için K6 gereği NOTICE girdisi.

**İki yol (sahip kararı):**
| | Preview (E0b-4) | Export + oracle (E0b-2/E0b-3) |
|---|---|---|
| Girdi | MediaCodec → Surface (SurfaceTexture, `GL_TEXTURE_EXTERNAL_OES`), CPU'ya kopya YOK | MediaCodec → `ImageReader` (YUV_420_888 / P010) → düzlemler GL dokusu |
| YUV→RGB | sürücü (hızlı; matris/range sürücüye bağlı) | bizim shader, `EncodedColorSpec` uniform'ları (tam kontrol) |
| İlk GPU adımı | **preview çözünürlüğüne küçült** (kısa kenar = kalite katmanı; 4K'da ~4× az iş) | tam çözünürlük (export orijinalden) |
| Doğruluk | E0b-4b: aynı karede OES preview ile düzlem-shader farkı ölçülüp raporlanır | CPU oracle `developEncoded` ile parity (eşik `GradeParitySweep` ile aynı) |

**Adımlar (güncel sıra):** E0b-2 (düzlem yükleme + shader + headless parity; önce cihazda ImageReader/P010 düzen probu) → E0b-3 (export bağlama)
→ E0b-4 (preview: OES + önce-küçült) → E0b-4b (OES vs düzlem renk farkı raporu) → E0b-5 (ölçüm) → **ayrı iş F2:** Klipp tarzı proxy
(8K ve uzun GOP için arka planda 1080p, 1 sn anahtar kareli HEVC Main10 / 8-bit'te H.264; oynatma ve sarma proxy'den, export orijinalden;
**yarım proxy asla oynatılmaz**: Klipp'teki yeşil glitch dersi, proxy yalnızca tamamlanıp doğrulandıktan sonra kullanılır).

**Kabul testleri (sahip klipleri, cihaz):** (1) Mi-Log 4K 10-bit 60p (`VID_20261124_175141.mp4`) akıcı oynasın (bugün ~0,5 kare/s);
(2) 8K 30p 8-bit (`VID_20261124_175209_8K.mp4`) BALANCED'da akıcı oynasın (bugün: hiç kare yok, tam boy `IntArray` OOM);
(3) renk etiketi doğru okunsun: Mi-Log klip ham `standard=6 range=1 transfer=0 profile=2` → BT.2020 / tam range / 10-bit / transfer etiketsiz
(Mi-Log varsayımı klibin gerçek eğrisiyle DOĞRULANMADI: HLG mi Mi-Log mu, E0b'nin renk testinde kapatılacak); (4) CPU oracle ile parity.
Bugünkü CPU yolu ölçümleri (debug izleri) `STATUS-2026-11-25.md` GÜNCELLEME 3'te.

**Bilinen kusur (E0b'den bağımsız, ayrıca düzeltilecek):** `nativeEncodedDevelop` preview katmanını yok sayıp tam çözünürlükte geliştirip
tam boy `IntArray` döndürüyor (8K = 132,7 MB, JVM 256 MB sınırında OOM) ve hata ham metin olarak gösteriliyor.
