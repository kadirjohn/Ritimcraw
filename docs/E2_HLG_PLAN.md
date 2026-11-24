# E2 — HLG ihrâcı hazırlık planı (önden keşif)

Durum: **plan, kod yok** (2026-11-24, gece görevi Queue #3, dal
`e2-hlg-research`, taban `main` @ `fd749d0`). E2, sahibin F1 N1
merge'inden sonra başlayacak paket; bu belge o gün geldiğinde ön
keşfin bitmiş olması için yazıldı. Üretim kodu YOK.

Kanıt sözlüğü (COLOR_MODEL_V2_PLAN.md ile aynı):
- **doğrulandı**: bu worktree'de main'deki koddan okundu (dosya:satır
  verilir) veya bu pakette hesaplandı/kontrol edildi (belirtilir).
- **plandan alıntı**: `.planning/KLIPP-FILMCRAFT-PLAN.md` §7.6/§13.2/
  §13.13/§13.14 — bu worktree'de `.planning` klasörü YOK (doğrulandı:
  `ls`); görev brief'inin aktardığı kararlar bu seviyede anılır. Sahibin
  20:32 onayı (E2/E3 F1 N1 sonrası) plandan alıntıdır.
- **doğrulanmadı**: cihaz davranışı. ADB YOK — cihaz turu sahibin sabah
  fazı; bu belge yalnız planın aktardığı, ZATEN OKUNMUŞ cihaz
  verilerine (§13.13: c2.qti.hevc.encoder hlg-editing VAR) dayanır.

Ayrım notu: e0-encoded-export dalı (peer) EncodedColorSpec'e dokunuyor;
bu belge YALNIZ main'deki dosyaları okur, o kola ve dosyalarına
dokunmaz (docs-only zaten).

---

## 0. Özet + fizibilite görüşü

1. **Fizibilite: BU CİHAZDA E2 YAPILABİLİR — kanıt zinciri güçlü.**
   (i) Kodlayıcı: plan §13.13 cihaz dökümü — `c2.qti.hevc.encoder`
   `hlg-editing` özelliğini TAŞIYOR (API 37 ROM; `.hdr` varyantı ayrı);
   (ii) 10-bit GPU bacağı: E1d'de ZATEN KOŞUYOR — RGBA1010102 EGL
   config'i sorgu-ile-seçen kod main'de (`GlesPipeline.cpp:198-223`,
   aday 64 config taranır, rapor edilen 10/10/10/2 hariç elenir),
   `probeTenBitConfig` + `openSessionTenBit` bu yoldan açılıyor
   (`GlesDevelopSession.cpp:333-341, :1754/:1782-1790 NativeSource`); bir
   10-bit HEVC ihrâcı (planın bitDepth=10'u) cihazda üretilmiş durumda;
   (iii) renk matematiği: OpenDRT HLG çıkışı C++ tarafında HAZIR —
   `hlgInverseEotf` (`OpenDrt.cpp:274-282`) 1000 nit BT.2100 sinyali
   üretir; bu pakette sayısal kontrol edildi: %18 gri → 203 nit
   referans beyazı → HLG sinyal 0.75 → **10-bit limited kod 721**
   (64 + 0.75×876; plan §13.14'ün iddiası birebir doğrulandı-hesaplandı).
   Tek cihaz-öncesi bilinmeyen: EGL colorspace uzantısının ROM'da varlığı
   (§3.2, doğrulanmadı).
2. **E2 dört sütun üzerine kurulur ve üçünde hazırlık ZATEN main'de:**
   (a) profil+kapı sözleşmesi — `HevcProfile.kt` HLG'yi TAM sözleşme
   olarak taşıyor (Main10=2, BT2020=6, HLG=7, limited=2,
   `requiredApiLevel=35` + API-34 fallback; §1.3); (b) 10-bit GPU
   zinciri — E1d (yukarıda); (c) OpenDRT HLG çıkışı — F1-4; (d) EKSİK
   OLAN TEK ŞEY: bu üçünü birleştiren parça — dynamicRange=HLG'nin
   `DYNAMIC_RANGE_UNAVAILABLE` duvarını aşması (ExportPlan.kt:259-262)
   ve DRT-TAIL varyantının F1-5/F1-6'dan gelmesi.
3. **API 34 kıskacı çözülmüş durumda ama HAYATİ TEK DOĞRULAMA EKSİK:**
   HevcProfile/HevcPreflight'ın API-34 yolu (FEATURE_HdrEditing
   fallback + `trialEncodeRequired`) TASARLANMIŞ (aşağıda §1.3, "iki
   düzeltmenin durumu"); deneme-encode (trial encode) kendisi E2'nin
   işi ve bu cihazda gerekmedikçe koşmaz — ROM API 37 olduğundan
   standart yol `hlg-editing` sorgusu olacak.
4. **Medya zinciri net:** HEVC Main10 + `KEY_COLOR_STANDARD=6
   (BT2020)` + `KEY_COLOR_TRANSFER=7 (HLG)` + `KEY_COLOR_RANGE=2
   (limited)` + profile/level; HLG için HDR statik blobu
   GEREKMEZ (HLG sahne-referred: kare-başı üstveri istemez —
   HevcProfile KDoc'unun kendi gerekçesi; `requiresHdrStaticInfo=false`).
5. **Öneri E2 kırılımı:** E2-a (profil+kapı wire'ı + etiketler →
   plandan HLG'yi çöz; saf Kotlin, JVM testi), E2-b (GPU HLG üretimi:
   DRT-TAIL HLG varyantı + EGL colorspace kararı + writer'a renk
   etiketleri), E2-c (cihaz kabul: deneme-encode + ffprobe satırları).
   Boyutlar/bağımlılıklar §7; E2-a'nın çoğu F1 beklemez, E2-b'nin
   tamamı F1-5/F1-6 bekler.
6. **En riskli iki nokta:** (i) ÇİFT OETF — HLG sinyali zaten
   display-kodlu olduğu için EGL colorspace'i `EGL_EXT_gl_colorspace_
   bt2020_hlg` yapılırsa donanım İKİNCİ bir dönüşüm uygular (§13.14
   uyarısı; öneri: shader üretir, colorspace default kalır — karar
   E2-b'de, §3.2);
   (ii) MediaMuxer'un HLG etiketlerini (mdhd/colr/stsd) yazıp
   yazamadığı — KEY_COLOR_* MediaCodec'ün SPS/VUI'sine gider, konteyner
   tarafı doğrulanmadı (§4.2).

---

## 1. Mevcut durum (doğrulandı, aksi belirtilmedikçe)

### 1.1 E1d 10-bit GPU zinciri (gerçek kod — varsayım yok)

Zincir (kısa ama tam; her adım main'de okundu):

1. **EGL config seçimi:** `EglSession::open(..., bool tenBit)`
   (`GlesPipeline.cpp:160-238`). 8-bit yol: doğrudan RGBA8888
   seçimi. 10-bit yol: `EGL_RED/GREEN/BLUE_SIZE 10, ALPHA_SIZE 2`
   isteği + **"Query, never assume"** filtresi (`:198-223`): 64 aday
   taranır, `eglGetConfigAttrib` ile RAPOR EDİLEN değerleri tam
   10/10/10/2 olan İLK aday seçilir — §13.14'ün "EGL_RGBA1010102
   sabiti varsayılmaz, probe ile" kuralı KODDADIR, planın kendisi
   değil. `windowCapable + recordable` bayrakları aynı config'i
   MediaCodec giriş Surface'ine sarılacak şekilde ister
   (`:174-192`).
2. **Probe:** `DevelopSession::probeTenBitConfig` (`GlesDevelopSession.cpp:333-341`)
   tek kullanımlık bir EglSession açıp aynı sorguyu koşar; Kotlin
   tarafı `GpuDevelop.tenBitProbe()` (Mp4Exporter.kt:558-566, lazy).
   E1 planı `tenBitPathAvailable = wantsTen && GPU_EXPERIMENTAL &&
   encoderDrawEligible && tenBitProbe()` ile birleşir
   (Mp4Exporter.kt:1615-1620).
3. **Oturum:** `openSessionTenBit` → `jniDevelopSessionOpenTenBit`
   (NativeSource.kt:1782-1790) — aynı on-beş shader metni, tenBit
   true; develop FBO hâlâ RGBA32F (EXT_color_buffer_float kapısı);
   10-bitlik yalnızca SON blit/eglCreateWindowSurface config'indedir.
4. **Encoder:** 10-bit plan → `writer.requestSessionEncoderSurface()`
   + `configureSessionSurfaceCodec` (MediaCodecMp4Writer.kt:210-217,
   :444-481): COLOR_FormatSurface, codec `plan.mime`'dan
   (HEVC: ExportCodec.HEVC.mime), format `ExportFormats.video(plan,
   surface=true)` — 10-bit CPU path'e İZİN YOK: `ExportFormat.kt:44`
   `require(!(plan.bitDepth == 10 && !surface))` (E1d'in "10-bit
   GPU-only, CPU 8-bit yalanı olmaz" kuralı).
5. **Koruma:** `adoptEncoderSurface` 10-bit planda reddedilir
   ("8-bit frames into a 10-bit file", MediaCodecMp4Writer.kt:537-540)
   — 10-bit yol açık kaldığında kullanıcıya ölçülmüş bir fallback yoktur;
   E2 için bu kural aynen kalır (HLG 10-bit Main10'dur).

**E2'ye taşınabilecekler:** zincirin tamamı. E2-b'de değişecek TEK
donanım kararı: develop oturumu açılırken (ya da writer'ın EGL'sinde)
`tenBit=true`'nun yanına HLG'ye özgü surface colorspace isteği
eklenip eklenmeyeceği (§3.2) ve TAIL varyantının DRT-HLG'ye geçişi
(§3.1).

### 1.2 Writer'da renk etiketi yazımı (bugünkü şekil)

`ExportFormats.video` (ExportFormat.kt:41-63) plandan anahtar anahtar
yazar: `KEY_COLOR_STANDARD=COLOR_STANDARD_BT709`, `KEY_COLOR_RANGE=
COLOR_RANGE_LIMITED`, `KEY_COLOR_TRANSFER=COLOR_TRANSFER_SDR_VIDEO`
(`:59-61`) — bugünkü satırlar SDR kodudur; SDR olmayan bir plan
üretime bugün ZATEN GELEMEZ (planner düzeltir, §1.4). 10-bit etiketi:
`KEY_PROFILE = plan.profile` (`:58`; E1d'de HEVC Main10=2, AVC
High10=16 — `EncoderFacts.tenBitProfile`). `KEY_HDR_STATIC_INFO`
hiçbir yerde YAZILMIYOR (grep: yok) — HLG için doğru (§4.1), HDR10
için E2/E3'ün ileriki işi.

`MediaMuxer`: track formatı codec'in `INFO_OUTPUT_FORMAT_CHANGED`
formatından gelir (`MediaCodecMp4Writer.kt:1103-1131` drain/addTrack)
— yani kodlayıcının SPS/VUI'sindeki etiketler neyse muxer onu
kaydeder; muxer'a BAŞKA bir HDR etiketi yazan kod YOK (doğrulandı).

### 1.3 HevcProfile/HevcPreflight'ın HLG davranışı + §13.2'deki iki düzeltmenin durumu

Tespit (dosya: HevcProfile.kt:159-204, HevcPreflight.kt:385-427):

- **API 35 düzeltmesi: YAPILMIŞ.** `HevcProfile.HLG` kapısı `feature =
  HevcFeatureGate("hlg-editing", requiredApiLevel = 35, ...,
  belowApiFallback = HevcFeatureGate("hdr-editing", 33))` — FEATURE
  STRING İLE sorgulanır ("hlg-editing"; requiredApiLevel veri olarak
  taşınır, derleme sabiti yok; KDoc: "an unknown feature name just
  answers false"). API 34'te cihaz `hlg-editing` sorgulanmaz,
  `hdr-editing` (API 33) fallback olur ve karar
  `trialEncodeRequired = true` taşır (HevcPreflight.kt:49-51,
  :395-412, :410+).
- **Medya düzeltmesi de YAPILMIŞ:** HLG profili `colorStandard = 6
  (BT2020), colorTransfer = 7 (HLG), colorRange = 2 (limited),
  profile = 2 (Main10), level = 16384 (MainTier L5)` tam sözleşme
  olarak duruyor (HevcProfile.kt:183-204) ve `mediaFormatEntries()`
  altı anahtarı yazım sırasıyla üretir (`:261-268`).
- **Eksik olan:** `decide`'i çağıran entegrasyon noktası —
  HevcPreflight "Nothing imports this model yet" çağrı zinciri hâlâ
  entegratöre ait (HevcProfile.kt:19-21); E1'in kendi `ExportPlanner`
  bu modeli KULLANMIYOR (doğrulandı: ExportPlan.kt'te HevcProfile
  yok — E1 kendi kodlayıcı seçimini EncoderFacts'tan yapar). E2-a'nın
  işi tam bu köprüdür.
- API 34'te deneme-encode: `trialEncodeRequired` bayrağı +
  HevcPreflight KDoc'unun 6 adımlı deneme planı (`:86-137`) — kod
  YOK, plan metni VAR; E2-a cache anahtarı (§2.2) bunun etrafına
  kurulur.

### 1.4 dynamicRange bugünkü duvarı

`ExportSettings.ExportDynamicRange { SDR, HLG, HDR10, DV }`
(ExportSettings.kt:33) — değerler VAR, seçilemez; planner
`eff.dynamicRange != SDR` gördüğünde DYNAMIC_RANGE_UNAVAILABLE ikazı
ile SDR'ye DÜZELTİR (ExportPlan.kt:259-263). Wire ad ile taşır
(`:164, :183`), yani HLG seçimi geldiğinde format bozulmaz — E2-a
planner'daki bu bloğu "HLG geldi + kodlayıcı kapısı geçti → HLG
plana geçer" biçiminde değiştirir; UI'da dynamicRange seçici YOK
(grep: ExportSettingsPanel'de yok — E2-a/F1-8 UI işi).

---

## 2. Cihaz kapıları

### 2.1 Standart yol — API 35+ (bu cihaz: API 37)

`c2.qti.hevc.encoder` bu ROM'da `hlg-editing` TAŞIYOR (plandan alıntı:
§13.13 cihaz dökümü; `.hdr` ayrı varyanttır). Standart sıra:

1. `MediaCodecList(REGULAR_CODECS)` → video/hevc donanım kodlayıcıları;
   zaten var: `CodecProbe.probe()` + `DeviceEncoderFacts.all()`
   (DeviceEncoderFacts.kt:24-56) — **`FEATURES` listesi zaten
   `FEATURE_HdrEditing` + `"hlg-editing"` sorguluyor** (aynı dosya
   `:58-61`) ve `EncoderFacts.features` setine yazıyor; yani E1'in
   kendi fact'i HLG kapısını BUGÜN bile taşıyor, tüketen yok.
2. Profil: profileLevels içinde Main10 (2) — 15 Ultra dökümünde VAR
   (CodecNames.profileName 2 → "Main10"; cihaz dökümü plan alıntısı).
3. Boyut/hız: `areSizeAndRateSupported` (transpoze dahil) —
   DeviceEncoderFacts'taki SizeRateProbe deseni aynen.
4. Sonuç: HLG planı `ExportPlan`'a "codec=HEVC, bitDepth=10,
   profile=2, etiketler HLG" olarak iner.

### 2.2 API 34 yolu — deneme-encode (tasarım önerisi)

Gerekçe: FEATURE_HlgEditing API 35'te geldi; API 34 ROM'larında
bayrak YOKTUR ama HLG, Main10+BT2020+limited ile DOĞRUDAN kodlanabilir
(HevcProfile KDoc'unun kendi notu; plan §13.2). `hdr-editing`
fallback'i HLG'ye ÖZEL DEĞİLDİR — tek gerçek kanıt deneme-encode'dur.

**Deneme-encode tasarımı (E2-a'da saf model + cache, E2-c'de cihaz):**

- Girdi: 4K'lık ÇOK KISA (ör. 8 kare) HEVC Main10 + BT2020 + HLG +
  limited + COLOR_FormatSurface formatı; bilinen desen: yatay gri
  rampa (bitişik adımlar tam BİR 10-bit kod farkı — E1d'nin "10-bit
  rampası" deseni; HevcPreflight KDoc'unun 4. adımı zaten budur).
- Kabul ölçütü (hepsi HevcPreflight KDoc'unda tasvir edilmiş): configure+start
  OK; çıkışın DECODER formatı Main10/HLG/BT2020/limited etiketlerini
  RAPOR EDİYOR (istek değil — çıkış); rampa adımları DÖNÜŞTE ayrık
  (8-bit gidiş-dönüş çöker); boyut eşit (4K portre dahil);
  `signalEndOfInputStream` sonrası kare sayısı = giren kare sayısı.
- **Cache anahtarı (öneri):** `mime + profile + level + colorStandard
  + colorTransfer + colorRange + width x height + apiLevel +
  codecName + Build.ID`. Süre: process içi (ROM/kernel
  güncellemesine kadar yaşar; kullanıcı ayarı değil, cihaz gerçeği).
  Başarısız sonuç da POZİTİF cache'lenir (ret gerekçesiyle) — aksi
  hâlde her ihrâç denemesi yeniden ısınır; süre/önem K1'de sahibin
  onayına açık.
- Bu cihazda (API 37): deneme koşMAZ — standart `hlg-editing` sorgusu
  yeter. Deneme yolu yalnız `trialEncodeRequired=true` gelen cihaz
  profillerinde (API 34) açılır.

---

## 3. Renk zinciri (F1-6 wire'ı beklemeden arayüz noktaları)

### 3.1 OpenDRT → HLG nereden devreye girer

- C++ tarafı HAZIR ve KLİPP vektörleriyle kilitli: `hlgInverseEotf`
  (OpenDrt.cpp:274-282; girdi "display light, 1 = 1000 nit"), HLG
  kolu `applyXyz`'in sonunda (`:409-415`: clamp → P3→2020 matrisi →
  clamp 0..1 → inverse EOTF). `OpenDrtDisplay.Hlg1000` + `OpenDrtSpec`
  hepsi main'de (OpenDrt.h:30-47). `tsDsc = 0.1` (HLG ölçeği) Impl
  kurucusunda (`:262`).
- **Sahne-referred giriş NOTU:** HLG çıkışı sahne ışığını "1 = 1000
  nit referansı"na ölçekler; OpenDRT zaten tsn/tsM2 ile kodlar — E2-b'nin
  Shader tarafı yalnız DRT-TAIL'in HLG bloğudur (F1-5 envanterinde
  §3 tablosunun son iki satırı; dal `uDrtEotf`).
- **E2-b arayüz önerisi (F1-6 wire'ından bağımsız noktalar):**
  `DevelopFrameRequest`'e `outputTransform` skalerleri (mode, look,
  tonescale, cwp, grey, display) NodeParcel'in çıkış-transform bloğu
  olarak girer (F1-5 envanteri §4.1 zaten bu şekli çizdi — E2, display=
  Hlg1000 seçeneğini bu bloğa taşır); DRT-TAIL varyantı `uOetf=0`
  pinlenmiş hâlde koşar (çünkü DRT çıkışı zaten display-kodlu: E1'in
  `glUniform1i(oetfLoc, 1)` satırı — GpuDevelopPipeline.cpp:1784 —
  DRT kipinde 0 olmalı; bugünkü kod HLG bilmez, bu değişiklik F1-5'in
  DRT-TAIL'inde kestirilir, E2 yalnız display parametresini seçer).

### 3.2 ÇİFT OETF tuzağı — EGL colorspace kararı (E2-b'nin en kritik satırı)

- Bugün EGL colorspace HİÇBİR YERDE ayarlanmıyor (grep:
  `EGL_GL_COLORSPACE`/`EGL_COLORSPACE` cpp tarafında yok —
  doğrulandı): pencere yüzeyi default kalır (colorspace ataması
  yapılmaz) ve kodlayıcı `KEY_COLOR_TRANSFER=HLG`'yi SPS/VUI'ye
  YAZAR. Bu şekil çalışıyor (E1d 10-bit cihazda üretildi).
- §13.14 uyarısı: HLG sinyali zaten OETF uygulanmış display
  kodludur; EGL yüzeyi `EGL_EXT_gl_colorspace_bt2020_hlg` isterse
  donanım İKİNCİ dönüşüm uygular (çift OETF: bantlı, yanlış
  parlaklık). Kural: **shader HLG kodunu üretiyorsa surface
  colorspace EXT'si İSTENMEZ (default kalır; kodlayıcıya etiket
  KEY_COLOR_* ile gider).** Tersi de düşünülebilir (surface'a
  colorspace ver, shader LINEER yaz) — o zaman BT.2408 ölçeği
  donanıma emanet edilir ve parite CPU ile kurulamaz; ÖNERİ: shader
  üretsin, colorspace default.
- İstisna durumu: bazı donanımlar default RGBA1010102 yüzeyden
  kodlayıcıya VERİYİ doğru taşır ama etiketi SADECE MediaFormat'tan
  alır (bugünkü şekil) — bu cihazda deneme E2-c'nin ffprobe satırı
  kanıtlar (§6.3).

### 3.3 SDR kompozit (başlık/altyazı) — BT.2408

- F4'ün başlık/altyazıları HLG sahnede %75 kodla (721) durmalı:
  10-bit limited kuantizasyonda **SDR beyaz = 203 nit = sinyal 0.75
  = kod 721** — bu pakette python ile doğrulandı-hesaplandı
  (64 + 0.75×876 = 721; OpenDrt.cpp:274-282 algoritması 203-nit griyi
  0.7499 sinyale yerleştirir).
- **Not:** kompozit modeli E2'ye BAĞIMLIDIR, E2'nin kompozit İŞİ
  YOK (tek klip ihrâcı; timeline TEXT kipsi F4'tür), ama E2 planı
  "display-linear + premultiplied alpha" kompozit kuralını (§13.14)
  bir KARAR olarak kaydeder: E2-b'nin ürettiği HLG karesinin
  üstüne çizilecek öğeler HLG kodunda değil, DRT-TAIL'den ÖNCE
  DWG-lineer ışıkta kompozit edilir; %75 (kod 721) eşlenmesi ancak
  F4'ün kompozit aşamasında gündeme gelir → E2 yalnızca sözleşmeyi
  tanımlar.

### 3.4 RGB 64..940 sıkıştırması ≠ YUV limited quantization

§13.14'ün ayrımı plan alıntısı olarak net: HLG sinyali [0,1] içinde,
10-bit LIMITED KUVANTİZASYON [64, 940] bandına taşınır (yukarıdaki
721 hesabı bu bantta). Bu, "RGB 64..940 sıkıştırması"nın TAM OLARAK
YUV limited-range kuralıdır — E2-b shader'ında HLG kodu 0..1 float
üretir; bandı (×876+64) kodlayıcının Surface yolundaki RGB→YUV
dönüşümü halleder (öneri; cihaz denemesi E2-c). Ayrım hatası
yapılmaması için bu madde K5'te sahibe sunulur.

---

## 4. Writer / MediaFormat — anahtarlar ve muxer

### 4.1 MediaFormat sözleşmesi (HLG planı yazarken)

`ExportFormats.video` bugünkü SDR yerine şu anahtarlarla yazmalı
(kaynak: HevcProfile.HLG.mediaFormatEntries sırası):

| Anahtar | Değer | Not |
|---|---|---|
| KEY_MIME | video/hevc | ExportCodec.HEVC.mime |
| KEY_PROFILE | 2 (Main10) | KEY_LEVEL ile birlikte — docs kuralı |
| KEY_LEVEL | 16384 (MainTier L5) | 4K30 |
| KEY_COLOR_STANDARD | 6 (BT2020) | bugünkü 1 (BT709) yerine |
| KEY_COLOR_RANGE | 2 (limited) | değişmez |
| KEY_COLOR_TRANSFER | 7 (HLG) | bugünkü 3 yerine |
| KEY_HDR_STATIC_INFO | YOK | HLG sahne-referred; zorunlu değil (HevcProfile :188-193) |
| KEY_BIT_RATE | BitrateTable | fps=ölçülen; HEVC faktörü 0.6 zaten (ExportCodec.HEVC) |
| KEY_FRAME_RATE / I_FRAME_INTERVAL | plan.fps / plan.keyframeIntervalSec | E1'den geliri |
| KEY_COLOR_FORMAT | COLOR_FormatSurface | 10-bit yol (CPU path yasak, §1.1.4) |

E1 mimarisi bu değişikliği zaten taşır: 10-bit plan dört kanaldan
akar (mime/profile — plan.effective.codec + plan.profile;
color-*- — ExportFormats.video). E2-a'nın işi `plan.dynamicRange ==
ExportDynamicRange.HLG` iken bu altı satırı HevcProfile.HLG'den
beslemektir (ExportFormat'a yeni bir dal — mevcut SDR satırları
dokunulmaz, E1 paritesi).

### 4.2 MediaMuxer ve konteyner etiketleri

- Kod tarafında muxer'a HLG için özel yazan bir şey YOK (§1.2) —
  track formatı codec'in çıkışından gelir. MP4'ta HEVC config
  (hvcC) SPS/VUI'yi taşır: kodlayıcı ConfigDescr'a color_primaries=
  9 (BT2020), transfer_characteristics=18 (HLG/ARIB-B67),
  matrix_coefficients=9 (BT2020 non-constant) ROM'un C2
  kodlayıcısına bağlı — **doğrulanmadı**; E2-c'nin ffprobe satırı
  tam bunu kanıtlar.
- MediaMuxer'un kendisi HDR10+ / CTA-861.3 blobu YAZAMAYABİLİR
  (KEY_HDR_STATIC_INFO codec tarafında işlenir; HLG'de gerek yok) —
  HLG için muxer ayağı bu belge kapsamında RİSKSİZ sayılır, ffprobe
  doğrulaması şart.
- Bir MP4 muxer riski gerçek: kolr/stsd etiketlerinin ffprobe'da
  "bt2020nc-arl-b709" gibi görünmesi — doğrulanmadı; kabul ölçütü
  K4'te.

---

## 5. Preview tarafı

- Mevcut preview zinciri HLG bilmez: preview'da uOetf=1 kalır
  (GlesPreviewSession.cpp:199 "shader already applied
  linearToRec709"; GpuDevelopPipeline.cpp:1784 aynı pin) — yani
  bugünkü preview daima SDR Rec.709 TAIL ile biter (doğrulandı).
- F1 N1 merge'inden sonra DRT-TAIL (F1-5) preview'a da girer:
  OpenDRT SDR çıkışı (sRGB 2.2) zaten "display-kodlu" — SDR ekranda
  tonemap gerekmeden gösterilir. HLG çıkışı ise SADECE HLG ekranda
  doğru görünür; SDR ekranda OpenDRT Display=SdrSrgb22 ile İKİNCİ
  bir derleme (preview ayrı bir Display parametresi) gerekir.
  **Öneri:** preview her zaman SDR (SdrSrgb22/Rec1886) DRT ile
  koşar; ihrâç hedefi HLG ise preview "HLG ihrâç hedefi — SDR
  önizleme" rozetidir (metin K3). Bu, preview zincirinde HLG
  tonemap kodu YAZMAZ (donanım tonemap'i (KEY_COLOR_TRANSFER_
  REQUEST) doğrulanmadı; kapı kapsamı dışı).
- Scope'lar: mevcut WaveformScope SDR luma değerleri üzerinde
  (%100 üstü kırpık); nit ölçeğinde 0–1000 gösterimi açıkça
  F1-7/F1-8 işi — bu belge not düşer, tasarım F1 paketine aittir.

---

## 6. Testler

### 6.1 Saf Kotlin (E2-a; hepsi JVM'de)

1. **Profil sözleşme testi:** HevcProfile.HLG'nin altı anahtarı
   (mime/profile/level/color-standard/range/transfer) ve
   requiresHdrStaticInfo=false — mevcut HevcProfileTest deseni
   genişletilir; AVC_SDR/HEVC10_SDR'den alan-farklısı iddiası.
2. **Kapı karar tablosu:** `HevcPreflight.decide` — API 37 cihaz
   (hlg-editing=true) → Supported, trialEncodeRequired=false; API 34
   (hdr-editing=true) → Supported + trialEncodeRequired=true; API 34
   (hdr yok) → Refused(FEATURE_API); Main10 listede yok →
   PROFILE_LEVEL; her ret DegradedOffer taşır (mevcut test
   dosyaları zaten bu deseni kurar — doğrulandı: hevc test paketi).
3. **Cache anahtarı testi:** anahtar bileşenleri + POZİTİF-RET
   cache'lenmesi (aynı anahtar → koşmadan ret); Build.ID değişince
   anahtar değişir.
4. **Planner HLG dalı:** dynamicRange=HLG + kodlayıcı kapıları OK →
   plan HLG etiketleriyle iner; kapı yok → DYNAMIC_RANGE_UNAVAILABLE
   + SDR fix (mevcut kuralın HLG'ye genişletilmesi; wire ad ile
   eski satırlar bozulmaz).

### 6.2 GPU üretimi (E2-b)

- **Ramp/kod-721 testi:** DRT-TAIL HLG varyantında gri rampa —
  hedef HLG kodları; %18 gri (203 nit referansı) → sinyal 0.75 →
  çıkış RGBA1010102 10-bit WORD'ünde kod 721 civarı (64+0.75×876;
  10-bit değer 16 bitlik kelimenin üst 10 bitinde — E1d'nin P010
  paketleme notuyla aynı konvansiyon). JVM'de C++ referans
  (OpenDrt.cpp hlgInverseEotf) ile saf karşılaştırma; cihazda
  dışa aktarma + ffprobe.
- **E1d 8-bit dither ayrımının HLG benzeri:** 8-bit derinlik
  SEÇİLEBİLİR kalmalı (HLG 10-bit şart mı? MediaFormat Main10
  zorunlu — 8-bit HLG "Main" profiliyle mümkün ama HevcProfile'da
  yok; K5 kararı) — dither'lı 8-bit HLG yok, reddi dürüst metinle.
- **Devre testi:** HLG kodu 0..1'i kapsar (aşırı poz E1d'de nasıl
  kırpıldıysa aynı — clamp [0,1] EN BİTTEN, OpenDrt.cpp:413).

### 6.3 Cihaz kabul adımları (E2-c; ffprobe komutları hazır)

```bash
# 1. Akış etiketleri (colr): transfer HLG (18), primaries BT2020 (9),
#    matrix BT2020nc (9) — HLG hedefi:
ffprobe -v error -select_streams v:0 -show_entries \
  stream=codec_name,profile,pix_fmt,color_range,color_transfer,\
color_primaries,color_space -of default=noprint_wrappers=1 OUT.mp4

# 2. (teşhis için) konteyner/örnek açıklaması — hvcC içindeki VUI'yi göster:
ffprobe -v trace -i OUT.mp4 2>&1 | grep -iE "vui|colour_primaries|\
transfer_characteristics|matrix_coefficients"

# 3. Bit derinliği + bant genişliği:
ffprobe -v error -select_streams v:0 \
  -show_entries stream=pix_fmt,width,height,bit_rate \
  -of default=noprint_wrappers=1 OUT.mp4
# beklenen: pix_fmt=yuv420p10le, profile="Main 10"

# 4. (parite) kod-721 kontrolü: kodlanmış yuv420p10le karesi çıkarılır:
ffmpeg -i OUT.mp4 -frames:v 1 -f rawvideo -pix_fmt yuv420p10le FRAME.yuv
# FRAME.yuv üzerinde %18 gri bölge Y kodu ~721 (limited bant 64..940)
```

(Hedef satırlar: `codec_name=hevc`, `profile=Main 10`,
`pix_fmt=yuv420p10le`, `color_transfer=arib-std-b67`,
`color_primaries=bt2020`, `color_space=bt2020nc`,
`color_range=tv`.)

Not: ffprobe'un bu etiketleri OKUYABİLMESİ hvcC/colr'ın dolu olmasına
bağlıdır — düzenli giderse üstteki hedef satırlar cihazda kanıt olur;
gidemezse muxer hattında (§4.2 riski) 2. komut teşhisi koyar.

---

## 7. İş kırılımı

| Paket | İş | Dosyalar (dokunulacak — PLAN, bu belge değiştirmez) | Boyut | Bağımlılık |
|---|---|---|---|---|
| **E2-a** | dynamicRange=HLG plana akar; HevcProfile/preflight Planner'a bağlanır; ExportFormats.video HLG bloğu; kapı + cache saf modeli + JVM testleri | ExportPlan.kt (dynamicRange dalı), ExportFormat.kt (HLG anahtarı), hevc/HevcPreflight entegrasyon noktası, yeni saf cache anahtarı modeli; test: ExportPlannerTest genişletme, yeni cache testi | **M** | F1 YOK — yalnız E1 mimarisi; UI (seçici) F1-8 sonrası ayrıca |
| **E2-b** | GPU HLG üretimi: DRT-TAIL HLG varyantını besleme (display=Hlg1000 seçimi NodeParcel'in çıkış-transform bloğundan), tenBit yolu zaten var (E1d); EGL colorspace kararı (default kalır — öneri); 10-bit HEVC zinciri kontrol | GlesShaderSources.kt (DRT-TAIL HLG bloğu F1-5'ten gelir — E2 yalnız display parametresini seçer), GlesDevelopSession/GpuDevelopPipeline (uOetf pini F1-5'in DRT-TAIL'ine aittir), MediaCodecMp4Writer (etiketler plan'dan zaten gelir — yalnız 10-bit kuralı aynen) | **L** | **F1-5 (DRT-TAIL varyantı) + F1-6 (OutputTransform wire) — dürüst bekleme** |
| **E2-c** | Cihaz kabul: deneme-encode (API 34 cihazlar), ffprobe satırları (§6.3), ramp/kod-721 doğrulama, MediaMuxer colr kanıtı, hata düzeltmeleri | cihaz-turu kod (cihaz-gated; yeni dosyalarda saf model), docs kaydı | **M** | E2-a + E2-b; ADB sahibin turu |

Dürüst tablo — F1 beklemeden YAPILABİLECEKLER: E2-a'nın tamamı
(planner/format/test); E2-b'nin `tenBit` mekanizması E1d'den hazır ama
**DRT-TAIL'in kendisi F1-5'te** ve `OutputTransform`'un wire'ı
**F1-6'da** — HLG üretimi bu ikisi olmadan test edilemez. E2, F1 N1
merge'inden sonra başlama nedeni budur (sahibin 20:32 kararı).

---

## 8. Sahibin onayına açık kararlar

- **K1 — Deneme-encode cache politikası:** pozitif-ret de
  cache'lenir (öneri); süre process ömrü (öneri — ROM güncellenince
  anahtar zaten değişir); anahtar = MIME+profil+etiketler+çözünürlük
  + API+codecAdı+Build.ID. Açık nokta: kullanıcıya "deneme başarısız"
  mesajı gösterilir mi bir kez, sessiz ret mi?
- **K2 — Ret UX metni:** HevcPreflight mevcut DegradedOffer cümlesi
  ("AVC 8-bit available, choose — never a silent fallback") HLG için
  de geçerli mi, yoksa HLG reddi "HENÜZ yok" (E2'de değil, cihazda
  da yok) mesajıyla mı? Öneri: aynı DegradedOffer kalıbı (sessiz
  düşüş YASAK kuralı E1'den).
- **K3 — Preview tonemap yöntemi:** preview daima SDR-DRT + "HLG
  ihrâç hedefi" rozeti (öneri); alternatif: donanım KEY_COLOR_
  TRANSFER_REQUEST ile SDR tonemap (doğrulanmadı, E2 kapsamı dışı).
- **K4 — ffprobe kabul ölçütü:** §6.1 hedef satırları (profile=Main
  10, pix_fmt=yuv420p10le, transfer=arib-std-b67, primaries=bt2020,
  matrix=bt2020nc, range=tv) — hangileri ZORUNLU hangileri BİLGİ?
  Öneri: zorunlu = codec/derinlik/profil; colr üçlüsü hedef ama tek
  eksik SATIR uyarı (ROM farkı olabilir).
- **K5 — 10-bit HLG zorunluluğu:** HLG yalnız 10-bit Main10 (öneri,
  HevcProfile şekli); 8-bit HLG'yi hiç sunma. Teyit.
- **K6 — EGL colorspace:** default (yok) + shader'da HLG üretimi
  (öneri, §3.2); EXT_colorspace_bt2020_hlg YOK sorgulanmaz bile.
  Cihazda colr sağlanamazsa ikinci adım olarak açılır.
- **K7 — HDR10 sırası:** E2 yalnız HLG; HDR10 (Main10HDR10=4096 +
  hdr-static-info) E3'ün işi — teyit (plan §7.6 sırası: HLG önce).

---

## 9. Doğrulanamayanlar + online araştırma soruları

Doğrulanamayanlar (kod okuma + plan alıntısı ile aşılamayan):
- `c2.qti.hevc.encoder`'ın HLG ConfigDescr/VUI'sinde colr'ı doğru
  yazması (SPS/VUI → hvcC) — ffprobe cihazda kanıtlar.
- MediaMuxer'un (framework 37) HLG track'inde mdhd/stsd etiketlerini
  yazması — §4.2; ffprobe satır 2 teşhis.
- EGL default (colorspace-atama-olmadan) RGBA1010102 Surface'den
  kodlayıcıya VERİ taşımada sürücünün herhangi bir dönüşüm yapmaması
  — E1d 10-bit SDR ihrâcı bunun güçlü dolaylı kanıtı ama HLG
  etiketli kombinasyon doğrulanmadı.
- `EGL_EXT_gl_colorspace_bt2020_hlg` uzantısının bu Adreno 830
  ROM'unda varlığı — kullanılmayacaksa (öneri) bilinmesine gerek yok.
- KEY_FRAME_RATE/KEY_BIT_RATE kombinasyonunun HLG 4K30'da kodlayıcı
  tarafından yeniden reddi — bir reddi ExportPlanRun'ın mevcut
  fixAfterRejection mekanizması (E1) karşılar; davranış doğrulanmadı.
- Deneme-encode maliyetinin kendisi (4K 8 kare HEVC Main10 — birkaç
  saniye seviyesi tahmin edilir, ölçülmedi).

Online araştırma ajanına sorular:
1. **MediaCodec HLG etiket davranışı:** Android cihazlarda HLG
   kodlayıcı çıkışında (özellikle Qualcomm C2 video/hevc) colr/VUI
   etiketlerinin otomatik yazımı — KEY_COLOR_* ile sağlanıyor mu,
   ffprobe'da hangi satırlar görünür (emsal cihaz raporları).
2. **EGL colorspace HLG:** `EGL_EXT_gl_colorspace_bt2020_hlg`
   uzantısının mobil Adreno/Mali sürücülerindeki durumu + VARSAYILDIĞI
   takdirde donanımın RGBA1010102 Surface verisine uyguladığı dönüşüm
   (çift-OETF emsalleri, Grafika/CFG tartışmaları).
3. **MediaMuxer HDR:** framework MediaMuxer'un HDR10 (CTA-861.3 blob)
   ve HLG track'lerinde mp4 etiketlerini yazıp yazamadığı; MediaRecorder
   ile MediaMuxer arasındaki farklar (HDR10 E2 kapsamı dışı ama colr
   davranışı için bağlam).
4. **BT.2408 kompozit emsalleri:** HLG sahnede SDR grafik yerleşimi
   (0.75 kod) — taşınabilirlik (AOSP SurfaceControl, VideoCapabilities
   layer çizimleri) konusunda Android HDR pipeline emsalleri.
5. **HLG 10-bit/Main10 ret nadir durumlar:** 8-bit Main ile HLG
   kombinasyonunu reddeden cihaz raporları (K5 kararının gerekçesi için).

Sonuç: E2 bu cihazda fizibil (kodlayıcı bayrağı planın cihaz
dökümünde VAR; 10-bit GPU zinciri E1d'de ÇALIŞIYOR; renk matematiği
OpenDRT C++'da hazır ve 203-nit→0.75→kod-721 zinciri bu pakette
sayısal olarak doğrulandı). Kod tarafında eksik olan TEK parça
F1-5/F1-6'ın getireceği DRT-TAIL + OutputTransform wire'ıdır; E2-a
planner/etiket/cache tarafı bugün bile F1 beklemeden yazılabilir
— ama sahibin F1-N1-merge-sonrası kararı beklenir.
