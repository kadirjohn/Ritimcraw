# Renk modeli v2 (F1) tasarım planı

Durum: **plan, kod yok** (2026-11-23, paket "F1-0", KOL C). F1 N1 koduna başlamak sahip onayı gerektirir (bkz. §12).
Taban: `main` @ `9a87d6d`. Dil: Türkçe; kod tanımlayıcıları İngilizce.

Kanıt sözlüğü (GPU_BASELINE.md ile aynı mantık):
- **doğrulandı**: bu worktree'de koddan okundu veya bu pakette hesaplandı (dosya:satır verilir).
- **plandan alıntı**: `.planning/KLIPP-FILMCRAFT-PLAN.md` ya da başka belgeden alındı, bu pakette yeniden ölçülmedi.
- **doğrulanmadı**: okunmadı/ölçülmedi/hatırlanan bilgi. Sayı uydurulmaz; boyutlar S/M/L'dir (plandaki sınıflama), saat değil.

Kaynaklar: plan §1.3, §4, §10, §12 (D2, D5), §13.5, §13.6, §13.14; `docs/COLOR_ROUTING_PLAN.md` (B1) ve
`data/color/SourceProfile.kt`; `docs/IMAGE_STAB_PLAN.md` (`s0-image-stab-design` dalı); `core/src/Develop.cpp`,
`core/src/Transfer.cpp`, `core/include/rawedit/Color.h`; `app/src/main/cpp/gles/ChainSlots.h`,
`GpuDevelopPipeline.{h,cpp}`, `GradeParcel.h`; `GradeStore.kt`, `ProjectMigrations.kt`, `ProjectJson.kt`,
`AtomicFiles.kt`, `ExportStore.kt`, `PresetStore.kt`, `PresetFile.kt`, `StabilizationGate.kt`, `EncodedAdapters.kt`;
Klipp `engine/OpenDrt.kt` (yerel `~/projects/klipp`). Not: istekte geçen `DESIGN.md` repoda **yok** (doğrulandı: `ls`).

---

## 1. Özet ve ana kararlar

1. **Çalışma uzayı:** node'lar **DaVinci Wide Gamut (DWG) lineer -> DaVinci Intermediate (DI)** sinyalinde çalışır (D2, sahip kararı).
   DI/DWG matematiği çekirdekte **zaten var** (`Color.h/.cpp`), yeni sabit yazılmaz.
2. **Öneri (açık karar K1):** *develop* (RAW/encoded -> NR -> keskinleştirme -> warp) **lineer Rec.709'da kalır**
   (bugünkü uzay, değişmez). DWG'ye geçiş yalnızca **NODES** kapısında olur (709 -> DWG matrisi, sonra DI kodlama).
   Plan §13.5'in "kamera -> XYZ -> DWG" sırasından bilinçli sapma; gerekçe §2.3.
3. **Eski projeler "legacy" kipinde açılır:** v1'in 9-20 aşama bloğu ve 23-24 (tonemap+LUT) kuyruğu **olduğu gibi** korunur.
   Migration bunları yeni bir "Primary node"a çevirmez (çevirmek piksel eşdeğerliğini bozar, bkz. §6.3 ve §9 çelişki 2).
   Sonuç: migrate edilmiş proje **bit düzeyinde** aynı parcel/aynı shader yolundan geçer.
4. **Yeni:** `nodes: List<GradeNode>` (N1: primaries, log wheels, eğriler, node LUT, mix/enable) + `OutputTransform{legacy | opendrt}`.
   NODES tek bir GPU pass'tir (warp'tan sonra, TAIL'den önce). `ChainSlots.h`'a **bir** pass türü eklenir (`kPassNodes`).
5. **OpenDRT v1.1.0**: C++ referans (`core/src/OpenDrt.cpp`) + GLSL; SDR (sRGB 2.2 / Rec.1886 2.4) ve HLG 1000 nit.
   **Lisans: GPLv3 türevi** (Klipp portunun başlığı böyle diyor), repo ise Apache-2.0: sahibin "konu dışı" kararı not düşülerek korunur (§4.5).
6. **Migration güvenliği:** v2'ye geçmeden önce `project.json` yedeklenir, tüm kök (grades+presets+exports+sources) tek atomik yazımla
   dönüştürülür. **Kritik bulgu:** 8 store `version`'ı kendi `SCHEMA_VERSION=1`'iyle damgalıyor (§6.5); bu F1'den önce
   düzeltilmezse v2 dosyası sessizce v1 damgasına döner ve çift migration olur. Bu F1-2'nin zorunlu parçasıdır.
7. **Stabilizasyon:** S4'ün düz `stabilization*` alanları (+`stabilizationEngine`) `transform.stab` nesnesine katlanır;
   kural **"stabilizationEngine yok = gyro (0)"** migration adımına ve test vektörüne yazılır (§7).
8. **Encoded kaynaklar:** E0 kaynak tanımını (bitDepth/range/matrix/transfer) düzeltir; F1 develop uzayını **değiştirmediği** için
   (K1 önerisi) E0'ın hedefi (`WorkingSpace.LinearRec709`) F1'den bağımsız kalır (§8).

---

## 2. Çalışma uzayı: DaVinci Intermediate + DaVinci Wide Gamut

### 2.1 Karar ve gerekçe (D2, sahip kararı; plandan alıntı)
- Node'lar log sinyalinde çalışır: wheel/eğri hissi lineer ışıkta değil log'da doğru verir (Resolve/Klipp yaklaşımı).
  Klipp ortak bir grading uzayı kullanmıyor (kendi klibinin log'unda, `pivot = 0.435`); Ritimcraw DI/DWG ile **tek** ortak uzay seçiyor.
- DI, sahne-lineer değerleri 0..1 civarında toplar; DWG, kamera gamutlarını (ARRI AWG3, S-Gamut3.Cine, Mi-Log Rec.2020...) negatif değere
  zorlamadan taşır. DI'nin lineer segmenti negatifleri de taşır (`Color.h`: "L <= 0.00262409 uses L*M (negatives included)", doğrulandı).

### 2.2 Koddaki hazır parçalar (doğrulandı)
| Parça | Yer |
|---|---|
| DI sabitleri A=0.0075, B=7, C=0.07329248, M=10.44426855, lineer eşik 0.00262409, log eşik 0.02740668 | `core/src/Color.cpp:87-93` |
| `TransferId::DaVinciIntermediate`, `GamutId::DaVinciWideGamut`, `encodeTransfer/decodeTransfer` | `Color.h:212-232, 285-289` |
| DWG düzeltilmiş matris çifti, `gamutToGamut(from,to)` (Bradford ile) | `Color.cpp:190`, `Color.h:297-327` |
| `LutRole::SceneToDisplay` (DWG/DI -> Rec.709 LUT'u için) | `Color.h:258` |
| 709 <-> DWG için yeni matris yazmaya gerek yok: `gamutToGamut(Rec709, DaVinciWideGamut)` | `Color.h:327` |

- %18 gri -> DI **0.336043**: bu pakette hesaplandı: `(log2(0.18+0.0075)+7)*0.07329248 = 0.336043272` (doğrulandı). Node `pivot` varsayılanı = 0.336043.
- Klipp `OpenDrt.kt`'deki `DWG_TO_XYZ` ile `Color.cpp`'deki pinned DWG matrisinin sayısal olarak aynı olduğu **doğrulanmadı**; F1-1'de test edilir.

### 2.3 Develop uzayı: neden 709 kalıyor (K1)
Plan §13.5/§4.1: "develop çıktısı (kamera -> XYZ) -> DWG lineer -> DI". Koda göre bu pahalı ve riskli:
- Develop.cpp aşama 5+6 kamera -> **lineer Rec.709 (D65)** üretir (`Develop.cpp:858-898`); NR (aşama 7/8) ve keskinleştirme (21/22) **Rec.709 luma ağırlıklarıyla**
  (0.2126/0.7152/0.0722) çalışır (`Develop.cpp:145, 228, 339`). DWG'de Y satırı negatif blue katsayısı içerir (Klipp `DWG_TO_XYZ` ikinci satır: -0.1477...), yani aynı kod DWG'de yanlış luma verir.
- v1 aşama 9-20 (shadows, midtones, saturation, wheel...) kanal-bazlı skaler eğrilerdir; gamut değişince sonuç değişir. v1 eşdeğerliği 709'da kalmayı gerektirir.
- Encoded yol da lineer 709'a iner; B1 planının `WorkingSpace { LinearRec709 }` modeli ve "migration ile mevcut lineer Rec.709 çalışma alanını koru"
  bağlayıcı kuralı (`COLOR_ROUTING_PLAN.md` §3.5) aynı yönü gösterir.
- Maliyet: 709'daki negatif/gamut-dışı değerlerin develop boyunca kırpılmadan geçtiği **doğrulanmadı** (RGBA32F kırpmaz, ama aşama kodunda `max(0,..)` olabilir). F1-1 testi:
  709 -> DWG -> 709 gidiş-dönüş ve negatif girişli develop aşamaları (K1'in doğrulama şartı).

**Öneri K1-A:** develop = lineer 709; `NODES girişi = M(709->DWG) -> DI kodlama`, `çıkışı = DI çözme -> M(DWG->709)` (legacy çıkış) ya da OpenDRT girişi (DWG/DI; Klipp portu `Input.DI` destekliyor).
**Alternatif K1-B:** PRE'de kamera -> DWG; NR/keskinleştirme/legacy blok için luma ağırlığı uniform'u + E0'ın hedef uzayı da DWG olur (E0 ile bağ). Tavsiye edilmez.

---

## 3. Node zinciri (N1): tanım ve her node'un girdi/çıktı uzayı

### 3.1 Üst yapı (plan §4.1'in kodla uzlaştırılmış hali)
```
ClipGradeV2
├── develop    : exposureEv, tempK, tint, asShot, highlightReconstruction, keepVignetting,
│                noiseReduction, chromaDenoise, temporalNr, temporalStrength, sharpness, detail
├── legacy     : (v1'in 9-20 aşama kontrolleri; yalnız legacy kipte etkin) highlights, blackPoint, whitePoint,
│                shadows, midtones, contrast, wheel*(6), split*(3), saturation
├── nodes      : List<GradeNode>              (N1)
├── output     : OutputTransform { mode: legacy | opendrt, legacy{tonemap,lutId,lutStrength,lutInterp}, opendrt{...} }
└── transform  : crop*, straighten, stab{enabled, engine, mode, strength, zoomLimit, trimMs, focalMm}
```
Plan §4.1'deki `input: InputTransform` (encoded log eğrisi + gamut) **grade'e girmez**: kaynak gerçeğidir, `SourceProfile` (B1/E0) taşır (§8); undo/preset'e girmemesi B1 §3.2/3.6 kararıdır.

### 3.2 Aşama sırası ve uzaylar
| # | Aşama | Girdi uzayı | Çıktı uzayı | Yer / not |
|---|---|---|---|---|
| 1 | Kaynak çözme: RAW CFA (aşama 1-4) veya encoded YUV -> RGB | CFA / YCbCr (+ E0 profili) | kamera RGB -> aşama 5+6 -> **lineer 709** | PRE pass (değişmez); encoded: CPU `developEncoded` |
| 2 | NR (aşama 7/8) | lineer 709 | lineer 709 | 3 NR pass (değişmez) |
| 3 | **LEGACY blok** (aşama 9-20; exposure/WB/highlights dahil) | lineer 709 | lineer 709 | POST pass (değişmez). Native kipte legacy alanları nötr, POST yine exposure/WB/highlights'ı uygular |
| 4 | Keskinleştirme/detay (21/22) | lineer 709 | lineer 709 | 4 unsharp pass (değişmez) |
| 5 | Warp (stabilizasyon; GPU'da kare başı kapılı) | lineer 709 | lineer 709 | `kPassWarp`; lineer ışıkta yeniden örnekleme (log'da bilinear yapılmaz) |
| 6 | **NODES** (yeni) | lineer 709 | lineer 709 (içeride DWG/DI) | yeni `kPassNodes`; nodes boşsa pass **eklenmez** (v1 ile bit-aynı) |
| 7 | **OUTPUT**: legacy (tonemap+LUT+Rec.709 OETF) veya OpenDRT | lineer 709 / DWG | display-referred kod değerleri | TAIL (iki shader varyantı) |

NODES içi (her node sırayla, tek shader döngüsü):
`lineer 709 --(M709->DWG)--> DWG lineer --(DI encode)--> DI --[node 1 .. n]--> DI --(DI decode)--> DWG lineer --(M DWG->709 | OpenDRT girişi)-->`.
Ardışık node'lar arasında DI'dan çıkılmaz (her node DI'da çalışır); dönüşüm yalnız zincir başında/sonunda yapılır.

### 3.3 GradeNode alanları (N1, plan §4.2)
| Grup | Alanlar | Uzay | Varsayılan |
|---|---|---|---|
| Primaries | lift/gamma/gain/offset (renk: wheel x,y + master), contrast, pivot, sat, hue, temp, tint | DI | nötr; pivot 0.336043 |
| Log wheels | logShadow/logMid/logHi + sınırlar | DI | nötr |
| Eğriler | curveY/R/G/B (spline -> 1D tablo) | DI giriş -> DI çıkış | kimlik |
| LUT | node başına `lutId` + `lutMix` | LUT'un kendi yorumu (`LutInterpretation`) | yok |
| Karışım | `enabled`, `mix` | -- | true, 1.0 |

N2+ (HSL eğrileri, qualifier, window, blur, density) bu belgenin kapsamı dışıdır; yalnız yer ayrılır (§5.2 N2 notu).
`CURVE_N` ve Klipp `GradeNode` (`Grade.kt:433`) **okunmadı** (doğrulanmadı); F1-1'de Klipp'ten alınırken kontrol edilir.

### 3.4 CPU referansı
`core/include/rawedit/ColorModel.h` + `core/src/Nodes.cpp` (plandaki `Grade.cpp` adı, mevcut `Grade` struct'ıyla karışmasın diye `Nodes.cpp`):
`void applyNodes(ImageF&, const NodeGrade*, size_t n)` ve `void applyOutput(ImageF&, const OutputTransformSpec&)`.
**Tek fonksiyon** hem `developCpu` hem `developEncoded` sonuna bağlanır (`Transfer.cpp`'deki `encodedRenderStage` ile aynı "lockstep" disiplini).
Rec.709 OETF bugün JNI köprüsü kuyruğunda; DRT kipinde köprü OETF'yi **atlar** (DRT çıkışı zaten display kodlu).

---

## 4. OpenDRT v1.1.0 entegrasyonu

### 4.1 Kaynaklar
- Orijinal: Jed Smith, `jedypod/open-display-transform` v1.1.0 (plan §13.6; 22 Ocak 2026, plandan alıntı).
- Referans port: Klipp `engine/OpenDrt.kt` (553 satır, doğrulandı): SDR 100 nit/Rec.709 primaries ve HLG 1000 nit "Rec.2100 HLG / Rec.2020 (P3 Limited)".
  Ön ayarlar Klipp'te var; **PQ yok** (doğrulandı: yalnız `eotf` 1/2/5). Plan §13.6 PQ preset'ini anıyor; orijinal DCTL yerelde **yok** -> PQ'nun portu N1 kapsamı dışı, doğrulanmadı.
- Test vektörleri: `klipp/app/src/test/resources/opendrt_ref.txt`, `opendrt_hlg_ref.txt` var (doğrulandı: dosyalar mevcut); **nasıl üretildikleri doğrulanmadı** (orijinal DCTL'den mi, port'tan mı). Referans olarak kullanmadan önce kökeni kontrol edilir.
- `itrp-patches` GLSL portu yerelde **yok** (doğrulanmadı); GLSL, C++ referanstan yazılır.

### 4.2 Kullanıcıya açılan parametreler (Klipp `Drt` veri sınıfından, doğrulandı)
| Parametre | Değerler | Varsayılan |
|---|---|---|
| input | DI/DWG (sabit: NODES çıkışı) | DI/DWG |
| look | Standard, Arriba, Sylvan, Colorful, Aery, Dystopic, Umbra | Standard |
| tonescale | 0 = look'tan, Low/Medium/High, 6 look adı, ACES 1.x, ACES 2.0, Marvelous Tonescape, DaGrinchi ToneGroan (14 etiket) | look'tan |
| creative white point | look'tan, D93, D75, D65, D60, D55, D50; `cwpLimit` 0..1 | look'tan; 0.25 |
| grey (orta gri nit) | Klipp'te 3..25 | 10 |
| display | SDR phone/web (sRGB 2.2), TV (Rec.1886 2.4), HLG (1000 nit; yalnız export hedefi HLG iken) | sRGB 2.2 |

İç parametreler (tn_con, tn_sh, tn_toe, tn_off, hcon/lcon, rs_*, pt_*, brl*, hc_*, hs_*) preset'ten gelir, kullanıcıya açılmaz.
N1'de look/tonescale/cwp/grey kümesinin tamamının mı yoksa alt kümesinin mi açılacağı UI paketinin kararıdır (K7).

### 4.3 C++ referans ve GLSL
- `core/src/OpenDrt.cpp` + `core/include/rawedit/OpenDrt.h`: `struct OpenDrtSpec { look, tonescale, cwp, cwpLimit, grey, display }`,
  `void applyOpenDrt(ImageF&, const OpenDrtSpec&)` (girdi DWG lineer). Kotlin portu satır satır taşınır; sabitler `constexpr`.
- GLSL: TAIL'in OpenDRT varyantı, **piksel başı** hesap (parity referansı = C++). Klipp 33³ LUT'a bake ediyor; bake, giriş DI aralığında ve
  aşırı pozlanmış bölgede enterpolasyon hatası getirir. **Öneri K7:** önce doğrudan GLSL; maliyet cihazda **ölçülür** (ölçülmedi), gerekirse 33³/65³ RGBA16F LUT'a bake edilir ve bake hatası ayrı raporlanır.
- HLG: ters EOTF, sistem gamma 1.2, 1000 nit referans (Klipp `hlgInverseEotf`, doğrulandı); F1 yalnız float HLG sinyali üretir, 10-bit kuantizasyon/EGL E2/E1d'dedir.

### 4.4 Test
- Klipp referans vektörleri (kökeni doğrulandıktan sonra) ile C++ karşılaştırması (eşik ölçümle sabitlenir, K9).
- Parity sweep'e OpenDRT vakaları (SDR, HLG; look başına 1 + gri rampa + gamut yamaları).

### 4.5 Lisans notu
Klipp portunun başlığı: "License: GNU General Public License v3.0 ... derivative of OpenDRT.dctl" (doğrulandı). Bu repo `LICENSE`: Apache-2.0
(doğrulandı), `NOTICE` üçüncü taraf listesi var. GPLv3 kod APK içinde dağıtılırsa birleşik eser GPLv3 yükümlülüğü taşır.
Plan D5: "Lisans sahibin kararıyla konu dışı". Bu belge karara dokunmaz; yalnız: (a) `NOTICE`'e OpenDRT kaydı ve kaynak/sürüm eklenir (F1-4),
(b) "konu dışı" kararının **dağıtım yapılırsa** da geçerli olduğu K6'da bir kez teyit edilir. Hukuki görüş değildir.

---

## 5. ChainSlots / planChain, slot yerleşimi, uniform bütçesi, shader varyantları

### 5.1 Bugünkü durum (doğrulandı)
- `ChainSlots.h`: 3 slot (A,B,C = RGBA32F ping-pong), `ChainPassKind {Post, SharpH, SharpV, DetailH, DetailV, Warp}`, `passes[6]`,
  `planChain(nrLive, runSharp, runDetail, runWarp)`; `draw()` her pass'in in/out/guide'ını yalnız buradan alır (warp slot hatasının dersi).
- `test_chain_slots.cpp`: `nr(4) x sharp(2) x detail(2) x warp(2) = 32` kombinasyon (döngü sınırlarından).
- Chain: PRE -> NR -> POST -> sharp -> detail -> warp -> TAIL (developTex, OETF dahil). `GpuDevelopPipeline.cpp:1609`.
- NEAREST sabit: RGBA32F texture'lar `GL_NEAREST` (`GpuDevelopPipeline.cpp:1159, 1218, 1245`). 3D LUT RGBA16F + `GL_LINEAR` (donanım trilinear) `:381, 1340` kodda kullanılıyor; Adreno'da bu **formatın** doğru filtrelediğine dair cihaz kanıtı bu pakette **doğrulanmadı**.

### 5.2 Önerilen değişiklik
```
enum ChainPassKind { ..., kPassWarp, kPassNodes /*N1*/ };
ChainPlan { ChainPass passes[7]; int count; ...; int nodes = -1; }
ChainPlan planChain(int nrLive, bool runSharp, bool runDetail, bool runWarp, bool runNodes);
```
- `kPassNodes`: `in = cur`, `out = chainNextSlot(cur)`, `guide = -1`; warp'tan **sonra** eklenir. `finalSlot` = nodes çıkışı; TAIL onu okur.
- POST her zaman çalışır (bugünkü gibi); legacy alanları nötrse POST'un nötr yolu zaten uniform korumalı (v1 davranışı).
- Test: kombinasyon sayısı `32 x 2 = 64`; `count == 1 + 2*sharp + 2*detail + warp + nodes`; mevcut "warp son pass" iddiası `warp == count - 1 - nodes` olur;
  yeni invariant: nodes varsa `nodes == count-1`, `in == bir önceki pass'in çıkışı`, `out != in`, TAIL'in okuduğu slot = nodes `out`.
  Mevcut "pre-refactor slot table" testi dokunulmaz; `runNodes=false` ile **aynı** çıktıyı vermesi regresyon kilididir.
- Slot formülü elle yazılmaz (kural). Örnek (planner formüllerinden bu pakette elle türetildi, kod çalıştırılmadı): NR yok, sharp+detail+warp+nodes açıkken:
  POST A->B; sharpH B->C, sharpV C->A (guide B); detailH A->B, detailV B->C (guide A); warp C->A; **nodes A->B**; TAIL B'yi okur.
- N2 notu (blur node'ları): komşuluk isteyen node'lar H/V çift pass + guide ister, 3 slot'la (in, hOut, vOut) mümkün (unsharp ile aynı desen); `passes[]` boyutu node sayısına bağlı büyür -> N2'de üst sınır kararı gerekir. N1 kapsamı değil.

### 5.3 Neden ayrı NODES pass (ve TAIL'e gömmemek)
Seçenek (a) node döngüsünü TAIL'e gömmek: 1 pass az, ama TAIL zaten tonemap+LUT+OETF taşıyor; DRT eklenince tek shader çok büyür (Adreno'da derleme süresi ve register baskısı **ölçülmedi**).
Seçenek (b) ayrı `kPassNodes` (öneri K8): invariant testi planner'da kalır, node cache (plan F2/C4) için kesin bir texture sınırı verir, shader varyantları bağımsız. Bedel: 1 tam çerçeve okuma+yazma.
Bellek (bu pakette aritmetik): 4096x3072 RGBA32F = 201.326.592 bayt (192 MiB) / texture; mevcut 4 texture (A, B, C, developTex) 4K'da ~768 MiB eder; preview'in gerçek çözünürlüğü **doğrulanmadı**.
Bu yüzden node başına ayrı pass reddedilir: **tek** NODES pass, node'lar shader içinde döngü.
Node'lar warp'tan **sonra**: warp lineer ışıkta yeniden örnekler; window/qualifier (N2) çıkış uzayında kalır (tracking semantiği için de doğru).

### 5.4 Uniform ve texture bütçesi (öneri; ölçülmedi)
- Bugün: PRE 12, POST 20, TAIL 12 `uniform` bildirimi (kaynaktan sayıldı; vec4 sayısı değil). Uniform bloğu (UBO/std140) **kullanılmıyor** (doğrulandı: grep boş); plan §4.3 "UBO" diyor -> düz `uniform vec4[]` + `glUniform4fv` önerilir (mevcut desen).
  `GL_MAX_FRAGMENT_UNIFORM_VECTORS` kodda **sorgulanmıyor** (doğrulandı). F1-5 derleme sonrası sorgu + log ekler.
- NODES: node başına ~12 vec4 (lift, gamma, gain, offset, contrast/pivot/sat/hue, temp/tint/mix/flags, log wheel x3, lut parametreleri); **üst sınır 8 node** (hipotez) ~ 96 vec4. ES 3.0 spec minimumu 224 fragment uniform vektörü (hatırlanan, doğrulanmadı); gerçek değer cihazda okunmalı.
- Texture unit: mevcut 0-5 (5 = LUT 3D). NODES: eğri atlası unit 6 (R32F, `texelFetch` + elle lerp; NEAREST kısıtından etkilenmez), node-LUT'lar unit 7-10 (**üst sınır 4 LUT'lu node**, hipotez); ES 3.0 spec minimumu 16 unit (hatırlanan, doğrulanmadı).
- Node verisi `GradeParcel` ile taşınamaz: parcel yalnız skaler, "slotlar append edilir", dosya `tools/gen-grade-parcel.py` ile **üretiliyor** (doğrulandı). Yeni ayrı bir `NodeParcel` FloatArray + JNI girişi gerekir; `OutputTransform` skalerleri (mode, look, tonescale, cwp, grey, display) parcel'e append edilir (üretici betik şemasından).

### 5.5 Shader varyantları
| Program | Varyant bayrakları | Adet |
|---|---|---|
| PRE, POST, NR, sharp/detail, warp | (değişmez) | -- |
| NODES (yeni) | hasCurves, hasNodeLut | <= 4 |
| TAIL | outputMode {legacy, opendrt}; `uOetf`/display kodlama uniform ile | 2 |

Bayrak maskesi cache anahtarıdır; `GlesShaderValidator` (doğrulandı: dosya mevcut) varyantları da doğrular. Canlı slider = uniform güncellemesi, yeniden derleme yok.

---

## 6. Şema 1 -> 2 migration

### 6.1 Mevcut altyapı (doğrulandı)
- `ProjectMigrations`: `MIGRATIONS = emptyList()`, `CURRENT_VERSION = 1 + MIGRATIONS.size`, `migrate()` zinciri; fazla yeni sürüm -> `Result.TooNew`. `MIGRATIONS[0]` = v1->v2 olacak.
- `GradeStore.kt:272` `load()` -> `parseGradesMigrated` (`:516-529`): zinciri **yalnız GradeStore** çağırıyor; TooNew'de GradeStore salt-okunur (`put/remove` yazmaz).
- Yazım: `AtomicFiles.writeText` (tmp + fsync + `ATOMIC_MOVE`) GradeStore'da kullanılıyor; **PresetStore** (`tmp.writeText` + `renameTo`, başarısızsa doğrudan `writeText`) ve `TimelineStore` (aynı desen) atomik **değil** (`PresetStore.kt:139-150`, `TimelineStore.kt:92-96`).
- Grade taşıyan bölümler: `grades` (kök), `presets`, `exports[].snapshot` (`ExportStore.renderSnapshot/parseSnapshot`), ayrıca taşınabilir preset dosyası (`PresetFile`; **kendi** `formatVersion=1` + migrate zinciri). `data/timeline` içinde `GradeK` referansı yok (grep): klip grade'i `sourceId` ile `grades`'ten gelir.

### 6.2 Alan eşlemesi: GradeK (v1, düz) -> ClipGradeV2
| v1 alanı | v2 yeri | Not |
|---|---|---|
| `exposureEv`, `tempK`, `tint`, `asShot`, `highlightReconstruction`, `keepVignetting` | `develop.*` | `asShot` yoksa mevcut kural (tempK=5600 && tint=0) v1 ayrıştırıcısında kalır |
| `noiseReduction`, `chromaDenoise`, `temporalNr`, `temporalStrength` | `develop.*` | omit-when-neutral korunur |
| `sharpness`, `detail` | `develop.*` | çalışma sırası legacy blok SONRASI kalır (v1 sırası) |
| `highlights`, `blackPoint`, `whitePoint`, `shadows`, `midtones`, `contrast`, `wheel*` x6, `split*` x3, `saturation` | `legacy.*` | **Primary node'a çevrilmez** |
| `tonemap`, `lutId`, `lutStrength`, `lutInterp` | `output.legacy.*` (`mode: "legacy"`) | LUT rolleri (`SceneLook/DisplayLook/SceneToDisplay`) mevcut `applyManagedLutStage` yolunda kalır |
| `cropAspect`, `cropX/Y/W/H`, `straightenDeg` | `transform.*` | |
| `stabilization`, `stabilizationMode/Strength/ZoomLimit/TrimMs/FocalMm`, (S4) `stabilizationEngine` | `transform.stab{}` | bkz. §7 |
| `version` | `version: 2` | |
| (yok) | `nodes: []` | boş |

`blackPoint/whitePoint`: plan §4.1 bunları develop'a "RAW seviyesi" olarak koyuyor; kodda aşama 9/10 demosaic sonrası ton-uç dönüşümü (A6: k=0.12) ve legacy sırada exposure'dan **önce** -> `legacy`te kalır (§9 çelişki 3).
Sıra korunumu: v1 aşama sırası tone(9-13) -> exposure(14) -> highlights(15) -> WB(16/17) -> wheel(18/19) -> sat(20). Exposure/WB `develop` alanıdır ama legacy kipte POST bu sırayı **aynen** uygular (alanlar farklı gruplarda olsa da yürütücü aynı); native kipte legacy alanları nötr olduğundan sıra fark etmez.

### 6.3 Görsel eşdeğerlik
- **Hedef: migrate edilmiş v1 projeler bit düzeyinde aynı pikselleri üretir** (plandaki "<=1 LSB CPU" eşiğinden daha sıkı, çünkü aynı kod yolu): parcel birebir aynı + `nodes=[]` (NODES pass eklenmez) + `output.mode=legacy` (TAIL v1 varyantı).
- **Bilinçli fark yok**; ama iki nokta açıkça yönetilmeli:
  1. **"Girdisi olmayan" klipler.** v1'de `grades` içinde kaydı olmayan kaynak `GradeK()` (tonemap=0) ile render ediliyor. Yeni klip varsayılanı legacy/off dışında bir şey olursa (ör. OpenDRT) mevcut ama hiç gradelenmemiş klipler **yükseltmede sessizce başka görünür**. Çözüm: migration, `sources` bölümündeki her `sourceId` için eksik girdiyi **açık legacy-nötr kayıtla** yazar; yeni varsayılan yalnız yeni içe aktarımlara uygulanır (K3).
  2. **Encoded klipler:** v1 CPU yolu aşama 9-20'yi yok sayar ve WB'yi farklı formülle (`1 + 0.002*Δ`) uygular (`Transfer.cpp:105-130`). Legacy kip encoded'da bu davranışı **aynen** korur (alanlar saklanır, uygulanmaz). Node'lar encoded'a yeni özellik olarak uygulanır (K11). E0'ın renk düzeltmesi (Mi-Log gamut/aralık) bu eşdeğerliğin **dışındadır** (B1 §3.5: kabul testi plan şeklidir, piksel eşitliği değil); v1<->v2 karşılaştırması aynı (E0 sonrası) kod tabanında yapılır.

### 6.4 Kullanıcı verisi güvenliği
1. **Tek giriş kapısı** (F1-2): `ProjectFile.open(file)` uygulama başlangıcında, **hiçbir store okumadan önce**: dosyayı okur, sürümü belirler.
   - `> CURRENT` -> **global salt-okunur** (bugün yalnız GradeStore biliyor; diğer store'lar yazarsa `version` damgasını ezer, §6.5).
   - `< CURRENT` -> yedek + migrate + atomik yazım (aşağıda).
2. **Yedek:** migrate öncesi `project.json` -> `filesDir/backups/project-v1-<yyyyMMdd-HHmmss>.json` (kopya, fsync, aynı dizinde tmp + atomik taşıma). Mevcut yedek **asla ezilmez**; migrate başarısız olursa dosyaya dokunulmaz. Saklama: en az bir v1 yedeği kalıcı (silme yok). Kullanıcıya iletme: ilk açılışta tek seferlik "Proje dosyası yeni sürüme yükseltildi, yedek: ..." (en/tr metin, ayrı iş).
3. **Atomik yazım:** tüm kök **bellekte** migrate edilir, `AtomicFiles.writeText` ile tek seferde yazılır. Çökme: ya eski v1 dosya ya tam v2 dosya (mevcut `AtomicFiles` sözleşmesi). Migrate ortasında yazma yok.
4. **İdempotent + güvenli hata:** adım saf fonksiyondur (`Map -> Map`, mevcut sözleşme). Bir grade nesnesi çözülemezse o kayıt **ham** taşınır (legacy kip + alan başına varsayılan; mevcut v1 ayrıştırıcının alan-bazlı toleransı korunur); tüm kök başarısızsa **yazma yok**, uygulama salt-okunur uyarıyla açılır (veri kaybı yok).
5. **Geri dönüşsüz adımlar:** v2 dosyası eski sürüm uygulamada açılmaz (TooNew, salt-okunur) -> **yedeğe geri dönüş = yedek dosyayı geri koymak** (yükseltmeden sonraki düzenlemeler kaybolur; kullanıcıya söylenir). v2 -> v1 otomatik indirgeme **yok** (nodes/DRT ifade edilemez). K4: alternatif "çift dosya" modeli (v2 yan dosyada, v1 dosya eski sürüm için dokunulmadan kalır): daha güvenli ama iki gerçek kaynağı ve senkron riski getirir; öneri **yedek + yerinde migrate**.
6. **ExportStore kuyruğu:** `exports[].snapshot` içindeki grade nesneleri aynı adımla dönüştürülür. Yükseltme sırasında yarım kalmış job'ların nasıl ele alındığı **doğrulanmadı** (K13).
7. **PresetStore/PresetFile:** `presets[].grade` aynı adım. Taşınabilir preset dosyası: v2 build v1 dosyasını (kendi `migrate`'i) okur; v2 dosyayı `formatVersion=2` ile yazar; v1 build v2 dosyayı zaten reddeder ("newer -> refused" kuralı mevcut).

### 6.5 Kritik bulgu: version damgası
`ProjectJson.withSection(..., version)` yazılan her dosyaya **çağıranın** sürümünü damgalar ve diğer bölümleri ham yeniden render eder.
Çağıranlar: `ExportStore:91`, `GyroStore:129`, `LutStore:228`, `AudioStore:91,118`, `SourceRepository:532`, `TimelineStore`, `MetadataStore`, `PresetStore:143`; bunların `SCHEMA_VERSION` sabitleri hep `1` (grep, doğrulandı; Timeline ve Metadata dahil hepsi `version = SCHEMA_VERSION` geçiriyor). Yalnız `GradeStore` `ProjectMigrations.CURRENT_VERSION` kullanıyor.
Sonuç, F1 gönderildiğinde: kullanıcı v2 dosyasında kaynak içe aktarırsa `SourceRepository` yazar, `version:1` damgalanır, `grades` bölümü v2 şekliyle kalır -> sonraki açılışta v1->v2 adımı **v2 verisine** uygulanır (bozulur). Aynı hata yeni sürüm dosyasının eski uygulamayla açılmasında da vardır (eski build diğer bölümlere yazar, damgayı v1 yapar). Bugün **gizli**, çünkü v2 yok.
Düzeltme (F1-2, zorunlu): `withSection` sürüm parametresini yok sayar; damga = `max(dosyanın mevcut sürümü, CURRENT_VERSION)` yalnız `ProjectFile.open` migrate ettikten sonra; mevcut sürüm `> CURRENT` ise hiçbir store yazmaz. Tüm store'lar `AtomicFiles`'a taşınır. Test: "her store'un yazımı v2 damgasını korur".

---

## 7. Stabilizasyon alanlarının v2'ye katlanması (S4 ile)

S0 `IMAGE_STAB_PLAN.md` (`s0-image-stab-design`, doğrulandı): S4 `GradeStore`/`ExportStore`'a **tek eklemeli** alan `stabilizationEngine: Int` (0 gyro/eski, 1 image; **yalnız 1 iken yazılır**) ekler; sürüm artışı/migration adımı yok. `GradeK`'da şu an böyle bir alan **yok** (doğrulandı: S4 henüz merge değil). Birleştirme sırası: E1 -> S4 -> E0 -> F1 (plan §10): F1, S4'ün üstüne rebase edilir.

Eşleme (v1 düz -> `transform.stab`):
```
"stab": { "enabled": bool, "engine": 0|1, "mode": int, "strength": f, "zoomLimit": f, "trimMs": f, "focalMm": f }
```
- Kural: **`stabilizationEngine` anahtarı yoksa `engine = 0` (gyro)**; migration `engine`'i **açıkça yazar** (`0`), böylece v2 yazarı varsayılanı yeniden türetmek zorunda kalmaz. `stabilization=false` ise (v1 zaten omit-when-off) `stab` nesnesi **hiç yazılmaz**.
- **Kayıtlı veri değişmez** (`FeatureFlags` kuralı): `stabilization=true`, engine yok eski kayıtlar -> `stab{enabled:true, engine:0, mode, strength, zoomLimit, trimMs, focalMm}` **tüm alt değerleriyle** korunur; gyro bayrağı kapalıyken `StabilizationGate` etkin değeri zorla kapatır (mevcut davranış), bayrak açılınca eski davranış döner. `engine=1` iken `mode` 2 okunursa 0'a eşlenir (S0 kararı; okuma anında, kayıt değişmez), `trimMs/focalMm` saklanır ama etkisiz.
- `StabilizationGate` (bugün `active(stabilization, gyroPath, enabled)`, doğrulandı) S4'te `engineFor` ile genişler; v2 okuyucusu `stab`'ı düz alanlara indirgeyen bir görünümle (`GradeK` adaptörü, F1-3) aynı kapıyı besler -> **render/parcel tarafı değişmez**.
- **Test vektörü (S4'ün istediği, F1-3 yazar):** (i) v1 + `stabilization=true` + engine yok -> `stab.engine == 0` ve tüm alt değerler eşit; (ii) v1 + `stabilizationEngine=1` -> `engine == 1`; (iii) v1 + `stabilization` yok/false -> `stab` yok; (iv) v2 -> `GradeK` görünümü -> v2 gidiş-dönüş eşitliği; (v) `exports[].snapshot` için (i)-(iii).
- A7 `pausedSkip` kuralı (gyro kapalıyken stab vakaları atlanır) değişmez; `ExportAbSweep` vakaları v2 okuyucusundan geçen aynı snapshot ile koşar.
- Not: S0 belgesi (§3.2/§3.3) "ProjectMigrations yükleyiciden çağrılmıyor" diyor; main'de F0 sonrası çağrılıyor (`GradeStore.kt:272, 523`) -> çelişki 12.

---

## 8. Encoded kaynakların renk tanımı (E0) ile ilişki

### 8.1 Bugünkü durum
- `EncodedAdapters.kt` (doğrulandı): yalnız `KEY_COLOR_STANDARD` okunur; BT.601 değilse **BT.709** sayılır (BT.2020 dahil) -> `isBt709` boolean. `KEY_COLOR_RANGE`, `KEY_COLOR_TRANSFER`, bit derinliği **okunmaz** (decode tarafında; yalnız export yazıcısında `KEY_COLOR_RANGE/TRANSFER` var).
- `YuvFrame` `uint8_t` (`Transfer.h`); `developEncoded` 8-bit limited BT.601/709 çevirip her kanala Mi-Log eğrisini koşulsuz uygular (`Transfer.cpp:147-150`).
- Fikstür (plan §13.14'ten alıntı; bu pakette ffprobe çalıştırılmadı, **yeniden doğrulanmadı**): `VID_20261110_004247.mp4` = HEVC Main10, P010, **FULL range**, BT.2020 NCL, transfer kodu **254**.
- B1 modeli (`SourceProfile.kt`, 27 test, uygulamada henüz kullanılmıyor): `SourcePrimaries/Transfer/Matrix/Range`, `ProfileProvenance`, `WorkingSpace { LinearRec709 }`, `planFor(profile)`.

### 8.2 E0 ne düzeltecek, F1 ona nasıl bağlanır
- E0: `bitDepth / range / matrix / transfer` açıkça taşınır (P010, full range, BT.2020 NCL, Mi-Log=254 tanıma), `planFor` ile `YcbcrToRgb -> DecodeTransfer -> GamutToWorking(BT2020->BT709)`; preview ve export aynı kaynak tanımı. Sonuç: **lineer Rec.709 görüntü** `developEncoded`'in "RGB girişi" olur.
- F1 ile arayüz: F1'in NODES/OUTPUT aşamaları **bu lineer 709 görüntüyü** girdi alır (§3.2 satır 1). K1-A (develop=709) seçilirse E0'da **değişiklik gerekmez**; K1-B seçilirse E0'ın `GamutToWorking` hedefi DWG olur ve E0/F1 birlikte tasarlanmalıdır (bu yüzden K1-A önerildi).
- `SourceProfile` **grade'e girmez** (B1 §3.2/3.6): kaynak gerçeği; v2 grade şeması encoded kaynak tanımı taşımaz, migration'ı bundan etkilenmez. Profil kimliği önbellek anahtarına girer (B1 §3.6): node cache (F2) eklenince anahtar = (kare, grade özeti, profil kimliği).
- Sıra: E0 F1'den önce merge (plan §10). F1 testleri E0 sonrası main'e karşı yazılır.
- GPU: encoded -> GPU geliştirme yolu yok (`app/src/main/cpp/gles` altında yok; yalnız `jni_bridge.cpp` encoded'ı anıyor, doğrulandı). F1 N1'de encoded için NODES/OUTPUT **CPU referansıyla** çalışır (§3.4); GPU karşılığı F2'nin "MediaCodec -> OES -> GPU" yoluna bağlıdır.

---

## 9. Plan ile kod arasında bulunan çelişkiler

Ürün davranışını değiştirenler **koyu** işaretli.

1. Plan §1.3.1/§4.1 sıra: "PRE -> NR -> [lineer -> DWG -> DI] -> NODES -> DRT". Kodda NR'den sonra POST (9-20), keskinleştirme (21/22) ve warp gelir; DWG girişinin bunlara göre yeri planda belirsiz. Bu belge: NODES warp'tan sonra (§3.2, §5.3).
2. **Plan §4.4 "GradeK -> tek node Primary, aynı pikseller, <=1 LSB" ile D2 "node'lar DI/log'da" birbiriyle çelişir:** v1 aşamaları lineer 0.18 pivotlu, kanal-bazlı, 709'da; DI'da aynı kontrast/gamma matematiği farklı sonuç verir. Çözüm: legacy blok (K2). Sahip "Primary node" isterse **eski projelerin görüntüsü değişir** (kabul eşiği değişikliği: durma koşulu).
3. Plan §4.1 `blackPoint/whitePoint` "RAW seviyesi" develop alanı der; kodda aşama 9/10 demosaic-sonrası ton-uç dönüşümü ve sıralı legacy blok parçasıdır (§6.2).
4. Plan §4.1 exposure/WB'yi develop'a koyar; v1'de tone(9-13) exposure(14)'ten **önce** çalışır, wheel/split WB'den sonradır. Alanları gruplamak yürütme sırasını değiştirirse piksel değişir -> yürütücü legacy blokta v1 sırasını korur.
5. **Plan §4.2 "LUT node başına, mevcut LutStore/LutInterp buraya taşınır":** `LutInterp` rolleri (`SceneToDisplay` vb.) bugün tonemap ile birlikte TAIL'de (`applyManagedLutStage`). Taşımak v1 LUT görünümünü bozar; bu belge: eski LUT `output.legacy`'de kalır, node-LUT yeni özelliktir.
6. Plan §4.3 UBO der; kodda UBO yok, düz uniform (§5.4). §4.3 "tek grade pass" ile warp konumu da belirsiz (§5.3).
7. Plan §4.4 `GradeClipboard`/`GradeHistory` node bazında der; ama grade taşıyan **kalıcı** yerler (`presets`, `exports[].snapshot`, taşınabilir preset dosyası) planda geçmiyor (§6.1).
8. Plan §3.2 "migration zinciri kuruldu" doğru, ama **yalnız GradeStore** bağlı; 8 store version'ı kendisi damgalıyor (§6.5). Plan bunu bilmiyor.
9. Plan §13.6 OpenDRT'de PQ preset'i anar; Klipp portunda yalnız SDR+HLG var (§4.1).
10. **SDR çıkış kodlaması:** v1 TAIL = Rec.709 OETF (`linearToRec709`: 0.45 gamma + 4.5 lineer parça). OpenDRT SDR = display kodlama (sRGB 2.2 veya Rec.1886 2.4). Dosya etiketi bugün `COLOR_TRANSFER_SDR_VIDEO` (`MediaCodecMp4Writer.kt:676`). **Yeni varsayılan OpenDRT olursa çıktının görünümü ve etiket yorumu değişir** (K3); OpenDRT çıkışlarında etiket E1/E2'de gözden geçirilmeli.
11. **Lisans:** plan D5 "konu dışı"; repo Apache-2.0, OpenDRT GPLv3 türevi (§4.5) -> dağıtım yükümlülüğü ürünü etkileyebilir; sahibin tek seferlik teyidi önerilir.
12. S0 `IMAGE_STAB_PLAN.md` §3.2/§3.3: "ProjectMigrations yükleyiciden çağrılmıyor" (**bayat**): main'de çağrılıyor. Aynı belge "sürüm artışı gerekmez" der; F1 ile v2 gelince S4'ün eklemeli alanı v2'de katlanır (§7).
13. Doğrulanan iddialar: plan §13.5 "DI sabitleri Color.h'ta zaten var" doğru (§2.2); %18 gri -> 0.336043 doğru (hesaplandı); plan §1.3.1 "32 kombinasyon" doğru.
14. D2 "contrast pivot 0.336" yalnız DI node'ları için geçerli; legacy contrast pivotu lineer **0.18** (`Develop.cpp:1022`) ve değişmez.
15. İstekte anılan `DESIGN.md` yok; `STEPS/` dizini bu dalda yok (doğrulandı: `ls`).

---

## 10. Piksel parity stratejisi

Katman 1 - **Matematik (C++ doctest, masaüstü):**
- DI kodlama/çözme gidiş-dönüş (negatif ve > 1 girişler dahil), 709 -> DWG -> 709 matris kimliği (float toleransı ölçümle), DWG matrisini Klipp ile karşılaştırma.
- `Nodes.cpp` vektörleri (primaries/log wheel/eğri/LUT/mix); nötr node = kimlik; `nodes=[]` fast path.
- `OpenDrt.cpp` Klipp referansları (kökeni doğrulandıktan sonra, §4.1).
- `test_chain_slots.cpp` 64 kombinasyon (§5.2).

Katman 2 - **Migration (JVM, `ProjectMigrationsTest`/`GradeStoreTest`/yeni):**
- **Altın v1 dosyaları** (repoya küçük JSON): her omit-when-neutral kombinasyonu, tüm alanlı grade, `lutInterp`'li, stab (engine yok/1), `presets`, `exports` snapshot, `sources` olup `grades` kaydı olmayan kaynak (§6.3), bozuk/eksik alan.
- **Parcel eşitliği:** v1 dosyası -> doğrudan v1 ayrıştır -> `GradeParcel.encode`; migrate -> v2 okuyucu -> `GradeK` görünümü -> `encode`: **bit-aynı** FloatArray. Renderer değişmediği sürece piksel eşitliğinin ucuz ve deterministik kanıtıdır (cihaz gerekmez).
- Özellik testi: rastgele `GradeK` -> v2 yaz -> oku -> `GradeK` eşit; migrate idempotent; `TooNew`; her store yazımında damga korunur (§6.5).

Katman 3 - **CPU render:** `developCpu`/`developEncoded` v1 grade ile v2-eşlenmiş grade bit-aynı (mevcut altın hash altyapısı; `Develop.cpp` yorumları "locked by the golden hashes" diyor).

Katman 4 - **GPU <-> CPU (cihaz, A7 + GradeParitySweep):**
- Mevcut kapı (`ExportAbSweep.kt:115-120`, doğrulandı): `mismatch% > 0.5` **veya** `maxDiffLsb > 2` (8-bit baytlar) -> `EXPORT_MISMATCH`. Migrate edilmiş legacy vakalar bu kapıdan **değişmeden** geçmeli.
- Yeni vakalar: `nodes-primary`, `nodes-curves`, `nodes-lut`, `drt-sdr`, `drt-hlg`, `grade+nodes+drt`, `nodes+warp` (warp sonrası NODES slotu; warp slot hatasının dersi). `GradeParitySweep`'e node/DRT kontrol sırası.
- OpenDRT GPU-CPU eşiği **ölçümle** sabitlenir; mevcut kuraldan gevşetme = "kabul eşiği değişikliği": sahip onayı (durma koşulu).
- 10-bit/HLG kod değeri karşılaştırması E1d/E2'ye aittir (8-bit bayt karşılaştırıcısı HLG kodlarını yeterince ölçmez); F1'de yalnız float HLG sinyali C++ ile karşılaştırılır.

Katman 5 - **v1 <-> v2 geçiş doğrulaması (cihaz):** F1 merge'inden **önce** main'den "karne": sabit klip kümesi x vaka (neutral, grade, lut, tonemap, crop, stab varsa) için kare başına **CRC32/özet** log'u (harness'e eklenir; bugün harness istatistik yazıyor, özet yazmıyor, doğrulandı). F1 build'inde aynı kümeden migrate edilmiş projelerle **aynı özetler** beklenir (legacy kip). Fark = durma koşulu. Cihaz çalıştırması sahibe aittir.

---

## 11. İş kırılımı (F1-1 ... F1-8), bağımlılıklar, riskler

Sınıf: S/M/L (plan §10 sınıflaması; ölçülmüş süre yok). **Sahip onayı olmadan F1-1 başlamaz.**

| # | İş | Kapsam | Boyut | Bağımlılık |
|---|---|---|---|---|
| F1-1 | Renk matematiği + CPU node referansı | `ColorModel.h`, `Nodes.cpp`; 709<->DWG/DI glue; test vektörleri; Klipp `GradeNode`'dan alma | M | onay |
| F1-2 | Proje dosyası güvenliği | `ProjectFile.open` kapısı, `withSection` damga politikası, yedek, `AtomicFiles`'a geçiş (Preset/Timeline), global TooNew; 8 store testi | M | onay; S4 sonrası rebase |
| F1-3 | Şema v2 + migration | `ClipGradeV2` + JSON, `MIGRATIONS[0]`, sources materyalizasyonu, presets/exports/taşınabilir preset, `GradeK` adaptörü, altın dosyalar, stab katlama | L | F1-2, S4 merge |
| F1-4 | OpenDRT C++ referans | `OpenDrt.cpp/.h`, Klipp vektörleri, `NOTICE` | M-L | F1-1 |
| F1-5 | GPU | `kPassNodes` + `planChain` + 64 kombinasyon testi, NODES shader, TAIL OpenDRT varyantı, `NodeParcel` + parcel append, uniform/unit sorguları | L | F1-1, F1-4 |
| F1-6 | Bağlama | JNI, `GradeMapping`, `PreviewRenderer`, `ExportJob` snapshot, köprü OETF atlama, `developEncoded` kancası, `StabilizationGate` | M-L | F1-3, F1-5 |
| F1-7 | Parity | `GradeParitySweep`/`ExportAbSweep` vakaları, kare özeti log'u, JVM parcel eşitliği | M | F1-5, F1-6 |
| F1-8 | UI (node şeridi, Develop/Output kartları, en/tr, 48dp) | ayrı onay | L | F1-6 |

Sıra: F1-1 ve F1-2 paralel; F1-3 -> F1-6; F1-4 -> F1-5; F1-7 sonda. E2/E3 F1 N1 merge sonrası (plan §10). N2/N3/N4 bu belgenin kapsamı dışındadır.

Riskler:
| Risk | Etki | Önlem |
|---|---|---|
| version damgası sessiz bozulma (§6.5) | Yüksek | F1-2 zorunlu, store testleri, F1-3'ten önce merge |
| Eski projelerin görüntüsü değişimi ("girdisiz klip", varsayılan çıkış) | Yüksek | §6.3 madde 1 materyalizasyon; karne (Katman 5) |
| 709 develop'ta negatif değerlerin kırpılması (K1-A doğrulaması) | Orta | F1-1 testi; aksi halde K1-B tartışılır |
| OpenDRT piksel-başı GLSL maliyeti 4K preview'de | Orta | cihazda ölç; 33³ bake yedeği (hata ayrı ölçülür) |
| Adreno RGBA16F 3D LINEAR / sampler unit sayısı | Orta | mevcut LUT yolu kullanılıyor; cihaz probe + unit sorgusu |
| S4/E0 ile dosya çakışması (`GradeStore`, `ExportStore`) | Orta | merge sırası E1 -> S4 -> E0 -> F1; F1 rebase |
| OpenDRT lisansı | Orta | K6 teyit + `NOTICE` |
| Referans vektör kökeni belirsiz | Düşük | bağımsız üretim / kaynak kontrol (§4.1) |

---

## 12. Sahibin onayına açık kararlar

- **K1:** Develop uzayı: (A, öneri) lineer 709 kalır, DWG yalnız NODES kapısında; (B) PRE'de kamera -> DWG (E0 hedefi de DWG olur). Plan §13.5'ten sapma.
- **K2:** Eski projeler legacy kipinde (bit-aynı, öneri) mi, yoksa plan §4.4'teki gibi "Primary node"a dönüştürülüp görünüm farkı kabul mü edilir?
- **K3:** Yeni klip varsayılan çıkışı: legacy/off (bugünkü) / OpenDRT SDR Standard / ACES. Hiç gradelenmemiş mevcut klipler için "açık legacy-nötr kayıt yaz" onayı (§6.3).
- **K4:** Migration modeli: yerinde migrate + yedek (öneri) / çift dosya. Yedek saklama ("asla silinmez" önerisi).
- **K5:** F1-2'nin 8 store ve `ProjectJson`'a dokunması (damga politikası, atomik yazıma geçiş) F1 paketine dahil mi; eski sürüm uygulamaların v2 dosyada salt-okunur olması.
- **K6:** OpenDRT GPLv3 türevi, repo Apache-2.0: "konu dışı" kararının dağıtım için de geçerli teyidi; `NOTICE` kaydı.
- **K7:** OpenDRT: önce piksel-başı GLSL, cihazda ölçüp gerekirse LUT bake (öneri); N1'de açılacak parametre kümesi; PQ N1 dışı.
- **K8:** NODES ayrı pass (öneri) / TAIL'e gömme; N1 üst sınırları (8 node, 4 node-LUT; ölçülmemiş hipotez).
- **K9:** Kabul eşikleri: legacy için "bit-aynı" (plandaki "<=1 LSB"den sıkı); OpenDRT GPU-CPU eşiği ölçümle sabitlenir, gevşetme gerekirse yeniden onay.
- **K10:** Stab: `transform.stab` iç içe nesne (öneri, S0 §5b.3.7 ile uyumlu) / düz alanlar kalsın.
- **K11:** N1 node'ları encoded klipler için de (CPU) açık mı? Legacy blok encoded'da etkisiz kalmaya devam eder.
- **K12:** Taşınabilir preset formatı `formatVersion 2` ve v1 dosyalarının içe aktarımı.
- **K13:** Kuyruktaki export job'larının yükseltmedeki davranışı (yeniden başlat/iptal).
- **K14:** F1 N1 koduna başlama onayı ve zamanı (S4 + E0 merge sonrası).

## 13. Doğrulanmayanlar (özet)
Klipp `GradeNode`/`CURVE_N` ve Klipp DWG matrisi ile `Color.cpp` eşitliği; Klipp OpenDRT referans vektörlerinin kökeni; orijinal OpenDRT v1.1.0 PQ ve `itrp-patches` GLSL; piksel-başı OpenDRT'nin cihaz maliyeti; Adreno RGBA16F 3D LINEAR; GL uniform/texture-unit gerçek limitleri (spec asgarileri hatırlandı); develop aşamalarında negatif değer kırpması; 15 Ultra fikstürünün etiketleri (plandan alıntı); yükseltme anında yarım export job'ları; preview'in gerçek çözünürlüğü.
