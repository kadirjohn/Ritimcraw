# R1 — CinemaDNG dizisi export (plan, kod yok)

Durum: öneri, 2026-11-25. P4'ten sonra ya da sırada bekler. Bu belge yalnızca plandır; hiçbir şey yazılmadı.

## Hedef
Bir klibin tamamını (ya da seçili aralığı) şu üçlüyle dışa aktarmak: **DNG kare dizisi + WAV + timecode**. Çıktı MotionCam'in kendi
CinemaDNG çıktısıyla uyumlu olmalı ve **DaVinci Resolve'da açılabilmeli**. Bugün `DngExporter` yalnızca tek kare yazıyor.

## Bugün olanlar (kodda doğrulandı)
- `core/src/DngWriter.cpp`: tek kare, tek IFD, sıkıştırmasız 16-bit CFA (ve Linear DNG yazıcısı). Yazılan etiketler arasında
  CFA/BlackLevel/WhiteLevel/ColorMatrix1/ActiveArea var. **TimeCodes / FrameRate / CinemaDNG'ye özgü etiket yazılmıyor**
  (dosyada `TimeCode`, `FrameRate` araması boş; yalnızca `ctime` include'u var).
- `engine/DngSequence.kt` (843 satır) DNG dizisini **kaynak olarak okur** (`docs/DNG_SOURCE_PLAN.md`); yazan taraf yok.
- WAV: **yazan bir yol yok.** `grep` ile `.wav`/`RIFF` yalnızca `engine/DngSequence.kt`'de (CinemaDNG kaynağının sesini okurken)
  çıkıyor; `export/` altında ses/WAV dosyası yok. Oynatma tarafında `engine/EditorAudio.kt` ve MCRAW'ın tüm-klip PCM okuması
  (ClipEditorScreen yorumunda anılıyor) var; WAV dışa yazımı bunlardan türetilir ama kodda henüz yok.

## Boyut ve süre tahmini (depolama uyarısı bu rakamlardan gelir)
Varsayım (doğrulanacak): kare başına dosya ≈ genişlik × yükseklik × 2 bayt (16-bit sıkıştırmasız). Fikstür kaynağı 3840×2160
(`ritim-trace` satırlarında `source=3840x2160`).

| Süre @ 30 fps | Kare | Kare başı | Toplam |
|---|---|---|---|
| 4 s  | 120   | ≈ 15,8 MiB | ≈ 1,9 GiB |
| 58 s | 1 740 | ≈ 15,8 MiB | ≈ 26,9 GiB |
| 5 dk | 9 000 | ≈ 15,8 MiB | ≈ 139 GiB |

Süre **ölçülmedi**: tek kare DNG export süresi cihazda hiç ölçülmedi. İlk iş bir kare/s ölçümü (debug düğmesi, `ritim-trace`
benzeri tek satır) ve tahminin buna bağlanması; o zamana kadar arayüz "süre bilinmiyor" der (ExportSheet'in mevcut kuralı).

**Depolama uyarısı kuralı:** başlamadan önce `gerekli = kare × kare_boyutu × 1,05` ile hedefteki boş alan karşılaştırılır;
boş alan < gerekli ise Export kilitlenir (ERROR bandı, "Düzelt" = aralığı kısalt); boş alan < gerekli × 1,5 ise uyarı.
Devam eden export'ta alan biterse: yazılan kareler korunur, iş "kısmi" olarak biter, sessizce silinmez.

## Parçalar (her biri ayrı merge edilebilir)
1. **R1-0 Ölçüm:** tek kare DNG yazma süresi (cihaz), kare boyutu doğrulaması. Plan rakamları buradan düzeltilir.
2. **R1-1 Çıktı sözleşmesi:** MotionCam'in CinemaDNG çıktısının gerçek bir örneği alınır (klasör düzeni, dosya adları, WAV adı,
   hangi DNG etiketleri) ve `docs/` altına karşılaştırma tablosu yazılır. **Örnek dosya olmadan sözleşme tahmin edilmez.**
3. **R1-2 Dizi yazıcı:** kare döngüsü + iş kuyruğu (mevcut `ExportEngine` desenine oturur), aralık seçimi, iptal, kısmi sonuç.
4. **R1-3 Timecode:** DNG'ye `TimeCodes` (51043) ve `FrameRate` (51044) etiketleri; başlangıç timecode'u kaynağı karar bekliyor
   (aşağıda). Çekim anı / klip başı 00:00:00:00 / kullanıcı girişi.
5. **R1-4 WAV:** kaynak sesin aralığa kesilmiş PCM WAV'ı (örnek hızı/kanal sayısı klipten, yeniden örnekleme yok).
6. **R1-5 Resolve doğrulaması:** gerçek bir Resolve kurulumunda açılma testi; dizi tek klip olarak geliyor mu, timecode ve ses
   hizası doğru mu. **Bu adımı yalnızca sahip yapabilir.**

## Parity / doğruluk
DNG'ye geliştirme uygulanmaz (ham CFA yazılır, bugünkü tek kare davranışı); yani GPU parity kuralı bu paket için geçerli değil.
Doğruluk ölçütü: yazılan kare, kaynak kareyle bayt bayt aynı CFA verisi (JVM/desktop testi: `test_dng_writer` benzeri bir
dizi testi). Linear DNG dizisi kapsam dışı (ayrı karar).

## Açık kararlar (sahip)
- Başlangıç timecode'u nereden gelsin (çekim saati / 00:00:00:00 / girilen)?
- Çıktı klasörü: `Documents/` altında ayrı klasör mü, SAF ile seçilen klasör mü? (Depolama kontrolü hedefe göre değişir.)
- Sıkıştırma: ilk sürüm sıkıştırmasız mı (önerilen, Resolve uyumu en güvenli), yoksa kayıpsız sıkıştırma da mı?
- Seçili aralık dışında bütün klip varsayılanı yeterli mi?

## Uygulama durumu (gece E-D3 + E-F, 2026-11-27)

**D3 (çekirdek, dal `feat-cinemadng-sequence-core`, denetçi ACCEPT):**
- `DngWriter`'a opsiyonel TimeCodes (51043) + FrameRate (51044) etiketleri;
  seçenek yoksa çıktı bayt-birebir aynı (test kanıtlı).
- `DngSequenceWriter` (iptalde yazılanlar korunur), `WavWriter` (PCM 16-bit),
  `CinemaDngPlan` (boyut tahmini ×1,05; kilit < gerekli, uyarı < gerekli×1,5).

**F (uçtan uca, dal `feat-cinemadng-export`, D3'ün üstünde):**
- **JNI:** `nativeCinemaDngExportFrame` — kaynak fd + frameIndex + meta (tam fps
  rasyonel, başlangıç timecode, kare ofseti) + SAF YAZMA fd'si; bayt dizisi
  JNI sınırını geçmez. `nativeAudioInfoForFd` / `nativeReadAudioForFd` —
  tek-atımlık ses kapıları (oturum açan MP4 yoluyla aynı desen). Mevcut
  `nativeDngExport` imzaları DEĞİŞMEDİ.
- **Çekirdek fd kolu:** `writeCfaDngToFd` — yol koluyla AYNI baytlar (testle
  kanıtlanmış bayt-birebir eşitlik; tag seti: BlackLevel, WhiteLevel,
  CFAPattern, ColorMatrix1, ActiveArea, TimeCodes, FrameRate; CFA verisi
  kaynakla bayt-bayt aynı).
- **Kotlin:** `CinemaDngExporter` (kare döngüsü, ilerleme, kooperatif iptal
  → yazılan kareler klasörde kalır, kısmi sonuç raporlanır; hata yolları
  dürüst), `CinemaDngWav` (44-bayt başlık + PCM verbatim), `CinemaDngNaming`
  (`<önek>_<kare:06>.dng`, parametre), `CinemaDngFolder` (SAF adapter).
- **Ayarlar:** AYRI `CinemaDngSettings` + kendi wire'ı (`"cinemaDng":{"v":1}`) —
  ExportSettings wire zincirine BİLİNÇLİ dokunulmadı (koordinatör merge
  çakışması sıfır). Bilinmeyen sürüm → iş FAILED, sessiz düşüş yok.
- **Arayüz:** export sayfasında `CinemaDNG` sekmesi (yalnız MCRAW kaynak);
  klasör seç (SAF ACTION_OPEN_DOCUMENT_TREE + kalıcı izin), tahmini boyut,
  "süre bilinmez" satırı, ham veri notu, Exports satırında kendi format adı.
- **Kuyruk:** `cinemaDng` işaretli iş sıra üzerinden gider; iptal DETAYI
  (yazılan kare sayısı) korunur — mevcut kuyruğun "detay temizle" kuralının
  bilinçli istisnası (kısmi sonuç dürüst raporlanır).

**Parametreler (sahip değiştirebilir):**
- Dosya adı deseni: `<önek>_<kare:06>.dng` (önek parametre, varsayılan "clip").
- Başlangıç timecode: 00:00:00:00 (parametre — çekim saati bağlanmadı).
- Kare hızı: kaynağın tam rasyoneli (29.97 → 30000/1001).
- Sıkıştırma: yok (sıkıştırmasız DNG).
- Aralık: bütün klip (ilk/son kare parametre).

**Bilinen sınırlar:**
- R1-1 (MotionCam'in gerçek CinemaDNG çıktısıyla dosya adı/klasör karşılaştırması)
  YAPILMADI — gerçek örnek gelince doğrulanacak.
- Süre tahmini YOK (R1-0 kare başı yazım ölçümü cihaz gerektirir).
- Depolama boş alan sorgusu yalnız birincil birimlerde ölçülebilir
  (SAF sağlayıcı taşınabilir boş alan API'si sunmaz) — ölçülemeyen durumda
  kilit YOK, dürüst ara-yazım hatası devreye girer.
- Sadece MCRAW kaynak; timeline/grade/geo/NR uygulanmaz (bilinçli).
