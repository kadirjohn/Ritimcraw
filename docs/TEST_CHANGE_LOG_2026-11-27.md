# Gece dalları test beklentisi değişim + commit konusu envanteri — 2026-11-27

Bu belge bağımsız bir DOĞRULAMADIR: her kayıt bu belgeyi yazan ajanın kendi
`git diff`/`git show` çıktısından gelir; hiçbir işçi raporundan kopyalanmamıştır.
İşçi raporları (`.planning/night-2026-11-27/NIGHT-REPORT.md`) yalnız
KARŞILAŞTIRMA amacıyla kullanılmış, uyışmayan yerler "Beyan-diff uyuşmazlıkları"
bölümündedir. Geçmişe hiçbir müdahale yapılmamıştır (rebase/rewrite yok);
belgenin kendisi dışında hiçbir dosya değişmemiştir.

**Not (sahip):** I3 konu uzunluğu için sahip "çok önemli değil" demiştir —
bu tablo yalnız sabah toplu `git rebase` kararı istenirse hazır olsun diye
çıkarılmıştır; işlem önerisi değildir.

## Yöntem

- Dal başına `git diff <taban>..<dal> --diff-filter=M -- '*Test*.kt' '*test*.kt'`
  (JVM) ve ayrıca `core/tests/*` (masaüstü doctest) taranmıştır.
- `feat-export-fps-e2e` CANLI bir daldır: dalına hiç dokunulmadı; okuma,
  iş sırasındaki sabit HEAD anlık görüntüsüne göre yapılmıştır (aşağıda not).
- Her kayıtta verilen satır numaraları DAL ağacındadır (taban satırı ayrıca
  verilir). Kanıt olarak diff satırı aynen alıntılanır.
- **GÜVENLİ/ŞÜPHELİ tanımı:** yeni beklenti eskisinden DAHA AZ şey iddia
  ediyorsa ŞÜPHELİ (ör. exact→contains, eşitlik→aralık, alan sayısı düştü).
  Aynı iddia gücüyle taşınan sürüm-bump pin'leri GEVŞETME değildir
  (eşdeğer); daha çok şey iddia eden değişimler spesifikleşmedir.

---

## BÖLÜM 1 (I2) — Mevcut test beklentisi değişimleri

### Özet

| Dal | Değişen mevcut test dosyası | Silinen/taşınan beklenti |
|---|---|---|
| feat-audio-dsp-core | yok | 0 |
| feat-export-loudness-chain | 5 dosya | 3 pin grubu (6 satır) + SOCIAL ekleme |
| feat-cinemadng-sequence-core | yok (CinemaDngPlanTest dal İÇİNDE yeni; son commit'i kendi beklentilerini düzeltti — ayrı kayıt) | 0 (yeni-dosya içi, 4 kayıt) |
| feat-hlg-pure-layer | 2 dosya (yalnız ekleme) | 0 |
| feat-cube-export-core | yok | 0 |
| feat-proxy-p234-logic | yok | 0 |
| test-project-file-resilience | yok | 0 |
| test-strings-parity-lock | yok | 0 |
| feat-export-fps-mapping | 2 dosya | 3 pin grubu (5 satır) |
| fix-mp4-badge-kind | 2 dosya | 1 beklenti değişimi (+yalnız ekleme) |
| feat-caption-data-layer | yok | 0 |
| feat-hdr10-dv-static-metadata | yok | 0 |
| feat-cinemadng-export | 2 dosya (CinemaDngExporterTest + ExportQueueTest) + doctest | 2 beklenti değişimi (denetçi düzeltmesi) |
| perf-temporal-nr-cpu | doctest (yalnız ekleme) | 0 |
| research-recovery-joint-estimate | doctest (`test_highlight_recovery.cpp`) | 1: `should_fail` işaretinin kaldırılması |
| feat-release-r8-readiness | yok | 0 |
| feat-export-audio-e2e | 5 dosya | 5 pin grubu (wire v2→v3/v3→v4 + read-only seti + v1 testine 2 iddia) |
| feat-proxy-production | 1 dosya (yalnız ekleme) | 0 |
| feat-cube-export-ui | yok (yalnız yeni CubeExportPlanTest) | 0 |
| feat-export-fps-e2e (CANLI) | 2 dosya (ExportSheetStateTest'te 2 mevcut test DEĞİŞTİ) | 4 beklenti değişimi (K6 + read-only seti) |

---

### 1. `feat-export-loudness-chain` (taban `8a69956d`, dal HEAD `bba728f6`)

Değişen dosyalar (`--diff-filter=M`): `ExportSettingsTest.kt`,
`ExportStoreSettingsTest.kt`, `ExportSheetStateTest.kt`, `Mp4ExporterTest.kt`
(yalnız sahte yazara ekleme), `ExportSettingsExportTest.kt` (yalnız yeni
test). Silinen beklenti satırları yalnız ilk üçte.

| # | Dosya:satır (dal) | Eski beklenti | Yeni beklenti | Neden | Gevşetme/spesifikleşme | Verdi |
|---|---|---|---|---|---|---|
| L1 | `ExportSettingsTest.kt:23` | `for (p in listOf(COMPATIBLE, SMALLER, QUALITY))` | aynı döngü + `ExportPreset.SOCIAL` | yeni SOCIAL preset'i `of()/apply()` gidiş-dönüş ailesine katıldı; kaçınılmaz (preset ekleme) | spesifikleşme (daha ÇOK preset iddia ediyor) | GÜVENLİ |
| L2 | `ExportSettingsTest.kt:140` | `text.startsWith("{\"v\":1,")` (taban :91) | `text.startsWith("{\"v\":2,")` | wire sürüm bump'ı (v2 bu dalın ta kendisi); tanım gereği | eşdeğer (aynı iddia, yeni sürüm) | GÜVENLİ |
| L3 | `ExportSettingsTest.kt:164` | `unsupported(ok.replace("\"v\":1","\"v\":2"))` (taban :107) | `replace("\"v\":2","\"v\":3")` | too-new pini bir sürüm yukarı (v2 artık BİZİZ, bilinmeyen v3) | eşdeğer (aynı red davranışı, bir sürüm kaydı) | GÜVENLİ |
| L4 | `ExportStoreSettingsTest.kt:37` | `contains("\"settings\":{\"v\":1,")` (taban :37) | `contains("\"settings\":{\"v\":2,")` | sürüm bump'ı | eşdeğer | GÜVENLİ |
| L5 | `ExportStoreSettingsTest.kt:50,54` | `replace("v":1,"v":2)` + `detail contains "settings version 2"` (taban :48,52) | `replace("v":2,"v":3)` + `version 3` | too-new pini sürüm kaydı | eşdeğer | GÜVENLİ |
| L6 | `ExportSheetStateTest.kt:122` | `listOf(...,"AUDIO","AUDIO_FORMAT")` (taban :117) | `listOf(...,"AUDIO","LOUDNESS","AUDIO_FORMAT")` | yeni LOUDNESS satırı Advanced sırasına girdi; satır sırası pini davranışla birlikte taşır (UI commit'i `bba728f6` içinde beyanlı) | spesifikleşme (daha uzun sıra pinli) | GÜVENLİ |

Kanıt (L2, diff'ten): `-        assertTrue(text, text.startsWith("{\"v\":1,"))` →
`+        assertTrue(text, text.startsWith("{\"v\":2,"))`.

`Mp4ExporterTest.kt`'teki değişim yalnız `FakeWriter`'a `audioSamples`
kaydı eklenmesi + 3 yeni testtir; silinen beklenti yok. `ExportSettingsExportTest.kt`
yalnız yeni test. Bu iki dosya yukarıdaki tabloya girmez (beklenti değişimi 0).

### 2. `feat-export-fps-mapping` (taban `fb0f80d6`, dal HEAD `cd07b8a6`)

Değişen dosyalar: `ExportSettingsTest.kt`, `ExportStoreSettingsTest.kt`.
Değişen satırlar loudness dalıyla BİREBİR aynı desende (iki dal aynı pinleri
taşıdı — I1 birleşiminde çakışma kaynağı da buydu):

| # | Dosya:satır (dal) | Eski | Yeni | Neden | Verdi |
|---|---|---|---|---|---|
| F1 | `ExportSettingsTest.kt:92` | `startsWith("{\"v\":1,")` (taban :91) | `startsWith("{\"v\":2,")` | wire v2 (targetFps alanı) | GÜVENLİ (eşdeğer) |
| F2 | `ExportSettingsTest.kt:114` | `unsupported(replace("v":1→"v":2))` (taban :107) | `replace("v":2→"v":3)` | too-new kaydı | GÜVENLİ (eşdeğer) |
| F3 | `ExportStoreSettingsTest.kt:37` | `contains("\"settings\":{\"v\":1,")` | `contains("\"settings\":{\"v\":2,")` | sürüm bump'ı | GÜVENLİ (eşdeğer) |
| F4 | `ExportStoreSettingsTest.kt:50,54` | `replace v1→v2` + `version 2` | `replace v2→v3` + `version 3` | too-new kaydı | GÜVENLİ (eşdeğer) |

### 3. `feat-hlg-pure-layer` (taban `8a69956d`, dal HEAD `3a357528`)

Değişen dosyalar: `HevcPreflightTest.kt` (+112), `HevcProfileTest.kt` (+51).
Her iki dosyada da SİLİNEN satır yok (`git diff | grep '^-'` boş — yalnız
ekleme). Mevcut beklenti değişimi: **0**. Doğrulandı: `git diff
8a69956d..feat-hlg-pure-layer -- 'app/src/test/*'` toplamı 5 dosya / +708
ekleme, 0 silme.

### 4. `fix-mp4-badge-kind` (taban `fb0f80d6`, dal HEAD `00729729`)

Değişen dosyalar: `SourceRepositoryTest.kt` (yalnız 5 yeni test — silinen
satır yok), `LibraryRowModelTest.kt` (1 mevcut beklenti değişimi + 2 yeni
test).

| # | Dosya:satır (dal) | Eski | Yeni | Neden | Verdi |
|---|---|---|---|---|---|
| M1 | `LibraryRowModelTest.kt:238` | `Badge(BadgeStyle.HIGHLIGHTED, "MCRAW")` (taban :235) | `Badge(BadgeStyle.MOTIONCAM, "MCRAW")` | sahibin M2 talebinin ta kendisi: rozet stili değişti; davranış değişimi bilinçli | spesifikleşme (yeni stil iddiası; eski HIGHLIGHTED iddiası zaten ortadan kalkan davranışı pinliyordu) | GÜVENLİ |

Kanıt (diff'ten): `-            LibraryRowModel.Badge(LibraryRowModel.BadgeStyle.HIGHLIGHTED, "MCRAW"),`
→ `+            LibraryRowModel.Badge(LibraryRowModel.BadgeStyle.MOTIONCAM, "MCRAW"),`.
Bu değişim commit `00729729` içindedir; commit gövdesi "TEST CHANGE (owner
request 2026-11-27, M2)" diye açılır ve beklenti değişiminin gerekçesini
taşır — kuralın istediği "gerekçe commit'te" hali mevcut (yalnız ayrı commit
değil; işçi gerekçesi: split kırmızı ara commit üretirdi).

### 5. `feat-cinemadng-sequence-core` (taban `8a69956d`, dal HEAD `0d62839a`)

Mevcut test dosyası değişimi YOK (`--diff-filter=M` boş; `CinemaDngPlanTest.kt`
bu dalda YENİDİR). Fakat bu yeni dosyanın beklentileri dal İÇİNDE son
commit'le (`0d62839a` — konusu `tests: fix new plan test expectations`)
düzeltildi; bu kayıtlar envantere girer çünkü aynı dalda dört iddia
değişimi bir commit sonra taşındı:

| # | Dosya (yeni dosya, commit `c5a26973`→`0d62839a`) | Eski (c5a26973 ağacında) | Yeni (0d62839a ağacında) | Neden | Verdi |
|---|---|---|---|---|---|
| C1 | `CinemaDngPlanTest.kt:44` (eski :44) | `assertEquals("138.9 GiB", ...)` | `assertEquals("139.0 GiB", ...)` | yanlış yuvarlama düzeltmesi: ham tahmin 139,046 GiB; "%.1f" 139,0 verir, 138,9 değil — hesap hatası onarımı | spesifikleşme/düzeltme (belgenin kendi ~139 değeriyle artık iki biçim de tutarlı) | GÜVENLİ |
| C2 | `CinemaDngPlanTest.kt:108-109` | `assertEquals(11, frameCount(90, -1, 100))` + `assertEquals(11, frameCount(90, 500, 100))` | her ikisi `10` | aralık matematiği hatası: 90..99 = 10 kare (dahil uç); 11 iddiası yanlış sayıyordu | davranış değişimi YOK — beklenti koda (doğru koda) hizalandı | GÜVENLİ |
| C3 | `CinemaDngPlanTest.kt:133` | `storageGate(1000, required) is Locked` | `... is Warn` | planın kendi kuralı `boş < gerekli` TIGHT kilit; free==required eşiğin üstündedir | beklenti planın metnine hizalandı (kod davranışı zaten buydu) | GÜVENLİ |
| C4 | `CinemaDngPlanTest.kt:192` | `assertEquals(ceil(...).toDouble(), plan.wavBytes.toDouble())` | `assertEquals(ceil(...), plan.wavBytes)` (Long==Long) | Double eşitlik Long'a taşındı (yardımcının kendi yuvarlaması) | eşdeğer (aynı sayı, daha katı tür) | GÜVENLİ |

Bu dört değişim yeni-dosya-içi olduğundan "mevcut test" kuralını teknik
olarak ihlal etmez, ama konusu itibarıyla `test change` commit'idir;
işçi raporu bunu ayrıca beyan etmiştir (uyuşuyor).

### 6. `feat-cinemadng-export` (taban `feat-cinemadng-sequence-core` @ `0d62839a`, dal HEAD `ab8fd6d7`)

Değişen mevcut test dosyaları: `CinemaDngExporterTest.kt`,
`ExportQueueTest.kt`; doctest tarafında `core/tests/test_dng_sequence.cpp`
(yalnız yeni TEST_CASE ekleme — silinen satır yok).

| # | Dosya:satır (dal) | Eski | Yeni | Neden | Verdi |
|---|---|---|---|---|---|
| N1 | `CinemaDngExporterTest.kt:293` (eski ağaç :293) | `assertEquals("2/2 frames", result.detail)` | `assertEquals("2/2 frames · no audio", result.detail)` | denetçi RED düzeltmesi (`ab8fd6d7`): DONE detayı ses yokluğunu dürüstçe adlandırsın diye üretim değişimi; test eski davranışın (ölü ifade `if (r.audioAbsent) "" else ""` — ses yokluğunu detaydan GİZLEYEN) pinini taşıdı | spesifikleşme (detay daha FAZLA şey iddia ediyor; eski beklenti yalan-piniydi) | GÜVENLİ |
| N2 | `ExportQueueTest.kt:1568` (eski ağaç :1553) | `assertEquals("2/2 frames", row.detail)` | `assertEquals("2/2 frames · no audio", row.detail)` | aynı davranış değişiminin kuyruk tarafındaki pini | spesifikleşme | GÜVENLİ |

Kanıt (diff'ten): `-            assertEquals("2/2 frames", result.detail)` →
`+            assertEquals("2/2 frames · no audio", result.detail)`.
İşçi raporu bu iki değişimi "test gevşetme değil, yalan-pininin dürüst-pinine
değişimi" diye beyan etmiş ve gerekçeyi taşımıştır — bağımsız diff bunu
doğruluyor: yeni beklenti daha KATI (aynı iki sabit + " · no audio" eki).

Ayrıca `ab8fd6d7` içinde `ExportQueueTest.kt:1556-1563`'te kuyruk fabrikası
`CinemaDngExporter(io, freshEngine())` → `{ settings -> ... CinemaDngExporter(io, freshEngine()) }`
şekline taşındı (yeni `seenFolder` iddiası eklendi) — beklenti değişimi değil,
fabrika imzası değişiminin test tarafındaki karşılığı; yeni iddia eklemesi
(spesifikleşme), mevcut iddia silinmedi.

### 7. `perf-temporal-nr-cpu` (taban `fb0f80d6`, dal HEAD `8c41e449`)

Mevcut test değişimi: doctest `core/tests/test_temporal.cpp` (+131; yalnız
YENİ TEST_CASE `merge_rows_ab_bit_identical` + yardımcılar). Silinen satır
yok (`git diff | grep '^-'` boş). Mevcut altın-hash testi
`merge_output_is_identical_serial_and_parallel_and_pinned` DOKUNULMADI.
JVM test dosyası değişimi yok. **Beklenti değişimi: 0.**

### 8. `research-recovery-joint-estimate` (taban `fb0f80d6`, dal HEAD `2b2da0dc`)

Mevcut test değişimi: doctest `core/tests/test_highlight_recovery.cpp`.

| # | Dosya:satır | Eski | Yeni | Neden | Verdi |
|---|---|---|---|---|---|
| R1 | `test_highlight_recovery.cpp:200` (eski :200) | `TEST_CASE("recovery_two_channel_hue_rg_not_worse_than_neutral_KNOWN_GAP" * doctest::should_fail())` | `TEST_CASE("recovery_two_channel_hue_rg_not_worse_than_neutral")` | `should_fail` işaretinin kaldırılması: KNOWN_GAP ölçümle KAPANDI (hue R/G 0,0105 ≤ Nötr 0,0174) — işaretin kalkma koşulu brifte tanımlıydı; ayrı `test change` commit'i (`fed1f6fc`) | iddia gücü DEĞİŞMEDİ (CHECK gövdesi bit-birebir aynı); işaretin anlamı değişti: eskiden "başarısız olması beklenir", şimdi "geçmelidir" — spesifikleşme (test artık gerçekten geçer olmalı) | GÜVENLİ |

Kanıt (diff'ten, CHECK gövdesi aynı): diff yalnız TEST_CASE satırını ve
açıklama yorumunu değiştirir; `-TEST_CASE("..._KNOWN_GAP" * doctest::should_fail())`
→ `+TEST_CASE("recovery_two_channel_hue_rg_not_worse_than_neutral")`.
Gövdedeki tek satır değişimi yoktur (`grep '^[-+].*CHECK'` boş).

### 9. `feat-export-audio-e2e` (G1; taban `feat-export-loudness-chain` @ `bba728f6`, dal HEAD `9f0a5991`)

Değişen dosyalar: `ExportSettingsTest.kt`, `ExportStoreSettingsTest.kt`,
`ExportSheetStateTest.kt` (pin taşımaları), `Mp4ExporterTest.kt` (yalnız
ekleme), `ExportSettingsExportTest.kt` (yalnız yeni test).

| # | Dosya:satır (dal) | Eski | Yeni | Neden | Verdi |
|---|---|---|---|---|---|
| G1a | `ExportSettingsTest.kt:141` | `startsWith("{\"v\":2,")` | `startsWith("{\"v\":3,")` | wire v3 (audioSampleRateHz/audioChannels); sürüm bump tanımı | GÜVENLİ (eşdeğer) |
| G1b | `ExportSettingsTest.kt:169` | `unsupported(replace("v":2→"v":3))` | `replace("v":3→"v":4)` | too-new pini kaydı | GÜVENLİ (eşdeğer) |
| G1c | `ExportStoreSettingsTest.kt:37` | `contains("\"settings\":{\"v\":2,")` | `contains("\"settings\":{\"v\":3,")` | sürüm bump'ı | GÜVENLİ (eşdeğer) |
| G1d | `ExportStoreSettingsTest.kt:50,54` | `replace v2→v3` + `version 3` | `replace v3→v4` + `version 4` | too-new kaydı | GÜVENLİ (eşdeğer) |
| G1e | `ExportSheetStateTest.kt:147` | `setOf(FRAME_RATE, AUDIO_FORMAT)` | `setOf(FRAME_RATE)` | AUDIO_FORMAT satırı read-only'den çıktı (rate/channel SEÇİMİ oldu — satırın amacı değişti) | GEVŞETME Mİ? — hayır: küme küçüldü AMA iddia konusu da değişti (AUDIO_FORMAT artık seçmeli satır; read-only kaldığı iddiası yanlış olurdu). Küme SHRINK görünümlüdür; **bu kayıt GEVŞETME DEĞİL, amaca uyarlanme** olarak işaretlenir; ayrıntı: küçük kümenin kendisi başka testte (`the_frame_rate_row_is_openable...` G2'de) pozitif iddiayla sabitlenir. **Dikkat isteyen kayıt** (aşağıda "Sahibe gözden geçirilsin" listesinde) | **ŞÜPHELİ GÖRÜNÜMLÜ** (küme 2→1) — gerekçeli |
| G1f | `ExportStoreSettingsTest.kt` (v1 satır testine 2 yeni satır) | — | `assertNull(back.settings!!.audioSampleRateHz)` + `assertNull(...audioChannels)` | MEVCUT `a_queued_job_with_v1_settings...` testine İKİ iddia eklendi | spesifikleşme | GÜVENLİ |

Kanıt (G1e, diff'ten): `-            setOf(ExportSheetState.Row.FRAME_RATE, ExportSheetState.Row.AUDIO_FORMAT),`
→ `+            assertEquals(setOf(ExportSheetState.Row.FRAME_RATE), ro.toSet())`.

### 10. `feat-proxy-production` (G3; taban `feat-proxy-p234-logic` @ `d82b655b`, dal HEAD `8f71ccbd`)

Değişen mevcut test dosyası: `EncodedPreviewSessionTest.kt` (yalnız 3 yeni
test; silinen beklenti satırı yok — `git diff | grep '^-'` boş). Yeni
dosyalar: `ProxyForegroundTest.kt` (iki sınıf: Controller 6 + Engine 2),
`ProxyProductionTest.kt` (QueueDecision 6 + BuildSpecNumbers 5 + BuildEngine
10), `ProxyStorageFeedTest.kt` (5), `ProxyPlaybackGateTest.kt` (7).
`ProxyP234LogicTest.kt` DOKUNULMADI (bağımsız `git diff --name-status`
kanıtı: yalnız M EncodedPreviewSessionTest + 4 A). **Mevcut beklenti
değişimi: 0.**

### 11. `feat-export-fps-e2e` (G2 — CANLI DAL, sabit anlık görüntü)

**NOT:** Bu dal iş sırasında İLERLEDİ. İlk taramada HEAD `8bd2e0de` idi;
belge yazımı sırasında HEAD `b4bb96ca` ("test: pin the 23.976 to 30
pipeline duplication case") olarak okundu. Bu bölüm YALNIZ sabit
`b4bb96ca` anlık görüntüsüne göre yazılmıştır (dalın kendisine hiç
dokunulmadı; `worktree`'si temizdir). Sabah sahibi bu dalı incelerken
HEAD'in değişmiş olabileceğini göz önünde bulundursun.

Değişen mevcut test dosyaları (taban `feat-export-fps-mapping` @ `cd07b8a6`):
`Mp4ExporterTest.kt` (yalnız ekleme), `ExportSheetModelTest.kt` (yalnız
ekleme), `ExportSheetStateTest.kt` (İKİ mevcut test değişti).

| # | Dosya:satır | Eski | Yeni | Neden | Verdi |
|---|---|---|---|---|---|
| E1 | `ExportSheetStateTest.kt:44` (eski :44, test `a_measured_rate_shows_as_the_nearest_standard_one`) | `assertEquals(29.97, displayFps(29.826), 0.0)` (+ 6 benzeri + `fpsText` 2 satır) | test yeniden adlandı: `a_measured_rate_shows_raw_never_snapped_to_a_standard`; `assertEquals(29.83, displayFps(29.826), 0.0)` vb. | K6: sahibin 2026-11-24 kararı — ölçülen hız asla standarta yuvarlanmaz; eski test (bde227d) kaldırılan snap davranışını pinliyordu; commit `f0817697` gövdesi "test change" diye gerekçeyi taşır | davranış değişimi bilinçli (sahip kararı); iddia sayısı eşdeğer, iddia YÖNÜ değişti | GÜVENLİ (gerekçeli) |
| E2 | `ExportSheetStateTest.kt:71` (summaryLine testi) | `startsWith("3840×2160 · 29.97 fps · H.264 8-bit · ")` | `startsWith("3840×2160 · 29.83 fps · H.264 8-bit · ")` | K6'nın özet satırına yansıması | GÜVENLİ |
| E3 | `ExportSheetStateTest.kt:90,96` (compactSummaryLine testi) | `"29.97 fps"` ×2 | `"29.83 fps"` ×2 | K6 | GÜVENLİ |
| E4 | `ExportSheetStateTest.kt:141` (`only_the_clip_derived_rows_are_read_only`) | `setOf(FRAME_RATE, AUDIO_FORMAT)` | `setOf(AUDIO_FORMAT)` | FRAME_RATE satırı fps hedef SEÇİCİSİ oldu (E1f-3, commit `79b9f14c`; commit gövdesi "test change" gerekçesini taşır) | küme 2→1: G1e ile aynı yapıda — amaca uyarlanma; FRAME_RATE'in artık seçmeli olduğu POZİTİF iddiayla ayrıca pinlendi (`the_frame_rate_row_is_openable...`) | **ŞÜPHELİ GÖRÜNÜMLÜ** (gerekçeli; pozitif telafi testi var) |

Not: G2'nin bu dalda wire'a DOKUNMADIĞI bağımsız doğrulandı
(`git diff cd07b8a6..b4bb96ca --name-only` içinde `ExportSettings.kt`
yok; ExportSettingsTest/ExportStoreSettingsTest yok).

### 12. Diğer dallar — beklenti değişimi YOK (bağımsız doğrulama)

Aşağıdaki dallarda `git diff <taban>..<dal> --diff-filter=M -- '*Test*.kt'
'*test*.kt'` ve `core/tests/*` BOŞ veya yalnız-ekleme çıktı; mevcut
beklenti değişimi **0**:

- `feat-audio-dsp-core` (8a69956d): core/tests'te 3 YENİ dosya
  (`test_audio_fx/test_dynamics/test_ducking`); JVM test dosyası yok.
  NOT: `git diff fb0f80d6..feat-audio-dsp-core` çalıştırılırsa
  `test_node_chain_wire.cpp` görünür — bu dalın değişimi DEĞİLDİR;
  main'in kendi geçmişindeki `a1f2d3fc` (node lut page) commit'idir
  (8a69956d..fb0f80d6 arasında). Yanlış taban kullanımına karşı not.
- `feat-cube-export-core` (fb0f80d6): core/tests'te yalnız yeni
  `test_cube_export.cpp`.
- `feat-proxy-p234-logic` (fb0f80d6): yalnız yeni `ProxyP234LogicTest.kt`.
- `test-project-file-resilience` (fb0f80d6): yalnız yeni
  `ProjectFileResilienceTest.kt`.
- `test-strings-parity-lock` (fb0f80d6): yalnız yeni `StringsParityTest.kt`.
- `feat-caption-data-layer` (fb0f80d6): yalnız yeni
  `SrtCodecTest/WebVttCodecTest/AdversarialProbeTest`.
- `feat-hdr10-dv-static-metadata` (taban `feat-hlg-pure-layer`): TAMAMI
  yeni dosya (`HdrStaticMetadataTest`, `DolbyVision84Test`); mevcut
  HLG testlerine dokunulmadı.
- `feat-release-r8-readiness` (fb0f80d6): test dosyası değişimi hiç yok.
- `feat-cube-export-ui` (taban `feat-cube-export-core`): yalnız yeni
  `CubeExportPlanTest.kt`; denetçi düzeltmesi `92391dad` yalnız
  `ClipEditorScreen.kt` (test değişimi 0).

### 13. `integrate-export-wire-chain` (I1; taban `main` @ `fb0f80d6`, dal HEAD `2ebbd0c9`) — ek doğrulama

Bu birleşim dalının kendi test değişimleri (merge'lerin getirdiği asıl dal
değişimlerinin ÜSTÜNDE, `git diff fb0f80d6..integrate-export-wire-chain`):
`ExportSettingsTest`/`ExportStoreSettingsTest` pin'leri v2→v3/v3→v4
taşındı (I1 raporunun kendi listesiyle birebir uyuşuyor; bağımsız diff de
aynı satırları veriyor) + `wire_v2_round_trips_every_ladder_target` →
`wire_v3_...` yeniden adlandırma + iki dalın aynı adlı
`wire_v1_rows_parse_with_the_v1_behavior` testi birleşti (birleşik test
v1 satırını dört anlamda pinler: loudness OFF + fps SOURCE — spesifikleşme)
+ `ExportSheetStateTest` LOUDNESS sırası (D2'den geldi). I1'in listesi
bağımsız doğrulamayla UYUŞUYOR (uyuşmazlık yok).

---

## ŞÜPHELİ KAYITLAR — özet

Tanım (brif): yeni beklenti eskisinden daha az şey iddia ediyorsa şüpheli.

1. **G1e** (`feat-export-audio-e2e`): read-only satır kümesi
   `{FRAME_RATE, AUDIO_FORMAT}` → `{FRAME_RATE}` — küme daraldı. Gerekçe:
   AUDIO_FORMAT satırının AMACI değişti (read-only açıklama → rate/kanal
   seçici). Küme-daralması burada gevşetme değil, kuralın konu değişimine
   uyarlanması; ancak brifin dar tanımı altında KÜME BÜYÜKLÜĞÜ DÜŞTÜĞÜ
   için kayıt şüpheli görünümlü olarak listelenir. Telafi: aynı dalda
   satırın yeni davranışı yeni testlerle pozitif pinlendi.
2. **E4** (`feat-export-fps-e2e`, G2): aynı yapının aynası — read-only
   kümesi `{FRAME_RATE, AUDIO_FORMAT}` → `{AUDIO_FORMAT}`; FRAME_RATE
   seçici oldu. Telafi: `the_frame_rate_row_is_openable_and_the_audio_format_row_stays_read_only`
   testi iki satırı da POZİTİF iddia eder (FRAME_RATE seçilebilir +
   AUDIO_FORMAT read-only) — aslında kural bu iki testle ESKİSİNDEN DAHA
   KATI pinlenmiş durumdadır.

Kanıt satırları yukarıdaki maddelerde alıntılandı. Bu iki kayıt dışında
hiçbir değişim iddia gücünü düşürmemiştir (sürüm-bump pin taşımaları
eşdeğer; SOCIAL/LOUDNESS/MOTIONCAM/no-audio eklentileri spesifikleşme;
`should_fail` kaldırılması iddia gövdesini değiştirmez).

---

## BEYAN-DIFF UYUŞMAZLIKLARI

Her dalın gece raporu beyanı ile bu envanterin bağımsız diff bulgusu
karşılaştırıldı. Uyuşmayan noktalar:

1. **`feat-cinemadng-sequence-core` — Rapor: "Mevcut testlerin hiçbiri
   değişmedi." KISMENİ UYUŞMAZ.** Rapor (NIGHT-REPORT E-D3 bölümü) genel
   olarak "mevcut hiçbir testin beklentisi değişmedi" der; bu DOĞRU (mevcut
   dosya değişimi yok), ANCAK kendi dalında YENİ yazdığı
   `CinemaDngPlanTest.kt`'in dört beklentisi son commit `0d62839a`'da
   değişti (C1-C4; konu bile `tests: fix new plan test expectations`).
   Rapor bu commit'i listeler ama beklenti değişimlerini "test change"
   olarak ayrıca beyan etmez. I2 kapsamı açısından bu bir UYUMSUZLUKTUR:
   diff'te VAR (4 değişim), beyanda YOK (yalnızca commit listesi).
   Ciddiyeti düşük (yeni-dosya-içi, brif kuralını teknik olarak ihlal
   etmez), ama envanterde taşınmalı.
2. **`feat-cinemadng-export` — UYUMSUZLUK YOK, TEYİT.** Raporun "Mevcut
   test beklentilerinin HİÇBİRİ değişmedi (diff yalnız ekleme; ayrı test
   change commit gerekmedi)" cümlesi ana dal bölümünde VARDI (satır 528),
   ama aynı raporun DENETÇİ DÜZELTME alt bölümü iki beklenti değişimini
   (N1/N2) açıkça beyan eder. Ana bölüm cümlesi ile alt bölüm beyanı
   çelişir; diff gerçeği alt bölümü destekler (2 değişim VAR, gerekçeli).
   Sabah okuyucusu ana bölüm cümlesine değil alt bölüme itibar etmeli —
   bu not buraya kaydedildi.
3. **`feat-export-fps-e2e` (G2) — Raporun commit listesi belge yazım
   anındaki HEAD'den GERİDE.** Rapor 5 commit listeler (`ce53fb3a`,
   `8bd2e0de`, `f0817697`, `79b9f14c`, `b4bb96ca`) — bu liste
   `b4bb96ca`'yı DA kapsar (rapor günceldir). Uyuşmazlık YOK; ancak
   canlı dal uyarısı: bu envanterin G2 bölümü `b4bb96ca` sabit
   anlık görüntüsüne göredir. İlk taramada (bu işçi dalı açtığında)
   HEAD `8bd2e0de` idi; belge yazımı sırasında `b4bb96ca`'ya ilerledi.
   Sabah sahibi G2'yi birleştirmeden önce HEAD'i yeniden okumalı.
4. **`feat-proxy-production` (G3) — rapor beyanı UYUŞUYOR.** "Mevcut
   P1/P2 testlerinin 70'i AYNEN geçti... ProxyP234LogicTest'e yalnız
   failures() erişimcisi EKLENDİ" beyanı: `failures()` erişimcisi ANA
   KODA (`ProxyState.kt`) eklendi, test dosyasına değil; bağımsız
   `git diff --name-status` ProxyP234LogicTest.kt'nin DOKUNULMADIĞINI
   gösterir. Beyan ile diff uyumlu (beyan açık yazılmış, yanlış anlaşılabilir
   ama doğru). Uyuşmazlık yok.
5. **`fix-mp4-badge-kind` (M12) — rapor beyanı UYUŞUYOR.** "1 beklenti
   bilinçli değişti (aşağıda beyan)" + ayrıntılı gerekçe + "TEST CHANGE"
   commit konusu: diff'te tek beklenti değişimi M1 olarak aynen doğrulandı.
   Uyuşmazlık yok. (Raporun "3397'si bayt-birebir aynı kaldı" cümlesi
   bu envanterin kapsamı dışında — test sayısı denetimi; diff tarafında
   beyanla çelişki yok.)
6. **`feat-export-loudness-chain` + `feat-export-fps-mapping` — rapor
   beyanları UYUŞUYOR.** Her iki dal da wire pin taşımalarını commit
   gövdesinde/rapor bölümünde beyan etti; bağımsız diff L1-L6 ve F1-F4
   satırlarıyla birebir örtüşüyor. Uyuşmazlık yok.
7. **`research-recovery-joint-estimate` (D8) — rapor beyanı UYUŞUYOR.**
   "should_fail işareti ayrı commit'te kaldırıldı (test change, gerekçeli)"
   beyanı: `fed1f6fc` commit'i + diff R1 kaydıyla doğrulandı. Uyuşmazlık yok.
8. **`feat-hlg-pure-layer` (D6) — rapor beyanı UYUŞUYOR.** "Mevcut
   beklentiler aynı, yalnız yeni test eklendi" beyanı: diff'te silinen
   satır 0. Uyuşmazlık yok.
9. **`integrate-export-wire-chain` (I1) — kendi I2 girdi listesi UYUŞUYOR**
   (13. madde). Bağımsız diff, I1'in listesindeki her satırı doğruladı;
   ek olarak I1 listesi "ExportSheetStateTest:122 | ...LOUDNESS..." için
   `test change olarak commit gövdesinde de beyanlı` der — bu beyan
   loudness UI commit'ine aittir, doğru.

**Uyuşmazlık sayısı: 1 gerçek (D3 — yeni-dosya beklenti düzeltmesinin
"test change" olarak beyan edilmemesi) + 1 rapor-içi çelişki (F dalının
ana bölüm cümlesi ile alt bölüm beyanı) + 1 canlı-dal duruş bildirimi (G2).**

---

## BÖLÜM 2 (I3) — Commit konusu uzunluk envanteri

**Hatırlatma (sahibin own words): konu uzunluğu "çok önemli değil".**
Aşağıdaki tablo 50 karakteri aşan konuları listeler; YENİDEN YAZIM
ÖNERİSİDİR, hiçbir dalın geçmişine dokunulmadı. Sabah toplu rebase kararı
sahibindir. Önerilen kısa konular anlamı koruyacak biçimde türetilmiştir;
`prefix: kısa ifade` deseni korunur.

### Tablo — konu >50 karakter

| Dal | Commit | Konu | Uzunluk | Önerilen ≤50 kısa konu |
|---|---|---|---|---|
| feat-export-loudness-chain | `ff014f51` | core: e5 loudness normalize chain (measure-gain-clip-verify) | 60 | `core: e5 loudness normalize chain` |
| fix-mp4-badge-kind | `69bd8d83` | fix: classify single-file imports from the file name | 52 | `fix: classify imports from file name` |
| perf-temporal-nr-cpu | `b0411d21` | tools: temporal merge frame bench with serial/parallel medians | 62 | `tools: temporal merge frame bench` |
| perf-temporal-nr-cpu | `cb0df95e` | refactor: split the merge loop out for ab bit-identity tests | 60 | `refactor: split merge loop for ab tests` |
| perf-temporal-nr-cpu | `55a74ed5` | perf: cache the per-column obmc candidates in the merge loop | 60 | `perf: cache per-column obmc candidates` |
| perf-temporal-nr-cpu | `18d81044` | perf: fold noise profile loads out of the merge loop | 52 | `perf: fold noise profile loads out` |
| research-recovery-joint-estimate | `2b2da0dc` | twin the joint estimate into the recovery composite glsl | 56 | `twin joint estimate into recovery glsl` |
| research-recovery-joint-estimate | `3b06c83d` | extend hr_joint_estimate with band stats and variant sweep | 58 | `extend hr_joint_estimate band stats sweep` |
| feat-release-r8-readiness | `56a9e293` | enable r8 minify for release with jni and wire-enum keep rules | 62 | `enable r8 minify with keep rules` |
| feat-release-r8-readiness | `f601fe53` | add release readiness report (r8, lint, debug sweep, licenses) | 62 | `add release readiness report` |
| feat-cinemadng-export | `1dbc96b7` | export: cinema dng sequence exporter and job queue wiring | 57 | `export: cinema dng exporter wiring` |
| feat-proxy-production | `45ea1b06` | feat: proxy production layer with fake-encoder tests | 52 | `feat: proxy production layer` |
| feat-proxy-production | `fa22befa` | feat: proxy runtime with media codec encoder adapter | 52 | `feat: proxy media codec runtime` |
| feat-proxy-production | `17f03c80` | feat: proxy storage page feed and settings section | 50 | (50 — sınırda, aşıyor DEĞİL; yalnız not) |
| feat-proxy-production | `2fcbad22` | feat: wire proxy runtime behind the production flag | 51 | `feat: wire proxy behind the flag` |
| feat-proxy-production | `e62063d8` | feat: proxy playback gate wired to the editor session | 53 | `feat: proxy playback gate wiring` |
| feat-proxy-production | `e4c195f1` | chore: drop an unused import in the proxy storage section | 57 | `chore: drop an unused proxy import` |
| feat-proxy-production | `f9bf1e87` | fix: play the originals audio when the session opened a proxy | 61 | `fix: play original audio from proxy` |
| feat-proxy-production | `8f71ccbd` | fix: correct the proxyOpenFd empty-cache rationale comment | 58 | `fix: correct proxyOpenFd comment` |
| feat-cube-export-ui | `92391dad` | fix: carry encoded highlights shadows into the cube | 51 | `fix: encoded highlights shadows in cube` |

Not: `17f03c80` tam 50 karakterdedir (>50 DEĞİLDİR) — tabloda yalnız
sınır bilgisi olarak durur. `feat-export-fps-e2e`'nin canlı HEAD anlık
görüntüsündeki (`b4bb96ca`) üç commit'ten ikisi sınırı aşar; üçü de
aşağıdaki ek tablodadır.

### Ekleme — `feat-export-fps-e2e` (canlı dal, `b4bb96ca` anlık görüntüsü)

| Dal | Commit | Konu | Uzunluk | Önerilen |
|---|---|---|---|---|
| feat-export-fps-e2e | `79b9f14c` | ui: make the export frame rate row the fps target picker | 56 | `ui: frame rate row becomes fps picker` |
| feat-export-fps-e2e | `b4bb96ca` | test: pin the 23.976 to 30 pipeline duplication case | 52 | `test: pin 23.976 to 30 duplication` |

(`f0817697` — `show the measured frame rate raw, never snapped` — 47
karakter, sınırın altında; `8bd2e0de` 40, `ce53fb3a` 44.)

### Uzun konu dağılımı (özet, komut çıktısından)

- Toplam gece dalı commit'i tarandı: 20 dal (I3 brif listesi) — commit
  konularının tamamı `git log <taban>..<dal> --format='%h|%s'` ile
  ölçüldü (uzunluk `awk length()` ile).
- >50 karakter: **21 konu** (asıl tabloda 17 + G2 anlık görüntüsünden 2
  [`79b9f14c`, `b4bb96ca`] + `69bd8d83`; `17f03c80` tam 50, not olarak).
- En uzunlar: 62 (`b0411d21`, `56a9e293`, `f601fe53`), 61 (`f9bf1e87`),
  60 (`ff014f51`, `cb0df95e`, `55a74ed5`), 56-58 arası 6 konu, 51-53
  arası 5 konu.
- 50 karakterin altındaki dallar (temiz): feat-audio-dsp-core (4/4),
  feat-hlg-pure-layer (3/3), feat-cube-export-core (2/2),
  feat-proxy-p234-logic (1/1), test-project-file-resilience (2/2),
  test-strings-parity-lock (1/1), feat-export-fps-mapping (3/3),
  feat-caption-data-layer (4/4), feat-hdr10-dv-static-metadata (2/2),
  feat-export-audio-e2e (3/3).

---

## Sahibe gözden geçirilme listesi (sabah)

1. **Şüpheli görünümlü 2 kayıt** (G1e, E4): read-only satır kümelerinin
   daralması — ikisi de satır-amacı değişimi, ikisinde de pozitif telafi
   testi var. Kural olarak gevşetme sayılmaları mı, sayılmamaları mı
   istenir kararı sahibin.
2. **D3 beyan boşluğu**: `CinemaDngPlanTest` beklenti düzeltmeleri
   (`0d62839a`) "test change" olarak beyan edilmemişti — sabah rebase
   sırasında bu commit'in konusu zaten açıklıyor; ek işlem önerilmez,
   bilgi notudur.
3. **G2 canlı dal**: birleştirme öncesi HEAD'i yeniden okuyun (bu belge
   `b4bb96ca` anlık görüntüsüdür).
4. **I3 tablosu**: toplu rebase istenirse yukarıdaki öneriler hazırdır;
   hiçbir dal geçmişine bu iş sırasında dokunulmadı.
