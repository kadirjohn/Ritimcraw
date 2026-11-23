# E1e — Codec ölçüm modu tasarım planı (DEBUG, opsiyonel)

Durum: TASARIM PLANI (üretim kodu yok; docs-only). Tarih: 2026-11-24,
gece. Dal: `e1e-codec-measure-plan` (main `fd749d0`'dan). Plan maddesi:
KLIPP·FILMCRAFT-PLAN §13.1 ("Ölçüm modu (debug): 4K30 ve 4K60'ta, CBR
ve VBR ayrı ayrı. Her koşu için istenen bitrate, configure→start→drain
sonucu ve gerçekleşen bitrate kaydedilir. Sonuçlar ROM build kimliğiyle
saklanır"), §13.13 madde 4 ("Bitrate tavanları ... 'Uncapped'
seçeneği cihazda ölçülerek doğrulanmalı. Fiilen kabul edilen tavan hala
ölçülmedi") ve §7.3'ün uncapped maddesi. E1 paketindeki E1e satırı:
"Codec ölçüm modu (DEBUG, opsiyonel; zaman kalırsa). Ayarlar'da debug
satırı. Amaç: listelenen 160/220 Mb/s tavanının üstünün gerçekten kabul
edilip edilmediğini (uncapped) ölçmek."

Ek bağlam (sahibin gerçek cihaz gözlemi, görev iletisinden; repoda
kayıtlı bir ölçüm dosyası YOK): VBR'de hedef 74.5 Mb/s istendiğinde
gerçekleşen ~92.7 Mb/s (+%24); tahmin 523 MB iken gerçek dosya
~682 MB. Boyut tahmininin düzeltilmesi E1c'ye ait; bu plan ölçüm
modunun KENDİSİ ve sapma verisinin toplanması.

Disiplin: her iddia kod okunarak **doğrulandı** ya **doğrulanmadı**
(ölçüm ya da dış kaynak gerekir) etiketli. Üretim kodu yok; koda
dokunulmaz; adb çalıştırılmadı.

---

## 0. Özet + fizibilite

**Ne:** Ayarlar ekranının debug bölümüne bir "Codec ölçümü" satırı.
Tek dokunuş: cihazın gerçek encoder'larıyla kısa (≤2 sn) sentetik
koşular — kodek × çözünürlük/fps × rate mode × bitrate noktası
matrisi — configure kabulü, I-frame başına üretilen bayt ve gerçekleşen
ortalama bitrate kaydedilir; sonuçlar ROM build kimliğiyle saklanır,
DeviceReportScreen'de tablo olarak gösterilir.

**Neden:** CodecProbe (mevcut, doğrulandı) encoder'ın NE İLAN
ETTİĞİNİ okur (`bitrateRange.upper`, §13.13: HEVC/DV 160, AVC 220
Mb/s listeleniyor). İki soruya cevap YOK: (a) istenen tavan üstü
bitrate gerçekten KABUL EDİLİYOR MU (uncapped'ın c2.qti'deki gerçek
davranışı)? (b) VBR'de gerçekleşen bitrate istenenden NE KADAR
sapıyor (sahip gözlemi: +%24)? Her ikisi de ancak GERÇEK KOŞU ile
cevaplanır.

**Fizibilite (kısa):**

1. Altyapı büyük ölçüde VAR: `ExportFormats.video` zaten MediaFormat
   değerlerini saf Map olarak üretiyor (doğrulandı); `EncoderFacts`
   encoder'ı ada göre seçiyor; `EncoderDiagnostics` koşan encoder kimliğini
   kaydediyor; MP4 writer'ın `configure → start → drain` zinciri
   (MediaCodecMp4Writer.kt:757-779) ölçümün izleyeceği gerçek şekil.
   YENİ kod: sentetik kare üretimi + koşucu döngüsü + sonuç akışı.
2. MP4 YOK: koşular MediaCodec → ByteBuffer'a drain edilir, muxer
   KULLANILMAZ (bitrate ölçümü ham output baytlarından sayılır —
   dosya yazımı ve ffprobe bu modun PARÇASI DEĞİL; ffprobe yalnız
   sahibin cihaz-doğrulama adımında).
3. Süre bütçesi gerçekçi: 8-bit 4K30 sentetik 2 sn (60 kare); tam
   matris ~48 koşu + bekleme ≈ 3-4 dk (aşağıda).
4. Tek risk unsuru: 4K60 koşularının ve art arda ~30 encode'un termal
   etkisi (bütçe: koşular arası sabit bekleme; iptal her an).

**Sonuç modeli (tek cümle):** her koşu → `MeasureResult` (case
alanları + configureOk, encodedBytes, ptsDurationSec, measuredBps,
iFrameBytes/iFrameCount, encoder/hardware, wallMs — §3'teki tam
liste); birleştirilmiş tablo → `CodecMeasureReport(buildIdentity,
rows)` — JSON'a yazılır, ROM build kimliği anahtar.

---

## 1. Mevcut durum

### 1.1 Probe gerçekliği — LİSTE, GERÇEK KOŞU DEĞİL (doğrulandı)

`engine/CodecProbe.kt:73` `probe()` `MediaCodecList(REGULAR_CODECS)`
yürür; her encoder için `CodecReport` dolar: `bitrateModes` (CBR=2,
VBR=1, CQ=0 destek listesi, `CodecProbe.kt:162-167`), `bitrateUpper`
(`videoCapabilities.bitrateRange.upper`, `CodecProbe.kt:171` —
kodun yorumu bunu açıkça söylüyor: "Some encoders accept more than
they list; the measurement mode (separate package) finds that"),
`profileLevels`, `colorFormats`. Bu bilgi `EncoderFacts.fromReport`'la
(EncoderFacts.kt:89-108) plan katmanına akar; `ExportPlanner` tavanı
`BITRATE_ABOVE_CEILING` uyarsıyla kısar ve uncapped isteğine
`BITRATE_UNCAPPED_RISK` verir (ExportPlan.kt:343-355).

Yani bugün cihaz "160 Mb/s listeliyorsa" plan 160'ta durur ya da
uncapped'ı WARN ile geçer — ama 160'ın ÜSTÜNÜN gerçekten encode edilip
edilmediği HİÇBİR yerde ölçülmez. `DeviceReportScreen` (685 satır)
probe'un listesini gösterir; koşu verisi yok.

### 1.2 Export zinciri — ölçümün izleyeceği şekil (doğrulandı)

- Format inşası: `ExportFormats.video(plan, surface=false)` —
  `KEY_COLOR_FORMAT=YUV420Flexible`, `KEY_BIT_RATE`, `KEY_BITRATE_MODE`,
  `KEY_FRAME_RATE`, `KEY_I_FRAME_INTERVAL`, `KEY_PROFILE`, SDR BT.709
  limited (ExportFormat.kt:41-63). Ölçüm koşucusu bu üreticiyi AYNEN
  kullanır (kendi MediaFormat kurma KOPYASI YOK — sürüklenme riski
  baştan tek kaynakla kapanır).
- Encoder seçimi: `MediaCodec.createByCodecName(plan.encoderName)`
  (MediaCodecMp4Writer.kt:239-242) — ölçüm de ada göre seçer (c2.qti
  kardeşlerini ayırt etmek için; by-type seçim taahhüdü yok).
- Kare besleme: ByteBuffer yolu — `dequeueInputBuffer →
  getInputImage → copyI420ToImage → queueInputBuffer`
  (writer :757-779). Ölçüm koşucusu aynı Image API'sini sentetik
  karelerle kullanır (Surface GEREKMEZ — Surface yolu E1d/GPU işine
  aittir; öneri: v1 yalnız ByteBuffer).

### 1.3 VBR sapma verisi — sahip gözlemi (repoda ölçüm YOK)

| gözlem (sahip, gerçek cihaz) | değer |
|---|---|
| VBR istenen | 74.5 Mb/s |
| VBR gerçekleşen (dosyadan) | ~92.7 Mb/s (+%24) |
| tahmin boyut → gerçek | 523 MB → ~682 MB |

Bunların ölçüm MODUNA biçimi: `measured/requested` oranının koşu
başına toplanması (§6) — 74.5'in kendisi bir sabit değil, örneklem.
Plan sapmanın NEREDE kaynaklandığını varsaymaz (encoder VBR overshoot
mü, anahtar yanlış mı, I-frame yumuşatma mı) — ölçüm modu verinin
kendisini üretir; yorum E1c/E1e-b rapor aşamasında.

### 1.4 E1c notu

E1c (compact sheet + fps ölçümü, `e1c-polish` dalında bde227d/7b29a29 —
main'in İLERİSİNDE değil, doğrulandı: bu worktree main HEAD fd749d0)
boyut tahmininin düzeltilmesini içerir. Bu plan E1c'ye BAĞLI DEĞİL:
sapma verisi E1c'nin scale faktörüne girdi OLACAK, ama ölçüm modu
E1c'den bağımsız derlenir. Merge sırası için bakınız §7.

---

## 2. Ölçüm matrisi

Sabitler (öneri; §8'de sahibin onayı):

- **Kodekler:** AVC + HEVC ana satır grubu; **DV opsiyonel** (E3
  öncesi deneme ölçümü; DV encoder'ı bu cihazda VAR — §13.13 —
  ama ölçüm v1'inde KOŞULMASIN; matris tablosunda yalnız
  "dahil edilebilir" satırı görünsün (gerekçe §8)).
- **Bit derinliği:** 8-bit (ByteBuffer YUV420) ana grup. **10-bit
  (P010) YALNIZ HEVC Main10 için EK satır** — bu, E1d'nin GPU
  encoder-surface yolu DEĞİL; P010 ByteBuffer koşusu. AVC High10
  listede YOKSA satır otomatik düşer (EncoderFacts'tan türetilir).
- **Boyut/fps:** 3840×2160@30 ve 3840×2160@60 (§13.1'in iki
  noktası).
- **Rate mode:** CBR ve VBR. (CQ: cihazın .cq encoder'ı 512×512
  sınırıyla video için kullanılamaz — koşu listesine GİRMEZ.)
- **Bitrate noktaları (istenen):** her (kodek, mod) için
  tavan-altı 0.875× + tavan + tavan-üstü 1.25× ve 1.5×
  (HEVC listeli tavan 160 → 140 / 160 / 200 / 240 Mb/s;
  AVC 220 → 192.5 / 220 / 275 / 330). Noktaların KURALI:
  `listedCeiling × {0.875, 1.0, 1.25, 1.5}` — encoder tavan
  listelemiyorsa (yazılım/küçük cihaz) o encoder'ın satırı DÜŞER
  (rapora "no listed ceiling" notu); matris noktaları
  EncoderFacts'tan TÜRETİLİR (saf Kotlin; §3).

Satır sayısı (Xiaomi 15 Ultra varsayılanı):

| grup | koşu |
|---|---|
| AVC 8-bit 4K30 CBR ×4 nokta | 4 |
| AVC 8-bit 4K60 CBR ×4 | 4 |
| AVC 8-bit 4K30 VBR ×4 | 4 |
| AVC 8-bit 4K60 VBR ×4 | 4 |
| HEVC 8-bit aynısı | 16 |
| HEVC Main10 4K30/60 CBR/VBR ×4 nokta (yalnız P010 kabul ediyorsa) | 16 |
| **Toplam** | **48** |

48 × (2 sn encode + ~0.5 sn kurulum + 1.5 sn bekleme) ≈ **~3.2 dk**;
RAHAT bütçe <5 dk (§4'te iptal + ilerleme). "toplam < 60 sn" idealine ancak
kısaltılmış matrisle ulaşılır — §8'de sahibin
kısaltma kararı (ör. 10-bit grubunu E1d sonrasına bırakma → 32 koşu
≈ 2.2 dk).

**Her koşunun ölçtükleri:**

1. configure kabulü: `codec.configure(fmt, null, null, ENCODE)`
   başarılı mı / hangi exception (aynı typed red sınıfı değil —
   ölçüm hata gördüğünde koşuyu ATLAR, seri durmaz; koşunun kendisi
   `MeasureResult`'a düşer).
2. I-frame başına gerçekleşen bitrate: drain edilen bayt blokları
   `BUFFER_FLAG_SYNC_FRAME` bayraklıysa ayrı sayaçta toplanır
   (MediaCodec output flags; muxer yok, parse yok).
3. Gerçek ortalama bitrate: toplam ham output bayt × 8 / koşu
   süresi (besleme gerçek zamanlı DEĞİL — pts 1/fps
   aralıklı; süre PTS'ten, duvar saati yalnız rapor alanı).
4. `EncoderDiagnostics` (koşan encoder kimliği, donanım beyanı).

**Ölçümün dürüst sınırı:** sentetik kare gerçek klibin bitrate
temsili DEĞİLDİR (düz gri alan bitrate'i düşürür; hareket için
ramp + gürültü dokusu kullanılır; §9 sorusu). Ölçüm modunun işi
CONFIGURE kabiliyeti + mekanik tavan/alt sınırlar + relatif
sapmadır; "gerçek klip bitrate'i" her zaman export RAPORUNDAN
(ExportReport.actualVideoBps) okunur.

**Kare içeriği (öneri, ayarlanabilir):** basit bir ramp yürüyüşü: blackLuma'dan
whiteLuma'ya kare başına sabit oranda kayan çapraz ramp + bağımsız
n×n gürültü bitleri (her kare 16×16 bloklarla). Amaç: VBR encoder'ın sabit karede aşırı düşük bitrate'e
kaçmasını önlemek. İçerik etiketi sonuç satırına YAZILIR
(koşular yeniden üretilebilir).

---

## 3. Saf çekirdek: CodecMeasurePlan (JVM-test edilebilir)

Yeni dosya (öneri): `export/CodecMeasurePlan.kt`. Saf Kotlin —
Android tipi YOK (MediaFormat sabitleri Int/String olarak inline —
ExportFormat.kt'ın deseni).

```
data class CodecMeasureCase(            // bir koşu
    val mime: String,                    // "video/avc" | "video/hevc"
    val encoderName: String?,            // null = by-type (yedek)
    val profile: Int?, val bitDepth: Int,
    val width: Int, val height: Int, val fps: Int,
    val rateMode: Int,                   // BITRATE_MODE_* sabiti (2=CBR,1=VBR)
    val requestedBps: Long, val uncapped: Boolean,
    val durationSec: Double, val keyframeSec: Float,
    val content: String,                 // "ramp+v1" gibi içerik etiketi
)

data class MeasureResult(               // bir koşunun çıkışı
    val case: CodecMeasureCase,
    val configureOk: Boolean, val errorCode: String?,   // ör. IllegalStateException sınıf adı
    val encodedBytes: Long, val ptsDurationSec: Double?,
    val measuredBps: Long?,              // encodedBytes*8/ptsDurationSec
    val iFrameBytes: Long, val iFrameCount: Int,
    val encoder: String?, val hardware: Boolean?,       // EncoderDiagnostics
    val wallMs: Long,
    val stdLog: String,                  // cihaz logcat satırı (§5)
)

object CodecMeasurePlanner {
    // 1. Matris üretimi: (encoder facts) × sabit nokta kuralları → List<CodecMeasureCase>
    fun matrix(encoders: List<EncoderFacts>, opts: MeasureOptions): List<CodecMeasureCase>
    //    - role == GENERAL olan encoder'lar (CQ_ONLY/HDR_VARIANT ölçme)
    //    - nokta kuralı: listed ceiling × {0.875, 1.0, 1.25, 1.5} (hyp: §2)
    //    - ceiling yoksa encoder atlanır (bilgi satırı "no listed ceiling")
    //    - 10-bit HEVC satırı yalnız acceptsTenBitInput() && supportsTenBitProfile()
    //    - CBR desteklenmiyorsa mod satırı düşer, rapora not
    // 2. Filtre: durationSec (varsayılan 2.0), iptal: run loop check
    // 3. Format üretimi: encoder'a değil CASE'e MediaFormat değer Map'i döndürür
    //    (Value: ExportFormats.video'nu YENİDEN KULLANAMAZ — plan tipi ExportPlan
    //    istiyor; ölçüm Map'i ExportFormats'ın ANAHTAR KURALINI izler: aynı key
    //    adları, aynı tip kuralı; test ExportFormats.video'nun çıktısıyla karşılaştırır)
    // 4. Sonuç birleştirme: rows → özet satırlar (max measured/requested, VBR
    //    sapma ortalaması, configure reddi sayısı) → DeviceReportScreen tablosu
    fun summarize(rows: List<MeasureResult>): MeasureSummary
}
```

JVM testleri (öneri, E1e-a):

1. Matris üretimi: üç sahte encoder (ceiling 160 M, 220 M, liste yok) →
   nokta çarpanları doğru (140/160/200/240 ve 192.5/220/275/330;
   yuvarlama kuralı testte sabitlenir), 10-bit satırı Main10'suz
   düşer, GENERAL olmayan atlanır.
2. Case→MediaFormat Map: ExportFormats.video'nun aynı ayarla
   ürettiği Map ile key-value EŞ (bitrate/bps, mode, prof, i-frame;
   "Bitrate modu anahtar adı MediaFormat.KEY_BITRATE_MODE sabitine
   eş" — sabit Int/String inline).
3. MeasureResult ölçümleri: encodedBytes=120000, ptsDurationSec=2.0 →
   measuredBps=480000; iFrame sayacı SYNC frame bayrağı mock dizi
   ile; configure red → configureOk=false + hata sınıf adı kayıtlı kalır.
4. summarize: VBR sapma listesi (4 koşu: %10/%20/%24/%30) →
   ortalama/maks rapor satırı; CBR satırları sapma
   hesabına GİRMEZ.
5. Uç: boş matris (ceiling'siz cihaz) → boş liste + neden satırı.

---

## 4. Cihaz koşucusu (Android tarafı — E1e-b)

`engine/CodecMeasurer.kt` (öneri; device-gated — PreviewRenderer/
CodecProbe deseni): her case sırayla,

1. `MediaCodec.createByCodecName(case.encoderName)`; hata →
   `MeasureResult(configureOk=false, ...)`; devam.
2. MediaFormat kur (CodecMeasurePlanner'ın Map'i); `configure(ENCODE)`.
   Hata → aynı red kaydı. BAŞARIDA `EncoderDiagnostics` okunur
   (codecInfo üzerinden, writer'ın kalıbı).
3. `start()`; sentetik kareler beslenir: `dequeueInputBuffer →
   getInputImage → copyI420ToImage benzeri doğrudan plane yazımı
   (ramp yürüyüşü I420 üretir; yazım Image'ın plane stride'ına uyar) → queueInputBuffer(ptsUs=i*1e6/fps)`; durationSec×fps
   kare beslenir (30 fps'de 60, 60 fps'de 120 kare).
4. Drain: output baytları toplanır; `BUFFER_FLAG_CODEC_CONFIG` baytları
   ölçüme GİRMEZ (parameter setler); `BUFFER_FLAG_SYNC_FRAME` işaretli
   bloklar iFrameBytes/iFrameCount'a ayrılır; pts delta toplamı
   ptsDurationSec'i verir (son FRAME pts − ilk FRAME pts, /1e6).
5. `stop/release`. Bekleme: sabit `INTER_RUN_COOLDOWN_MS`
   (öneri 1500 ms; termal — §8). Her 4 koşuda bir ilerleme
   callback; iptal: koşucu her case sınırında checked flag —
   iptalde şimdiye kadarki sonuçlar yine kaydedilir (kısmi tablo dürüst değerde).
6. Log: her koşu tek satır `ritim-codec-measure` etiketiyle
   (yeni etiket; stdLog formatı saf `CodecMeasurePlanner.line(r)`).

Süre bütçesi: 48 koşu × (2 sn pts + ~0.4sn drain/kurulum + 1.5 sn
bekleme) ≈ 3.2 dk; sıcak başlangıçta bir kez daha az. **RAHATLIKLA**
60 sn AŞILIR — görev metnindeki "<60 sn" hedefi yalnız 4K30 8-bit
alt matrisiyle (16 koşu × ~2.5 sn aktif) görünür; tam matris
uzundur. Öneri: matrisi tek hamlede koşturmak yerine KISIMLI
koştur (alt matrisler: "8-bit CBR", "8-bit VBR", "10-bit"), her
kısım kendi düğmesinde — hem süre hem termal kontrolü (§8 sahibin
kararı).

---

## 5. Sonuç saklama ve sunum

- **Kimlik (ROM build):** `Build.FINGERPRINT` + `Build.VERSION.INCREMENTAL`
  + model — bugün hiçbir yerde okunmuyor (grep doğrulandı: Build
  sadece SDK INT kullanılıyor — Theme.kt:40), yenidir. Anahtar
  önerisi: `"fingerprint"` tek satır (+ model ayrı alan);
  HyperOS/Neoteric gerçek ayrımı fingerprint'in `eng.dino` incremental'i
  zaten taşır (§13.13'te okunan: fingerprint HyperOS gösteriyor,
  incremental `eng.dino` — ölçüm satırları İKİSİNİ de yazar).
- **Yer:** `filesDir/codec-measure/<fingerprint-hash>.json` —
  export kuyruğunun `project.json` YANINA DEĞİL (ölçüm debug verisi;
  proje dosyası kirletilmez). Yazma: ExportStore deseni (tmp + rename).
  Kural: aynı fingerprint'te yeni koşu eskiyi DEĞİŞTİRİR (öykü yok;
  cache süresi YOK — ROM değişince fingerprint değişir, yeni dosya).
- **JSON biçimi (`CodecMeasureWire.render`, saf):**
  `{"v":1,"fingerprint":"...","model":"...","incremental":"...",
   "rows":[{case alanları..., "configureOk":, "errorCode":,
   "encodedBytes":, "ptsDurationSec":, "measuredBps":,
   "iFrameBytes":, "iFrameCount":, "encoder":, "hardware":,
   "wallMs":}]}` — okuma `parse` bilinmeyen sürümde `null`
  (ExportReportWire deseni).
- **Sunum:** DeviceReportScreen'e yeni bölüm "Codec ölçümü":
  satır başına `codec · WxH@fps · MODE · istenen → gerçek (fark %)`;
  configure red satırı kırmızı ton; üstte özet (summarize). Copy
  button: tam JSON (mevcut ekranın "copy" kalıbı).
- **EncoderFacts'e besleme (öneri: ELLE, cache):** ölçümün bulduğu
  "gerçek tavan" (configure'ın kabul ettiği en yüksek nokta)
  bir satırda "plan kısıtına uygula" düğmesiyle sahibin onayına
  sunulur; otomatik kalıcı değer YAZILMAZ (EncoderFacts zaten probe
  listesiyle oluşur; sahibin onayı olmadan ölçüm verisi plan
  davranışını DEĞİŞTİRMEZ — plan maddesinin "sonuçlar saklanır"
  hükmü raporlama içindir; davranış değişimi ayrı bir karardır.
  Uygulama şekli: debug satırında `uncappedCeilingBps` cache'i
  (filesDir json) + "uygula" düğmesi EncoderFacts'i o oturum için
  zenginleştirir (in-memory; kalıcı yazma yalnız sahibin ana
  kararında).

---

## 6. VBR sapma kaydı (E1c'nin veri kaynağı)

Her koşunun satırına NÖTR çift olarak yazılır:

```
requestedBps: 74500000, measuredBps: 92700000  (satır başına; temsili örnek)
```

Türevler (şimdi hiçbir yerde hesaplanmaz — yorum E1c'de):

- sapma = measured/requested (CBR satırları ayrı etiketle — CBR
  sapması encoder hatası göstergesi, ölçüm yapılabilir);
- VBR scale faktörü adayı = VBR satırlarının sapma medyanı
  (matristen 10-bit dahil tümü; içerik satırdan okunur — içerik
  bağımlılığı rapor edilir, gizlenmez).

Böyle bir veri kaynağı E1c merge sürecinde tek düzlem: E1c'nin
boyut tahmini bu JSON'u OKUR (cache yoksa mevcut davranış: istenen
bitrate'le tahmin) — ölçüm bir kez koşulmadan plan değişmez;
koşulduysa tahmin sapmaya göre düzelir. Bu okuma E1c'nin tamamlayıcı işidir;
E1e teslimine DAHİL DEĞİLDİR.

---

## 7. İş kırılımı

| Paket | Kapsam | Dosyalar (yeni) | Testler | Boyut |
|---|---|---|---|---|
| **E1e-a** Saf plan + model | `CodecMeasurePlan.kt` (case/result/planner/summary/line), `CodecMeasureWire.kt` (JSON), MeasureOptions sabitleri | `app/src/main/java/com/ritim/ritimcraw/export/CodecMeasurePlan.kt` (+ `CodecMeasureWire.kt`) | 5 JVM testi (§3) + wire round-trip | S |
| **E1e-b** Koşucu + UI + protokol | `engine/CodecMeasurer.kt` (device-gated; Image besleme, drain sayaçları, iptal, cooldown), Ayarlar debug satırı ("Codec ölçümü (8-bit)" / "(10-bit)" iki kısım düğmesi), DeviceReportScreen tablosu, EncoderDiagnostics kaydı; sahibin cihaz protokolü (ffprobe doğrulaması adımı) bu planın §2-4'üne eklenmiş bir "sabah turu" kısmı | koşu elle tetiklenir (cihazda) | androidTest: cihazda bir koşu (küçük matris) bayt/duration assert; JVM tarafı yok (koşucu Android tipi ile dolu) | M |

**Bağımlılık sırası:** E1e-a yalnız mevcut `EncoderFacts`/`ExportFormats`
sabitlerine bağlı (main'de VAR) → ÖNCE. E1e-b, E1e-a + DeviceReport
(ekran mevcut) + EncoderDiagnostics (mevcut). E1c ile ÇAKIŞMA YOK:
E1c export/ altında farklı dosyalar; sadece summarize satırının
E1c'nin okuyacağı JSON'a akması birleştirme notudur.

---

## 8. Sahibin onayına açık kararlar

1. **DV koşusu:** v1 matrisinde KOŞULMASIN (öneri) — DV encoder'ın
   üçüncü taraftan davranışı E3'ün işi; ölçüm satırı yalnız
   "dahil edilebilir" notu taşır. Aksini istersen DV 8 koşu daha
   (+~1 dk; P010 zorunlu — DV 10-bittir).
2. **Koşu süresi:** 2 sn (öneri). 1 sn'e düşürme koşu sayısını
   etkilemez, drain'in ilk kare istatistiğini (I-frame başlangıç
   patlaması) gürültülü kılar; 3-4 sn VBR overshoot'unun
   oturmasını daha iyi izler ama toplamı 6+ dk yapar.
3. **Uncapped deneme noktaları:** {0.875, 1.0, 1.25, 1.5} çarpanları
   (öneri) — 240 Mb/s noktası kamera modlarının 200-240 Mb/s saha
   raporuyla (§13.13) uyumlu; 300+ Mb/s noktaları (Klipp'in ayar
   aralığı) matrisi büyütür — ikinci bir tur olarak bırakılmalı.
4. **10-bit grubunun zamanı:** P010 ByteBuffer koşusu E1d GPU
   yolunun yerine GEÇMEZ (v1 sınırları dürüstçe; ölçüm E1d'den
   bağımsız). Main10 satırları bu turda koşsun mu, E1d tamamlanınca
   mı — süre bütçesi tercihi.
5. **Cache süresi:** öneri yok (fingerprint anahtarı zaten ROM'a
   bağlı). "Ölçümü uygula" düğmesinin oturum-ömrü etkisi (§5)
   kalıcı oturum mu — sahibin tercihi.
6. **Termal bekleme:** 1500 ms sabiti (iptal her koşu sınırında).

## 9. Doğrulanamayanlar + online sorular

1. **c2.qti VBR davranışı:** Qualcomm Codec2'ın VBR'de istenen
   bitrate'i nasıl yorumladığı (hedef ortalama mı, üst sınır mı,
   "quality" katlanması mı) konusunda belgelenmiş bir biçim var mı — +%24 sapmanın
   mekanik açıklaması buradadır; ölçüm modu VERİYİ üretir,
   online araştırma mekanizmayı açıklar.
2. **VBR overshoot'un I-frame ile ilişkisi:** ilk GOP'un başlangıç
   patlaması hesaba katılmadan kısa-klip ölçümü sapmayı abartıyor mu — koşu süresi kararını (§8.2) etkiler.
3. **BUFFER_FLAG_SYNC_FRAME güvenilirliği:** Codec2 katmanında
   bayrağın gerçekten her key frame'de geldiği — sentetik koşuda
   doğrulanır; doküman belirsizse muxer'sız I-frame ölçümünün
   güveni raporda AÇIKÇA DÜŞÜK işaretlenir.
4. **Sentetik içeriğin temsili:** düz gri alan koşusu bitrate'in alt
   sınırını ölçer; "ramp hareket" fiilen ne kadar zorlayıcı —
   gerçek klibin ölçümüyle E1e-b cihaz turunda karşılaştırılır
   (sahip ffprobe adımı), referans karşılaştırma verisi ölçümden
   SONRA bu plana eklenir (ölçüm modu kapsamının dışında değil).
5. **`video/dolby-vision` MIME'ında createByCodecName + configure
   davranışı** (E3 öncesi bilgi toplama; v1 koşusu yok — §8.1).
