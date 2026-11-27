# F4-0 — Metin/altyazı veri katmanı planı (TextCue + SRT/WebVTT)

Durum: ÖNERİ + bu gece uygulanan SAF KATMAN (model + ayrıştırıcı + biçimlendirici).
UI, render, timeline entegrasyonu YOKTUR — bu doküman yalnız veri katmanını ve
 bağlanma YERLERİNİN önerisini tanımlar.

## 1. TrackKind.TEXT'in bugünkü boş durumu (kod kanıtı)

`TrackKind` enum'u (`app/src/main/java/com/ritim/ritimcraw/data/timeline/TimelineModel.kt:49`)
`{ VIDEO, AUDIO, TEXT }` üçünü de tanımlar ve her yeni proje otomatik bir TEXT
track'i açar (`TimelineModel.kt:160-162`: `t0..t2`, `entries[i % 3]`). Ama TEXT
"track"i bugün fiilen yalnız bir BAŞLIK KARTI taşıyıcısıdır; altyazı/seq kavramı
modelde yoktur:

- `TimelineClip.text` TEK düz String'dir (`TimelineModel.kt:141`: "Text content
  for TEXT clips (sourceId null); blank for media clips"). Bir klip = bir başlık
  bloğu; zaman içinde değişen satır dizisi (cue listesi) temsili YOK.
- Uzunluk çözücüsü TEXT klip sonlarını sayar (`TimelineOps.kt:408-410`) ama
  içerikle hiçbir şey yapmaz.
- OTIO export'u TEXT track'lerini atlar ve adedi
  `metadata.ritimcraw.skippedTextClips`'e yazar (`OtioExport.kt:60-64, 77`) —
  "içerik taşınmıyor" gerçeği bu sayaçla itiraf edilir.
- MP4 export'u yalnız VIDEO/AUDIO track'lerini süzer (`TimelineExport.kt:175-179`):
  TEXT hiçbir çıktıya girmez.
- Timeline UI'sinde TEXT başlığı video ikonunu/etiketini geri dönüşümlü kullanır
  (`ui/timeline/TimelineScreen.kt:1372`: `TrackKind.TEXT -> R.drawable.ic_track_video
  to R.string.timeline_track_video`) — kendi görsel kimliği bile yok.

Sonuç: TEXT'in bugünkü gerçek kullanımı "tek seferlik başlık metni". Altyazı
(birden çok zamanlanmış satır) için ne model, ne ayrıştırıcı, ne biçimlendirici var.

## 2. Bu gece uygulanan katman (tamamlanmış)

- `app/src/main/java/com/ritim/ritimcraw/data/text/TextCue.kt` — model
  (`TextCue(startUs, endUs, cleanText, rawText, rawSettings)`), `ParseResult(cues,
  issues)`, `ParseIssue(line, reason)`; hepsi saf Kotlin, µs tamsayı zamanlar.
- `app/src/main/java/com/ritim/ritimcraw/data/text/SrtCodec.kt` — SRT ayrıştırıcı
  + biçimlendirici (gidiş-dönüş garantili).
- `app/src/main/java/com/ritim/ritimcraw/data/text/WebVttCodec.kt` — WebVTT
  ayrıştırıcı + biçimlendirici.
- `app/src/test/java/com/ritim/ritimcraw/data/text/…` — her biçim ≥12 JVM testi.

Kararlar: (a) zaman birimi MİKRO-SANIYE tamsayı — ms→µs kayıpsız (×1000), frame
grid'e yuvarlama bu katmanda YAPILMAZ (yuvarlama, timeline'a bağlama anına aittir
— kayıpsızlık bu katmanın sözleşmesi). (b) etiketler için İKİ alan: `cleanText`
(etiketsiz, `<b>/<i>/<c.x>` vb. ayıklanmış, `\n` satır ayracı) + `rawText`
(orijinal satırler korunur); render yok ama bilgi kaybı da yok. (c) cue ayarları
(position/align/line/size vb.) HAM tek String olarak `rawSettings`'te saklanır,
yorumlanmaz. (d) "reported, never guessed": belirsiz/bozuk blok için cue
UYDURULMAZ; `issues`'e satır numarası + neden yazılır ve ayrıştırmaya devam edilir.

## 3. Track'e bağlanma yeri ÖNERİSİ (uygulama YOK — sahibin kararı)

Öneri: altyazı verisi TimelineClip'e GENİŞLETME olarak değil, klip içi PAYLOAD
olarak bağlanır:

1. **Depolama:** bir SRT/VTT dosyası projeye bir "text kaynağı" olarak girer;
   `TimelineClip.text` yerine yeni alan `TimelineClip.subtitleCues: List<TextCue>?`
   (null = bugünkü başlık davranışı BİT-AYNI kalır; project.json'a yalnız null
   olmayan kliplerde yazılır — TimelineStore'un omit-when-absent deseni).
2. **Timebase:** TextCue.startUs/endUs → proje kare gridine çevrim YALNIZ export
   ve preview düzleminde yapılır (`TimelineExport.kt:175-179` süzme noktası ve
   AudioPlan'ın kullandığı aynı Double frame-grid yaklaşımı). Model frame-grid
   kalır; µs→frame dönüşümü bu katmanın DIŞINDA, tek yerde.
3. **Export:** MP4'ye altyazı gömme YOKTUR (F4 ileride isterse); OTIO export'unda
   TEXT track'i bugün olduğu gibi atlanır ama `skippedTextClips` yanına cue
   sayısı da yazılabilir (öneri; davranış değişikliği gerektirir, ayrı iş).
4. **UI:** timeline'da TEXT track'i her cue'yu mini blok olarak gösterebilir;
   bu tamamen ayrı bir iştir (gece kapsamı dışı).

## 4. Kapsam dışı (açıkça)

Render, font/stil yorumlama (`rawSettings`/`rawText` bu katmanda ANLAMSIZDIR),
`EncodedColorSpec` bağlantısı, cihazda davranış, gerçek altyazı dosyalarıyla uyum
(elde örnek yok — sentetik girdilerle test edildi).

## 5. Kaynak dosyalar (referans noktaları)

- `app/src/main/java/com/ritim/ritimcraw/data/text/TextCue.kt`
- `app/src/main/java/com/ritim/ritimcraw/data/text/SrtCodec.kt`
- `app/src/main/java/com/ritim/ritimcraw/data/text/WebVttCodec.kt`
