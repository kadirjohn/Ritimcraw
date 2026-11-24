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
- WAV: kodda timeline sesi için PCM çıkarma var (`AudioRenders`, `TIMELINE_AUDIO_PLAN.md`); sürekli WAV dosyası olarak dışa
  yazan bir yol doğrulanmadı.

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
