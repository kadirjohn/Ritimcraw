# Görüntü tabanlı (gyro'suz) stabilizasyon — tasarım planı (Paket S0)

Durum: SADECE TASARIM. Bu pakette üretim kodu yok. Tarih: 2026-11-23. Dal: `s0-image-stab-design` (`f0-prep`'ten açıldı).
Hedef cihaz: Xiaomi 15 Ultra (SM8750 / Adreno, Android 17 / SDK 37, minSdk 34). Hedef içerik: telefonla elde çekilmiş MCRAW ve encoded (Mi-Log MP4) klipler.

Kod tanımlayıcıları ve dosya adları olduğu gibi bırakıldı. "Doğrulandı" = ilgili sembolü/dosyayı okuyup teyit ettim.
"Doğrulanamadı / tahmin" = ölçmedim ya da dış bilgiye bağlı. Cihaza hiç bağlanılmadı (adb yok), hiçbir derleme/test çalıştırılmadı; tüm süre ve doğruluk rakamları TAHMİNDİR.

Soru -> bölüm haritası: S1 §3.1 · S2 §3.2 · S3 §3.3 · S4 §3.4 · S5 §3.5 · S6 §3.6 · S7 §3.7 · S8 §3.8 · S9 §3.9 · S10 §3.10 · S11 §4 · S12 §5.

---

## 0. Özet (5-8 madde)

1. **Model:** v1 = kareler arası **similarity (4 DOF: tx, ty, log ölçek, rotasyon)**. Homografi/mesh v1'de yok; kayıt formatı ve plan API'si genişlemeye açık (S10 "perspektif/kalibre dönme" modu, S9 rolling shutter).
2. **Tahmin:** CPU'da **KLT (piramidal Lucas-Kanade, ileri-geri kontrol) + RANSAC similarity**, ~640 px uzun kenarda, Klipp `track.rs`'in C++ portu (`core/src/ImageStab.cpp`, yeni). Yoğun akış (DIS) v1'de yok. Luma: MCRAW için **CFA'dan doğrudan 2x2 yeşil ortalaması + alan ortalamalı küçültme** (`developThumbnail` değil); encoded için **MediaCodec çıktısının Y düzlemi** (zaten elde).
3. **İş akışı:** ayrı arka plan işçisi, bağımsız `Source` (`reopenIndependentFd`), **bir kez analiz**; sonuç = kare başına 32 bayt'lık **ham çift hareket kaydı** dosyası, anahtar `sourceFingerprint + analysisVersion`. Grade/yumuşatma değişince analiz yeniden ÇALIŞMAZ.
4. **Yumuşatma + kırpma:** render zamanında **saf fonksiyon** (`ImageStabPlan`): hız-uyarlamalı çift yönlü yumuşatma (Smooth), Lock; **dinamik zoom** = `Gauss(yuvarlanan-maks(z_i))` (kanıtlanabilir `zoom_i >= z_i`); zoom limiti yetmezse **düzeltme katsayısı a_i** azaltılır; son adımda kare-bazlı kesin köşe doğrulaması. Kenar asla görünmez.
5. **Entegrasyon:** mevcut GPU warp pass'i, shader'ı (elle bilinear, `texelFetch`) ve `ChainSlots/planChain` **değişmez**. Değişen matris kaynağı: yeni JNI çözücü aynı `[active, M×9]` düzenini döndürür; preview ve export **aynı tek Kotlin fonksiyonu** (`WarpResolver`) ve aynı C++ planı çağırır. Encoded'da GPU yolu yok: mevcut CPU `warpFrame`, `developEncoded` çıktısına (rotasyondan ÖNCE) uygulanır.
6. **Şema:** `GradeK`'a TEK yeni alan: `stabilizationEngine` (0 = gyro/eski kayıt, 1 = image). Mevcut `stabilizationMode/Strength/ZoomLimit` yeniden kullanılır. Eklemeli anahtar, varsayılan = eski davranış → `project.json` sürüm artışı GEREKMEZ (`ProjectMigrations` zinciri zaten yükleyiciye bağlı değil, bkz. §2).
7. **Dürüstlük:** klip/çift başına **güven puanı** (inlier oranı, uzamsal kapsama, kayıp kare oranı, kalan hata). UI "İyi / Orta / Zayıf / Kullanılamaz" ve nedenini söyler; zayıf aralıkta düzeltme kısılır.
8. **İlk kod paketi: S1** = tracker + RANSAC + sentetik testler (saf C++, masaüstü; cihaz/UI yok).

---

## 1. Mevcut durumun doğrulanmış özeti

| Konu | Bulgu (dosya / sembol) |
|---|---|
| Gate | `engine/StabilizationGate.kt`: `active(stabilization, gyroPath, enabled)`, `pathIfActive`, `gyroWorkAllowed`. `util/FeatureFlags.GYRO_STABILIZATION_ENABLED = false`. Bugün TÜM çağrı yerleri `gyroPath != null` şartına bağlı; görüntü modu için yol yok. |
| Warp CPU | `core/include/rawedit/Warp.h`: `warpFrame(src, h[9], zoom, cx, cy)`; `p_src = H^-1 (c + (p_out-c)/Z)`; uç piksele kelepçe; `H==I && zoom==1` byte-identical hızlı yol. Warp, **saklı-yönelim (stored orientation) buffer'ında, upright rotasyondan ÖNCE** uygulanır (bağlayıcı karar). |
| Warp GPU | `GlesShaderSources.WARP`: `uM` (mat3, `GL_TRUE` transpoze) ile `texelFetch` x4 **elle bilinear**; `GpuDevelopPipeline.cpp` "draw 10", `req.warpActive`/`req.warpM`; yalnız aktifse çizilir (identity = pass yok). `ChainSlots.h`: `planChain(nrLive, runSharp, runDetail, runWarp)` warp'ı sharp/detail'den SONRA, tail'den ÖNCE koyar. |
| Çözücü | `jni_bridge.cpp` `nativeStabWarpResolve` -> `[active, M(9)]` (M = Hinv·S, çift hassasiyette, float'a daraltılır; `stabMatInv` core'daki `matInv`'in KOPYASI). `cx = bufW/2`, `cy = bufH/2`. Çağıranlar: `Mp4Exporter` (kare başına) ve `GpuPreviewSession.resolveWarp`; ikisi de `gyroPath` + `StabilizationGate.active`'e bağlı. |
| CPU yolu | `jni_bridge.cpp` `applyStabilization` + `stabilizationFrameFor` (Entry'deki `StabPlanCache` ile). Export kendi handle'ını açar (ayrı Entry; preview ile cache paylaşmaz). |
| Plan API | `Stabilization.h`: `StabilizationFrame{h[9], zoom, status}`, `staticCropZoom` (çıkış piksel-merkezi köşeleri `[0,w-1]x[0,h-1]` içine, pozitif payda), `smoothOrientationSeries(+Adaptive)` — **quaternion'a** özel, 4 kanallı 2D yol için doğrudan kullanılamaz. `stab_measure` `computeStabilization`'ı sentetik sahte kare saati ve offset 0 ile sürüyor. |
| Görüntü hareketi | `OpticalSync.h/.cpp`: 2 geçişli **yalnızca öteleme** SAD, düşük çözünürlüklü luma, MAD outlier kapısı (en çok üst çeyrek), "Refined" güven mantığı. Üretim çağıranı `refineSyncEstimate` (jni_bridge): `developThumbnail(... Grade{} nötr, ~480px)`, en çok 75 kare, `RefineJob` ile `reopenIndependentFd` + `std::thread`. |
| Thumbnail | Gerçek sembol `developThumbnail` (`Thumbnail.h`; "ThumbnailDevelop" yok). CFA'yı hücre-hizalı **seyrelterek** (stride) küçültür, sonra TÜM `developCpu` boru hattını çalıştırır. Alan ortalaması DEĞİL (alias riski). |
| Kaynak | `Source::open(fd)`, `reopenIndependentFd()`, `frameInfo(i)` (`ptsUs`, `exposureNs`, `dynamicBlack/White`, `orientationDeg`), `readFrame(i)` (uint16 CFA), `rawMeta()` (`focalLengthMm`, 35mm-eşdeğer). **Readout süresi yok**, `exposureNs` var. |
| Encoded | `engine/EncodedSource.kt`: MediaExtractor+MediaCodec, `YuvPlanes{y,u,v}` (Y düzlemi stride'lı), native `developEncoded` -> `ImageF` (coded yönelim) -> Rec709 -> ARGB, **upright rotasyon en sonda**. `SourcePath{MCRAW, ENCODED}`. Preview yalnız CPU, "her render decode eder". **`export/` içinde encoded kaynağı export eden bir yol bulamadım** (grep: `EncodedSource`/`nativeEncodedDevelop` yalnız `engine/`, `ui/`, `data/`'da). |
| Timeline | `TimelineMp4Exporter` her klip için `session.renderFrame(handle, f, grade, lutPath, null)` çağırıyor: **gyroPath = null** → timeline export bugün hiç stabilize etmiyor. Kare eşlemesi: `sourceFrame(j) = round((sourceRange.start + j) * sourceFps / projectFps)` (`TimelineExport.kt`). Grade **kaynak başına** (`gradeFor(sourceId)`). |
| Şema | `GradeK`: `stabilization:Boolean=false`, `stabilizationMode:Int=0` (0 Smooth, 1 Lock First Frame, 2 Horizon Lock), `stabilizationStrength:Float=50` (0..100), `stabilizationZoomLimit:Float=1.25` (1.0..1.5), `stabilizationTrimMs`, `stabilizationFocalMm`. **Omit-when-off** yazım kuralı. `GradeK.SCHEMA_VERSION = 1`. `ExportStore.renderSnapshot` aynı kuralla job başına grade anlık görüntüsü yazar. |
| Migration | `data/ProjectMigrations.kt`: `MIGRATIONS = emptyList()`, `CURRENT_VERSION = 1`, `migrate()`, `TooNew`. **Yükleyiciden çağrılmıyor** (grep: yalnız `ProjectMigrationsTest` referans veriyor). |
| Parmak izi | `SourceRepository.fingerprintOf`: SHA-256(ilk 1 MiB); `source.fingerprint` mevcut. |
| A7 | `engine/ExportAbSweep.kt`: 4 vaka (neutral, grade, stab, grade-stab), CPU kapısı vs GPU kapısı; `pausedSkip` stab vakalarını gyro kapalıyken ATLAR. Stab ekseni grade anlık görüntüsünün içinde (`GradeK(stabilization=true)`), gyroPath ayrı geçiyor. |
| Ölçüm | `core/tests/stab_measure.cpp` (jerk, resid, corr, zoom). `tools/build-desktop.sh` ile derleniyor, CMake'te değil. Doctest testleri `core/CMakeLists.txt`'te (`test_warp`, `test_stabilization`, `test_optical_sync`...). |
| Fikstürler | `STEPS/media/test/261110_004150_VIDEO_24mm.{mcraw,gcsv}`, `261110_004208_VIDEO_24mm.{mcraw,gcsv}` (her biri ~2.1 GB), `VID_20261110_004247.mp4` (187 MB). Worktree'de yok, ana checkout'ta var. Kare sayısı/çözünürlük/EIS durumunu ölçmedim. |
| UI | `ClipEditorScreen.kt`: `showStabilization(path) = path == MCRAW` (~420. satır); `gyroWorkAllowed()` false iken yalnız pasif toggle + `editor_stabilization_gyro_paused` notu (~6439); mevcut dizeler `editor_stabilization_*`. |

---

## 2. Plan ile kod arasındaki çelişkiler

1. **"Sadece matrisin kaynağı değişir" (plan §5b.3.5) eksik.** Pass ve shader değişmez, doğru; ama çözücü (`nativeStabWarpResolve`), `GpuPreviewSession.resolveWarp`, `Mp4Exporter` döngüsü ve `GradeMapping.stabilizationArgs` hep `gyroPath`'e kilitli. Yeni çözücü + gate genişlemesi gerekir (S4).
2. **Encoded için GPU pass yok** (plan §1.3.5 söylüyor). Plan §5b encoded'ı aynı yolmuş gibi anlatıyor; gerçekte encoded CPU `developEncoded` yolunda ve warp için ayrı bir CPU kancası lazım. Ayrıca encoded export'u koda bulamadım.
3. **Plan §3.1 bayrak adı `STABILIZATION_ENABLED`;** kodda `GYRO_STABILIZATION_ENABLED` (gyro'ya özel). Görüntü modu için ayrı bayrak gerek (öneri: `IMAGE_STABILIZATION_ENABLED`).
4. **Plan §3.1 "kontroller gizlenir"** — kod da öyle, ama `showStabilization(path) = MCRAW` yüzünden encoded'da sekme hiç yok; görüntü motoru için kalkmalı.
5. **Plan §3.2 "migration zinciri kuruldu" — kurulu ama bağlı değil** (yalnız testte). Görüntü stabilizasyonu zincire bağımlı olmayacak şekilde tasarlandı (§3.3).
6. **Plan §5b.3.7 `TransformStage.stab = {...}` nesnesi:** kodda böyle bir aşama yok; GradeK düz alanlar. Nesne Renk Modeli v2 (şema 2) ile gelecek; S-fazı düz alanlarla çalışır, v2 migration'ı katlar (§3.3).
7. **Zoom merkezi:** bileşim `(w/2, h/2)` etrafında (piksel-dikdörtgen merkezi `(w-1)/2` değil, 0.5 px fark). Köşe garantisi testi çalışma zamanındaki AYNI bileşimi kullanmalı (§3.5 `composeWarpM`).
8. **Plan "ThumbnailDevelop":** sembol `developThumbnail`; seyreltmeli, alan ortalamalı değil, tam `developCpu` çalıştırır (§3.2).
9. **Timeline export hiç stabilize etmiyor** (`renderFrame(..., null)`): plan "timeline'da da çalışır" varsayıyor; ayrı paket gerekir (S7).
10. **A7 `pausedSkip`** tüm stab vakalarını atlıyor; görüntü vakaları için kural "yalnız gyro motorunu atla" olmalı (S4).
11. **Readout süresi:** plan §13.7 Gyroflow profilindeki `frame_readout_time`'dan söz ediyor; MCRAW'da yok (`exposureNs` var, readout yok; `OpticalSync.h` ve `FrameInfo`).

---

## 3. Sorular ve kararlar

### 3.1 Hareket modeli — similarity mi, homografi mi, mesh mi?

| Seçenek | Artı | Eksi |
|---|---|---|
| **Similarity (tx, ty, log s, θ)** | Dayanıklı (2 nokta RANSAC), az parametre, odak uzaklığı gerekmez (encoded'da focal bilinmiyor), Klipp'te kanıtlı, kenar garantisi köşe kontrolüyle KESİN (afin, dışbükey) | Geniş açıda dönme hareketini tam modelleyemez (hesap aşağıda) |
| Homografi (8 DOF) | Perspektif/dönme tam | Elde çekimde kötü koşullu, düzlemsel olmayan sahnede parallax, kararsız yol, RANSAC için 4 nokta |
| Mesh (MeshFlow) | Rolling shutter + parallax | Büyük iş, kare başına grid, yeni mesh-warp shader'ı, artefakt riski |
| (Ek) Kalibre dönme homografisi (3 DOF, `K·R·K⁻¹`) | Fiziksel doğru, mevcut `frameTransform`/`staticCropZoom` yeniden kullanılır | Odak uzunluğu şart (MCRAW'da var, encoded'da yok), paralax/öteleme duyarsız |

**Öneri: v1 = similarity.** En dayanıklı, encoded'ı da kapsar.

**Bilinen sınırlama (kendi hesabım, doğrulanmadı):** saf yaw için görüntü kayması `dx ≈ f·Δθ/cos²α` (α = eksenden açı). 24 mm-eşdeğer lenste yatay yarım FOV ≈ 36.9° → `1/cos²α ≈ 1.56`: kenar, merkezden ~%56 fazla kayar (dikeyde ~%18). Similarity bunu tek bir tx ile ortalar → kenarlarda kalan titreşim (düzeltilen sallantının kabaca %15-25'i, tahmin). Çözüm yolu S10 (MCRAW'da odak mevcut). Bu yüzden kayıt formatı ve plan API'si `modelKind` taşır.

### 3.2 Hareket tahmini

**Seçenekler:** (a) KLT+RANSAC CPU/NEON, (b) GPU yoğun akış (DIS), (c) mevcut `OpticalSync` SAD'ını genişletmek.

**Öneri: (a).**
- Gyroflow bile OpenCV DIS/pyrLK kullanıyor; biz OpenCV bağlamıyoruz, DIS portu ayrı büyük iş ve v1'de yoğun alan gerekmiyor (mesh/RS yok).
- GPU: GLES bağlamı preview'a ait ve fragment-pass tabanlı; analiz için ikinci GL bağlamı Adreno'da bellek/zaman baskısı ve test yükü getirir. 640 px'te CPU yeter.
- (c) yetersiz: yalnız öteleme (OpticalSync kendi başlığında söylüyor); rotasyon/ölçek yok.

**Algoritma (Klipp `track.rs`'ten PORT; kod kopyalanmaz):**
- Her çift (i-1, i): önceki karede ızgara köşeleri (Shi-Tomasi; 12x8 hücre, hücre başı ≤3 nokta, ≤~150 nokta), GÖRECELİ eşik → log çekimde de çalışır.
- 3 seviye piramit, 16x16 pencere, ≤15 iterasyon, ileri-geri hata ≤1 px, önceki hızdan tahmin (`pred`).
- RANSAC: 2 nokta similarity, 150 iterasyon, inlier eşiği 1.5 px (çalışma çözünürlüğü), ardından ağırlıklı en küçük kareler. **Sabit tohum** → iki koşu aynı sonuç.
- Çıktı: `[tx, ty, logS, θ]` + `inlierRatio, nInliers, residualRms, coverage (3x3 hücre doluluğu), status`.

**Luma kaynağı:**
- **MCRAW:** `Source::readFrame(i)` CFA -> **özel `cfaToLuma`**: kara seviye çıkar (`dynamicBlack` yoksa `RawMeta.black`), 2x2 hücre ortalaması (yeşil ağırlıklı), ~6x alan ortalamalı küçültme (4K -> 640), sabit `sqrt`-benzeri LUT (karanlık sahnede gradyan). Neden `developThumbnail` değil: seyreltme alias üretir (tracker için kötü), TÜM `developCpu`'yu çalıştırır (gereksiz maliyet), nötr grade gerekir. **Analizin grade'den bağımsız olması şart** (kullanıcı pozlamayı değiştirince analiz bozulmasın). **Doğrulanamadı:** ham-alan luma ile geliştirilmiş luma aynı yolu verir mi → S2 kabul testi: fark < 0.1 px (çalışma çözünürlüğü).
- **Encoded:** MediaCodec'in Y düzlemi (`YuvPlane`, limited range) doğrudan alan ortalamasıyla küçültülür (renk dönüşümü yok). Log eğrisi düşük kontrast verir; eşikler göreceli olduğundan sorun beklenmez, gerekirse klip başına sabit kontrast germe (ilk N kare yüzdelikleri) — S6'da ölç.

**Çözünürlük:** 640 px uzun kenar (Klipp 480). Ölçüm gürültüsü doğrudan çıktıya titreşim olarak geçer (düzeltme = yumuşak − ham). 0.1 px öz-doğruluk @640 ≈ 0.6 px @4K, kabul edilebilir. Zayıf güvenli kliplerde 960 ile 2. geçiş S11'de.

**İş parçacığı modeli:**
- `ImageStabAnalyzer` çekirdek sınıfı luma kareleri **alır** (`push(luma8, w, h, stride, ptsUs)`) ve çift kaydı üretir; iki besleyici: MCRAW (native döngü: `Source::open(reopenIndependentFd)`, `readFrame`, `cfaToLuma`) ve encoded (Kotlin: İKİNCİ bir `EncodedSource` + MediaCodec, Y düzlemi JNI ile).
- Preview'ın tek iş parçacıklı motoruna (`PreviewRenderer.previewEngine`) DOKUNMAZ; kendi arka plan işçisi (düşük öncelik). `RefineJob` desenini izler (bağımsız fd, atomik `done/progress/cancel`).
- İki aşama: decode iş parçacığı + izleme iş parçacığı (ikili tampon). Üçüncü iş parçacığı yok (termal/preview rekabeti; açık soru).

**Süre hedefi (4K30, 15 Ultra) — TAHMİN:** decode baskın. Plan §1.3 4K CPU decode için ~18.7 ms (preview yolu için ölçülmüş) veriyor; + CFA->luma ~3-6 ms (16 MB okuma) + KLT/RANSAC ~2-4 ms (640x360). Kare başı ~25-30 ms → 30 sn'lik klip (900 kare) ≈ **20-27 sn**. Hedef: **≤ 1.0x klip süresi, kabul sınırı 1.5x**; ilerleme ve iptal zorunlu. Encoded 4K HEVC: donanım decode hızlı; Y kopyası/küçültme baskın (~8-12 ms/kare, tahmin). Cihaz ölçümü S12'de.

### 3.3 Kamera yolu ve yumuşatma modları

**Yol tanımı (çözünürlükten bağımsız):** `u = (x - w/2)/h`, `v = (y - h/2)/h` (yükseklik = 1). Çift hareketi `(tx, ty, logS, θ)` bu birimlerde saklanır; piksel `H_px = Sc · Sim · Sc⁻¹` (`Sc`: `h` ile ölçek + merkeze öteleme). Preview (1280) ve export (3840) AYNI yoldan türer.

**Yol:** `P_i = P_{i-1} ∘ ΔP_i` (birikimli, referans = kaynağın 0. karesi; kırpılmış aralıktan bağımsız). Kayıp çiftler: en çok 2 kare önceki hızı tut, sonra dur (Klipp). Birikimli sürüklenme alçak frekanslıdır → Smooth'ta zararsız, Lock'ta zararlı (bilinen sınırlama; S11'de atlamalı çiftlerle döngü kapatma).

**Modlar:**

| Mod | v1? | Tanım |
|---|---|---|
| Smooth (int 0) | Evet | Hız-uyarlamalı, ileri+geri üstel süzgeç, 4 kanal (x, y, logS, θ). `strength` -> `tauLow = 2^(2s-1)` sn, `tauHigh = 0.1` sn (mevcut gyro eşlemesinin aynısı → kaydırıcı hissi aynı). Hızlı pan'de tau küçülür (niyetli hareket korunur). Hız eşiği başlangıç hipotezi ≈ 0.5 yükseklik/sn; S3'te fikstürle kalibre edilir (gyro'daki 500°/s doğrudan aktarılamaz). |
| Lock (int 1) | Evet | Hedef = sabit; referans = kullanılan aralıktaki yolun **medyanı** (kırpmayı en aza indirir; gyro'daki "İlk Kare" DEĞİL). Zoom büyüyeceği için tipik olarak `a_i < 1` olur; UI söyler. |
| Sinematik/optimal (yeni int 3) | Hayır (S8) | Gyroflow `optimal/path.rs` fikri: jerk+ivme+hız enerjisi, kırpma kısıtlı BANDLI QP (Cholesky O(n)). Grundmann L1 LP'si DEĞİL: LP çözücü bağımlılığı gerekir; QP bağımlılıksız banded Cholesky ile yapılır. |
| Horizon Lock (int 2) | **Hayır** | Görüntüden ufuk/yer çekimi bilinemez; gyro'ya özel kalır. Image motorunda int 2 okunursa `Smooth`'a eşlenir. İleride dürüst adıyla "Roll yumuşat" (yalnız θ kanalı) opsiyonel. |

**GradeK eşlemesi:** motor seçici dışında yeni alan gerekmez.
- `stabilization` (ana anahtar), `stabilizationMode` (0 Smooth, 1 Lock; image'da 2 -> 0), `stabilizationStrength` (0..100 -> 0..1), `stabilizationZoomLimit` (1.0..1.5 = kırpma tavanı) **aynen kullanılır**.
- `stabilizationTrimMs`, `stabilizationFocalMm` image motorunda **etkisiz** (gate native'e geçirmez; kayıt dokunulmaz).
- **Yeni:** `stabilizationEngine: Int` (0 = gyro/eski, 1 = image); yalnız 1 iken yazılır.
- Gelecek, yalnız ilgili pakette: `stabilizationRsPercent` (S9), mod int 3 (S8).

**Şema etkisi / Faz 0 migration zinciri:** eklemeli opsiyonel anahtar + varsayılan = eski davranış → **sürüm artışı ve migration adımı yok.** Zincir yükleyiciye bağlı olmadığından ona bağımlı olmak risk olurdu. Renk Modeli v2 geldiğinde (`MIGRATIONS[0]`: v1→v2) düz `stabilization*` alanlarını `TransformStage.stab` nesnesine katlayacak; **kural: `stabilizationEngine` yoksa = gyro** (eski kayıtlar). Bu kural S4'te `ProjectMigrationsTest`/`GradeStoreTest`'e girdi olarak yazılır ki v2 yazarı kaçırmasın.

### 3.4 Kırpma penceresi ve zoom

| Seçenek | Artı | Eksi |
|---|---|---|
| Statik (klip genelinde tek zoom) | Basit (Klipp) | Tek kötü an tüm klibi büyütür |
| **Dinamik (kare başına)** | Ortalama kırpma düşük (gyro sürümü de dinamik: round 3'te 1.25 tavanda ortalama 1.135 ölçülmüş) | "Zoom nefesi" riski → yumuşatma şart |

**Öneri: dinamik zoom + düzeltme katsayısı.**

1. Her kare için tam düzeltmenin gerektirdiği minimum zoom `z_i`: çıkışın piksel-merkezi köşeleri `(0,0)…(w-1,h-1)`, çalışma zamanındaki AYNI bileşimle `p_src = H⁻¹(c + (p_out-c)/Z)`, `c=(w/2,h/2)`, kaynağın `[0,w-1]x[0,h-1]` kutusunun içine düşmeli (+ε = 0.05 px, float daralması payı). Similarity afin olduğundan KÖŞE KONTROLÜ KESİNDİR.
2. `zoom_i = Gauss_σ( rollingMax_W(z_i) )`, pencere ±h, Gauss desteği ±h. **Kanıt:** destek içindeki her j'de `rollingMax_j ≥ z_i` (i, j'nin penceresinde) → ağırlıklı ortalama ≥ `z_i`. Yani `zoom_i ≥ z_i` HER ZAMAN ve zoom pürüzsüz. (Gyroflow `zoom_dynamic` fikri: yuvarlanan min FOV + Gauss; burada eşdeğeri maks zoom.)
3. Bütçe: `z_i > zoomLimit` olan karelerde `a_i ∈ [0,1]` ikili aramayla (≤12 adım): `hedef' = ham + a(hedef − ham)`. `a'_i = Gauss(rollingMin_W(a_i)) ≤ a_i` (aynı argüman). Varsayım: `z_i(a)` a'da monoton (a=0'da z=1).
4. **Garanti son adımı:** her karede kesin köşe kontrolü; ihlal varsa o karenin `a`'sı küçültülüp yeniden yumuşatılır (≤5 tur), son çare kare başına projeksiyon (küçük süreksizlik kabul; `invalidSampleFrames` sayacı, beklenen 0).
5. Klipp'in "yumuşat↔projekte et ×25" döngüsünden fark: kapalı biçimli üst sınır + son kesin doğrulama; sonuç kare sırasından bağımsız ve deterministik.

**"Kenar asla görünmez" testi:** (a) saf C++ özellik (property) testi: ≥5000 rastgele yürüyüş (genlik `zoomLimit`'i çok aşan, ani sıçrama, tüm modlar, farklı en-boy), her karede `coverageSlack(H, zoom, w, h) ≥ -ε`; (b) sentetik görüntüde `warpFrame`-benzeri sayaçla kelepçelenen örnek sayısı == 0 (köşe + kenar orta noktaları); (c) GPU float32 `M` ile aynı kontrol (host'ta float daraltma simülasyonu); (d) fikstürlerde `invalidSampleFrames == 0`; (e) ardışık kareler için "sıçrama" ölçütü (§4).

### 3.5 Entegrasyon (`resolveWarp -> warpM`)

**Veri akışı:** analiz dosyası -> `ImagePath` (C++, Entry'de) -> `ImageStabPlan(params, aralık, aspect)` -> kare i için `(H, zoom)` -> `composeWarpM(H, zoom, w, h)` -> `float[9]` -> mevcut warp pass.

- **Yeni C++ (ayrı dosyalar):** `core/include/rawedit/ImageStab.h`, `core/src/ImageStab.cpp`: `ImageStabAnalyzer`, `ImagePath` (+serileştirme), `ImageStabPlan`, `composeWarpM`. `Stabilization.cpp/Sync.cpp/OpticalSync.cpp` **değişmez**.
- **Paylaşılan / yeniden kullanılan:** `warpFrame` (CPU), WARP shader + `GpuDevelopPipeline` draw 10 + `ChainSlots/planChain` (DEĞİŞMEZ), `rawedit::Source`, `parallelForRows`, Entry'deki plan-cache **deseni** (yeni `ImageStabPlanCache`), `refineSyncEstimate`'in iş parçacığı/`reopenIndependentFd` deseni, `ritim-stab-timing` log kalıbı, `stab_measure` metrik tanımları.
- **Paylaşılmayan:** quaternion yumuşatma (`smoothOrientationSeries*`: kavram — tau eşlemesi — kopyalanır, kod değil), `computeStabilization`, `Sync`, `OpticalSync` (yalnız fikir/test altyapısı), `StabPlanCache`'in gyro anahtarı, `nativeStabWarpResolve` (yanına kardeşi yazılır).
- **`composeWarpM` tek kaynak:** Hinv·S bileşimi bugün `Warp.cpp`'de ve `jni_bridge.cpp`'de KOPYA. Yeni kod `ImageStab.cpp`'de TEK `composeWarpM(H, zoom, w, h, out)` tanımlar; planlayıcının kenar testi, JNI çözücü ve testler bunu çağırır (kopya çoğalmaz; mevcut kopyalara dokunulmaz).
- **JNI:** `nativeImageStabLoad(handle, analysisPath)` (Entry'ye yükler), `nativeImageStabWarpResolve(handle, mode, strength, zoomLimit, rangeStart, rangeEnd, frameIndex, maxDim)` -> `[active, M×9]` (**mevcut düzenle aynı**; Kotlin tüketicileri değişmez), `nativeImageStabProbe(...)` -> `[requiredZoomMax, meanZoom, limitedFraction, confidence...]` (UI okuması; mevcut zoom probe'unun kardeşi).
- **Kotlin:** tek `engine/WarpResolver.kt` (yeni): `resolve(handle, engineDecision, grade, frameIndex, maxDim): WarpVerdict(active, m9)`. `GpuPreviewSession.resolveWarp` ve `Mp4Exporter` döngüsü bunu çağırır. Bugün iki yerde "aynı 6 değer, kural gereği aynı" kopyası var; sürüklenme riski burada tekilleştirilir.
- **Gate:** `StabilizationGate` genişler (§3.7).
- **Preview = export garantisi:** (1) tek kod yolu (`WarpResolver` + C++ plan); (2) aynı girdi: analiz dosyası, parametreler, aralık; (3) planın **normalize uzayda** hesaplanması: test: `Sc_exp⁻¹·H_exp·Sc_exp` ile `Sc_prev⁻¹·H_prev·Sc_prev` (ikisi de normalize `Sim`) farkı < 1e-9 olmalı, yani iki tier'in matrisleri aynı normalize dönüşümden türer (zoom'lar eşit); (4) çift hassasiyet, plan TU'sunda `-ffp-contract=off` (`Yuv.cpp` emsali), 1 vs N iş parçacığında aynı sonuç testi; (5) A7'ye görüntü vakaları (§4).
- **`ChainSlots`/`planChain`:** değişiklik YOK (warp zaten ayrı kapılı pass). **Adreno kısıtı:** WARP shader RGBA32F slot'tan `texelFetch` ile okur (filtreleme yok → LINEAR hatasından etkilenmez) ve bilinear'ı elle yapar; yeni iş gerekmez. Dikkat: (a) Renk v2 zinciri yeniden düzenlerken warp için slot/yer korunmalı (`test_chain_slots` 32 kombinasyon); (b) warp keskinleştirmeden SONRA çalıştığı için yeniden örnekleme keskinliği biraz yumuşatır — CPU referansıyla aynı, parite bozulmaz; v2'de "warp'ı keskinleştirmeden önce" sorusu açık; (c) kullanıcının A2 GPU crop/straighten'ı ile zoom'un birleşik etkisi (etkin çözünürlük) — sıra doğrulanmadı.
- **Encoded:** native `nativeEncodedDevelop`'ta `developEncoded` (ImageF, coded yönelim) SONRASI, Rec709/upright rotasyondan ÖNCE `warpFrame(img, H, zoom, cx, cy)`; `H` aynı `ImageStabPlan`'dan, buffer boyutuna göre. Analiz de coded yönelimli luma üzerinde yapıldığından rotasyon eşlemesi gerekmez (MCRAW'daki "warp upright'tan önce" kuralıyla aynı).

### 3.6 Analiz cache'i, anahtar, geçersizleme, timeline eşlemesi

- **Konum:** `filesDir/cache/imagestab/<fingerprint>-v<analysisVersion>.ristab` (uygulama özel alanı). `cacheDir` değil (OS temizleyebilir, analiz pahalı); Depolama sayfasında (plan C5) "Stabilizasyon analizleri" satırı + temizle. `ClipDeletion`'ın klip silince dosyayı silmesi **doğrulanamadı**, S4'te kontrol.
- **Anahtar:** `sourceFingerprint` (SHA-256 ilk 1 MiB) + `analysisVersion` (algoritma/çözünürlük/luma yolu değişince artan sabit). Çarpışma koruması: başlıkta `frameCount`, ilk/son `ptsUs`, çözünürlük; uyuşmazsa geçersiz. **Anahtarda grade, strength, mod, aralık YOK.**
- **Biçim (little-endian, sürümlü):** başlık `{magic, formatVersion, analysisVersion, workW, workH, frameCount, flags, crc}`; kare başına 32 bayt: `ptsUs(i64)`, `tx, ty, logS, θ (f32)`, `inlierRatio (u8)`, `nInliers (u16)`, `coverage (u8)`, `residualRms (f32)`, `status (u8)`. 900 kare ≈ 29 KB. **Ham ÇİFT kayıtları** saklanır (birikimli yol değil) → herhangi bir aralık için yol sıfırdan toplanabilir.
- **Kısmi/sürdürülebilir:** her 256 karede kontrol noktası; iptal edilen analiz kısmi dosya bırakır ve devam eder (`analyzedUpTo`).
- **Geçersizleme:** `analysisVersion` artışı, başlık uyuşmazlığı, CRC hatası, kullanıcının "Yeniden analiz" eylemi. Grade/trim/timeline değişimi geçersiz KILMAZ.
- **Timeline eşlemesi:** yol **kaynak kare indeksine** göre. Kırpılmış klip için kullanılan kaynak kareler `TimelineExport`'un `sourceFrames[]`'ı (frame-nearest, tekrar/atlama olabilir); plan kare `f` için `(H, zoom)`'u kaynak kare f'e göre verir. **Aralık bağımsızlığı:** Smooth modda matris kare i'nin ±destek komşuluğuna bağlıdır, aralıktan bağımsızdır (referans kaynağın 0. karesi; kırpma dışı kareler yumuşatma/zoom penceresine KATILIR → biraz muhafazakâr ama preview/export/timeline aynı sonucu verir). **Lock modunda hedef (medyan) aralığa bağlıdır:** timeline klibi ve önizleme AYNI `[a,b]`'yi geçmeli; clip editöründe kırpma yoksa tüm kaynak. Belgelenir ve test edilir. Timeline preview (A4) ve timeline stabilizasyonu S7.
- **Export'ta eksik analiz:** export başlangıcında `ensureAnalysis(fingerprint, version)`; yoksa export ön aşama olarak analizi çalıştırır (ilerleme: "Stabilizasyon analizi %x") — sessizce stabilizasyonsuz çıkmaz. Başarısız/iptalse job açık hatayla düşer. Kuyruktaki job'un anlık görüntüsü `stabilizationEngine/Mode/Strength/ZoomLimit` taşır; analiz sürümü güncellemeyle değişirse yeniden analiz.

### 3.7 `StabilizationGate`: off | image | gyro

```
enum StabEngine { OFF, IMAGE, GYRO }
engineFor(grade, gyroPath, imageAnalysisReady, flags):
  !grade.stabilization                      -> OFF
  grade.stabilizationEngine == 1 (image):
       flags.IMAGE_STABILIZATION_ENABLED && imageAnalysisReady -> IMAGE else OFF
  else (0 = gyro / eski kayıt):
       flags.GYRO_STABILIZATION_ENABLED && gyroPath != null    -> GYRO else OFF
```
- Mevcut `active()`, `pathIfActive()`, `gyroWorkAllowed()` imzaları ve davranışları **aynen kalır** (gyro semantiği, mevcut testler bozulmaz); `engineFor` yanına eklenir. `GYRO_STABILIZATION_ENABLED` **false kalır.** Yeni `IMAGE_STABILIZATION_ENABLED` S5 UI'si bitene kadar false.
- "Analiz hazır değil" → `OFF` döner ama UI'ya `analysisMissing` bilgisi gider (sessiz yok sayma değil).
- **Eski kayıtlı grade'ler:** `stabilization=true + mode ints`, `stabilizationEngine` yok → engine 0 (gyro) → şu an `OFF`, kayıt DEĞİŞMEZ; gyro bayrağı açılırsa eski davranış birebir döner. UI: "Kapalı" seçili + "Bu klipte duraklatılmış gyro stabilizasyon ayarı var" notu. Kullanıcı "Görüntü"yü seçince `engine=1` yazılır; `strength/zoomLimit/mode` korunur (mode 2 → okunurken 0). `trim/focal` kayıtta kalır ama etkisiz.
- Yazım: `engine` anahtarı **yalnız 1 iken** yazılır (`GradeStore`, `ExportStore.renderSnapshot`) → gyro/off grade dosyaları bit-bit aynı kalır.

### 3.8 UI — Stabilize sekmesi

Nötr editör şeması (`STEPS/DESIGN.md`: `editor-surface #1b1b1b`, `editor-surface-container #262626`, `editor-primary #c6c6c6`, `editor-error #ffb4ab`), stok Material 3, 48 dp dokunma hedefleri, durum yalnız renkle değil **simge + metinle**. Sekme encoded'da da görünür (`showStabilization` MCRAW şartı image motoru için kalkar; gyro'ya özel kısımlar MCRAW'a bağlı kalır).

Yerleşim (yukarıdan aşağı):
1. **Motor seçici** (`SingleChoiceSegmentedButtonRow`, 48 dp): `Kapalı | Görüntü | Gyro`. Gyro segmenti `enabled=false`, altında `editor_stabilization_gyro_paused` notu.
2. **Analiz kartı** (durum makinesi):
   - Analiz yok: açıklama + `[Analiz et]` (`FilledTonalButton`, simge `analytics`).
   - Çalışıyor: determinate `LinearProgressIndicator`, "Analiz ediliyor %42 · yaklaşık 4 sn kaldı", `[İptal]` (`close`). Ekrandan çıkış analizi durdurmaz (uygulama kapsamlı korutin); kısmi sonuç saklanır.
   - Bitti: güven rozeti (`check_circle` İyi / `info` Orta / `warning` Zayıf / `error` Kullanılamaz) + tek cümle neden + `[Ayrıntılar]`: zayıf/kayıp kare oranı, ortalama inlier, bölge kapsamı; zayıf aralıkların seek çubuğunda ince işareti (opsiyonel).
   - Eski sürüm/başarısız: `[Yeniden analiz et]` (`refresh`) + hata mesajı "ne oldu + ne yapmalı" (DESIGN.md hata kuralı).
3. **Kontroller** (analiz yoksa devre dışı + gri açıklama): Mod `Yumuşak | Kilit` (Sinematik S8'de eklenir); **Güç** 0-100; **Zoom limiti** 1.00-1.50 (mevcut `CompactSlider`); canlı okuma "Kırpma: ort. 1.08x · en çok 1.21x"; sınırlandıysa "Zoom limiti yetmiyor: düzeltme %X azaltıldı" (`warning`).
4. **Yol grafiği** (daraltılabilir, `show_chart`): 3 üst üste iz — X, Y, Rotasyon (Ölçek opsiyonel; 48 dp filtre çipleri); **ham** (soluk `editor-outline`) vs **yumuşatılmış** (`editor-primary`); geçerli kare imleci, dokununca seek; zayıf aralıklar gölgeli; ≤~1000 nokta (min/maks seyreltme); erişilebilirlik özet metni ("Ham yol ±14 px, düzeltilmiş ±3 px").
5. Alt not: "Analiz bir kez yapılır; güç ve zoom değişince yeniden çalışmaz."

Metinler (`values/strings.xml` + `values-tr/strings.xml`):

| anahtar | EN | TR |
|---|---|---|
| `editor_stab_engine_off` | Off | Kapalı |
| `editor_stab_engine_image` | Image | Görüntü |
| `editor_stab_engine_gyro` | Gyro | Gyro |
| `editor_stab_legacy_gyro_note` | This clip has a paused gyro stabilization setting | Bu klipte duraklatılmış bir gyro stabilizasyon ayarı var |
| `editor_stab_analyze` | Analyze | Analiz et |
| `editor_stab_analyzing` | Analyzing %1$d%% · about %2$d s left | Analiz ediliyor %1$d%% · yaklaşık %2$d sn kaldı |
| `editor_stab_cancel` | Cancel | İptal |
| `editor_stab_reanalyze` | Analyze again | Yeniden analiz et |
| `editor_stab_conf_good` | Good tracking | İyi takip |
| `editor_stab_conf_fair` | Fair tracking | Orta takip |
| `editor_stab_conf_poor` | Weak tracking | Zayıf takip |
| `editor_stab_conf_unusable` | Cannot stabilize this clip | Bu klip stabilize edilemiyor |
| `editor_stab_reason_low_texture` | Too little texture in the scene | Sahnede yeterli doku yok |
| `editor_stab_reason_subject` | A large moving subject fills the frame | Büyük hareketli bir nesne kareyi dolduruyor |
| `editor_stab_reason_fast` | The camera moved too fast to follow | Kamera takip edilemeyecek kadar hızlı hareket etti |
| `editor_stab_reason_noise` | Too much noise to track reliably | Güvenilir takip için fazla gürültü var |
| `editor_stab_mode_smooth` | Smooth | Yumuşak |
| `editor_stab_mode_lock` | Lock | Kilit |
| `editor_stab_crop_limited` | Zoom limit is not enough: correction reduced by %1$d%% | Zoom limiti yetmiyor: düzeltme %1$d%% azaltıldı |
| `editor_stab_path_graph` | Camera path | Kamera yolu |
| `editor_stab_path_raw` | Raw | Ham |
| `editor_stab_path_smoothed` | Smoothed | Yumuşatılmış |
| `editor_stab_analysis_error` | Analysis failed (%1$s). Try again; if it repeats, re-import the clip. | Analiz başarısız (%1$s). Tekrar deneyin; sürerse klibi yeniden içe aktarın. |

Material Symbols: `video_stable` (sekme/motor), `analytics`, `close`, `refresh`, `check_circle`, `info`, `warning`, `error`, `show_chart`, `crop`.

### 3.9 Rolling shutter

- **v1: HAYIR.** Readout süresi bilinmiyor (MCRAW: `exposureTime` var, readout yok — `OpticalSync.h` ve `FrameInfo` doğrulandı); mesh için yeni shader gerekir.
- **Bilinen readout olmadan yapılabilecek (S9):** birinci derece RS, hızın satıra doğrusal etkisidir: `x' = x + k·y` (yatay hız), dikeyde ölçek — bunlar **afindir**; mevcut 3x3 `H`'ye girer, **shader değişmez** (kendi çıkarımım). Parametre ρ = readout/kare aralığı. Kaynak: (a) kullanıcı kaydırıcısı `stabilizationRsPercent` (varsayılan 0); (b) **öz-kalibrasyon:** her çiftte üst/orta/alt şeritlerden ayrı öteleme tahmini, şerit farkının hızla regresyonu ρ'yu verir; klip geneli sağlam medyan + güven; yetersiz harekette kapalı; (c) cihaz sensör verisi (online araştırma sorusu). Gerçek jello/mesh düzeltmesi bu yaklaşımın ötesinde.
- v1'de hızlı pan'lerde jello kalır; belgelenir.

### 3.10 Başarısızlık durumları ve güven puanı

**Çift durumu:** `Ok`, `Weak` (inlier < eşik), `Lost` (takip yok).
**Klip puanı** `C ∈ [0,1]` (öneri formülü, fikstürle kalibre): `0.4·okFraction + 0.25·medianInlierRatio + 0.2·coverage(ort. 3x3 doluluğu) + 0.15·(1 − normalize(residualRms))`. Eşikler (başlangıç): `≥0.8` İyi, `≥0.55` Orta, `≥0.3` Zayıf, altı Kullanılamaz.

| Durum | Tespit | Davranış / geri bildirim |
|---|---|---|
| Düşük doku | az köşe, `nInliers < N_min` | `Weak/Lost`; hızı ≤2 kare tut sonra dur; "Sahnede yeterli doku yok"; düz bölge uzunsa düzeltme o aralıkta 0'a yaklaşır |
| Büyük hareketli nesne kareyi doldurur | inlier'lerin uzamsal sınır kutusu < ~%40 VEYA ikinci RANSAC ikinci modeli ≥%25 inlier ile bulur (çift modlu) | `ambiguous` bayrağı; temporal tutarlılık (önceki hıza yakın model) + kenar halkası önceliği (Klipp'in arka plan halkası fikri); "Büyük hareketli nesne" uyarısı |
| Hızlı pan | tahmin+3 seviye aralığı aşılır (~%5 genişlik/kare); ileri-geri hatası patlar | `Lost`; adaptif yumuşatma gerçek pan'i izler; kayıp oranı yüksekse "kamera çok hızlı" |
| Gece gürültüsü | düşük gradyan enerjisi, yüksek kalan hata | 2x2 binleme + 6x alan ortalaması gürültüyü bastırır; yine de `Weak` artar; "Fazla gürültü" |
| Sahne kesmesi | tüm eşleşmeler çöker, ani kare farkı | `Lost` + yol kesikliği: yumuşatma kesmenin iki yanında BAĞIMSIZ (kesme boyunca süzme yok) |
| Zoom/odak değişimi | logS sıçraması | ölçek kanalı ayrı hız sınırı; yalnız takip edilir |

Dürüstlük kuralı: puan düşükse (a) UI söyler, (b) `Kullanılamaz` ise Image motoru varsayılan olarak etkinleştirilemez ("yine de uygula" uyarısıyla geçersiz kılınabilir), (c) zayıf aralıklarda düzeltme `a_i`'ye güven eşlemesiyle yansır → kötü ölçüm titreşim ÜRETMEZ.

---

## 4. Ölçüm ve kabul (Soru 11)

**Metrikler** (`stab_measure` tanımları): jerk = çıkış yolunun ikinci türevi RMS'i (piksel @1080 yükseklik/kare²) → **jerk oranı** `out/raw` (1.0 = düzeltme yok; düşük iyi); residual = 1 sn merkezli hareketli ortalamadan sapma; zoom ort/maks; `invalidSampleFrames`; **sıçrama ölçütü** (yeni) = ardışık matrislerin köşe noktası ayrışması `max_corner |M_i − M_{i-1}|` ham hareketin yumuşak türevini aşıyor mu.

**Yeni araç:** `core/tests/image_stab_measure.cpp` (`tools/build-desktop.sh`'a eklenir): `.mcraw` (+ isteğe bağlı `.gcsv`) alır; analiz + plan çalıştırır; metrikleri basar. **Gyro çapraz kontrolü (fikstürlerin avantajı):** `.gcsv` var; mevcut kod (salt-okunur kullanım) yönelim + senkronu verir; her çift için gyro'nun öngördüğü merkez ötelemesi `H(c)−c` ile görüntü tahminini kıyasla (offset ±150 ms taramasıyla). Hipotez kabul: kalan RMS < ~1 px @çalışma çözünürlüğü. Bu, tahminciyi gyro'ya karşı DOĞRULAR; gyro kodunu değiştirmez.

**Sentetik testler** (`core/tests/test_image_stab.cpp`, doctest, `add_test`): dokulu prosedürel görüntü + `warpFrame` ile BİLİNEN similarity; hata < 0.1 px, rotasyon < 0.02°, ölçek < %0.1; **%40 outlier** (farklı hareketle kayan yamalar); gürültü/bulanıklık; sabit sahne → yol düz (±0.05 px); iki koşu bit-aynı, 1 vs N iş parçacığı aynı; kayıp/düşük doku → `Lost`, güven düşük; hızlı pan → `Lost` + toparlanma; serileştirme gidiş-dönüş + CRC bozulma; zoom/kenar özellik testi (§3.4); preview↔export normalize eşitliği; aralık bağımsızlığı (Smooth: aralık içi/dışı aynı matris; Lock: aralığa bağlı, belgelenen davranış).

**Fikstür klipleri:** 2 MCRAW + MP4. Çıktı tabloları (jerk oranı, residual, ort/maks zoom, güven, süre) S2/S3 sonunda bu belgeye eklenir. **İlk hedefler (kalibre edilecek, sabit değil):** jerk oranı ≤ 0.35 @ Güç 50, `invalidSampleFrames == 0`, ort. zoom ≤ 1.15, analiz ≤ 1.5x klip süresi (masaüstü referans; cihaz ayrı).

**A7 (`ExportAbSweep`):** yeni vakalar `stab-image` (`GradeK(stabilization=true, engine=1)`) ve `grade-stab-image`; CPU kapısı (`applyImageStab`) vs GPU kapısı (draw 10), AYNI matris. `pausedSkip` yalnız gyro motorunu atlar; analiz yoksa harness analizi önce çalıştırır (süre notuyla) ya da "SKIPPED: analiz yok" (EXPORT_MISMATCH sayılmaz). Kare sayısı kısa tutulur.

---

## 5. İş kırılımı S1..S12 (Soru 12)

Sıra: S1 → (S2 ∥ S3) → S4 → S5 → (S6, S7) → S8/S9/S10/S11 → S12. S1-S3 saf C++ masaüstü; cihaz gerektirmez.

| Paket | Kapsam | Dosyalar (yeni / değişen) | Testler | Boyut |
|---|---|---|---|---|
| **S1** İzleyici + RANSAC | `LumaFrame`, piramit+gradyan, ızgara köşe, LK ileri-geri, similarity RANSAC+LSQ, `PairMotion`, sabit tohum; API `estimatePair(prev, cur, opts)` | `core/include/rawedit/ImageStab.h`, `core/src/ImageStab.cpp`, `core/CMakeLists.txt`, `core/tests/test_image_stab.cpp` | §4 sentetik: bilinen dönüşüm, %40 outlier, düz sahne, determinizm, kayıp tespiti | M |
| **S2** Analizör + luma + cache | `ImageStabAnalyzer` (push API), MCRAW `cfaToLuma`, kayıt/okuma + CRC + kontrol noktası, `image_stab_measure` (analiz + gyro çapraz kontrol) | `ImageStab.*`, `core/tests/image_stab_measure.cpp`, `tools/build-desktop.sh`, `test_image_stab.cpp` | serileştirme, ham-luma vs geliştirilmiş-luma farkı, fikstür analizi/süre, iptal/sürdür | M |
| **S3** Plan: yumuşat+zoom+kırpma | `ImageStabPlan`: yol toplama, Smooth/Lock, dinamik zoom, `a_i` bütçe, kesin doğrulama, `composeWarpM`, `coverageSlack`; normalize uzay | `ImageStab.*`, `test_image_stab.cpp`, `image_stab_measure.cpp` | §3.4 özellik testi, preview↔export eşitliği, aralık bağımsızlığı, sıçrama ölçütü, fikstür jerk/resid/zoom | M |
| **S4** JNI + Kotlin akış | `nativeImageStabLoad/WarpResolve/Probe`; Entry'de `ImagePathCache`+`ImageStabPlanCache`; `WarpResolver.kt`; `StabilizationGate.engineFor`; `GradeK.stabilizationEngine` (GradeStore + ExportStore); `FeatureFlags.IMAGE_STABILIZATION_ENABLED`; `ImageStabAnalysisService` (işçi + durum akışı); `Mp4Exporter` `ensureAnalysis` + `WarpResolver`; `GpuPreviewSession`; `GradeMapping.stabilizationArgs`; `ExportAbSweep` vakaları; CPU `applyImageStab` | `jni_bridge.cpp`, `NativeSource.kt`, `StabilizationGate.kt`, `FeatureFlags.kt`, `GradeStore.kt`, `ExportStore.kt`, `PreviewRenderer.kt`, `GpuPreviewSession.kt`, `Mp4Exporter.kt`, `ExportAbSweep.kt`, yeni `WarpResolver.kt`, `ImageStabAnalysisService.kt` | JVM: gate tablosu (eski kayıt = OFF), GradeStore gidiş-dönüş (engine yalnız 1 iken yazılır, eski dosya bit-aynı), snapshot, `WarpResolver` null/identity katlamaları; "engine yoksa gyro" migration notu | L |
| **S5** UI | Stabilize sekmesi (§3.8), durum makinesi (saf mantık), grafik composable, strings en+tr, `showStabilization` genişletme | `ClipEditorScreen.kt` (dosya çok büyük; ayrı `StabilizeTab.kt` önerilir), `EditorPreviewLogic`, `res/values*/strings.xml` | durum makinesi JVM testi, en/tr anahtar eşliği testi | M |
| **S6** Encoded | encoded besleyici (ikinci `EncodedSource`, Y düzlemi → `push`), `nativeEncodedDevelop`'a CPU warp kancası, Y-luma kontrastı, sekme encoded'da | `EncodedSource.kt`, `EncodedAdapters.kt`, `jni_bridge.cpp`, `ImageStabAnalysisService.kt` | MP4 fikstür analizi, CPU warp paritesi | M |
| **S7** Timeline | `TimelineMp4Exporter`'da stabilizasyon: `sourceFrames` + aralık, analiz hazırlığı, Lock aralık kuralı (A4 timeline preview kararına bağlı) | `TimelineMp4Exporter.kt`, `TimelineExport.kt` | `sourceFrames` eşleme + plan, aralık bağımsızlık testi | M |
| S8 Sinematik yol | bandlı QP + doğrusallaştırılmış kırpma kısıtı, mod int 3 | `ImageStab.*`, UI | enerji azalması, kısıt ihlali 0, Smooth ile kıyas | M-L |
| S9 Rolling shutter-lite | afin RS (ρ), öz-kalibrasyon, `stabilizationRsPercent` | `ImageStab.*`, `GradeStore`, UI | sentetik RS, ρ geri kazanımı | M-L |
| S10 Kalibre dönme / perspektif | 3 DOF dönme-homografisi (K bilinen MCRAW), `frameTransform` yeniden kullanımı | `ImageStab.*` | geniş açı sentetik, kenar kalan hata | L |
| S11 Dayanıklılık | 960 px 2. geçiş, çift modlu hareket ayrımı, drift (atlamalı çift) | `ImageStab.*` | düşük doku/gürültü fikstürleri | M |
| S12 Cihaz kabulü (sahip, adb) | 15 Ultra süre/termal, A7 koşusu, MP4 fikstür | — | A7 log, `ritim-stab-timing` | S |

**Dosya çakışma riski:** S4 `GradeStore.kt`/`ExportStore.kt`'a dokunur; Renk Modeli v2 de buraya dokunacak → tek eklemeli alan + "anahtar yoksa gyro" kuralı çakışmayı küçültür; v2 birleştirmesi migration'da katlanır.

---

## 6. Sahibin vermesi gereken kararlar

1. **Analiz tetikleyicisi:** düğme + "Görüntü" seçilince otomatik başlat (öneri) mı, içe aktarmada otomatik mi, yalnız elle mi? Pil/termal etkisi var.
2. **`zoomLimit` varsayılanı:** image motorunda 1.25 (gyro'daki, ölçümlü ortalama 1.135) mi kalsın, daha muhafazakâr 1.15 mi?
3. **Encoded:** v1'de yalnız önizleme (export yolu yok) kabul mü, yoksa encoded export (Faz 3) beklenip S6 sonrası mı kapanır?
4. **Timeline stabilizasyonu** (S7) bu faza dahil mi? Bugün timeline hiç stabilize etmiyor.
5. **Lock semantiği:** "aralık medyanı" (öneri), "ilk kare" değil. Horizon Lock'ın image motorunda sunulmaması kabul mü ("Roll yumuşat" ileride)?
6. **Model önceliği:** S10 (kalibre dönme/perspektif) mi, S9 (rolling shutter-lite) mi önce? Geniş açıda kenar hatası görülürse S10.
7. **Yeni alan zamanı:** `stabilizationEngine` şimdi (S4, eklemeli) mi, yoksa Renk v2 şemasını mı bekleyelim? Öneri: şimdi.
8. **Analiz depolama:** `filesDir/cache/imagestab/` + Depolama sayfasında temizle — onay.
9. **İş parçacığı bütçesi:** analiz en çok kaç çekirdek (1 decode + 1 izleme)? Preview/export çalışırken duraklasın mı? Termal ağırlaşınca dursun mu?
10. **Bayrak adı** `IMAGE_STABILIZATION_ENABLED` ve ne zaman `true` yapılacağı (S5 sonrası).
11. **Kabul eşikleri** (jerk oranı, ort. zoom): §4'teki öneriler fikstür ölçümüyle kalibre edildikten sonra sabitlensin mi?

---

## 7. Doğrulanamayanlar

- Hiçbir şey çalıştırılmadı/derlenmedi/ölçülmedi: tüm süre ve doğruluk rakamları tahmin.
- Fikstürlerin çözünürlük/kare sayısı/fps'i; MP4'ün kameranın kendi EIS'ini içerip içermediği; gyro ile hizalı kalan titreşimin gerçek seviyesi.
- Ham-alan luma'nın geliştirilmiş luma kadar iyi izlenmesi (S2'de test).
- Encoded export'un gerçekten hiç var olmadığı (yalnız grep ile; çalışma zamanı doğrulanmadı).
- `ClipDeletion`'ın analiz dosyalarını temizleyip temizleyeceği.
- A2 GPU crop/straighten ile warp zoom'unun birleşik sırası.
- Adreno'da 4K RGBA32F warp pass süresi (mevcut ölçümlere bakılmadı).
- `z_i(a)` monotonluğu varsayımı (son kesin doğrulama güvence verir, ama kanıt değil).
- Kenar/merkez kayma oranı (`1/cos²α`) hesabı kendi türetmem, ölçülmedi.
- Rolling shutter'ın birinci derece etkisinin afin olduğu çıkarımı (kendi türetmem; küçük hareket varsayımı).

---

## 8. Online araştırma ajanına sorular

1. **DIS / pyrLK / KLT performansı:** Snapdragon 8 Elite'te CPU (Oryon) NEON ile 640x360'ta piramidal LK (~150 nokta) ve OpenCV DIS (ultrafast/fast) süreleri; Adreno 830 compute/GLES ile DIS veya KLT; Hexagon NPU'da optik akış/özellik takibi için kullanılabilir mevcut yol var mı? (Kararımız CPU KLT — hâlâ doğru mu?)
2. **MCRAW rolling-shutter bilgisi:** MotionCam MCRAW metaverisinde sensör readout süresi, skew ya da Camera2 `SENSOR_ROLLING_SHUTTER_SKEW` benzeri alan var mı? Xiaomi 15 Ultra ana sensörü için 4K30/4K60'ta tipik readout süresi? Gyroflow `frame_readout_time` değerleri bu cihaz için biliniyor mu?
3. **Android 17 kamera/video metaverisi:** Camera2/CameraX'te kare başına hareket verisi (OIS/EIS vektörleri, `LENS_OPTICAL_STABILIZATION_MODE`, vendor tag'leri), MP4'e gömülen EIS meta izleri; encoded kliplerde kamera uygulamasının uyguladığı EIS kırpmasını dosyadan nasıl anlarız?
4. **Gyro'suz stabilizasyonda güncel durum:** (a) öğrenme tabanlı yöntemlerin mobil gerçek zamanlı uygulanabilirliği, (b) Google Photos / Warp Stabilizer / DaVinci / CapCut'ın bilinen yöntem sınıfları, (c) similarity yeterli mi, yoksa homografi+RS endüstri standardı mı?
5. **Mobilde yol çözücü seçimi:** Grundmann L1 LP vs Gyroflow bandlı QP (jerk/ivme/hız + kırpma kısıtı) vs yinelemeli yumuşat/projekte et: kalite/süre, bağımsız C++ bandlı Cholesky/iç nokta uygulamaları, kararlı doğrusallaştırma önerileri.
6. **Dinamik zoom/FOV:** Gyroflow `zoom_dynamic`/`fov_iterative` varsayılan pencere ve Gauss parametreleri; "zoom nefesi"nin algısal eşikleri (zoom hızı limiti için literatür/pratik değer).
7. **Düşük doku / gürültü / baskın nesne:** gece görüntüsünde KLT için ön işleme (temporal binleme, gradyan normalizasyonu), özellik eşikleri; hareketli nesne baskın sahnelerde arka plan hareketini seçmek için güncel yöntemler (çift modlu RANSAC, MAGSAC++, hareket segmentasyonu) ve mobil maliyeti.
8. **Log/HDR kaynaklarda takip:** Mi-Log/HLG 10-bit videoda Y düzlemi üzerinde KLT için kontrast normalizasyonu en iyi uygulamaları.
9. **Termal/güç:** 15 Ultra'da arka plan CPU işine (2 iş parçacığı, ~1 dk) termal kısıtlama davranışı, `PowerManager.getThermalHeadroom` kullanımı, WorkManager/foreground service gerekliliği (Android 17 kısıtları).
10. **MediaCodec:** 4K HEVC/Mi-Log klibi için hızlı "yalnız küçültülmüş Y" decode yolları (ImageReader + `HardwareBuffer`, GL downscale + küçük readback) Android 17'de ByteBuffer çıkışına göre ne kadar hızlı? Bilinen sorun/kısıt var mı?
11. **Kalibre dönme (S10):** MCRAW'daki 35mm-eşdeğer odak uzaklığının (`focalLengthMm`) çoklu kamera/dijital kırpma durumlarında güvenilirliği (24 mm fikstür adı, gerçek efektif odak?); 15 Ultra'nın 4K video kırpma faktörü.
12. **Algoritma doğrulaması:** Klipp `track.rs` ve Gyroflow algoritmalarının akademik karşılıkları (Shi-Tomasi, Lucas-Kanade, Grundmann 2011, MeshFlow 2016) için güncel uygulama notları ve bilinen tuzaklar.
