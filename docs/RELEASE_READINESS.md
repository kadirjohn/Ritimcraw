# Release readiness — E5 (dal `feat-release-r8-readiness`)

Tarih: 2026-11-27 (gece ajanı, E-brief E5). Taban: `main` @ `fb0f80d6`.
Bu dosya bu dalın ürünüdür: R8 dalının kanıtları + lint/debug-sweep/lisans
denetimlerinin RAPORU. Ana dalın yapılandırması (minify, sürüm numaraları)
BU DOSYANIN ÖNERİLERİDİR; main'e almak sahibin kararıdır.

---

## 1. R8 dalı (kapı: yeşil)

**Ne değişti** (yalnız bu dal; `git log main..feat-release-r8-readiness`):

1. `56a9e293` — `app/build.gradle.kts`: release buildType
   `isMinifyEnabled = false → true` + `isShrinkResources = true`;
   `app/proguard-rules.pro`: gerçek kurallar (main'de dosya boş şablondur).
   Kaynak koda (Kotlin/C++ uygulama kodu) DOKUNULMADI.

**Kapılar** (komutlar brifteki gibidir; exit kodları kayıtlı):

| Kapı | Komut | Exit | Not |
|---|---|---|---|
| Debug + JVM öncesi | `:app:assembleDebug :app:testDebugUnitTest --rerun` | 0 | 3398 test, 0 failure+error, 0 skipped (XML toplamı, `app/build/test-results/testDebugUnitTest/*.xml`) |
| Release öncesi (minify KAPALI) | `:app:assembleRelease` | 0 | taban ölçümü |
| Release (R8 AÇIK) | `:app:assembleRelease` | 0 | `BUILD SUCCESSFUL in 58s`, R8 uyarısı YOK |
| Debug + JVM sonrası | `:app:assembleDebug :app:testDebugUnitTest --rerun` | 0 | 3398 test, 0 failure+error, 0 skipped (aynı toplam; önce/sonra iki ayrı koşu) |

Test sayıları yalnız XML toplamından (Brief D dersi 1). core/'a
dokunulmadı → `tools/build-desktop.sh` gerekmedi.

**APK boyutu** (`ls -l`, bayt):

| Derleme | Boyut (bayt) |
|---|---|
| release, minify kapalı (main'in bugünkü release'i) | 24 512 663 |
| release, R8 açık (bu dal) | 5 876 670 |
| Değişim | −18 635 993 (−76,0 %) |
| debug (referans) | 32 512 849 |

**Proguard kural aileleri** (gerekçe dosyada satır satır):

1. **JNI.** `NativeSource` sınıfının 73 `external fun`'i JNI sembol
   adlarıyla çözülür (`Java_com_ritim_ritimcraw_engine_NativeSource_*`,
   `jni_bridge.cpp` 69 + `jni_encoded_yuv.cpp` 3; Kotlin tarafındaki 73
   bildirimin 73'ü sembolle eşleşiyor — iki taraf da grep'le
   sayıldı). C++ tarafı `FindClass` + `GetMethodID("<init>")` ile üç
   sınıf kurar: `android/graphics/Rect` (framework), `FrameInfoK`
   (jni_bridge.cpp:2300, imza `(JIIIILandroid/graphics/Rect;I)V`),
   `ContainerIdentityK` (jni_bridge.cpp:3175, imza
   `(Ljava/lang/String;...;DD)V`). Ayrıca
   `nativePreviewSessionSetDrtReadyListener` bir Kotlin `Runnable`'ın
   `run()`'ını `GetMethodID` ile çağırır (jni_bridge.cpp:5458) —
   `Runnable.run()` keep. Manifest sınıfları (`ExportForegroundService`,
   `ExportApp`) keep.
2. **Wire enum'ları.** Kod tabanında enum'lar wire'a İSİMLE yazılır
   (`.name` / `valueOf` / `entries.firstOrNull { it.name == }`). R8
   obfuscation sabit adlarını değiştirirse ESKİ PROJE DOSYALARI
   Sessizce bozulur (parse ret / varsayılan düşüş — crash yok, veri
   kaybı davranışı var). Keep edilenler: `ExportCodec`,
   `ExportDynamicRange`, `ExportRateMode`, `BitrateLevel`,
   `KeyframeInterval` (ExportSettingsWire); `Target`, `State`,
   `RenderBackend`, `AudioLinkState` (ExportStore satırları);
   `SourceKind`, `Access` (SourceRepository); `Diagnosis`,
   `LogDiagnosis`, `Capability` (MetadataRecord); `TrackKind`
   (TimelineStore); `ThemeMode`; `PreviewQuality`; `ProxySourceKind`
   (proxy dosya-adı imza hash'inin içinde — ad değişirse TÜM eski
   proxy'ler bayatlanıp yeniden encode olur, en pahalı sessiz kırılma);
   `SourceMatrix`/`SourceTransfer`/`SourcePrimaries` (EncodedColorSpec
   tanı satırları); ayrıca `com.ritim.ritimcraw.**` genel enum kemeri
   (`values()`/`valueOf()`/`<fields>`).
   NOT: `LutGamut`/`LutTransfer`/`LutRole` wire'a İSİMLE değil
   `value` İNT'iyle yazılır (`LutInterpK.render`) — keep gerekmez,
   genel kemer yine de dokunmaz (ordinal değişmez; R8 enum
   obfuscation'u alan ADLARINI değiştirir, değerleri değil).
3. **Reflection.** Tek gerçek `Class.forName` brifteki gibi
   `GradeParitySweep.dumpCropDiag`'da (satır 626;
   `android.app.ActivityThread.currentApplication`) — debug-gated ama
   release binary'de derleniyor; `ActivityThread` keep.
4. Crash raporu okunurluğu: `-keepattributes SourceFile,LineNumberTable`
   + `-renamesourcefileattribute SourceFile`.

**R8 çıktı denetimi** (`app/build/outputs/mapping/release/`):
- `seeds.txt`: NativeSource (tüm üyeleri), FrameInfoK,
  ContainerIdentityK keep altında.
- `mapping.txt`: `NativeSource -> NativeSource` (ad KORUNDU; lambda'lar
  obfuscate). `FrameInfoK -> FrameInfoK` + `<init>(long,int,int,int,int,Rect,int)`
  imzası aynen korundu. `ContainerIdentityK` + imza korundu.
  `ExportCodec AVC -> AVC, HEVC -> HEVC` — enum sabit adları korundu.
  `ThemeMode CUSTOM/DARK/LIGHT/SYSTEM -> aynen`.
- `usage.txt` (çıkarılanlar — hepsi BEKLENEN): `RenderTrace`,
  `RenderTrace$Record` (debug-gated, release'de tüm çağrı yerleri
  const-false → ölü kod); `DisplayRate`/`DisplayRateApply`
  (`ENABLED_BY_DEFAULT = false` + enable flag'i BuildConfig.DEBUG
  bağlı → const-false); bağlanmamış `proxy.*` sınıflarının 32 tanesi
  (prodüksiyon kodu hiçbirini çağırmıyor; yalnız JVM testleri);
  580 ritim sınıfı/üyesi toplam çıkarıldı (debug araçları, kullanılmayan
  kopyalar).
- Kritik sınıfların hiçbiri çıkarılmadı: `MainActivity`, `ExportApp`,
  `ExportForegroundService`, `Mp4Exporter`, `TimelineMp4Exporter`,
  `MediaCodecMp4Writer`, `ExportQueue`, `PreviewRenderer`,
  `GpuPreviewSession`, `GradeParitySweep` (derlendi, runtime'da
  `enabled=false` — release'de hiçbir sweep koşmaz), `PreviewMetrics`,
  `ExportAbSweep`, `CodecMeasurer`, `PlaybackTrace`, `PanelAnimTrace`,
  `ScrubDragTrace`, `RecoveryBench`, `SystemStatsSampler`.

**İmza**: eklenmedi (brif kuralı). APK `app-release-unsigned.apk`;
kurulum sahibin sabah işi (imza uyuşmazlığı — §6).

**DOĞRULANMAYAN** (R8 sonrası gerçek davranış — yalnız derleme+test
kanıtı var, cihaz yok): release APK'nın cihazda açılışı, klip açma
(JNI yolu), preview, export, eski proje dosyası okuma, proxy eşleşme.
R8'in davranışsal riski şaşırtmaca olmayan türdendir (ad çözümleme +
ölü kod) ve yukarıdaki mapping denetimi her keep hedefini doğruladı,
ama CIHAZ DAVRANIŞI DOĞRULANMADI.

---

## 2. Lint raporu — ÇEVRİMDIŞI TIKANDI (rapor, kod değişikliği yok)

`./gradlew --offline lint > /tmp/gate-er-lint.log 2>&1` → **exit 1**,
`lintAnalyzeDebug` başarısız:

```
Could not resolve all files for configuration ':app:detachedConfiguration1'.
  Could not download lint-gradle-31.13.2.jar (com.android.tools.lint:lint-gradle:31.13.2)
  Could not download lint-31.13.2.jar, lint-checks-31.13.2.jar, lint-api-31.13.2.jar
  Could not download intellij-core-31.13.2.jar, kotlin-compiler-31.13.2.jar,
    uast-31.13.2.jar (com.android.tools.external.*)
  Could not download kotlin-reflect-2.2.0.jar, kotlin-stdlib-2.2.0.jar
  Could not download play-sdk-proto-31.13.2.jar
  → "No cached version available for offline mode"
```

**Kök neden** ( bytecode analiziyle kanıtlandı):
`LintFromMaven.from` (AGP 8.13.2, `gradle-8.13.2.jar`) lint sürümünü
`android.experimental.lint.version` özelliğinden okur
(`AndroidLintInputs.getLintMavenArtifactVersion`): override değeri bir
**AGP sürümü** olarak parse edilir ve lint sürümüne `major+23` ile
çevrilir (AGP 8.x → lint 31.x), VE override (AGP sürümü olarak) ≥
geçerli AGP olmalı ("Lint must be at least version 8.13.2").
Gradle önbelleğinde lint zincirinin TAM olanı yalnız **31.10.1**
(AGP 8.10.1'den kalma) ve 31.7.3; ikisi de 8.13.2 eşiğinin altında.
Yani AGP 8.13.2 ile çevrimdışı lint koşmak **imkânsız**, sürüm
geçersiz kılma dahil (denendi: `-Pandroid.experimental.lint.version=
31.10.1` → `lint-gradle:54.10.1` istedi (31+23), `-P...=8.10.1` →
"Lint must be at least version 8.13.2" hatası; bytecode'da
`bipush 23` + `compareTo < 0 → reportError` kanıtı).
`app/build.gradle.kts`'teki mevcut `lint { checkReleaseBuilds = false }`
kuralının açıklaması zaten bunu söylüyordu ("release lint-vital needs
lint jars that are not in the Gradle cache").

**Karar: kod/derleme yapılandırması değiştirilmedi, lint için
workaround uydurulmadı.** Çünkü: (a) brif lint'i RAPOR-ONLY istiyor,
kaynak kodu değiştirme; (b) tek geçerli yol ağ bağlantısı
(lint-31.13.2 zinciri indirmek) veya AGP düşürmek — AGP düşürmek
main'in yapılandırma kararı = sahibin kararı; (c) Studio'nun
paketlediği `sdk-tools.jar` lint motorunu içerir ama CLI girişi
yoktur, elle koşmak kırılgan bir uydurma harness olurdu.

**Sahibin sabah seçenekleri** (bir tanesi yeter):
1. Bir kez çevrimİÇİ: `./gradlew lint` (lint-31.13.2 zinciri iner,
   bir daha çevrimdışı çalışır) — sonra `/tmp` yerine
   `app/build/reports/lint-results-debug.html` okunur.
2. Android Studio: **Code > Inspect Code** (IDE lint'i paketlidir).
3. AGP 8.13.2 → 8.10.1 düşürmek: ÖNERİLMEZ (main'in derleme
   yapılandırması + 8.13 ile gelinen derleme davranışları).

**Statik telafi taraması** (lint'in kapamadığı yerde, kaynak taraması —
bunlar RAPORDUR, kod değişmedi):

| Alan | Bulgu |
|---|---|
| Eksik izin | Bulunmadı. Manifest: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` (service `dataSync`, targetSdk 36'da zorunlu — doğru), `POST_NOTIFICATIONS`. SAF ile erişim (kalıcı URI izinleri) kullanılıyor; `READ_EXTERNAL_STORAGE`/`WRITE_EXTERNAL_STORAGE` YOK — bilinçli (SAF-only). Runtime istekleri: POST_NOTIFICATIONS UI katmanında sorulmalı (kod: `ExportForegroundService` notification'ı izin yoksa sessiz — davranış zararsız). |
| Yanlış API seviyesi | `minSdk=34`, `targetSdk=36`. `Build.VERSION.SDK_INT` kapıları: Theme.kt:42 (`S`), DisplayRate.kt (30/31 — hepsi minSdk 34 üstünde, güvenli). `HevcPreflight` API seviyelerini VERİ olarak taşır (cihaz raporu), kapı değil. Hepsı minSdk 34'le uyumlu; API 36'ya özgü çağrı `SDK_INT` kapısız tespit edilmedi. |
| Crash riski (lint'in tipik ailesinden) | Elle taramada yüksek riskli desen bulunmadı: JNI hata yolları `NativeResult`/null döner, `System.loadLibrary` catch'li (`UnsatisfiedLinkError` → `nativeAvailable=false`, uygulama JNI'siz açılır), `runCatching` kapsamları kalıcı. NOT: bu bir lint'in yerini tutan DERİN denetim değildir; gerçek lint raporu sabah beklenir. |
| `android:allowBackup="true"` | Varsayılan; `dataExtractionRules`/`fullBackupContent` tanımlı (lint genelde INFO verir). Release öncesi sahibin kararı olabilir (yedekleme kapsamı: project.json + export dosyaları). |
| Debug ölçüm ekranları release'de | Bkz §3 — hepsi kapalı. |

---

## 3. Debug-yolu süpürmesi + FeatureFlags tablosu (RAPOR, kod değişmedi)

`BuildConfig.DEBUG` ve `FeatureFlags` üzerinden tarandı (grep: 40+ nokta).
**Kritik açık debug yolu BULUNMADI — hepsi kapı arkasında.** Tablo:

| Bayrak / kapı | Varsayılan | Ne yapıyor | Release'te görünür mü? |
|---|---|---|---|
| `FeatureFlags.GYRO_STABILIZATION_ENABLED` | `false` | Gyro stabilize motoru karanlık; Stabilize sekmesi "MotionCam güncellemesiyle gelecek" bildirimi gösterir | HAYIR (compile-time const false — Stabilize sekmesi bildirim modunda) |
| `FeatureFlags.IMAGE_STABILIZATION_ENABLED` | `false` | Görüntü-tabanlı stabilize + analiz servisi karanlık; `engineFor` hiç IMAGE döndürmez | HAYIR |
| `BuildConfig.DEBUG` (Settings ölçüm bloğu) | debug=true | Measure (PreviewMetrics), Grade sweep, Export A/B, Codec measure düğmeleri + preview-stats satırı Settings'te | HAYIR — release `onMeasure/onGradeSweep/onExportAb/onCodecMeasure` null geçer; düğme/`Advanced` bloğu hiç kompoze olmaz (SettingsScreen:402 `if (BuildConfig.DEBUG && onMeasure != null)`) |
| `PreviewMetrics.enabled` | `= BuildConfig.DEBUG` | fps/latency harness + GLES probu; logcat "ritim-pm" | HAYIR (çift kapı: UI null + runtime false) |
| `GradeParitySweep.enabled` | `= BuildConfig.DEBUG` | GPU↔CPU per-control + crop + v2 zincir sweep'i; "ritim-parity" | HAYIR |
| `ExportAbSweep.enabled` | `= BuildConfig.DEBUG` | Export CPU↔GPU A/B; "ritim-parity" | HAYIR |
| `CodecMeasurer.enabled` | `= BuildConfig.DEBUG` | Gerçek kısa encode'larla codec ölçümü (E1e); store `files/codec-measure` | HAYIR |
| `RenderTrace.enabled` | `= BuildConfig.DEBUG` | Editor render başına "ritim-trace" satırı | HAYIR — R8 release'de sınıfı tamamen attı (ölü kod) |
| `PlaybackTrace` + kancaları | `BuildConfig.DEBUG` guard'lı | Oynatma p50/p95 özeti | HAYIR |
| `PanelAnimTrace` | çağrı yerleri `RenderTrace.enabled` okur | Panel animasyonu render/resize sayımı | HAYIR |
| `ScrubDragTrace` | çağrı yerleri `RenderTrace.enabled` + scrubFrames ayarı okur | Scrub-cache isabet özeti | HAYIR |
| `RecoveryBench` | marker dosya `files/recbench` + sweepKick (debug-only artar) | Recovery/neutral stage-3 kıyaslama; "ritim-trace rectime" | HAYIR (sweepKick yalnız debug Settings'ten artar; 0 kalır → LaunchedEffect hiç koşmaz) |
| `nativeSetStabDebugLog` | `BuildConfig.DEBUG`'de arm | "ritim-stab" logcat | HAYIR (companion init yalnız debug'da çağırır; native default off) |
| `nativeSetExportTiming` | `BuildConfig.DEBUG`'de arm | Export alt-aşama süre slotları | HAYIR |
| `nativeSetPreviewWindowProbe` | `BuildConfig.DEBUG`'de arm | Beyaz-pencere sınıfı readback | HAYIR |
| `previewStats` (Settings satırı + `jniSetPreviewStatsEnabled`) | satır debug-only; native default ON | GPU scope readback A/B | HAYIR — release satırı göstermez, apply-on-start `if (BuildConfig.DEBUG)` (AppNav:566); native default (on) = öncesi davranış |
| `DisplayRateApply` (`debug-display-rate` / `debug-no-display-rate` dosyaları) | `ENABLED_BY_DEFAULT = false` | Oynatmada ekran tazeleme hızı eşleme | HAYIR — sabitler const-false zinciri; R8 sınıfı attı. Dosya bayrakları da `BuildConfig.DEBUG`'ye bağlı |
| `debug.ritim.rectime` / `debug.ritim.recnoexit` (setprop) | native taraf, kapalı | GPU recovery geçişi glFinish süreleri / erken çıkış kapatma | Yalnız sahibin `adb shell setprop debug.ritim.*` ile AÇILIR — release'te de çalışır ama kullanıcı erişemez (setprop root/debug gerektirir); davranış değişikliği ölçüm amaçlı. Rapor notu: release'te zararsız sayılır; istenirse native'de `#ifdef NDEBUG` eklenebilir (yeni iş, sahibin kararı) |
| `DeviceReportScreen` (codec listesi + codec-measure raporu kopyalama) | Settings satırı HER ZAMAN görünür | Cihaz codec raporu + (debug'da) ölçüm tablosu | KISMEN — ekran release'te AÇILIR; codec LISTESI (salt okunur prob) ürün özelliği olarak kalmış. Codec-measure BÖLÜMÜ yalnız bir ölçüm varsa render olur; release'de ölçüm koşamaz, diskte eski rapor varsa gösterir. SAHİBE SOR: Device report ekranı release'te görünsün mü? (ürün kararı; mevcut tasarım bilinçli görünür) |
| `SystemMonitorBar` (GPU/CPU/RAM/storage şeridi) | `SystemMonitorStore`, default GÖRÜNÜR | Ayarlardan kapatılabilir sahibin aracı | EVET — bilinçli (sahibin isteği 2026-11-23, ayarla gizlenebilir). SAHİBE SOR: release'te default açık mı kalsın? |

**Ek bulgu (release öncesi sahibin kararı — madde az üstte):**
- **Settings "Sürüm %1$s" satırı** `SettingsScreen(versionName: String = "1.0")`
  varsayılanıyla çalışıyor; AppNav/MainActivity HİÇBİR yerden versionName
  GEÇMİYOR (grep: tek çağrı `AppNav.kt:2520`'de parametre yok). Yani
  `versionName` gradle'dan okunmuyor: sürüm satırı HER ZAMAN "1.0"
  gösterir, gradle'da `1.5` yazsa bile. Kod değişikliği gerekli
  (`BuildConfig.VERSION_NAME` bağlamak, 1 satır) — main'e öneri,
  bu dalda yapılmadı (kaynak kodu dokunma kuralı).

---

## 4. Lisans/notice denetimi (RAPOR, değişiklik yok)

**Kaynaklar**: `NOTICE` (76 satır), `third_party/MANIFEST.md`,
`third_party/` ağacı, derleme yapılandırması (`core/CMakeLists.txt`,
`app/build.gradle.kts`).

**Eşleşenler** (vendor + notice doğru):
- motioncam-decoder (Apache-2.0, commit b931ec0) — NOTICE #1 ✓
- doctest 2.4.12 (MIT) — #2 ✓
- vqf (MIT, 86ba56b) — #3 ✓
- xiaomi-milog (4 dosya, sha256'li) — #4 ✓
- OpenDRT v1.1.0 (GPLv3, port-not-vendored, K6 kararı) — #5 ✓
- filmcraft audio-dsp (MIT OR Apache-2.0, port) — #6 ✓
- DisplayRate: clean-room notu ✓

**EKSİKLER / SAHİBE SOR** (düzeltme YAPILMADI — sahibin kararı):

1. **motioncam-decoder'ın kendi thirdparty başlıkları.**
   `third_party/motioncam-decoder/thirdparty/` içinde
   `nlohmann/json.hpp` (MIT, v3.11.3), `tinydng/tiny_dng_writer.h`
   (MIT, Syoyo Fujita), `audiofile/AudioFile.h` (MIT, Adam Stark),
   `simde/**` (MIT) vendor'lu ve DERLENİYOR (`core/CMakeLists.txt:21`
   include yolu `thirdparty/` — Decoder.cpp bu başlıkları çekiyor).
   NOTICE ve MANIFEST bunları motioncam GİRİSİ ALTINDA saymaz; her biri
   ayrı MIT telif sahibiyle ayrı bileşendir. Öneri: NOTICE'a
   "vendored within third_party/motioncam-decoder/thirdparty:
   nlohmann-json 3.11.3 (MIT), tinydngwriter (MIT), AudioFile (MIT),
   simde (MIT) — license texts in each header" maddesi.
2. **Inter (SIL OFL) izi.** `RitimIcons.kt:244` `AutoThemeA` vektörünün
   konturu "Inter Medium (SIL Open Font License)" diye kayıtlı; NOTICE'te
   Inter/OFL maddesi YOK. Tek harf konturu (path data) — OFL v1.1
   redistribute koşulları teknik olarak notice ister. Öneri: NOTICE'a
   tek satır (Inter, SIL OFL 1.1, kılavuz harf konturu).
3. **Material Symbols path data.** 17 drawable dosyası "OFFICIAL
   Material Symbols glyph ... verbatim from Google Fonts" diye
   işaretli (Rounded/Outlined, wght300/default). Google Fonts ikon
   lisansı (Apache-2.0) NOTICE'te yok. Öneri: NOTICE'a tek satır.
   (MANIFEST'in "Still missing" tablosu zaten Material Symbols'ı
   bekleyen maddesi olarak taşıyor.)
4. **NOTICE başlığı hâlâ şablon**: satır 2
   `Copyright [yyyy] [name of copyright owner]` — doldurulmamış.
   Sahibin adı/yıl yazılmalı (sahibin kararı).
5. **Uygulama içi "Licenses" listesi YOK** — kod ve string'lerde
   `License` ekranı/arayüzü bulunamadı. Apache-2.0 + MIT bileşenler
   için zorunlu değil ama Play'de yayımlanacaksa OSS lisanslar
   ekranı/Play "open source" beyanı önerilir (sahibin kararı;
   otomatik üretim için `com.android.tools.build`'ın bağımlılık
   listesinden üretilebilir — çevrimdışı yeni bağımlılık YASAK).
6. `BuildConfig`/Compose/AndroidX telif/bildirimleri AAR'larla
   gelir; NOTICE'te ayrıca istenmiyor — eşleşiyor sayıldı.

---

## 5. versionCode / versionName önerisi

**Mevcut** (`app/build.gradle.kts:17-18`): `versionCode = 1`,
`versionName = "1.0"`. Hiç yayınlanmamış (Play yok, kendi cihazına
debug kurulum). `main` üzerinde 971 commit; ürün çalışır durumda
(library, editor, export MP4/DNG/photo/linear, timeline, node'lar,
GPU preview), ama cihazda release kabulü yapılmadı (bkz S12 kabul
belgeleri) ve ilk release APK hiç kurulmadı.

**Öneri**:
- `versionCode = 2` (ilk release denemesi; her release denemesi +1 —
  küçük, monoton; `versionCode` cihazda imza uyuşmazlığı riski yüzünden
  ilk KURULUMDA yine 1 olabilir, ama release APK'yı yeniden derleyip
  kurma senaryosu için başlangıç değerini 2 yapmak temiz).
- `versionName = "1.0-rc1"`: v1 tanımı plan §0a.4 Dalga A+B. Dalga B'nin
  cihaz kabulü (S12) tamamlanmadığından "1.0" iddiası erken; rc1
  etiketi sahibin elinde. Alternatif: kabullenmeyi bitirip "1.0" —
  sahibin kararı.
- NOT: Settings ekranındaki sürüm satırı gradle'ı OKUMUYOR (§3'deki
  bulgu) — versionName'i bump'lasan bile ekranda "1.0" görünür; bu
  1 satırlık kod bağlaması (main'e öneri) yapılmadan sürüm artışı
  kullanıcıya görünmez.
- Sürüm/artış biçimi (semver mu, build sayısı mu) ürün kararı — bu
  öneri varsayılan ve geri alınabilir.

---

## 6. Uygulama durumu + sabah cihaz kontrol listesi

**Uygulama durumu** (gece ajanının statik okuması — cihaz kanıtı yok):
Derleme olarak sağlam: debug + release (R8'li) derleniyor, 3398 JVM
testi yeşil, JNI/keep hedefleri mapping çıktısında doğrulandı. Ana
riskler cihazda yatar: R8 sonrası runtime davranışı, GPU preview
paritesi, export yolları. Kod tarafında bilinen açık maddeler: lint
raporu eksik (çevrimdışı), Settings sürüm satırı sabit "1.0",
DeviceReportScreen/SystemMonitorBar'ın release görünürlüğü ürün kararı
bekliyor.

**Sahibin sabah cihaz listesi** (imza uyuşmazlığı riski: gece ajanının
ürettiği release APK, cihazdaki debug-imzalı kurulumla AYNI imzada
OLMAZ — `app-release-unsigned.apk` imzasızdır; release'İ KURMADAN
ÖNCE debug kurulumunu kaldırmak gerekir ya da release'ı debug keystore
ile imzalayın; veri kaybı: app verisi (project.json, kopyalanan LUT'lar,
proxy'ler) kaldırmayla GİDER — önce yedek):

1. (Bilgisayarda, bir kez çevrimiçi) `./gradlew lint` — eksik lint-31.13.2
   zinciri iner; `app/build/reports/` raporunu oku.
2. Release APK'yı imzala (debug keystore yeterli: build.gradle'e
   `signingConfig` eklemek sahibin kararı) VEYA
   `adb install-multiple`/`apksigner` ile imzala.
3. Debug kurulumunu kaldır, release'ı kur.
4. Smoke: klip aç (JNI + FrameInfoK yolu), preview (GPU + CPU),
   scope'lar, bir MP4 export, bir DNG export, Settings aç/kapa,
   eski proje dosyasının açılması (wire enum keep doğrulaması),
   proxy varsa eşleşme (imza hash değişmemeli).
5. `adb logcat -s AndroidRuntime` — release crash izlemesi
   (LineNumberTable keep sayesinde okunabilir satır numaraları).
6. release'te Settings'te Measure/sweep düğmelerinin OLMADIĞINI,
   Device report ekranının görünürlüğünü, SystemMonitorBar'ın default
   açık kaldığını kontrol et → ürün kararları.
7. Uzun kulak: 30+ dk'lık bir export'un arka planda bitmesi
   (foreground service davranışı release'te de aynı olmalı).

**Bu gece yapılmayanlar (kural gereği)**: merge YOK, push YOK, imza
anahtarı EKLEMEDİ, main'e DOKUNULMADI, kaynak kod değişikliği YOK
(yalnız build.gradle + proguard + bu doküman), telefon/adb YOK.
