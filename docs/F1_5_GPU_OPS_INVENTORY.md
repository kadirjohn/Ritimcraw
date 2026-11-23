# F1-5 hazırlık — OpenDRT + NODES GPU pass'i için saf operasyon envanteri

Durum: **plan, kod yok** (2026-11-24, gece görevi Queue #1, dal
`f1-5-gpu-ops-inventory`, taban `main` @ `fd749d0`). Bu belge F1-5'in
**keşif dokümanıdır**: GPU tarafına neyin ekleneceğinin envanteri.
üretim kodu YOK. F1-5'in kendisi (GPU kodu) sahibin onayıyla sonradan.

Kanıt sözlüğü (COLOR_MODEL_V2_PLAN.md ile aynı):
- **doğrulandı**: bu worktree'de koddan okundu (dosya:satır verilir) veya
  bu pakette hesaplandı (belirtilir).
- **plandan alıntı**: `docs/COLOR_MODEL_V2_PLAN.md` (K1/K7/K8 kararları,
  §5 GPU katmanları) — bu pakette yeniden ölçülmedi.
- **doğrulanmadı**: cihaz davranışı, driver limitleri, derleme süreleri.
  Sayı uydurulmaz; boyutlar S/M/L'dir.

Kaynak satır referansları taban `fd749d0`'dır; e1c-polish `bde227d`/`7b29a29`
main'de DEĞİL ve bu envanterin hiçbir maddesi o kola dokunmaz.

---

## 0. Özet

1. **Envanter temiz:** F1-5 GPU tarafına TAM OLARAK dört şey ekler —
   (i) `ChainSlots.h`'ye `kPassNodes` + `planChain`'e `runNodes` bayrağı,
   (ii) NODES fragment shader'ı (DI dönüşüm + node döngüsü), (iii) TAIL'in
   OpenDRT varyantı, (iv) `NodeParcel` FloatArray + JNI girişi. Kodun geri
   kalanına (PRE/NR/POST/unsharp/warp/kaynak TAIL, GlesShaderSources'ün
   mevcut metinleri) **dokunulmaz** — F1-7 paritesi zaten v1 akışını
   kilitliyor.
2. **Op sayıları (bu pakette hesaplandı, kod satırlarından):** NODES:
   zincir başı 709→DWG (9 MAD) + DI encode (her kanal: karşılaştırma +
   log2 + 2 mad); node başına (derlenmiş uniform şekliyle): primaries
   3×(MAD + koşullu pow) + Y-kolu ~8 mad + kontrast 6 mad + hue 9 MAD + sat 6
   mad + eğri tablo örneği 3× lerp + LUT (varsa DI↔709 4 matris + 3D
   örnekleme) + mix 6 mad — **~50-90 aritmetik madde/node**, 2'si pow
   (gamma ve LUT encode'unda). 8 node dolu zincir ≈ 500-800 madde, tek
   pass'ta dar. OpenDRT: **~200 madde + ~25 pow/exp/log/atan2/sqrt
   (dolaylı çağrı dahil ~45 aşırı-yüklü çağrı noktası)** — TAIL varyantı
   olarak bir çizim, ölçülebilir ama sıcak (§3).
3. **Önerilen shader organizasyonu (K8'e göre):** NODES **tek pass**,
   node'lar shader içinde `for` döngüsü (8 tur, uniform dizilerle);
   OpenDRT **ayrı bir TAIL varyantı** (`#define`/aynı metin dosyasından
   ikinci program — mevcut TAIL metnine satır içi koşul eklemek yerine
   varyant, kaynak TAIL metnini dondurur); node başına ayrı pass REDDEDİLİR
   (plan §5.3: 4K'da her tam çerçeve ~192 MiB bantgenişliği, 8 node = 8
   çizim).
4. **En riskli 3 nokta (§6 detay):** (a) uniform bütçesi — 8 node × ~33
   skaler = 264 float + eğri tabloları — tablo uniform OLMAZ (3×512
   float/node; 1D texture atlası şart, §2.4); (b) cihazın fragment
   uniform vektör limiti ve shader derleme süreleri (NODES+DRT iki büyük
   program, ölçülmedi); (c) NODES pass'in warp'la etkileşimi — NODES
   warp'tan SONRA olduğundan finalSlot zinciri bozulmadan korunmalı
   (warp slot hatasının dersi, §5).
5. **ChainSlots test genişletmesi: GEREKLİ, boyut S.** `planChain`'e
   `runNodes` eklendiğinde kombinasyon uzayı 32→64; mevcut
   `test_chain_slots.cpp` döngülerine bir eksen (nodes 2) + iki invariant
   (nodes varyantı, `runNodes=false` bit-paritesi). Sadece yeni eksen
   eklendiği için test maliyeti yok denecek kadar az (doğrulandı:
   dosya 108 satır, döngüleri yerel).
6. **NodeParcel bütçe tahmini (§4):** 8 node × 33 skaler = 264 slot +
   çıktı-transform 6 slot ≈ 270 float = 1.080 bayt (FloatArray, 4 bayt/
   slot) — tek JNI FloatArray olarak rahat; **uniform bütçesi ayrı
   konu**: 264 skaler ≈ 66 vec4 doldurma, NODES'un ~110 vec4 setine
   katkıda bulunur; GLES ES 3.0'da UBO yok, fragment uniform vektör
   limiti cihazda doğrulanmadı (Adreno 830 tipik ~1024 vec4,
   doğrulanmadı) — eşiği aşma riski VAR; eğri tabloları zaten 1D
   texture atlasına taşındığından (öneri §2.4/§4) skaler set limit
   içinde kalır.
7. **OpenDRT piksel-başı GLSL fizibilite (K7 önce-önerisi):** SAĞLAM.
   C++ port zaten float ve GPU ile aynı işlem sırasını koruyor (kaynak
   `OpenDrt.cpp:19-20` bizzat bunu söyler); ~45 aşkın-fonksiyon çağrı/piksel
   sıcak ama 4K preview'de bile modern Adreno yakalar (doğrulanmadı —
   ölçüm K7 çerçevesinde kalır); 33³ bake'in enterpolasyon hatası
   plan §4.3'te gerekçeli. Ölçüm gelmeden bake'e geçilmesi önerilmez.
8. **Hiçbir mevcut dosyada satır değişikliği bu pakette YOK** (docs-only).
   F1-5'in dokunacağı dosyalar §5'te listelenir.

---

## 1. Mevcut GPU envanteri (doğrulandı)

### 1.1 Pass listesi ve slot düzeni

Zincir (GpuDevelopPipeline.cpp:1481-1830, `draw()`):

| # | Pass | Shader | Girdi → çıktı | Kapı |
|---|---|---|---|---|
| 1 | PRE (stages 1-6) | preProgram | CFA (unit 0) → texA | her zaman |
| 2-4 | NR chroma H/V, NR luma (7/8) | nrChromaH/V, nrLuma | texA → texB(→texC), guide unit 4 | grade (raw≠0) + w/h koruması |
| 5 | POST (stages 9-20) | postProgram | plan.in → plan.out | her zaman |
| 6-9 | Sharpness H/V, Detail H/V (21/22) | sharp/detail | slot rotasyonu + guide unit 4 | grade (raw≠0) + w≥2/h≥2 |
| 10 | Gyro warp (donanım aşaması değil) | warpProgram | plan.warp.in → out | req.warpActive (kare-başı) |
| 11 | TAIL (23+24+OETF) | tailProgram | plan.finalSlot → developTex | her zaman (uOetf=1) |

- Slotlar: `ChainSlots.h:14-25` — 3 adet RGBA32F ping-pong (texA/texB/
  texC), `chainNextSlot` A→B→C→A; `planChain(nrLive, runSharp, runDetail,
  runWarp)` (`:46-72`) tüm in/out/guide'ı tek yerden verir
  (2026-11-23 warp slot hatasının dersi — formüller elle yazılmaz).
- developTex: TAIL'İN çıktısı, oturum sonrası pass'ler (store/blit/
  window) onu okur (`GpuDevelopPipeline.h:21-24`); TAIL her zaman
  developFbo'ya yazar (`GpuDevelopPipeline.cpp:1773-1781`).

### 1.2 Texture formatları ve birim ataması

| Birim | İçerik | Format | Filtre |
|---|---|---|---|
| 0 | CFA | GL_R16UI (tam sayı) | NEAREST (texelFetch) |
| 1 | zincir pass kaynağı (tüm pass'ler) | RGBA32F | NEAREST (`:1159,1218,1245`) |
| 2 | NR kroma çifti için geçici (scratch) | RGBA32F | NEAREST |
| 3 | shading haritası | GL_R32F | texelFetch (R32F ES 3.0'da filtrelenemez — bizzat kod yorumu) |
| 4 | dikey pass kılavuzu | RGBA32F | texelFetch/NEAREST |
| 5 | 3D LUT (stage 24) | **RGBA16F** | **GL_LINEAR** üç eksende (donanım trilinear, `:1340`) |

Adreno NEAREST/R32F kısıtı: zincir ve R32F texelFetch — sorun yok
(IMAGE_STAB_PLAN.md §162 da not eder: warp texelFetch + el bilinear).
**RGBA16F 3D LINEAR filtreleme cihaz-kanıtı bu repoda YOK**
(COLOR_MODEL_V2_PLAN.md §5.1: "doğrulanmadı"; LUT portu cihazda koştu ama
**ölçüm kaydı dokümante edilmedi** — E1 ölçümlerinde ayrıca yok,
doğrulandı: docs'ta RGBA16F cihaz ölçümü bulunamadı).

### 1.3 Mevcut uniform sayıları (sayıldı)

- `glUniform*` çağrı noktaları: **79** (GpuDevelopPipeline.cpp, bir kez
  sayıldı; başına init zamanı konum çözümü ~100+ `glGetUniformLocation`).
- POST pass'i: 22 uniform (10 noktasal stage ham değeri + 2 int kapı +
  boyut) — develop-tail'ine paralel en yoğun mevcut pass.
- TAIL: uTonemap, uOetf + 12 LUT seti (`GlesShaderSources.kt:465-479`) =
  14 uniform.
- Fragment uniform vektör limiti için **hiçbir yerde glGetIntegerv
  sorgusu yok** (GL_MAX_TEXTURE_SIZE ve compute eksenleri dışında —
  GlesPipeline.cpp:290-315). F1-5'in "uniform/unit sorguları" kalemi
  (plan §11 F1-5 satırı) tam da bu boşluğu doldurur (§4.3).

### 1.4 Slot içi bellek

- 4096×3072 RGBA32F = 192 MiB/texture; 3 slot + developTex ≈ 768 MiB
  (plan §5.3 aynı aritmetik). NODES mevcut 3 slotu kullanır — yeni
  texture yok (tek pass, in→next).
- NEAREST okuma/yazma bant genişliği: pass başına 2×(192 MiB) @4K; NODES
  1 pass ekler, DRT varyantı TAIL'in yerine geçer (ek çizim yok, §3).

---

## 2. ColorModel → GLSL op tablosu (NODES pass'inin çekirdeği)

Kaynak: `core/src/ColorModel.cpp:99-228` (`compile` + `applyCompiled`) —
GLSL'e çevrilecek **piksel-başı** liste. "Derleme-zamanı" sütunu CPU'da
bir kez hesaplanıp uniform'a gider (GPU'da per-pixel HESAPLANMAZ — bu
parçalanmanın kendisi iyi haber: node matematiği piksel içinde ucuz,
ağırlık tablolarda ve kare köklerde).

| Alt işlem | CPU (satır) | GLSL pseudocode (piksel) | madde | Uniform (ad, tip, boyut) |
|---|---|---|---|---|
| 709→DWG matris | ColorModel.cpp:240-245 | `dwg = uM709ToDwg * di` | 9 MAD | `uM709ToDwg` mat3 (1 sabit, init) |
| DWG→DI encode | Color.cpp:350-353 | `di = lin<=0.00262409 ? lin*10.444 : 0.0733*(log2(lin+0.0075)+7)` | kanal başına 4-5 | sabit (shader içi) |
| primaries: lift/gain/offset | ColorModel.cpp:183-185 | `c = uLift[i] + uGain[i]*x + uOffset[i]` | 9 MAD | `uNodeLift[8]` vec3 dizi, `uNodeGain[8]`, `uNodeOffset[8]` (3×8=24 vec3) |
| primaries: gamma | `:184` | `pow(max(c,0), uGammaExp[i])` | 3 pow | `uNodeGammaExp[8]` vec3 dizi |
| Y-kolu | `:186-196` | `yv = a+b*yIn+offLuma; pow?; d = yv-luma; x += d` | ~12 MAD + 1 pow | `uNodeYSide[8]` vec3 |
| kontrast/pivot | `:197-198` | `x = (x-uPivot)*uContrast + uPivot` | 6 MAD | `uNodeContrast[8]` float, `uNodePivot[8]` float (varsayılan ortak pivot) |
| WB (temp/tint) | `:129-131` + `:198` | `x *= uWb[i]` | 3 MAD | `uNodeWb[8]` vec3 |
| hue matrisi | `:132-139,200-202` | `h = uHueMat[8]*x` | 9 MAD | `uNodeHueMat[8]` mat3 dizi |
| sat | `:203-207` | `x = luma + (h-luma)*uSat` | 6 MAD | `uNodeSat[8]` float |
| eğriler (Y sonra R/G/B, log wheel'ler dahil kompozit) | `:143-167` (derleme) + `:208-211` + `:261-268` | `s = tablo1D(asama, x)` — tablo ÖRNEKLEMESİ, aritmetik değil | 3× (uv hesap + lerp ≈ 8) | **eğri tablosu: uniform DEĞİL, 1D texture** (§2.4) |
| node LUT | `:212-224` | DI→709 (mat) + encodeTransfer (pow'lu, `GlesShaderSources.kt:793-830`'daki aynı rutinler) + `texture(uNodeLut3d[i], t)` + 709 dönüş + `x += (out-x)*uLutMix[i]` | ~20 + 3D örneklem | `uNodeLut3d[4]` sampler3D dizi (4 node-LUT üst sınırı, `ColorModel.h:126`), `uNodeLutMix[8]` float + binding setleri (her aktif LUT için §2.5'teki 12'lik küme) |
| mix | `:225-227` | `x = in + (x-in)*uMix` | 6 MAD | `uNodeMix[8]` float |
| DI→DWG çöz + DWG→709 (veya DRT girişi) | `:428-437`, Color.cpp:439-442 | `lin = di<=0.02740668 ? di/10.444 : pow(2, di/0.0733-7)-0.0075` + mat mul | 3 pow + 9 MAD | sabit + `uMDwgTo709` (yalnız legacy kipta) |

Toplam (piksel, 1 node): **~40-60 mad + 2-4 pow + 0-1 3D örneklem**;
8 node: ~2 kat daha az uniformla 8 kat piksel iş (döngü). Tablo örneklemesi
LUT'suz node'da bir de texture okuması (eğri) ekler.

### 2.1 CPU'da derlenip uniform'a gidenler (GPU'da per-pixel YOK)

- `wheelRgb` (ColorModel.cpp:44-55): kosinüsler/atan2 — **init zamanı**,
  uniform değerleri.
- `primaries` çözümü (`:57-64`): wheel katkılarının lift/gain/exp
  katsayılarına katlanması — init.
- `nodeCurveTable` (Fritsch-Carlson monoton kübik, `:270-327`): 256'lık
  tablo — init; GPU yalnız tablo okur.
- `nodeLogCurve` (`:330-369`): 3072 nokta sayısal integral + yumuşatma —
  init; çıktı 512'lik kompozit tabloya karışır (`:162-167`).
- Kompozit tablo: `sampleExt256(y, sampleExt256(ch, lg[i])))` önceden
  katlanmış — GPU'da TEK örneklem.
Hepsinin GPU tarafı: doldurulmuş uniform dizileri + 1D texture yüklemesi.

### 2.2 Eğri tablo örnekleme sözleşmesi

`nodeCurveSample` (`:261-268`): x ∈ [-0.5, 1.5] (kNodeCurveLo/Hi,
`ColorModel.h:148-150`), 512 giriş, **delta** saklanır (tablo[x] − x),
çıktı `x + d0 + (d1-d0)*f`. GLSL: `float p = (x-uLo)/(uHi-uLo)*511.0;`
texel çifti lerp — mevcut `sampleShading` deseninin aynısı
(texelFetch, filtreleme yok; R32F değil **R32F'e izin**: filtre
TEXELFETCH ile atlanır). Nötr node eğri tablosu = tam sıfır deltası —
uNodeHasCurves bayrağı örnekleme tamamen atlar.

### 2.3 Nötr node / boş zincir hızlı yolları

- `GradeNode::isNeutral()` (`:373-378`) → GPU: `uNodeEnabled[8]` int
  dizisi; shader `if (uNodeEnabled[i]==0) continue`.
- `nodes=[]` + legacy kipt: **pass hiç eklenmez** (plan §1: "nodes boşsa
  pass eklenmez (v1 ile bit-aynı)") — planChain'e nodes=false olarak
  yansır.
- Boş zincir + DWG çıkışı: yalnız 709→DWG→DRT dönüşümü (applyNodes'ın
  `NodesOutput::DwgLinear` boş zincir kolu, `:410-419`). Bu kol NODES
  pass'inde uniform `uNodesCount==0` ile temsil edilir (yine pass çalışır
  ama içinde yalnız matris dönüşümü — 8 kere değil 1 kez).

### 2.4 Eğri tablosu taşınması: 1D texture atlası mı, ayrı texture'lar mı?

- **Uniform dizisi OLMAZ:** node başına 3×512 float = 1536 slot; 8 node
  = 12.288 float uniform — her cihazın fragment vektör limitini aşar
  (tipik ES 3.0 limit ~1024 vec4 = 4096 float, doğrulanmadı).
- **Ayrı texture'lar da OLMAZ:** node başına 3 texture × 8 node = 24
  sampler — ES 3.0'da fragment sampler sayısı tipik 16 (doğrulanmadı),
  zincir zaten 5 birim kullanıyor (birim 5 LUT dahil).
- **Öneri: 1D texture atlası, tek sampler.** `GL_R32F` 2D texture,
  satırlar: node i kanal c → satır `i*3+c`, genişlik 512, texelFetch ile
  okuma (filtre gereksiz — lerp shader'da elle, `sampleShading`
  deseni). Bir birim (6 numara önerilir), 24 satır × 512 = toplam 12.288
  texel = 48 KiB. Nötr kanallar sıfır deltası satırı. Ayrı bir
  atlas-per-node ayrımı GEREKSİZ — çizim başına bind yok, tek texture.
  Alternatif `GL_RG32F`/tek kanal önerisi duyulmadı; R32F mevcut
  desendir (shading map).

### 2.5 Node LUT'larının taşınması

- Üst sınır 4 aktif node-LUT (`ColorModel.h:126` `kMaxNodeLuts`).
- Öneri: dört **ayrı** 3D RGBA16F texture (mevcut stage-24 LUT'un
  format/bağlama sözleşmesi birebir: DOMAIN, encode/decode transferları,
  çalışma-uzayı matrisleri; `GlesShaderSources.kt:793-830` +
  `GpuDevelopPipeline.cpp:1340`'ın yeniden kullanımı). Birimler: öneri
  6, 7, 8, 9 (kullanılabilirlik F1-5'te sorgulanır — ES 3.0 core
  fragment sampler limiti cihazbaşı doğrulanmadı; 16 üst sınırıyla
  9+5=14 sığar, yoğun ama sınır içinde).
- Init/revizyon zaten mevcut altyapı: `bindLutDummyTex` +
  `floatToHalfBits` (`GpuDevelopPipeline.cpp:310-392`) — node-LUT
  yükleyicisi aynı iki yardımcıyla; dolu olmayan node-LUT birimlerine
  2×2×2 birlik dummy (şart: sampler tamamlığı kuralı, `:369-372`
  yorumunun aynısı).
- Node-LUT bağlama (working→input matrisi, transferlar) paralel: CPU
  `compileLutBinding` per node-LUT init zamanı; shader'a 12'lik set
  uniform dizileri ×4.

### 2.6 Uniform bütçesi özeti (NODES pass)

| Grup | kapasite | Not |
|---|---|---|
| uNodeEnabled[8] | 8 int (2 vec4) | hızlı yol |
| uNodeLift/Gain/Offset/GammaExp/YSide/Wb[8] (vec3) | 6×8=48 vec3 (48 vec4) | |
| uNodeContrast/Pivot/Sat/Mix/LutMix[8] | 5×8=40 float (10 vec4) | |
| uNodeHueMat[8] (mat3) | 8×3=24 vec3 (24 vec4) | |
| eğri atlası | 1 sampler (1-2 birim) | §2.4 |
| node LUT 3D ×4 | 4 sampler | §2.5 |
| node LUT binding ×4 | ~4×12 float (12 vec4) | |
| dönüşüm matrisleri | 2 mat3 (6 vec4) | sabit |
| düz saylar (uNodesCount, uOutputMode...) | ~4 vec4 | |
| **toplam vec4** | **~110-115** | 3D sampler'lar birim taksimatından AYRI |

ES 3.0 spec minimum fragment uniform vektörü **224 vec4** (bizzat spec
asgari; cihaz değeri doğrulanmadı). 110-115 asgarinin ÜSTÜNDE ama tipik Adreno
(~1024) İÇİNDE; ancak mevcut pass'lerin hiçbiri bu büyüklükte olmadığı
için **cihaz sorgusu zorunlu** (F1-5'in "uniform/unit sorguları"
kalemidir; §6 risk 2).

---

## 3. OpenDRT → GLSL op tablosu (TAIL varyantı)

Kaynak: `core/src/OpenDrt.cpp:234-423` (`Impl` + applyXyz). Adım adım;
"derleme zamanı" sütunundaki her şey `OpenDrt::Impl` kurucusunda
(`:241-271`) zaten hesaplanıyor — GLSL uniform'una birebir gider.

| Aşama | CPU (satır) | GLSL piksel maliyeti | Uniform (ad, tip) |
|---|---|---|---|
| Giriş matrisi DWG→XYZ (applyDwgLinear) | `:430-433` | 9 MAD | sabit mat3 (shader sabiti) |
| XYZ→P3D65 rendering space | `:302` | 9 MAD | sabit mat3 |
| rendering-space saturation + offset | `:305-307` | 6 MAD | `rs_sa`, `rs_rw`, `rs_bw`, `tn_off` float |
| tsn normalizasyonu | `:309-310` | 3 mul + 3 div + sqrt = 7 | sabit kSqrt3 |
| karşıt (opponent) vektör + achD | `:312-315` | ~8 (2 sub + 2 mul + sqrt + bölme + toe-quadratic 3) | sabit (0.25, 1.25) |
| hue hesabı | `:316` | atan2 + fmod + add = 3 | sabit |
| 9 gauss hue penceresi | `:318-326` | 9× (fmod + exp + bölme) = 27 | sabit offsetler/genişlikler |
| brl (highlight roll-off) | `:328-333` | pow + pow + exp2 + ~10 MAD = 13 | `brl*` 7 float (preset'ten) |
| lcon (düşük kontrast) | `:334-337` | compressToeCubic İLERİ: ~6 (cbrt dahil) | `lconM/W/Sc` 3 float (KURULUMDA) |
| hcon (yüksek kontrast) | `:338` + `:215-224` | contrastHigh: 2 exp2 + 3 pow + 2 cmp + ~6 = 12 | `hconP` (kurulum), `hc_pv/st` 2 |
| 3× hyperbolic kompres | `:340-342` + `:201` | 3× (div + pow) = 6 | `tsS`, `tsS1`, `tsP`, `sLp100` (kurulum) |
| hc (hue compress yeşil/mavi) | `:344-352` | pow + ~10 MAD = 11 | `hc_r`, `hc_r_rng` |
| hs_rgb (kanal kaymaları) | `:353-358` | 3 pow + ~12 = 15 | `hs_r/g/b` + rng ×3 |
| hs_cmy (ikincil kaymalar) | `:359-365` | 3 pow + ~12 = 15 | `hs_c/m/y` + rng ×3 |
| purity pt + ptm | `:368-382` | 2 pow + 2 exp + 2 pow + ~15 MAD = 21 | `pt_lml*` ×4, `pt_lmh*` ×3, `ptm_*` ×6 |
| rendering-space geri | `:385-387` | 6 MAD | rs_* (yukarıdaki) |
| displayWhite (CWP) | `:285-299` | 2 mat mul (18) + pow + ~8 MAD = 27 | `cwpCat` mat3 (kurulumda seçilir), `cwp_lm`, kCWP_NORM vektörü |
| brlp (gölge roll-off) | `:391-399` | sqrt + ~10 + exp2 = 12 | `brlp*` 4 float |
| ptl softplus ×3 | `:400-404` + `:225-228` | 3× (exp + log1p + cmp) = 9 | `ptl_c/m/y` |
| tsn kuyruğu (tsM2× toe × tsDsc) | `:406-408` | 4 | `tsM2`, `tn_toe`, `tsDsc` |
| SDR çıkış: gamma 1/ep | `:417-419` | 3 pow + clamp | `ep` = 2.2/2.4 (uniform) |
| HLG çıkış (yalnız Hlg1000) | `:274-282` + `:409-415` | luma + spow + 3× (sqrt/log) + 2020 mat (9) + clamp ≈ 18 | kP3_TO_REC2020 sabit; k sabitleri |

Piksel toplamı: **~200 MAD + ~25 pow/exp/log/atan2/sqrt aşkın-fonksiyon** (45 çağrı
noktasının çoğu tekrar; en ağırı 9 gauss penceresi ve displayWhite'in
çifte matrisi). Derleme zamanına gidenler: `Impl` kurucusunun hesapladığı
tsS/tsM2/tsP/tsS1/lconM/lconW/lconSc/hconP/tnLp/tsDsc (`:241-271`) —
hepsi uniform; preset'in kalan skalerleri (`P` yapısı) uniform bloğu.

### 3.1 SDR/HLG farkları

- eotf 1 (sRGB 2.2) / 2 (Rec.1886 2.4) / 5 (HLG): yalnız SON blok değişir
  (`:406-419` ve `:409-415`) — `uDrtEotf` int tek uniform; shader `if`
  ile iki kol (varyant patlaması yok, run-time dal).
- HLG ekstra: Rec.2020 matrisi + inverse EOTF (üstteki ~18 madde) + tnLp
  1000 (kurulum farkı) + rs_sa/hcon ölçekleme farkları (tnSu) — hepsi
  kurulum-uniform farkı, shader METNİ aynı kalır.
- DRT kipindeyken TAIL varyantı `uOetf` kuralını ÇİĞNER: DRT çıkışı zaten
  display kodlu (plan §3.4: "DRT kipinde köprü OETF'yi atlar"). Yani
  mevcut TAIL'in tonemap/OETF blok DEĞİŞMEZ — DRT varyantında tonemap=0
  ve uOetf=0 pinlenir ya da (öneri) DRT TAIL'i tamamen ayrı program
  olur, kaynak TAIL'e dokunulmaz (§0.3).

### 3.2 LUT-bake alternatifi (33³) — K7 çerçevesinde teorik hız

| Yol | piksel başına | bellek | hata |
|---|---|---|---|
| GLSL piksel-başı | ~200 MAD + ~25 aşkın-fonksiyon | 0 | ≤ 1e-6 hedef (§3.3) |
| 33³ RGBA16F bake (Klipp) | 1 3D örnekleme (donanımda ~8 texel trilinear) | 33³×4×2 bayt ≈ 287 KB (tek) | enterpolasyon hatası: DI giriş aralığının dışında hazırlıksız (plan §4.3); ölçülmedi |
| 65³ RGBA16F bake | aynı | ~2.2 MB | daha küçük, hâlâ sınırlı |

Teorik hız avantajı büyük (25 aşkın-fonksiyon → 1 donanım lerp), ANCAK:
(a) bake **ek üretim işi** (her look×tonescale×cwp×display kombinasyon
başına yüzlerce hatta binlerce kaba giriş — 7 look × 14 tonescale × 7
cwp × 3 display nominal kombinasyon cebirsel çoğu dışlanabilir ama
önemli); (b) Klipp bake'i `NaN->0` ile yapıyor (`applyOpenDrt`,
`:443-445`) — GPU bake'i de aynı kuralı taşımalı; (c) ölçüm yokken
karar verilmez (K7 birebir böyle diyor: "önce doğrudan GLSL; maliyet
cihazda ölçülür, gerekirse bake"). Bu envanterin konumu: **K7 önerisi
sağlam** — piksel-başı ~45 çağrı noktası modern mobil GPU'da bir
çizimde ölçülebilir bir şey; bake ancak ölçüm "yavaş" derse masaya gelir.

### 3.3 Parite hedefi

- C++ referans Klipp vektörleriyle **2e-6**'da kilitli (test_opendrt.cpp
  ölçümü 6.6e-7/7.7e-7; `:137-163`).
- GPU↔CPU paritesi float'ta **bit-parite HEDEFLENEMEZ** (plan §10 katman
  4: "ölçümle sabitlenir"); F1-5'in şartı: aynı işlem sırası + aynı
  sabitler → sapma aşkın-fonksiyon yuvarlama seviyesinde. Başlangıç eşiği
  önerisi: **göreli ≤ 1e-6 (dolgun kutu, referans olmayan pikseller)** —
  Klipp vektör toleransıyla aynı seviye; 8-bit bayt karşılaştırıcının
  (mismatch>0.5%/maxDiffLsb>2) altında, yani üretim kapısını da geçer.
  Sahip onayı F1-7'de (K9'un devamı).

---

## 4. NodeParcel + uniform bütçesi

### 4.1 NodeParcel şekli (plan §6: "parcel yalnız skaler, slotlar append")

```
NodeParcel (FloatArray, append-only):
  [0]  uNodesCount                       (0=boş zincir)
  her aktif node (≤8), sırayla:
    enabled, mix, contrast, pivot,
    lift.x, lift.y, lift.master, gamma.x, gamma.y, gamma.master,
    gain.x, gain.y, gain.master, offset.x, offset.y, offset.master,
    ySide0, ySide1, ySide2,
    sat, hueRad, wb[3],
    hasCurves, hasLog, hasLut, lutMix, lutIndex
  çıktı transform (düz):
    outputMode (0 legacy709, 1 DwgLinear→DRT), drtLook, drtTonescale,
    drtCwp, drtCwpLimit, drtGrey, drtDisplay
```

- **Bayt tahmini:** node başına skaler 29 (yukarıdaki sayım) + çıktı
  7 ≈ maksimum 8×29 + 8 = **240 float = 960 bayt**; üst sınır 1 KB'ın
  altında — tek JNI FloatArray, `GradeParcel` deseninin (29 slot)
  ekolojisiyle aynı taşınım. NodeParcel **GradeParcel'e append edilir**
  (plan §6 bizzat: "NodeParcel ayrı FloatArray + parcel append edilir")
  — yani tek seferde birleşik gönderim ya da ardışık iki ayrı dizi;
  her iki durumda da bayt üst sınırı aynı.
  (tools/gen-grade-parcel.py şemasına NodeParcel bölümü eklenerek
  üretilir — üretici betiğe bu pakette DOKUNULMAZ.)
- **Parcel dışında kalanlar:** lutId'ler string — parcel DIŞINDA (mevcut
  lutPath pattern'ı: `DevelopFrameRequest`'in `lutPath` alanı, string,
  ayrı JNI parametresi; node-LUT'lar aynı yolu izler).

### 4.2 Uniform bütçesi (§2.6 tablosu) ve vec4 sınırları

- GLES 3.0 spec ASGARİ: fragment 224 vec4 + 16 sampler; vertex 256
  vec4 (spec asgarileri; cihaz değerleri doğrulanmadı).
- NODES pass teklifinin ~110-115 vec4'lük uniform seti için mevcut probe
  `GL_MAX_FRAGMENT_UNIFORM_VECTORS`'u SORGULAMAZ — bu sorgunun eklenmesi
  şart (F1-5 kalem 5, plan §11 satırı "uniform/unit sorguları").
- Aşım halinde (önerilen daraltmalar, sırayla): (1) hueMat' INIT zamanı
  tek matris değil — CPU zaten her node için matrisi üretiyor, GPU'ya
  **önceden çarpılmış kombine matris** (hue×wb) gönder: matris
  sayısı yarıya; (2) pivot sabit ise (default 0.336) tek global; (3)
  WB değerleri vec3→vec2 (R/B oranları); (4) son çare: NODES'u 4+4 iki
  pass'e böl (K8'e aykırı, ölçüm şart).

### 4.3 Parite hedefleri (NodeParcel)

- CPU ColorModel float (IEEE 754 binary32) — GPU highp da binary32.
  Bit-parite **hedef dışı** (aşkın-fonksiyon farkları); önerilen eşik
  OpenDRT ile aynı: göreli ≤ 1e-6 (dolu kutu) + 1D eğri atlasının
  texel lerp farkı (CPU `nodeCurveSample`'ın lerp'i — birebir
  taklit; fark yalnız float yuvarlaması).
- JVM tarafında NodeParcel eşitlik testi: encode→decode→encode
  round-trip bit-aynı (plan §10 katman 2 deseni) — NaN/Infinity reddi
  ve bool 0/1 kontrolü GradeParcel kurallarıyla aynı.

---

## 5. ChainSlots etkisi (yalnız plan metni — koda DOKUNMA)

### 5.1 planChain'e kPassNodes yerleşimi

```
enum ChainPassKind { ..., kPassWarp, kPassNodes };
struct ChainPlan { ChainPass passes[7]; int count; ...; int nodes = -1; };
ChainPlan planChain(int nrLive, bool runSharp, bool runDetail,
                    bool runWarp, bool runNodes);   // yeni parametre
```

- `kPassNodes`: `in = cur`, `out = chainNextSlot(cur)`, `guide = -1` —
  warp'tan SONRA (plan §5.2 birebir: warp'ın yeniden örneklemesi lineer
  ışıkta; N2'nin window/qualifier'ı çıkış uzayında kalmalı).
  `finalSlot` = nodes.out; TAIL (veya DRT-TAIL) onu okur.
- POST her zaman çalışır; NODES yalnız `runNodes` doğruysa — nodes
  yoksa zincir bugünkü hali (regresyon kilidi: test tablosu).
- Örnek (planner formülleriyle elle, kod çalıştırılmadan — plan §5.2
  aynı yöntem): NR yok + sharp+detail+warp+nodes açık:
  POST A→B; sharpH B→C, sharpV C→A(guide B); detailH A→B, detailV B→C
  (guide A); warp C→A; **nodes A→B**; TAIL B okur. 3 slot yeterli
  (kPassNodes'un guide'ı yok — unsharp gibi üçüncü slot istemez).

### 5.2 Test genişletmesi: 32 → 64 kombinasyon

- `test_chain_slots.cpp` döngüleri: `nr(4)×sharp(2)×detail(2)×warp(2)`
  = 32 → **+nodes(2) = 64**.
- Gerekli yeni invariantlar (eksen başına):
  1. `count == 1 + 2*sharp + 2*detail + warp + nodes` (uzanan formül);
  2. nodes varyantı: `p.nodes != -1` ⟺ `runNodes`; varyantsa
     `kind == kPassNodes`, `in` önceki pass.out, `out == chainNextSlot(in)`,
     `out != in`, `guide == -1`, `nodes == count-1` (son pass);
  3. `finalSlot == passes[count-1].out` her durumda;
  4. mevcut "warp son pass" testi güncellenir: `warp == count-1-nodes`;
  5. **regresyon kilidi**: `runNodes=false` inputları mevcut 32
     kombinasyonda tablonun bugünkü değerleriyle birebir (mevcut
     "pre-refactor slot table" testi DOKUNULMAZ — satır satır aynı
     kalmalı; yalnızca yeni parametre false geçilir).
- Maliyet: doctest, 64 döngü — masaüstünde önemsiz (build-desktop
  testleri 13 vaka toplamda <1 sn; test dosyası 108 satır, S boyut).

### 5.3 Mevcut kod uygunluğu (okundu, değişiklik YOK)

- `GpuDevelopPipeline.cpp:1609` çağrısı `planChain(nrLive, runSharp,
  runDetail, req.warpActive)` — F1-5'te 5. argüman eklenir; bu pakette
  dokunulmaz.
- draw() her pass'in slotunu planner'dan alır (warp hatasının dersi) —
  NODES pass'i de aynen; gelenek korunur.
- TAIL'in okuduğu `slotTex[plan.finalSlot]` (`:1781`) — DRT varyantı
  aynı satırı okur; developTex çıktısı aynen.
- GlesShaderSources.kt: mevcut 12 metin sabiti DOKUNULMAZ; NODES ve
  DRT-TAIL **yeni** metin sabitleri (gerekirse editte elle — üretici
  yok shader'lar için saf elle yazım, mevcut üslup).

---

## 6. Riskler

| # | Risk | Etki | Önlem / belirti |
|---|---|---|---|
| 1 | **Uniform bütçesi:** NODES ~110 vec4 + 4 node-LUT binding seti; cihaz fragment vektör limiti spec asgarisi 224 civarı olabilir | NODES pass'i yapılandırılamaz (link/validate hatası) | F1-5 başında `GL_MAX_FRAGMENT_UNIFORM_VECTORS` + `GL_MAX_TEXTURE_IMAGE_UNITS` sorguları (mevcut probe'da YOKTUR, §1.3); aşarsa §4.2 daraltmaları; önce uniform, bake sonra |
| 2 | **Shader derleme süresi / varyant patlaması:** NODES (8-tur döngü + 2-4 pow/tur) ve DRT (~25 aşkın-fonksiyon) iki büyük program; Adreno derleme süresi ölçülmedi | ilk çizimde jank, preview geç açılır | varyant YOK (tek NODES + 2 DRT programı: SDR/HLG tek kerede, dal run-time `uDrtEotf`); derleme süreleri F1-5 ölçüm kalemi; önlem: geç derleme (ilk kullanım) + ön-linkleme opsiyonu |
| 3 | **NODES-warp sırası etkileşimi:** NODES warp SONRASI çalışır; warp'ın bilinear yeniden örneklemesi node eğrilerinden geçmiş pikselleri yumuşatır | parite CPU ile kırılmaz (aynı sıra CPU'da) ama görsel yumuşama ölçülür; slot zinciri nodes eklenince bozulabilir | 64 kombinasyon testi + finalSlot invariantı: TAIL her zaman plan.finalSlot'u okur (2026-11-23 hata deseninin aynısı); grade sweep vaka `nodes+warp` (plan §10 katman 4 listesinde) |
| 4 | **3D LUT RGBA16F LINEAR (Adreno):** node-LUT 4 tablo aynı formatta | LUT portu cihazda koştu ama AYRI ölçüm kaydı dokümante edilmedi | F1-5 cihaz ölçümü: node-LUT örnekleme vektörleri üzerinde 1 kare A/B; hata olursa shepard-interp yok — hata raporu + CPU fallback |
| 5 | **1D eğri atlası çözünürlüğü:** 512 giriş, [-0.5,1.5] aralığında lineer lerp | aşırı eğri düğümlerinde görünür hata (öteleme değil renk kayması) | CPU tablosu 512'yi birebir taşıyor; parite eşiği 1e-6 içinde kalır çünkü HER İKİ taraf aynı tabloyu okur (GPU yalnız örnekleme aritmetiğini taklit eder); vaka testi durağan nokta eğrisi |
| 6 | **OpenDRT HLG kolu cihazda test edilememiş** (export hedefi HLG E2/E1d'de; F1 yalnız float HLG üretir) | HLG DRT varyantı preview'da kimsenin görmediği kod | F1-7 paritesi HLG vakasını Klipp vektörleriyle (opendrt_klipp_hlg.txt) JVM/C++ doğrular; GPU HLG yalnız cihaz ölçümünde |

---

## 7. Doğrulanamayanlar + online araştırma soruları

Doğrulanamayanlar (kod okuma ile aşılamaz):
- Adreno 830'un gerçek `GL_MAX_FRAGMENT_UNIFORM_VECTORS` /
  `GL_MAX_TEXTURE_IMAGE_UNITS` değerleri (kod sorgulamıyor — F1-5'te
  eklenecek).
- NODES/DRT shader'larının Adreno'da derleme süresi (ilk çizim jank'i).
- RGBA16F 3D LINEAR filtrelemenin cihaz kanıtı (LUT portu koştu;
  ölçüm kaydı yok).
- OpenDRT GLSL'in 4K preview'de kare-başı maliyeti (K7'nin ölçüm
  kaleminin ta kendisi).
- Klipp bake'in DI-aşırı aralıkta hata profili (33³ enterpolasyon),
  karşılaştırma için.
- NodeParcel'in JNI geçişinde GC baskısı (1 KB FloatArray/kare —
  mevcut GradeParcel 29 slot ile aynı türden, büyük olasılıkla önemsiz,
  ölçülmedi).

Online araştırma ajanına sorular:
1. **GLSL ES 3.0 fragment uniform limitleri pratikte:** ana Adreno
   (3xx/7xx/8xx) ve Mali cihazlarda rapor edilen MAX_FRAGMENT_UNIFORM_
   VECTORS değerleri; sampler + uniform vec4 toplam bütçesinin gerçek
   dünya tabloları — NODES'un ~110 vec4 teklifinin güvenliği için.
2. **Texture-unit bütçesi:** mobil GPU'larda fragment aşaması texture
   image unit asgari/sayıları (16'yı aşan cihaz var mı; shadow/nearest
   ayrımı) — 4 node-LUT + 1 atlas + 5 mevcut birim = 10-11 birimin
   taşınabilirliği.
3. **pow() derinliği:** mobil GPU'larda GLSL pow/log2/exp2'ın ALU
   maliyeti (aşkın-fonksiyon birim sayısı, işleyiş turu) — NODES'un
   döngü-başı 2-4 pow ile DRT'nin ~25 aşkın-fonksiyon'ının gerçek süredeki
   oranı (K7 ölçüm hipotezine sayı vermek için).
4. **OpenDRT GPU port emsalleri:** Jed Smith'in DCTL'si GPU'da piksel-
   başı mı bake mi; herhangi bir GPU (DCTL/DaVinci zaten GPU ama)
   disiplin notu; itrp-patches GLSL portunun varlığı (plan §4.1
   "yerelde yok" doğrulandı) — referans GLSL mevcut mu?
5. **33³/65³ 3D LUT boyut/pratik:** mobil GPU'larda 33³ RGBA16F LUT
   örnekleme bedeli + bake altyapısı emsalleri (Colorspace dönüşüm
   LUT'ları) — bake gerektiğinde hata profili karşılaştırması.

Sonuç: F1-5, bu envanterin sınırları içinde tek NODES + iki DRT-TAIL
programıyla, mevcut 3-slot zincirine bir pass türü ekleyerek ve mevcut
12 shader metnine dokunmadan kurulabilir. Kritik ön-koşul: ilk iş
uniform/unit sorgularının eklenmesi ve 64 kombinasyon planChain
regresyonunun geçmesi.
