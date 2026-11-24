# E1f — Kare hızı dönüşümü (opsiyonel frame drop/duplicate) tasarım planı

Durum: **plan, kod yok** (2026-11-24, gece görevi, dal `e1f-frame-rate-plan`).
Taban: `main` @ `fd749d0` (E1 merge `a5577b1` dahil). Dil: Türkçe; kod
tanımlayıcıları İngilizce. E1f koduna başlamak sahip onayı gerektirir (§8).

Kanıt sözlüğü (COLOR_MODEL_V2_PLAN.md ile aynı):
- **doğrulandı**: bu worktree'de koddan okundu (dosya:satır verilir).
- **plandan alıntı**: görev brief'i ve sahibin E1/E1c karar notları
  (§7.3/§10); bu pakette yeniden ölçülmedi. `.planning/KLIPP-FILMCRAFT-PLAN.md`
  bu worktree'de **yok** (doğrulandı: `find`) — o dosyaya atıf yapan maddeler
  "plandan alıntı" seviyesindedir.
- **doğrulanmadı**: okunmadı/ölçülmedi; cihaz davranışı. Sayı uydurulmaz;
  boyutlar S/M/L'dir.

---

## 0. Özet

1. **E1 bugün fps SEÇTİRMEZ** ve bunu bilinçli yapar: tek klipte plan ölçülen
   kaynak fps'yi alır (`ExportFps.fromPts`, `ExportPlanRun.kt:32-37`),
   `KEY_FRAME_RATE`'e gerçek değer yazılır (`ExportFormat.kt:56`), sheet'te satır
   salt-okunurdur (`ExportSettingsPanel.kt:413`). `ExportSettings.kt:9-12`
   bunu "sonraki paket" olarak vaat eder — E1f o pakettir (doğrulandı).
2. **Ölçülen fps gerçek ve standart değil:** sahibin kendi cihazında
   ffprobe ile ölçtüğü ihrâç dosyası gerçekte **29.83 fps, ~CFR** (medyan kare
   aralığı 33.52 ms, tüm klipte tek 67 ms boşluk — plandan alıntı, E1c raporu).
   Standart değere yuvarlama **yasak** kararı verildi; ekran "29.83 fps (kaynak)"
   gösterir. E1f bu gerçeğin üstüne *isteğe bağlı* dönüşüm olarak kurulur:
   `SOURCE` (varsayılan) bugünkü davranışın ta kendisidir, hiçbir şey değişmez.
3. **Öneri (a): frame-nearest atma/çoğaltma.** Çıktı karesi j, hedef grid
   zamanında kaynak karesine en yakın olanı gösterir; video PTS hedef fps'in
   tam grid'ine yazılır; **SES'E DOKUNULMAZ, ses pts aynen korunur**. Kural
   timeline'ın `TimelineExport.kt:221-223` eşlemesiyle birebir aynı cebirsel
   düzendedir (§4) — iki yolun ayrışması engellenir.
4. **Ses senkronu analizi net:** CFR video pts + korunmuş ses pts **aynı
   duvar saati ekseninde** (ortak t=0 orijini) yazıldığı sürece kümülatif
   kayma **sıfırdır**; kalan sadece kare-içi ±yarım-kare seğirmedir. Kayma
   YALNIZCA içeriği standart hıza *çarpan* (kare-indeks → grid) yazım tutumunda
   doğar: 29.83→30 çarpması 79 sn'lik klipte sonda ≈ **0.45 sn** audio/video
   kayması üretir (plandan alıntı: E1c raporu tuzak senaryosu). İlk turda
   sahibe önerilen bu yaklaşım hatalıydı; §2.4 hatayı düzeltir ve kaydı
   dokümante eder: **dönüşüm yalnız video eşlemesidir, PTS duvar saati
   korunmalıdır.**
5. **Model:** `ExportSettings.targetFps: TargetFps = SOURCE` + hedef merdiveni
   24/25/29.97/30/50/59.94/60 (29.97 = kesin 30000/1001 rasyoneli, §3.3);
   wire **v2** (v1 okuyucusu sessizce SOURCE'a düşmez — mevcut
   "honest-about-the-device" kuralı, `ExportSettings.kt:147-150`); plan
   girdi fps'si **hedef** olur (bitrate tablosu ve encoder yetenek sorguları
   hedef fps ile, §3.4).
6. **Writer değişmez:** pts'ler zaten pipeline'dan gelir
   (`writeVideoFrame(ptsUs,…)`), `KEY_FRAME_RATE` plandan yazılır
   (`ExportFormat.kt:56`); `MediaCodecMp4Writer.kt` E1f-2'de **sıfır satır**
   değişir (§5). Değişiklik `ExportPipeline`'ın video örnekleme üretimindedir.
7. **Testler tamamen JVM'de:** saf eşleme nesnesi `PtsTimeline` deseni
   (saf fonksiyon + tablo testleri); vaka listesi §6 (23.976→30, 30→24,
   29.83→30 kayıplı, tek kare, sonda-kesir, rasyonel grid monotonluğu,
   timeline paritesi).
8. **e1c-polish bağımlılığı açık:** `PtsTimeline.measuredFps` ve kompakt sheet
   (`bde227d`, `7b29a29`) main'de **değil**; E1f bunlar merge edilince
   çatışmasız oturur (§1.4). TEK çelişki: bde227d'nin %1 displayFps
   yuvarlaması sahibin sonraki "yuvarlama yasak" kararıyla çakışır — K6.

**Tek öneri:** Seçenek **(a)** — frame-nearest atma/çoğaltma, video hedef-grid
CFR, ses pts ve PCM aynen, plan/wire v2, üç alt-paket (E1f-1 saf eşleme+plan,
E1f-2 pipeline+parite, E1f-3 UI).

---

## 1. Mevcut durum (doğrulandı, aksi belirtilmedikçe)

### 1.1 E1 sonrası fps yolculuğu

`ExportJob.settings` (E1) kare hızını **içermez**; `ExportSettings.kt:9-12`
gerekçeyi yazar: "frame rate: single-clip exports keep the source's own pts…
a picker plus frame drop/duplicate is a later package". Akış:

- Kaynak pts'ler native'den okunur: `framePts(handle, i)` → `Mp4Exporter.kt:1354-1357`
  (`FrameInfo.ptsUs`, `core/include/rawedit/Source.h:42`).
- `PtsTimeline.videoPts` ilk kareyi 0'a normalize eder, monotonluk korusu
  koyar (`export/PtsTimeline.kt:39-53`); **bugün her kare KENDİ normalize
  kaynak pts'iyle** kodlanır (VFR geçirilir; gerçek boşluklar dosyada yaşar).
- Ses blokları örnek indeksinden gelir: `audioBlockPts(k) = k*1024/sampleRate`
  (`export/PtsTimeline.kt:61-62`, çağrı `Mp4Exporter.kt:1385-1387`); video ve
  ses **aynı t=0 orijinine** bağlıdır (`interleave`, `Mp4Exporter.kt:1786`).
- Plan fps'si ölçümden: `ExportFps.fromPts` = (N-1) aralık ölçümü + `snap`
  (`ExportPlanRun.kt:32-45`); `planFor(…, fps)`'e girer (`Mp4Exporter.kt:1627-1630`),
  `KEY_FRAME_RATE = frameRateValue(plan.fps)` yazılır (`ExportFormat.kt:56`,
  Int/Float seçimi `:78-82`). `ExportPlan.fps` alanının KDoc'u "the real fps"
  der (`ExportPlan.kt:102-103`); legacy plan (E1 öncesi job) için
  `fps = 30.0` sabitidir (`ExportPlan.kt:156` + `BitrateTable.legacyBitsPerSecond`).
- Bitrate tablosu fps'i piksel-hızı çarpanı olarak kullanır
  (`BitrateTable.kt:20-25`); encoder yetenek sorgusu `input.fps` ile
  (`EncoderFacts.sizeSupport`, `ExportPlan.kt:200-204`) ve
  `RATE_UNLISTED` bilgisi hedef aralık dışında bilgi notu düşer
  (`ExportPlan.kt:366-369`).
- Süre raporu `run.durationSec = sonPts/1e6 + 1/fps` şeklinde **son karenin
  görüntülenme süresini de sayar** (`Mp4Exporter.kt:1631`) — §4'ün kare-sayısı
  kuralı bununla uyumlu olmalıdır.
- Timeline tarafı proje fps'ini kullanır: `TimelineMp4Exporter`
  `planRun.planFor(plan.width, plan.height, plan.fps)` (`:263-271`), pts
  `pts(done, plan.fps) = i*1e6/fps` grid'indedir (`:431`); eşleme
  `sourceFrame(j) = round((start + j) * sourceFps / projectFps)`
  (`TimelineExport.kt:221-223`) — E1f buna paralel tek-klip versiyonudur.

### 1.2 Gerçek 29.83 fps vakası ve VFR/CFR gerçeği

| Ölçüm (plandan alıntı — E1c raporu, sahibin ffprobe'u) | Değer |
|---|---|
| Dosya | telefon içi ihrâç `261112_220209_VIDEO_99mm.mp4` |
| Ölçülen fps | **29.83** (standart değil; 29.97'den %0.47 uzak) |
| Karakter | ~CFR (medyan kare aralığı **33.52 ms**) |
| Boşluk sayısı | tüm klipte **1** adet **67 ms** (≈ 2 kare süresi) |
| Ekran kararı | standart değere yuvarlama **yasak**; "29.83 fps (kaynak)" |
| `KEY_FRAME_RATE` kararı | gerçek ölçülen değer (E1 ile geldi) |

Yani "kaynak" pratikte CFR'e çok yakın ama kusursuz değil: tek boşluk real
pts'lerde yaşar, `SOURCE` modunda (bugünkü davranış) bunlar **olduğu gibi**
dosyaya yazılır — mevcut ihrâç yolu zaten VFR taşımaktadır. Kodun kendi
ölçüm zinciri de bu gerçeğe paralel: metadata katmanı fps'i (N-1)/span
üzerinden ESTIMATED yazar (`MetadataFacts.kt:197-215`); ihrâç tarafı
`ExportFps.fromPts` aynı cebiri kullanır (`ExportPlanRun.kt:35-36`).
**Editör görüntü satırı ise main'de hâlâ N/span okur** (ClipEditorScreen.kt:3182,
(N-1)/N kadar düşük) — e1c-polish `7b29a29` tam bunu düzeltir (§1.4).

### 1.3 E1'in tasarımdaki sabitleri (E1f bunları BOZMAZ)

- `SOURCE` varsayılan = bugünkü davranışın ** taşınması**: kaynak pts aynen,
  ses aynen, `KEY_FRAME_RATE` ölçülen değer. E1f hiçbir ayar seçilmedikçe
  byte-davranış değişikliği getirmez.
- Timeline ihrâçı E1f kapsamı dışındadır: proje fps'i zaten kullanıcının
  seçimi (`TimelineOutput.fps`), `targetFps` tek-klip ayarı olarak kalır (K8).

### 1.4 e1c-polish bağımlılığı (main'de DEĞİL — dal'a dokunulmaz)

`git show bde227d 7b29a29` incelendi (doğrulandı):

- **7b29a29** `engine/PtsTimeline.kt`'ye `measuredFps(ptsUs) = (N-1)/span`
  ekler (Float) + test; ayrıca editör görüntü değerini düzeltir
  (`ClipEditorScreen.kt` fps satırı). E1f-1'in eşleme nesnesi bu ölçümü
  **var sayar**: kaynak fps'i `(N-1)/span` ile ölçülmemiş hiçbir yerden almaz.
- **bde227d** kompakt sheet'i getirir (`ExportSheetState.kt`, hedef sekmeleri,
  özet kartı). **`displayFps`: %1 toleransla en yakın standart değere yuvarlar**
  (29.826 → 29.97). Bu, sahibin 29.83 vakasından **sonra** verdiği
  "standart değere YUVARLAMAK YASAK, ekranda '29.83 fps (kaynak)'" kararıyla
  **çelişir** (K6): E1f-3, fps satırında ölçülen değeri ham gösterir ve
  bde227d'nin `displayFps` snap'i bu satır için **kullanılmaz/kaldırılır**.

E1f kodu hiçbir dosyada e1c-polish ile çakışmaz: E1f-1 yalnız `export/`
tarafına ve `ExportSettings`/wire'a, E1f-3 yalnız `ExportSettingsPanel`/strings
tarafına dokunur; `engine/PtsTimeline.kt`'ye yalnızca **okuma** bağımlılığı
vardır. Doküman, E1c merge edilince çakışma olmadığını varsayar (görev
brief'i gereği); `e1c-polish` dalına bu iş kapsamında dokunulmaz.

---

## 2. Dönüşüm seçenekleri ve kıyas

### 2.1 (a) Frame-nearest atma/çoğaltma + hedef-grid CFR video, ses wire — ÖNERİLEN

- Çıktı karesi j, hedef grid zamanı `t_j = j/targetFps`'te gösterilecek
  **en yakın kaynak karesini** alır (formül §4). Hedef < kaynak ⇒ atma;
  hedef > kaynak ⇒ çoğaltma; eşit ⇒ özdeşlik (uygulamada bile). Gerçek zaman
  **tam korunur**: her iki grid de aynı duvar saati ekseninde ölçeklenir,
  yuvarlama dışında hiçbir yeniden örnekleme yoktur — `TimelineExport`'un
  v1 kuralının aynısı ("Real time is preserved… only rounding, never
  resampling", `TimelineExport.kt:27-30`).
- Video PTS **hedef fps'in tam grid'ine** yazılır (CFR konteyner);
  **audio pts (`audioBlockPts`) ve PCM byte'ları hiç değişmez.**
- Uygulama yüzeyi: `ExportPipeline` video örnekleme listesi değişir
  (`videoPts` 1:1 kaynak yerine eşleşmiş `(pts_j, kaynakIndex_j)` listesi);
  çoğaltmada son decode edilmiş kare yeniden kullanılır (timeline emsali
  `TimelineMp4Exporter.kt:315-322` `lastFrame/lastRgb` örüntüsü — ses
  örnekleri araya girse de önbellek yerel kalır).
- Bedel: video'ya kare-içi ± yarım kaynak-periyodu seğirme; ses sıfır etki.

### 2.2 (b) Gerçek hız değişimi (slow-motion/speed change) — E1f DIŞI

İçerik süresini değiştirir: sesin de yeniden örnekleme (resample) + pitch/zaman
çekimi gerekir. Kodda ses zinciri tamamen örnek-hızı üzerinden kuruludur
(`audioBlockPts` = örnek indeksince duvar saati; `ExportSettings.kt:14-16`
zaten "audio stays 'source' until the resampler of package E5" der) — E5
bağımlılığıdır. E1f dokunmaz; kapsam notu olarak yazılır.

### 2.3 (c) VFR taşıma — REDDEDİLİR

Gerekçe: **`SOURCE` modu zaten VFR taşımanın ta kendisidir** — gerçek pts'ler
(tek 67 ms boşluk dahil) bugün dosyaya yazılıyor (§1.2). Ayrı bir "VFR'e çevir"
seçeneği çözülecek bir sorun eklemez; hedef fps seçimi problematik (merdiven
"hangi hız?" sorusuna cevap vermez); CFR isteyen oynatıcı/editör akışları için
de bir getiri yoktur. (Oynatıcıların VFR MP4 hoşgörüsü: doğrulanmadı — §9
online sorusu.)

### 2.4 Ses senkronu — hangi yazım tutumu kayma yaratır (hatanın düzeltilmesi)

**Doğru tutum (a):** video pts grid'i `t_j = j/targetFps`, ses pts'leri
`k*1024/sampleRate`. İkisi de **aynı t=0 orijinli duvar saatidir**; j'nin
içeriği `t_j` anındaki en yakın kaynak karesidir — dolayısıyla her çıktı
karesi, duvar saatinde doğru anda doğru kareyi gösterir. Kümülatif kayma
**0**; kalan, kare başına en fazla yarım kaynak-periyot iç seğirmedir ve
birikmez (her kare bağımsız eşlenir). Tek 67 ms boşluklu gerçek klip için de
geçerli: eşleme gerçeği span üzerinden tanımlıdır (§4.3), boşluk bölgesinde
tek kaynak karesi ~2 çıktı karesi boyunca tutulur — görsel olarak donuk bir
kare, kayma değil.

**Yanlış tutum (ilk turda önerilmişti):** "29.83'ü 30'a ÇARP" — çıktı karesi
j'ye kaynak karesi **j'yi indeksle** ver ve pts'leri 30-grid'ine yaz. Bu,
içeriği duvar saatinden "%0.57 hızlandırır": video (N-1)/29.83 sn'de biter,
ses (N-1)/29.83 sn'de. Kayma lineer büyür: **kayma(sn) = süre × (1 − fpsSource/fpsTarget)**;
29.83→30 için 79 sn'lik klipte sonda ≈ **0.45 sn** (0.00567 × 79 — plandan
alıntı, E1c raporu tuzak senaryosu). Ses hiç dokunulmadığı halde video ondan
kaçar — "sonda 0.45 sn kayma" tuzak senaryosu tam olarak budur. Doküman bu
hatayı kaydeder: **dönüşüm
YALNIZ video eşlemesidir; PTS'lere duvar saati ölçeğinde dokunmak yasaktır,
ses pts'i mutlaka korunur.**

Özet tablo:

| Seçenek | Gerçek süre | Ses senkronu | Ses dokunma | Uygulama yüzeyi | Karar |
|---|---|---|---|---|---|
| (a) frame-nearest + CFR grid | korunur | kümülatif 0 | **yok** | pipeline örnekleme + saf eşleme | **öneri** |
| (b) gerçek hız | değişir | resample gerekir | resample (E5) | E5+ | E1f dışı |
| (c) VFR taşıma | korunur | zaten VFR | yok | "SOURCE" bugünkü hali | ret |

---

## 3. Model değişiklikleri

### 3.1 `ExportSettings.targetFps` (E1f-1)

```kotlin
enum class TargetFps(val label: String, val num: Int, val den: Int) {
    SOURCE("kaynak", 0, 0),          // ayar yok: bugünkü davranış
    FPS_24 ("24", 24, 1),
    FPS_25 ("25", 25, 1),
    FPS_29_97("29.97", 30000, 1001),  // kesin NTSC rasyoneli
    FPS_30 ("30", 30, 1),
    FPS_50 ("50", 50, 1),
    FPS_59_94("59.94", 60000, 1001),
    FPS_60 ("60", 60, 1),
}
data class ExportSettings(…, val targetFps: TargetFps = TargetFps.SOURCE)
```

- `SOURCE` = ölçülen gerçek hız (29.83 dahil, yuvarlanmadan). Varsayılan;
  preset'ler bu alana **dokunmaz** (`ExportPreset.apply` copy listesi sabit
  kalır; test: QUALITY sonrası targetFps korunur).
- 23.976 (24000/1001) hedef merdiveninde **yok** (brief listesi); sinema-NTSC
  ihtiyacı için eklenmesi K2'de sahip onayına açık bırakılır.

### 3.2 Wire — v2 (sessiz SOURCE'a düşüş yasak)

`ExportSettingsWire.VERSION: 1 → 2`; yeni anahtar `"targetFps":"<NAME>"`,
**her yeni job'da yazılır**. v1 satırlar (E1 job'ları) okuyucuda
`Unsupported("settings version 1…")` olarak dürüstçe başarısız olur —
mevcut kuralın aynısı ("a row whose settings a newer app wrote and this one
cannot read is NOT run with the legacy behavior", `ExportJob.kt:167-170`;
kelime anlamı `ExportSettings.kt:146-150`). Alternatif (yazma-taşıma):
`SOURCE` iken alanı atlayıp v1 satırlarını bayt-özde tutmak — tek kullanıcılı
uygulamada basitlik dürüstlüğe tercih edilmez; öneri v2-her-zaman (K5).

### 3.3 Rasyonel fps matematiği (§4'ün aritmetiği)

- NTSC hedefler **kesin rasyoneldir**: 29.97 ≠ 29.97 ondalığı; gerçek hız
  30000/1001 = 29.97002997… fps'tir. Grid periyodu `1e6*1001/30000 = 33366.67 us`;
  pts üretimi `pts_j = round(j * 1e6 * num / (den * … ))` — tek adımda, ara
  tamsayı yuvarlaması olmadan (bkz. §4.5).
- `MediaFormat`'a giden `KEY_FRAME_RATE` yalnız Int/Float alınabilir
  (`ExportFormat.frameRateValue`, `ExportFormat.kt:78-82`): 30000/1001 →
  Float 29.97003. Etiket hatası %0.0001 — oynatıcı için önemsiz, ama PTS
  grid'i **her zaman** kesin rasyonelden hesaplanır; etiket asla grid'in
  kaynağı değildir.

### 3.4 Plan girdisi ve bitrate tablosu hangi fps'i kullanır

- `ExportInput.fps` **çıktı fps'i** olur: dönüşüm seçildiyse **hedef**
  (num/den), değilse ölçülen kaynak. Gerekçe: bitrate tablosu kodlanan
  akışın piksel hızını modeller (`BitrateTable.kt:20-25`), yetenek sorguları
  kodlayıcının üreteceği hız için sorulur (`EncoderFacts.sizeSupport(w,h,fps)`,
  `ExportPlan.kt:200-204`). `ExportInput`/`ExportPlan.fps` KDoc'ları E1f-1'de
  güncellenir ("the OUTPUT fps: the source's, or the chosen target").
- `ExportPlan` yeni `fpsConverting: Boolean` (veya `sourceFps: Double?`)
  taşır — writer'ın KeyFrameInterval'i saniye birimli kalır (§5), bu yüzden
  planda yeni hesap yok; alan yalnız rapor/test okunabilirliği içindir.
- Yeni `ExportIssue` gerekmez: `RATE_UNLISTED` (hedef, kodlayıcının aralık
  listesinin dışındaysa) ve `SIZE_UNSUPPORTED` mekanizmaları hedef fps ile
  zaten doğru çalışır (sorgular input.fps kullanır). Görünür uyarı satırı
  (drop/duplicate oranı) UI katmanına aittir (E1f-3).

### 3.5 Issue/fix örnekleri (E1f-1'de test edilir)

- 60 hedef, 4K boyut, kodlayıcı `areSizeAndRateSupported(3840,2160,60)=NO`
  → mevcut `SIZE_UNSUPPORTED` ERROR + `fixResolution`; fix ayarı değil,
  çözünürlük fikri mevcut mekanizmadan gelir (`ExportPlanner.resolve` adım 3,
  `ExportPlan.kt:303-315`).
- Hedef 29.97, kodlayıcı `supportedFps = 24.0..30.0` → `RATE_UNLISTED`
  INFO (arama yarı-açık; indeks tabanlı kontrol `ExportPlan.kt:366-369`
  mevcut haliyle çalışır — 29.97 ∉ [24,30] notu düşer, kare aritmetiğinde
  sorun yoktur; bilgi notudur).
- `targetFps != SOURCE` + ses yok → ses yok; `A/V senkronu` maddesi
  rapor adımlarına log cümlesi olarak eklenir ("fps-conv: 29.83 -> 30,
  drops=0 duplicates=~%0.57" — örnekleme istatistiği plan adımı olarak).

---

## 4. Eşleme formülü (saf fonksiyon — E1f-1 çekirdeği)

Yeni saf nesne: `export/FrameRateMap.kt` (önerilen isim; `PtsTimeline`
deseninde — bağımsızlık, JVM test edilebilirlik, Android import'u yok).

### 4.1 Ana fonksiyon

```
i(j) = round( (startUs + j * 1e6 * den / (num*1)) * sourceFps / 1e6 )
     = round( (startUs + t_j_us) * sourceFps / 1e6 )     // t_j_us = j hedef grid süresi (us, rasyonel)
```

- `num/den` hedef fps rasyoneli (§3.1); `t_j = j * den / num` saniye — us
  cinsinden **noktalı/rasyonel** taşınır (ara yuvarlama yok; §4.5).
- `startUs` tek-klip ihrâcında 0'dır (bu uygulama klipte kırpma yapmaz);
  timeline paritesi ve ileri-uyumluluk için formülde yer alır — timeline'ın
  `sourceRange.start`'ıyla aynı rol (`TimelineExport.kt:221-223`).
- `sourceFps` = **ölçülen** değer, `(N-1)/span` cebiriyle
  (`ExportFps.fromPts`: `ExportPlanRun.kt:35-36`; e1c-polish
  `PtsTimeline.measuredFps` aynı). Asla standart değere yuvarlanmaz.
- `i(j)` her zaman `[0, N-1]`'e kelepçelenir (§4.4 uçlar).

**Timeline paritesi (testte sabitlenir):** `startUs=0`, `sourceFps=vs`
projectFps oranı için `i(j) = round(j * sourceFps / projectFps)` —
`TimelineExport.kt:222` ile **aynı cebir**. Kaynak fps = hedef fps ⇒ `i(j)=j`
(özdeşlik). Parite testi iki üretimi aynı girdilerle eşitler (§6 v9).

### 4.2 Kare sayısı — brief'teki formülün düzeltilmesi

Brief `ceil(duration*targetFps)` önerir. **Doğru kural (öneri):**
`spanUs = lastPts - firstPts` (n-1 aralık; `engine/PtsTimeline.totalDurationUs`,
`e1c-polish measuredFps` ile aynı ölçüm) ve

```
lastJ = floor(spanUs * num / (den * 1e6))      // span'ı aşmayan son grid noktası
outputFrames = lastJ + 1                       // j = 0..lastJ (dahil)
```

Gerekçe: `ceil` yalnız kesirli uçlarda `floor+1`'e eşittir; **tam sayı
sinirda son kareyi düşürür** — özdeşlik vakasında (30→30, span=(N-1)/30):
`ceil(span*30) = N-1`, yani kaynak N kareyi N-1'e indirir. `floor+1` hem
özdeşlikte N üretir hem `durationSec` tanımıyla uyumludur (`sonPts + 1/fps`,
`Mp4Exporter.kt:1631`: son kare bir periyot *görüntülenir*). Kesirli uçta
("sonda fraction") floor+1 son grid noktasını span İÇİNDE tutar — kısa bir
kuyruk karesi asla eklenmez. Bu düzeltme tasarım sırasında bulundu; brief'teki
ceil'den farkı §4.6'daki uç vakalarla sabitlenir.

### 4.3 Boşluklu (VFR-eğilimli) kaynak: tekdüze ölçüm modeli

Eşleme, kaynağı **ölçülen hıza tekdüze (uniform)** sayar (model A). Gerçek
67 ms boşlukta: span boşluğu içerir, `sourceFps=(N-1)/span` hızı hafifçe
düşürür; boşluk bölgesinde bir kaynak karesi ~2 çıktı karesi tutulur ve kuyruk
`N-1`'e kelepçelenir. Toplamsal hata: boşluk komşuluğunda ≤1 kare seçim farkı.
Alternatif model B (pts-dizi ARGMIN, gerçek VFR-eğilimi): aynı sonucu VERİR
burada, ama ARGMIN'e N'lik tarama/sıçrama listesi gerekir; E1f'de **önerilmez**
(K3'te karar sahibin). `SOURCE` modu her durumda gerçek pts'leri taşır —
 model hatası yalnız dönüşüm seçildiğinde yaşar ve istemli bir seçimdir.

### 4.4 Uç durumlar

- **Tek kare** (span=0): `outputFrames = 1`, `i(0)=0` (floor+1 kuralı 0+1=1
  üretir; `ceil(0)=0` olurdu — tek karelik klip bugün de 1 kare kodlar,
  `videoPts` boyu 1; test §6 v5).
- **İlk kare:** `i(0)=round(0)=0` her zaman — açılışta kopya yok (29.83→30'da
  çoğaltmanın ilki ~j=88 civarı; 23.976→30'da j=1'den itibaren).
- **Son kare:** `i(lastJ)` kelepçeyle `N-1`'e iner (fraction/boşluk uçlarında)
  — kuyruk asla kaynağın dışına taşmaz.
- **Özdeşlik:** kaynak fps == hedef ⇒ bayt-düzeyinde değil (pts'ler kesin
  grid'e yazılır, kaynak pts'lerdeki us-jitter kaybolur) ama içerik-düzeyinde
  özdeş; `SOURCE` seçildiğinde ise bugünkü bayt-davranış aynen sürer. UI,
  "kaynak 30.06 → hedef 30" gibi eşlerde yalnızca pts hizalaması yapıldığını
  belirtir (K7'de yardım metni).

### 4.5 Çift yuvarlama çakışmaları

- `pts_j` tek yuvarlamayla: `pts_j = round(j * 1e6 * den / num)` (us).
  Örn. 29.97: `round(j*1001000/30000)` → adım 33366/33367 karışık; **monoton**
  (adım ≫ 1 us). Uzun koşu testi (3000 kare) girintiyi ve sapmayı ≤0.5 us
  sınar (§6 v8).
- `i(j)`'de ara `t_j_us` tamsayıya asla indirgenmez: `round(j*1e6*den/num) *
  sourceFps/1e6` DEĞİL — noktalı/rasyonel `t_j` üzerinden tek `round`.
  İki aşamalı tamsayı yuvarlaması 29.97 hedefinde bazı j'lerde farklı kare
  seçebilir (özel test: iki yolun ayrıştığı j bulunur ve doğrusal yol kazanır).
- `PtsTimeline.ptsNs` taşma/negatif korusu aynen geçerli
  (`export/PtsTimeline.kt:103-112`) — Surface bacağı grid pts'iyle çalışır.

### 4.6 Örnek sayılar (test vektörlerinin sabitleri; bu pakette hesaplandı)

| Vaka | sourceFps → hedef | Beklenen karakter |
|---|---|---|
| özdeşlik | 30 → 30 | `i(j)=j`; frames N; pts adım 33333 us |
| çoğaltma | 23.976 → 30 | oran 0.7992; ~%20.1 çoğaltma (yaklaşık her 5. çıktıda) |
| atma | 30 → 24 | oran 1.25; ~%20 atım (her 5 kaynakta ~1) |
| gerçek vaka | 29.83 → 30 | oran 0.99433; ~%0.57 çoğaltma (~%175'te 1) |
| NTSC grid | — → 29.97 | pts adım 33366/33367; KEY_FRAME_RATE Float 29.97003 |

(Tam diziler testte formülden üretilir; yukarıdaki oranlar yalnızca
karakterizasyondur, kare dizisi değildir.)

### 4.7 Writer'a giden örnekleme listesi (E1f-2 sözleşmesi)

```
videoSamples[j] = Sample(Track.VIDEO, pts = pts_j, frameIndex = i(j))
audioSamples[k] = Sample(Track.AUDIO, pts = audioBlockPts(k), frameIndex = k)  // DEĞİŞMEZ
interleave(videoSamples, audioSamples)  // mevcut fonksiyon aynen
```

`s.frameIndex` bugün **kaynak** kare indeksidir (`renderFrame(handle,
s.frameIndex…)`, `Mp4Exporter.kt:1879`); dönüşümde de kaynak indeksi kalır —
yalnız süreklilik: art ardışın video örneklerinde `i(j)` tekerrür edebildiği
için decode önbelleği (lastFrame/lastRgb, timeline emsali
`TimelineMp4Exporter.kt:315-322`) pipeline'a eklenir; ses örnekleri araya
girerse önbellek bozulmaz (yerel değişken, ses dalı onu yazmaz).

---

## 5. Writer/pts etkisi (E1f-2)

**Net öneri: video CFR hedef grid + ses pts aynen.**

- `MediaCodecMp4Writer`'da bugün her kare **kendi pts'iyle** yazılır:
  CPU/planes: `queueInputBuffer(…, ptsUs, …)` (`MediaCodecMp4Writer.kt:779`,
  `:947`); Surface: `eglPresentationTimeANDROID(PtsTimeline.ptsNs(ptsUs))`
  (`:840-846`). ptsUs'ler pipeline'dan gelir — yani **writer dosyası
  değişmez**; değişiklik pipeline'ın pts üretiminde (§4.7).
- "Çıktı hedef fps'te CFR" ne demektir: video track'in tüm pts aralıkları
  `round(j*1e6*den/num)`; her ara sabit-periyot (us yuvarlaması ±0.5),
  `i(j)` tekerrürleri olsa bile ( MediaCodec, aynı pts'in ÇAKIŞMASINI değil
  TEKRARINI yasaklar — pts'ler art arda eşit OLMAYIP monoton artmalı;
  grid'de `pts_j > pts_{j-1}` her zaman sağlanır, çoğaltma == aynı kare içeriği,
  farklı pts).
- **Ses pts'i mutlaka korunur** (`audioBlockPts`; `writeAudioBlock(s.ptsUs,…)`
  `Mp4Exporter.kt:2121-2122`) — §2.4 analitik sonucu: duvar saati korunur,
  kayma 0.
- `KEY_FRAME_RATE = plan.fps` = **hedef** olur (kodta ExportFormat.kt:56
  değişmez; plan.fps'in anlamı değişti — §3.4). MediaCodec'un `KEY_FRAME_RATE`'in
  rate-control/VBV üzerindeki etkisi: doğrulanmadı — §9 sorusu 1.
- `KEY_I_FRAME_INTERVAL` saniye birimlidir (KeyframeInterval.seconds,
  `ExportSettings.kt:53-62`); fps dönüşümünde saniye-başına GOP kare sayısı değişir:
  "1 sn" her hedefte 1 sn'dir, kare sayısı `round(1s*fps)` olur (24→24 kare,
  60→60). Kodta değişiklik gerekmez; sadece rapor metni. ALL_INTRA (0) aynen.
- `run.durationSec = outputPts.last()/1e6 + 1/targetFps` (§4.2 ile tutarlı);
  `estimatedBytes(durationSec)` (`ExportPlan.kt:134-136`) çıktı süresiyle
  çalışır. `framesTotal` progress'i de çıktı kare sayısı olur (UI'da payda
  değişir; UI metni güncellenir — E1f-3).
- GPU bacakları aynı sözleşme: `writeVideoFrameRgb(pts_j, …)` ve
  `renderFrameToEncoderParcel(…, ptsUs = pts_j, frameIndex = i(j))` —
  imza/karar değişikliği yok (`Mp4Exporter.kt:1879`, `:2065-2066`).
- **Timeline ihrâçı E1f'te değişmeden kalır**: proje fps'i zaten grid
  üretilir (`TimelineMp4Exporter.kt:431`); tek-klip eşlemesi timeline
  cebriyle (§4.1) aynıdır — iki yol ayrışmaz (parite testi §6 v9).

---

## 6. A/B ve testler

### 6.1 Saf Kotlin tablo testleri (E1f-1; hepsi JVM)

Yeni `FrameRateMapTest.kt` (PtsTimelineTest deseni; beklenenler formülden
üretilip elle doğrulanan sabit açılarla):

1. **özdeşlik 30→30:** `i(j)=j`; `outputFrames=N`; pts adımı 33333/33334;
   `SOURCE` bayt-davranışla aynı kare dizisi.
2. **23.976→30:** oran 0.7992; dizi monoton-çoğaltmalı; çoğaltma oranı
   ≈%20.1; ilk kare 0.
3. **30→24:** atımlı dizi (≈ her 5. çıkışta 1 atım); hiçbir kaynak karesi
   iki kez seçilmez, dizi monoton.
4. **29.83→30 gerçek vaka:** tekdüze 33_523 us pts + 60. kareden sonra tek
   67_046 us boşluk; `outputFrames = floor(span*30/1e6)+1`; boşluk
   komşuluğunda aynı kaynak karesi ~2 çıktı; kuyruk `N-1` kelepçe.
5. **tek kare (span=0):** `outputFrames=1`, `i(0)=0` (ceil(0)=0 REDDEDİLİR —
   §4.2 düzeltmesinin sabitlenmesi).
6. **iki kare, kısa span:** floor+1'in kesirli uçta davranışı (son grid
   noktası span içinde kalır; kısa kuyruk karesi eklenmez).
7. **sonda fraction:** `span*num/(den*1e6)` tam sayı değilken (`59.94`, ya da
   asimetrik span) floor+1 ile ceil'in eşleşip eşleşmediği sabitlenir.
8. **rasyonel grid monotonluğu:** 29.97 hedefi, 3000 kare; pts monoton;
   sapma |pts_j − j*1001000/30000| ≤ 0.5 us; 33366/33367 karışık.
9. **timeline paritesi:** aynı (sourceFps, projectFps) çiftleri için
   FrameRateMap dizisi `TimelineExportPlan`'ın `sourceFrames` dizisiyle
   örtüşür (timeline testinde üretim, burada karşılaştırma).
10. **çift yuvarlama çakışması:** ara us-tamsayılı yol ile doğrusal yolun
    ayrıştığı j'de doğrusal kazanır (§4.5).
11. **planner:** `input.fps = hedef` (bitrate tablosu kb, `BitrateTableTest`
    genişletmesi; `sizeSupport` sorguları hedefle).
12. **wire v2:** ad ile yaz/oku; bilinmeyen ad → `Unsupported`; v1 satır →
    `Unsupported` (dürüst hata, sessiz SOURCE düşüşü YOK).
13. **preset korunumu:** her `ExportPreset.apply` `targetFps`'i bozmaz.

### 6.2 ExportAbSweep'e "fps-dönüşüm" vakası (E1f-2, opsiyon — K9)

Mevcut süpürme CPU↔GPU develop kapılarını BAYT karşılaştırır
(`ExportAbSweep.kt` — kare sayıları eşit varsayar). Dönüşümün cihaz-bacak
riski bayt değil **pts damgasıdır** (Surface bacağı
eglPresentationTimeANDROID). Öneri: yeni vaka id `fps-conv` — kısa pencere
(≈90 çıktı karesi) iki bacakta da **aynı eşlenmiş kaynak indeksleriyle**
üretilir; (i) bayt karşılaştırması mevcut makinayla (aynı indeksler, aynı
grade), (ii) ek olarak **pts akışı**: fake/gerçek writer'ın kaydettiği pts
dizisinin CPU ve Surface bacaklarında aynı grid'i verdiği doğrulanır
(`assertEquals` grid dizisi; saf kısım JVM'de). A/B sweepe dahil edilmemesi
durumunda: pts grid zaten JVM testse sabit; geri kalan risk (MediaCodec
KEY_FRAME_RATE etkisi, §9-1) dokümante edilir. K9 sahibin tercihi.

### 6.3 JVM doğrulanabilirlik çerçevesi

Eşleme, wire, planner, pts grid tam saf — JVM. Pipeline etkisi
`Mp4ExporterTest`'te fake'lerle (mevcut desen: fake session/writer pts kaydı)
sınanır: `writeVideoFrame`'e geçen pts dizisinin hedef grid olduğu ve ses
pts'lerinin değişmediği; `framesTotal`'ın çıktı sayısı olduğu. Kodlayıcıya
gerçek dokunuş yok — `MediaCodecMp4Writer` dokunulmaz (§5).

---

## 7. Paket kırılımı

| Paket | İçerik | Dosyalar | Boyut | Öncül |
|---|---|---|---|---|
| **E1f-1** | Saf eşleme + model + plan + testler; UI yok | yeni `export/FrameRateMap.kt`; `ExportSettings.kt` (enum+alan+wire v2); `ExportPlan.kt` (KDoc, `ExportInput.fps` anlamı); `ExportPlanRun.kt` (`ExportFps` kalır ölçüm için); `ExportSheetModel.kt` (Subject fps anlamı); test: `FrameRateMapTest.kt`, `ExportSettingsTest/Wire` genişletme, `ExportPlannerTest`, `BitrateTableTest` | **M** | e1c-polish merge (measuredFps) |
| **E1f-2** | Pipeline + writer/pts paritesi | `Mp4Exporter.kt` (encode: pts üretimi §4.7, decode-önbellek, progress/durationSec, GPU bacak argümanları); `MediaCodecMp4Writer.kt` **değişmez**; `TimelineMp4Exporter.kt` **değişmez**; testler `Mp4ExporterTest`, `TimelineMp4ExporterTest` (parite); opsiyonel A/B vakası (K9) | **M** | E1f-1 |
| **E1f-3** | UI: hedef fps seçici (en+tr), "kaynak" etiketi, yardım | `ExportSettingsPanel.kt` (chips/HelpTitle — mevcut örüntü `:403-413`); `EditorTopBar.kt` (`Subject`/plan girdisi hedef fps + mevcut metinden kare özeti); `ExportSheetModel.kt` (drop/duplicate oranı satırı); `strings.xml` + `values-tr/strings.xml` (yeni anahtarlar; `export_frame_rate_value` İKİ durum: "(kaynak)" / "(hedef)"); e1c-polish `ExportSheetState.displayFps` snap'inin kaldırılması (K6) | **M** | E1f-2 |

Notlar: `EncoderFacts`/cihaz dokusu yok (E1f-1); `MediaCodecMp4Writer` satır
değişikliği yok — plan-beşli mimarinin E1f getirisi tam budur. E1f-3'ün
riskli tek işi `displayFps` davranışıdır (K6) — sahibin yuvarlama yasağı
bde227d'deki eski karardan üstündür.

---

## 8. Sahibin onayına açık kararlar

- **K1 — Atma/çoğaltma ana kuralı:** "en yakın" (round-half-up, Kotlin
  `roundToInt`; timeline ile aynı, deterministik) — öneri. Alternatif:
  floor+faz (NTSC atım örüntüsünde kararlı drop-pattern) — uzun klipte
  "en yakın"ın rastgele-görünümlü hataları yarım kare; kabul edilebilir.
- **K2 — 29.97 gibi rasyonel hedefler:** kesin 30000/1001 (öneri; etiket
  "29.97") ve **drop-pattern**'i sabit kalsın (formülün deterministik
  olması yeter; ayrı desen gerekmez — sadece onay notu). 23.976/47.952/119.88
  hedef merdivenine eklensin mi? (brief listesinde yok; sinema-NTSC için
  eklenmesi öneri).
- **K3 — VFR kaynağa hangi sabit:** kaynak fps'i **(N-1)/span ölçümlü tekdüze
  model** (öneri — §4.3); ARGMIN (pts-dizi) alternatifi; tek boşluklu 29.83
  vakasında sonuç farkı ≤1 kare.
- **K4 — Kare sayısı kuralı:** `floor(span*hedef)+1` (öneri, §4.2 — brief'teki
  ceil'in tam-sayı ucu düzeltildi). Onay, ceil'in son-kareyi düşürmesinin
  bilinçli reddi demektir.
- **K5 — Wire v2 politikası:** hedef alan her yeni job'da yazılır, v1 satır
  dürüstçe başarısız (öneri — sessiz SOURCE düşüşü yasak). Alternatif:
  SOURCE iken alanı atlama (bayt-özde satırlar; E1 sonrası satır sayısı az).
- **K6 — e1c-polish `displayFps` %1 snap'inin kaldırılması** (bde227d):
  sahibin "yuvarlama yasak / 29.83 fps (kaynak)" kararı fps satırı için
  geçerli; sheet özet kartındaki fps gösterimi de ölçülen değeri ham yazacak
  şekilde revize edilir (29.83 vakasında bugünkü main zaten "29.83" yazıyor;
  yasak kural olarak kalıcılaştırılır — 29.92 gibi %0.3 içi ölçümler de
  ham kalır). Öneri: kaldır.
- **K7 — UI yardım metni:** hedef ≠ kaynak seçildiyse özet satırında
  ekran içi bilgilendirme (en/tr): "29.83 → 30: ~%0.57 kare çoğaltılır; ses
  değişmeden senkte kalır"; eş-ölçüm durumunda pts-hizalama notu. Onay.
- **K8 — Timeline:** E1f yalnız tek-klip; timeline proje fps'ini kullanmaya
  devam eder (öneri — timeline zaten kendi fps seçicisidir). Onay.
- **K9 — A/B süpürmesinde `fps-conv` vakası** (§6.2): eklenmesi öneri
  (pts-grid doğrulaması), opsiyonel.
- **K10 — KEY_I_FRAME_INTERVAL saniye kalır** (GOP/sn yeniden hesap yok,
  §5) — onay notu.

---

## 9. Doğrulanamayanlar + online araştırma ajanına sorular

Doğrulanamayanlar (cihaz/doküman ölçümü gerekli; kod okuma ile aşılamaz):

- `KEY_FRAME_RATE` değerinin MediaCodec kodlayıcısının rate-control/VBV
  penceresi üzerindeki GERÇEK etkisi (Int/Float farkı, ~%0.5 hız farkında
  kalite/boyut etkisi; codec2 katmanı).
- Media3'ün FPS dönüşüm yaklaşımı (Transformer): drop/duplicate mi,
  FrameProcessorChain mi; hangi yuvarlama; VFR kaynak davranışı — E1f'in
  şekillendirmesine referans uygulanabilirlik.
- MediaMuxer'un kayan (kesirli) fps'te track timescale'ı nasıl seçtiği;
  pts (us) net kaybı; 29.97 hedefinde container'daki gerçek zaman tabanı
  (30000/1001'e mi 29970/1000'e mi oturur) — grid'in konteynerde bozulmaması
  için.
- Ana akım oynatıcıların (ExoPlayer/AVPlayer/Chrome) VFR MP4 hoşgörüsü
  (ret edilen seçenek (c)'in gerekçesini teknikleştirmek için).
- Melez/NTSC için düşünülen drop-pattern sabitliği: uzun klipte round'un
  atım örüntüsü periyodik mi yoksa yavaş kayan mı (testte örüntü incelenir;
  gerçek periyodiklik cihaz oyuncusunda ölçülemez).

Online araştırma ajanına sorular (öncelik sırasıyla):

1. **MediaCodec `KEY_FRAME_RATE` semantiği:** rate control'a etkisi (bitrate
   pencereleme, CBR düzeltmesi), kesirli Float kabul üreticilerde
   tutarlı mı, CTS'te hangi varsayımlar test edilir — E1f-1'in plan.fps
   = hedef kararını sağlamlaştırmak için.
2. **Media3 Transformer fps dönüşümü:** varsayılan `setFrameRate`/frame
   drop davranışı, frame-drop yaklaşımı ve NTSC rasyonelleri (30000/1001)
   için önerilen yol — bizim §4 seçimlerini sektör referansıyla kıyaslamak.
3. **MediaMuxer timescale:** kesirli KEY_FRAME_RATE ile MP4 mdhd/timescale
   seçimi ve us-pts kaybı (KEY_I_FRAME_INTERVAL ile birleşik davranış).
4. **ffmpeg kural referansı:** `-r`/`-fps_mode` CFR dönüşümünde nasıl
   atar/çoğaltır; NTSC için drop-pattern; `29.83` gibi standart-dışı
   kaynaklarda önerilen mapping (frame-nearest'i doğrulamak için).
5. **VFR MP4 oynatıcı desteği:** ana akım oynatıcıların gerçek-gaps'li
   videoyu nasıl karşılar (ret (c) için teknik not).

Sonuç: E1f-1 kod başlangıcı K1-K5 kararları + e1c-polish merge'ı ile
açılabilir; E1f-2/3 sırasıyla onay sonrası.
