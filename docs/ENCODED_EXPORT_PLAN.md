# E0 — Encoded export (Mi-Log / MP4) tasarım notu

Durum: E0 v1 (CPU decode+develop). Önceki durum: encoded klipler yalnız preview'da
açılıyordu; export kuyruğu onları MCRAW decoder'ına veriyordu (başarısız oluyordu).

## 1. Doğruluk düzeltmesi (ayrı `fix:` commit'i)

Mi-Log fikstürü (`VID_20261110_004247.mp4`, ffprobe + HEVC VUI ile doğrulandı):
HEVC Main10, P010, FULL range (`video_full_range_flag=1`), primaries 9 / matrix 9
(BT.2020 NCL), `transfer_characteristics=254`, colr nclx `trc 2` (unspecified).
Eski develop 8-bit / limited / BT.601-709 varsayıyordu.

- `EncodedColorSpec` (engine): MediaFormat `KEY_COLOR_STANDARD/RANGE/TRANSFER` + codec
  profili -> B1 `SourceProfile`. **Preview ve export aynı değeri kullanır.**
- Çekirdek: `YuvFrame.{bitDepth, fullRange, matrix, transfer, primaries}`; 10-bit =
  16-bit LE MSB-aligned (P010, kod = `word >> 6`); sıra = YCbCr -> RGB -> transfer çözme
  -> BT.2020 -> Rec.709 çalışma uzayı (lineer) -> grade. Varsayılanlar eski matematikle
  bit-bit aynıdır.
- Decoder 10-bit track'te `COLOR_FormatYUVP010` ister; gerçek derinlik Image'dan
  (pixelStride/format) okunur, container iddiasından değil.
- Android Mi-Log kodunu (254) tanımaz ("unspecified" bildirir): etiketsiz transfer =
  Mi-Log (uygulamanın encoded varsayılanı). HLG/PQ adlandırılır, Rec.709 tersiyle
  yaklaştırılır ve raporda `approx=` olarak yazılır (HDR decode ayrı iş).

## 2. Export yolu

```
EncodedSource (MediaExtractor + MediaCodec, P010)  ->  developEncoded (CPU, aynı grade)
   -> RGB888 (döndürülmemiş)  ->  ExportPipeline (E1: plan, döndür/kırp/ölçek, I420, writer)
   audio: MediaExtractorAudioSource (AAC -> PCM)  ->  writer'ın AAC encoder'ı
```

- `EncodedMp4Session : Mp4Session` — boru hattı MCRAW oturumuyla aynı arayüzü görür
  (`open/renderFrame/framePts/audioInfo/readAudio`); E1'in `ExportPlan`, `ExportSettings`,
  `MediaCodecMp4Writer`, staging, kuyruk, ilerleme, rapor kodu **değişmeden** kullanılır.
- Fd: kuyruğun fd'si job boyunca açık kalır; video reader ve ses extractor'ı kendi
  `dup`'unu alır (ve kapatır).
- Rotasyon: oturum kodlanmış yönelimi döndürmeden verir, boru hattı pikselleri döndürür
  (MCRAW ile aynı sözleşme).
- `Mp4Exporter.isEncoded(sourceId)` (AppNav kaynağın `kind`'ına bağlar): encoded için
  oturum `EncodedMp4Session`, backend her zaman CPU, gyro yolu null.
- 10-bit: v1'de GPU'ya bağlı olduğundan encoded'da sunulmaz; sheet'te seçenek
  kapalı, istek gelirse E1d planlayıcısı 8-bit'e indirir ve nedeni rapora yazar.

## 3. UI

Editör export sheet'i (E1) encoded klipte aynen çıkar: çözünürlük satırı, preset'ler,
Advanced, "MP4 dışa aktar". **DNG ve Lineer DNG satırları gizlenir** (RAW teslimatları),
10-bit kapalı. Stabilizasyon sekmesi encoded'da gizli kalır (v1 dışı, S6).

## 4. GPU yolu (P3 / F2): bu pakette YAPILMADI

"MediaCodec -> OES -> GPU" için gerekenler: (a) GLES session'ının YUV/OES girdisi alması
(şu an CFA girdisi), (b) P010 için `GL_EXT_YUV_target` / ayrı R16+RG16 doku yükleme ve
aynı shader'da range/matrix/transfer/gamut, (c) CPU oracle'a karşı parity (bu paketin
`developEncoded` + P010 testleri oracle'ı hazır). Ayrı paket (E0b / F2'nin ön koşulu);
bu paketin `EncodedColorSpec` değeri GPU shader uniform'larının tek kaynağıdır.
Gerekçe: GLES session ve ChainSlots başka kollarla çakışır; CPU yolu doğruluğu
kanıtlıyor ve export'u bugün mümkün kılıyor. Hız cihazda ölçüldü (rapor).

## 5. Kapsam dışı / açık
- Stabilizasyon (S6), HDR decode (HLG/PQ), 10-bit encoded export, GPU decode yolu,
  timeline exporter'da encoded klip, frame picker (PHOTO) encoded için.
