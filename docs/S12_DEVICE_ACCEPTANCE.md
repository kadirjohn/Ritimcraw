# S12 — Görüntü stabilizasyonu cihaz kabul protokolü (sahibin el kitabı)

Durum: PROTOKÖL (üretim kodu yok; docs-only). Tarih: 2026-11-24,
gece. Dal: `s12-acceptance-protocol` (main `fd749d0`'dan). Bu
doküman, IMAGE_STAB_PLAN.md §5'in son paketi S12'nin ("Cihaz kabulü —
sahip, adb") ADIM ADIM tur protokolüdür. IMAGE_STAB_PLAN.md'ye
dokunulmaz — gece s-measure ajanı o dosyanın §4.1b'sini (el
sallantılı fikstür masaüstü ölçümleri) yazıyor; bu protokol o
sayılar ÇIKTIKTAN SONRA sabah koşulacak turun tarifidir.

Üslup kuralı: her adım TEK satır kopyalanabilir komut içerir;
"ne görmelisin" net yazılır; kabul/kabul değil eşikleri plan
hedeflerinden verilir (ölçülen sayı değil — s-measure sayıları
çıkınca sahip onayına gidecek). `%PLACEHOLDER%` = s-measure
çıktısıyla doldurulacak yer.

Dürüstlük durumu (bu protokolün yazıldığı taban, kod okunarak
doğrulandı):

- **main (fd749d0) image stabilizasyon motorunu ÇALIŞTIRAMAZ:**
  `IMAGE_STABILIZATION_ENABLED` bayrağı S4 dalında (`s4-image-stab
  -integration`, commit `73be3b7`), main'e MERGE EDİLMEDİ. Main'de
  `FeatureFlags.kt` yalnız `GYRO_STABILIZATION_ENABLED = false`
  içeriyor; `StabilizationGate` yalnız gyro semantiği biliyor.
- **A7 harness'inde (main + S4 dalında da) image vakaları YOK:**
  `ExportAbSweep.cases()` sabit 4 vaka: neutral, grade, stab,
  grade-stab (hepsi gyro ekseni). `stab-image` / `grade-stab-image`
  vakaları plan §4'te S4 kapsamında tanımlı; kodda henüz
  görünmüyorlar (S4'ün A/B kısmı bitmemiş).
- Bu yüzden bu protokol İKİ senaryoya yazıldı: **Senaryo 0**
  (bugünkü main build'i ile koşulabilir: bayrak-bilmez taban
  ölçümleri — süre, termal, A7 gyro koşusu) ve **Senaryo E**
  (S4+S5 birleşince motorun açık olduğu build ile koşulacak
  eksiksiz kabul turu). Hangi senaryonun "S12 geçti" işaretini
  verdiği §7'de.

Kod tanımlayıcıları okundu ve doğrulandı; hiçbir adb komutu bu gece
ÇALIŞTIRILMADI (telefon kuralı). Hiçbir cihaz sayısı ölçülmedi.

---

## 0. Ön koşullar

### 0.1 Telefon (Xiaomi 15 Ultra, kod adı "xuanyuan", SM8750)

Cihaz 23:30'dan beri prize takılı DEĞİL olmalı (sıcak taban
ölçümü şart — §1.1). ROM kimliği protokol başında bir kez
okunur:

```
adb shell getprop ro.product.model && adb shell getprop ro.build.version.sdk
```

Ne görmelisin: `25010PN30G` ve `37` (§13.13'te okunan build: SDK 37).
Farklıysa tablodaki ölçümler farklı build kimliğiyle etiketlenir,
durdur gerekmez.

### 0.2 Klipler (STEPS/media/test — Mac'te; telefona aktarılacak)

| dosya | boyut | rolü |
|---|---|---|
| `elde_sabit.mcraw` (+ .gcsv) | 1.5 GB | el sallantılı GERÇEK klip (gece B ajanının §4.1b masaüstü ölçümü bu klip üzerinde; cihaz ana ölçüm klipsi) |
| `yurumek.mcraw` (+ .gcsv) | 5.9 GB | yürüyüş (yüksek genlikli sallantı; uzun — termal turu bu üzerinde) |
| `yavas_pan.mcraw` (+ .gcsv) | 1.7 GB | yavaş pan (hız-adaptive yumuşatma; pan'in korunması görsel kontrol) |
| `261110_004150_VIDEO_24mm.mcraw` (+ .gcsv) | 2.2 GB | nearly-static fikstür (A7 koşusu için gyro ekli; S1–S3 masaüstü karşılaştırma tabanı) |
| `261110_004208_VIDEO_24mm.mcraw` (+ .gcsv) | 2.2 GB | ikinci nearly-static fikstür (2. A7 koşusu) |
| `VID_20261110_004247.mp4` | 187 MB | MP4 (Mi-Log) — **bu turda ölçülemez** (§5) |

Aktarım (doluluk yetersizse elde_sabit + bir fikstür yeter):
telefonun Camera/DCIM klasörüne Mac'ten kopya (Finder/MTP veya
`adb push` — sahip seçer; adb push örnek adımı):

```
adb push ~/projects/Ritimcraw/STEPS/media/test/elde_sabit.mcraw /sdcard/DCIM/Camera/
```

Uygulama içe aktarma kalıbı (elle, sahip yapar): uygulama →
Library → klasör içe aktarma (FolderImportBrowser) → klibi aç.

### 0.3 Mac'te hazır olması gerekenler

- `adb` (`/opt/homebrew/bin/adb` bu makinede doğrulandı). Kablosuz
  bağlantı gerekiyorsa `tools/ww-device-replay.sh` deseni:
  `tools/ww-device-replay.sh <pairHost:port> <pairCode> <connectHost:port>`
  (pair + connect).
- `tools/build-desktop.sh` YEŞİL (masaüstü taban; "1 Faz A").
- `ffprobe` (MP4 fikstürün künyesi için; bu turda MP4 ölçümü v1 dışı, §5).
- Sabah ölçüm tablolarının basılacağı yer: **IMAGE_STAB_PLAN.md
  §4.1b** (B ajanının tablosu) — bu protokol OKUMA yapar, YAZMAZ.

### 0.4 Build (hangi APK?)

| Senaryo | Build | Bayraklar | Ne ölçebilir |
|---|---|---|---|
| **0** (bugün koşulabilir) | herhangi bir güncel main build'i | GYRO=false, IMAGE bayrağı yok | Termal taban (motor kapalı, iş yükü yok), A7 gyro vakalarıyla koşusunun SKIPPED kalıbı, FATAL=0 akışı — motorun KENDİSİ ölçülemez |
| **E** (S4+S5 birleşince) | S4+S5 merge'lu build | GYRO=false, IMAGE=false (S12 geçmeden açılmaz — §6 madde 10) | Analiz işçi süresi (bayrak KAPALIYKEN işçi ölçülür mü? HAYIR — kapalı bayrak işçiyi hiç başlatmaz. Senaryo E için bayrağın TEST amaçlı açıldığı debug build gerekir; aşağıdaki nota bak) |

**Senaryo E'nin bayrak gerçeği (dürüst sınırlar):**
`engineFor`, `IMAGE_STABILIZATION_ENABLED && imageAnalysisReady`
olmadan IMAGE dönmez (S4 dal gate kodu doğrulandı). Cihazda
motorun KENDİSİNİ ölçmek (analiz işçisinden preview warp'a) için
bayrağın geçici olarak `true` derlendiği bir DEBUG build gerekir:
`const val IMAGE_STABILIZATION_ENABLED = false` → `true`
(`FeatureFlags.kt`), assembleDebug, install. Bu bir "test build"idir —
"geçti" işareti yalnız bu build ile yapılan ölçümlerle konur ve
bayrak S12 geçtikten sonra kalıcı `true` yapılır (§7).

APK kurulum + temiz başlangıç adımı:

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Logcat temizleme (tur başında bir kez):

```
adb logcat -c
```

---

## 1. Faz A — Masaüstü taban çıtısı (önceden hazır)

Amaç: sabah cihaz sayıları bu tabana KARŞILAŞTIRILACAK. Gece B ajanı
(IMAGE_STAB_PLAN §4.1b) şu tabloyu dolduruyor; sabah okunur,
kopyalanmaz — bu protokolün tabloları "cihaz" sütunlarını ister.

Referans masaüstü sayıları (§4.1'den, ZATEN PLAN'DA VAR — tekrar
koşulmaz; s-measure güncelleyecekse B ajanının ölçümüdür):

| metrik (masaüstü, elde_sabit benzeri klip) | değer (§4.1/§4.1b) |
|---|---|
| analiz duvar saati / klip süresi | 1 thread 0.37x, 2 thread 0.19x (fikstür 261110_004150, masaüstü) |
| köşe tespiti / LK / fit / piramit ms-çift | 3.25 / 2.34 / 0.03 / 0.68 ms (masaüstü, sentetik bench) |
| hedef analiz süresi | ≤1.0x klip süresi; **KABUL ≤1.5x (plandan)** |

Sahibin sabah yapması gereken Faz A işlemi yalnızca: build/desktop
araçlarının taze olduğunu doğrulamak (main'de kod değişmediyse
`bash tools/build-desktop.sh --no-tests` YEŞİL yeter) ve §4.1b'in
varlığını kontrol etmek.

---

## 2. Faz B — Cihazda analiz süresi/termal

**Senaryo belirleme:** Senaryo 0'da (bayraksız build) bu fazın
"analiz" adımı YOKTUR — işçi hiç başlamaz (gate doğrulandı: bayrak
kapalıya engineFor hiç IMAGE dönmez). Senaryo 0'da Faz B yalnızca
TERMAL TABAN ölçer (uygulama boşta sıcaklığı). Senaryo E'de
(bayraklı debug build) tam analiz süreci ölçülür. İki senaryo
da tabloya işlenir.

### B.0 Sıcak taban okuması (her iki senaryo, tur başında)

```
adb shell dumpsys battery | grep -i temp && adb shell dumpsys thermalservice | grep -i "status\|temperature" | head -5
```

Ne görmelisin: pil sıcaklığı ve termal servis durum satırı
(THERMAL_STATUS_NONE beklenir; "SEVERE" görürsen turu soğumaya
bırak). NOT: **`PowerManager.getThermalHeadroom` uygulama içinde
hiç kullanılmıyor** (grep doğrulandı; SystemStats.kt GPU/CPU/RAM/
storage okur, termal okumaz) — plan §13.14 "headroom ~10 s
aralıkla okunmalı" önerisi UYGULANMAMIŞ; bu turda headroom adb'den
okunur (dumpsys); uygulama içi okuma ileriki pakete madde
olabilir. §8'e soru düşüldü.

### B.1 Analiz süresi (yalnız Senaryo E: bayraklı debug build)

Sahip elle (ekran): uygulamayı aç → elde_sabit klibini aç →
Stabilize sekmesi → `[Analiz et]` düğmesine bas → ekrandaki
ilerleme çubuğu bitene kadar bekle; bitiş zamanını not et
(başlangıç: düğme basımı; bitiş: kart "İyi/Orta/Zayıf" rozetine
döner). Analiz sırasında sahip başka bir şey yapmamalı (screen-off
yok, uygulama ön planda kalsın; işçi karar 9 gereği
oynatma sırasında duraklar — bu ölçümü bozmamak için
hiçbir şey oynatılmaz).

Yardımcı döküm (analiz sırasında/sonrası render plan satırları; analiz süresinin logcat'ten OKUNAMAYACAĞI aşağıda):

```
adb logcat -d -s ritim-stab-timing | tail -20
```

Ne görmelisin (Senaryo E): stabilize edilmiş oynatmadaki
`planMs/warpMs` satırları — bunlar RENDER süreleridir,
analiz süresi DEĞİLDİR (aşağıdaki dürüst limit).
**DÜRÜST LİMİT:** `ritim-stab-timing` etiketi bugünkü koddaki
şekliyle RENDER (plan/warp) satırları yazar (jni_bridge.cpp:102-125
doğrulandı: "planMs/warpMs/totalMs" — analiz işçisinin decode/
tracker aşama süreleri için AYRI bir etiket kodda YOK). Analiz
süresi bu turda **duvar saatiyle (sahibin kronometresi veya
ekrandaki ilerleme tahminleri)** ölçülür; aşama aşama cihaz
profili (köşe/LK/fit ms) `image_stab_measure`'dan değil —
cihaz build'i yok. Aşama profili masaüstü §4.1b tabanından
okunur; cihazda yalnız TOPLAM süre kabul edilir. (Aşama etiketi
eksikliği §8'e kaydedildi.)

Kabul kuralı (plandan, sayı değil): analiz duvar saati ≤ **1.5x
klip süresi** (hedef 1.0x). Klip süresi `elde_sabit` için:
%KLIP_SURE_SN% sn (sahip dosyadan okur veya kronometre; §4.1b
tablosunda masaüstü klip süresi yazılı). Doldurulacak tablo:

| klip | klip süresi (sn) | analiz duvar (sn) | oran | 1.5x içeride mi |
|---|---|---|---|---|
| elde_sabit | %...% | %...% | %...% | EVET/HAYIR |
| yurumek | %...% | %...% | %...% | EVET/HAYIR |

### B.2 Termal provokasyon (uzun klip; hem Senaryo 0 tabanı hem E)

Sahip elle: yurumek klibini aç; ANALİZ E turunda stabilize
et, analiz sonrası 60 sn önizleme OYNAT (kaydırıcı hareketleri
yapmadan düz oynatma; oynatma sırasında işçi karar 9 gereği
duraklar — ekranda gözle; ilerleme geri gelmiyorsa §8 notu).

B.0'daki sıcak okumasını tekrarla (60 sn oynatma SONRASI):

```
adb shell dumpsys battery | grep -i temp
```

Kayıt: oynatma sonu sıcaklık − taban sıcaklık farkı. Eşik yok
(plan sabit termal hedefi koymadı; kayıt "makul" olarak okunur — aşırı
ısınma (>5-8°C pil veya termal status yükselmesi SEVERE'ye
kadar) görürsen Faz B kırmızıya düşer; nümerik eşik sahip
kararıdır — %ESIK_TERMAL_DEG% (sahip doldurur)).

### B.3 İşçi duraklama davranışı (yalnız Senaryo E; karar 9)

Sahip elle: analiz sırasında (ilerleme %50'ye gelmeden) aynı
klibi OYNATMAYA başla. Ne görmelisin: analiz ilerlemesi durur; oynatma
bitince ilerleme kaldığı yerden devam eder (checkpoint, plan §3.6). Ekran
görüntüsü al (§4'teki adım). KAYIT: durakladı mı (E/H),
devam etti mi (E/H). Bu kabulün "S12 geçti" kriterine katkısı
görüşseldir (sayısal eşik yok) — tabloya EVET/HAYIR yazılır.

---

## 3. Faz C — A7 export A/B koşusu

### C.1 Bugünkü A7 gerçeği (main + S4 dalında doğrulandı)

`ExportAbSweep.cases()` sabit 4 vaka: neutral, grade, stab
(gyro), grade-stab (gyro). Bayrak durumu farkı:

| build | vakalar | koşar mı | satır |
|---|---|---|---|
| main (Senaryo 0), GYRO=false | 4 gyro vakası | neutral+grade koşar; stab+grade-stab **SKIPPED (gyro paused)** satırıyla atlanır (`pausedSkip` doğrulandı, eb687fa) | summary: cases=6 skipped=6 flagged=0 ... |
| bayraklı debug build (Senaryo E), GYRO=false, IMAGE=true | 4 gyro vakası + (S4 tamamlanmadıysa) image vakaları YOK | image vakaları plan §4'te tanımlı (stab-image/grade-stab-image) ama kod koşularında GÖRÜLMEDİ — S4'ün A/B kısmı bitmemiş; bu turda yalnız gyro satırları okunur | image satırı olmalı, §8'e soru |

Uygulama komutu gerektirmeden tetiği (sahip): Ayarlar →
geliştirici hata ayıklama bloğu → **"Export A/B (debug)"** düğmesi
(SettingsScreen doğrulandı; `BuildConfig.DEBUG` dışında düğme
YOK — release performans koşusu A7'yi içermez). Düğme ilk
gyro-loglu klibi otomatik seçer (AppNav doğrulandı); log yoksa
toast: "İçe aktarılan klibin bağlı bir gyro kaydı yok".
Klip SEÇİMİNİ etkilemek için: 261110_004150 klibinin
içe aktarımında .gcsv'i birlikte aktar (import eşleştirmesi
gyro'yu bağlar).

Koşu sonrası log dökümü:

```
adb logcat -d -s ritim-parity | grep "export-ab"
```

Ne görmelisin:
- `export-ab neutral frame=...: maxDiffLsb=... mismatch=...%`
  ve `grade` satırları (istatistik satırları).
- `export-ab stab frame=... SKIPPED (gyro paused)` — 6 satır
  (2 vaka × 3 kare) BEKLENEN durum; HATA DEĞİL.
- Özet: `export-ab summary: cases=6 skipped=6 flagged=0
  worstMaxDiffLsb=%...% tookMs=%...%` — Senaryo 0'da
  karşılaştırılan 6 satır (neutral+grade × 3 kare), atlanan 6
  satır (stab+grade-stab × 3 kare, gyro paused); ~2 dk bütçe
  (tookMs klip uzunluğuna bağlı; fikstür ~310 kare).

KABUL (Senaryo 0): `flagged=0` VE `EXPORT_MISMATCH` bayrağı
HİÇBİR satırda yok (`isMismatch`: mismatch>0.5% VEYA
maxDiffLsb>2 — ikisi de aşılmamalı). Koşu süresi tookMs
%...% ms (tabloya; sabit eşik yok — şeffaf kayıt).

KABUL (Senaryo E, S4'ün A7 tamamlanmış olmalı):
ek `export-ab stab-image ...` satırları koşmalı ve flagged=0
olmalı. "SKIPPED: analiz yok" satırı görürsen: harness analizi
önce çalıştıramadı demektir — klip için analiz YAPILMAMIŞTIR;
önce Faz B/D'de analiz tamamlanmalı, sonra A7 tekrar koşulur.
Bu satır EXPORT_MISMATCH SAYILMAZ (plan §4) ama kabul turu
"eksik koşu" olarak işaretlenir.

KAYIT: summary satırı ve tüm case satırları sabah §4.1c
alt bölümüne kopyalanmak üzere bir dosyaya alınır (komut):

```
adb logcat -d -s ritim-parity | grep "export-ab" > s12-export-ab-$(date +%H%M).log
```

### C.2 A7 koşusunun fikstürleri: 2 adet

261110_004150 VE 261110_004208 için tekrar. AppNav her koşuda
kaynak listesinden İLK gyro-loglu klibi seçer; koşunun klip
seçimini doğrudan etkileyemezsin. İkinci fikstürü koşturmak
istiyorsan kütüphane sırasını değiştir (veya birinci klibi
geçici kaldır) ve düğmeye tekrar bas. Pragmatik yol: tek
fikstürle bir koşu yeterli; ikinci fikstür opsiyonel (süre
bütçesi).

---

## 4. Faz D — Görsel/preview kabul (sahip elle)

Bu faz EKRAN gözlemidir; ölçüm yok; her gözlem EVET/HAYIR
olarak tabloya işlenir. Sahip adımları (bayraklı debug build —
Senaryo E'de):

1. elde_sabit klibini aç; Stabilize sekmesi → Motor: `Görüntü`
   seçilir (segment butonu; §3.8). NOT: S5 UI'si bu build'de
   YOKSA (S5 birleşmemişse) sekmede yalnız gyro notu görünür —
   Senaryo E ancak S4+S5 birleşince koşulabilir (§0.4). BAYRAK
   YOKSA BU FAZIN TÜMÜ MÜMKÜN DEĞİLDİR (dürüst sınır).
2. `[Analiz et]`; beklenen: kart biter, rozet **İyi**
   (`check_circle`, "İyi takip") — Zayıf/Kullanılamaz çıkarsa
   klibin takip kalitesi tabloya not edilir, hata değil.
3. Güç %50, Zoom limiti 1.15 (karar 2 varsayılanı) bırak.
   ÖNİZLEME OYNAT.
4. GÖZLEMLER (§3 hedeflerinin görsel karşılıkları):
   - Kenar görünmemesi: stabilized oynatmada kare kenarında
     SİYAH bant/çirkin sınır GÖRÜNMEMELİ (plan §3.4 "kenar asla
     görünmez"). EVET/HAYIR.
   - Ani zıplama yok: oynatma boyunca tek bir karede
     görüntünün sıçraması/atlama yok (plan §4 sıçrama ölçütü
     görsel ayağı). EVET/HAYIR.
   - Pan korunması (yavaş_pan klibiyle): niyetli pan hareketi
     ezilip durmuyor — yumuşatma pan'i takip ediyor; duran
     kadraj gibi görünmüyor. EVET/HAYIR.
   - Güven rozeti metinleri §3.8'deki dizelerle AYNI mı
     ("İyi takip"/"Orta takip"/"Zayıf takip"/"Bu klip
     stabilize edilemiyor"; "Analiz ediliyor %x · yaklaşık
     y sn kaldı"). EVET/HAYIR (string eşleşmesi).
5. Ekran görüntüsü (sahip yapar; protokol komut önerir):
   her klibin stabilized oynatmasından 2 kare — analiz kartı
   açıkken 1, oynatma sırasında 1:

```
adb shell screencap -p /sdcard/s12-elde-sabit-preview.png && adb pull /sdcard/s12-elde-sabit-preview.png
```

KAYIT tablosu (Faz D):

| gözlem | elde_sabit | yavas_pan | yurumek |
|---|---|---|---|
| kenar görünmüyor | %E/H% | %E/H% | %E/H% |
| zıplama yok | %E/H% | %E/H% | %E/H% |
| pan korunur | — | %E/H% | — |
| rozet metinleri §3.8 eş | %E/H% | %E/H% | %E/H% |
| stabilized önizleme akıcı (export karşılaştırması ayrı adım; bu turda preview akıcılığı) | %E/H% | %E/H% | %E/H% |

### 4.1 Faz D'nin Senaryo 0 ayağı (bayraksız build'de koşulabilir)

Yalnızca şu bayrak-kapalı davranışı doğrula: stabilize
toggle'u AÇIK bile olsa (eski kayıtlı grade) oynatma
stabilizasyonsuz, hiçbir crash/log patlaması yok:

```
adb logcat -d | grep -ciE "FATAL|AndroidRuntime"
```

Ne görmelisin: `0`. (0 değilse tur kırmızı, hangi build'de
olduğu not edilir.)

---

## 5. Faz E — MP4/encoded durumu (bu turda ölçülemez — dürüst sınır)

`VID_20261110_004247.mp4` fikstürü bu turda YALNIZCA içe
aktarılıp AÇILABİLİRLİK gözlemi yapılır (encoded kaynak
preview). Stabilizasyon ölçülemez çünkü:

- karar 3 (sahip): encoded desteği v1 dışı, S6 paketi; Stabilize
  sekmesi encoded kliplerde GİZLİ.
- encoded EXPORT yolu §1 tespitine göre yok.

Kayıt (tek gözlem): MP4 açılıyor mu (E/H), önizleme oynuyor mu
(E/H), sekmenin gizli olduğu doğrulandı mı (E/H — gözlem).
BU BÖLÜM S6 sonrası genişletilir; S12 turunun "geçti"
kriterine GİRMEZ.

---

## 6. Kayıt ve karşılaştırma (ölçümlerin nereye işleneceği)

Sabah toplanan sayılar iki yere işlenir (gece ajanı hiçbirine yazmaz):

**(a) IMAGE_STAB_PLAN.md §4.1c benzeri bir alt bölüm** — plan
dosyasına sahip veya sonraki ajan ekler; bu protokol yalnız
şablon verir. Başlık ve tablo biçimi:
Şablon:

```
### 4.1c S12 cihaz kabul ölçümleri (CİHAZ: Xiaomi 15 Ultra, build %COMMIT%, tarih %TARIH%)
| klip | süre sn | analiz sn | oran | 1.5x iç | termal Δ°C | işçi duraklama | kenar | zıplama | pan | A7 flagged |
|---|---|---|---|---|---|---|---|---|---|---|
| elde_sabit | %..% | %..% | %..% | %..% | %..% | %..% | %..% | %..% | — | %..% |
| yurumek | ... | | | | | | | | | |
**A7 koşusu (fikstür 261110_004150)**: tookMs=%..% flagged=%..% worstMaxDiffLsb=%..% (%SAHIBIN_NOT%)
```

**(b) Eşik sabitleme sahibin** (plan karar 11): bu
turda toplanan sayılar sahip onayına gider; kimse dokümanda
eşik GÜNCELLEMEZ (sahip veya açıkça görevlendirilmiş ajan
yapar).

---

## 7. Başarısızlık durumları ve "S12 geçti" kriteri

| durum | sarı/kırmızı | not |
|---|---|---|
| analiz oranı >1.5x | KIRMIZI | plan kabul sınırı aşıldı; planın "iyileştirme adayı" maddesi (köşe tespiti) devreye girer; S12 geçemez |
| Termal Δ > %ESIK% veya termal status SEVERE | KIRMIZI (sahip eşiği) | nümerik eşik sahip kararı; SEVERE anında tur durur |
| İşçi oynatmada duraklamıyor | SARI | karar 9'a aykırı; UI/işçi davranış hatası; S4/S5'e madde, geçiş engellenir mi sahibin yorumu |
| Kenar görünüyor / zıplama var | KIRMIZI | planın temel görsel garantileri (§3.4/§4) |
| Pan eziliyor | SARI | yumuşatma kalibrasyonu (panSpeed 0.5 h/s hipotezi, §4.1) — eşiğe bağlı değil, sahibin gözü |
| A7 flagged>0 veya EXPORT_MISMATCH satırı | KIRMIZI | iki kapı farkı; ilk hamle: aynı koşuyu bir kez daha koş (tek seferlik olasılığı eleyin); tekrar flag'liyse GPU warp/grade bileşim hattı |
| A7 image vakaları hiç koşmadı (Senaryo E'de satır yok) | SARI | S4'ün A7 kısmı bitmemiş demektir; önkoşul eksik, Faz C eksik koşu — S12 eksik sayılır, §8 sorusuyla sun |
| FATAL crash sayısı >0 | KIRMIZI | hangi build'de, hangi adımda not |
| MP4/encoded gözlemleri | — | S12 kriterine GİRMEZ (§5) |

**"S12 geçti" işaretinin kriteri** (bayrak açısından):

1. Senaryo E tümüyle koşuldu (S4+S5 birleşik, bayraklı debug
   build), VE
2. Faz B analiz oranı ≤1.5x (tüm klipler), VE
3. Faz D görsellerin tümü EVET, VE
4. Faz C A7 flagged=0 (koşan tüm vakalarda), VE
5. FATAL 0.

Bunlar tutunca sahip: `FeatureFlags.IMAGE_STABILIZATION_ENABLED`
false → true derleme (kalıcı) + IMAGE_STAB_PLAN §5 S12 satırı
"geçti" (plan dosyasına sahip yazar). HİÇBİR koşul eksikse bayrak
AÇILMAZ (karar 10: "S5 + S12 cihaz kabulünden sonra true").
Bayrak MEKANİZMA olarak bu protokolde yer alır; gece ajanı
bayrağa DOKUNMAZ.

---

## 8. Doğrulanamayanlar + online araştırma ajanına sorular

Bu gece kod okuyarak saptanan, cihaz/çalışma gerektiren noktalar:

1. **Analiz işçisinin log etiketi YOK:** `ritim-stab-timing`
   yalnız render satırları yazar (jni_bridge.cpp:102-125,
   1255-1300, 4729-4745 doğrulandı); analiz işçisinin (decode /
   köşe / LK / fit) cihaz aşama sürelerini logcat'ten okumak
   mümkün DEĞİL. Şu anki tur duvar saatiyle yetinir; S4'e madde
   önerisi: analiz bitişinde TEK özet satır (etiket önerisi
   `ritim-stab-analysis`: `analyzed frames=%d ms=%d
   threads=%d`). Soru: Android'te stop-watch olarak
   `SystemClock.elapsedRealtime` + işçi thread'te trace bölümü
   (androidx.tracing) pratik mi?
2. **Termal headroom uygulama içinde okunmuyor**
   (`PowerManager.getThermalHeadroom` hiçbir yerde yok — grep).
   dumpsys tabanı kullanıyoruz; soru: getThermalHeadroom'un
   API 34+ cihazda (SDK 37'de) davranışı, önerilen okuma
   aralığı (10 s, histerezis) ve dumpsys battery termometresi
   ile ilişkisi.
3. **A7 image vakalarının varlığı:** `stab-image` /
   `grade-stab-image` plan §4'te S4 kapsamında; kod
   ExportAbSweep'de görülmüyor (main + S4 dalı). S4 bitince
   protokolün C.1 tablosu güncellenmelidir. Soru değil, dipped
   not — sabah sahibine "S4'ün A7 kısmı eksik" olarak sunulur.
4. **Klip sürelerinin kesin değerleri:** elde_sabit/yurumek/
   yavas_pan klibi süreleri (sn) bilinmiyor — masaüstünde
   s-measure ajanı §4.1b'ye yazacaktır; bu protokolde
   %KLIP_SURE_SN% placeholder'ları duruyor.
5. **Senaryo 0/ Senaryo E ayrımında S5'in UI bağımlılığı:** S5
   birleşmeden "Görüntü" segmenti/sekme ayağı yok; Faz D'nin
   tek koşulabilir hali S4+S5 birleşik build. Sabah hangi
   build'lerin hazır olduğu koordinatörden öğrenilmeli
   (gece S4/S5 ajanlarının dal birleştirme durumu).
6. **adb'i bypass etmeden oynatma sırasında sıcaklığı 10 sn'de
   bir okuma otomasyonu** (önerilen ama bu turda el yeter):
   `adb shell "while true; do dumpsys battery | grep temp; sleep 10; done"`
   — tur protokolüne yardımcı satır; sahibin keyfine bağlı.

---

## 9. Hızlı tur özeti (tek sayfa — sabah bastırılacak)

```
0) adb devices → telefon görülmeli; adb shell getprop ro.product.model (25010PN30G) + ro.build.version.sdk (37)
1) Build yükle: adb install -r app/build/outputs/apk/debug/app-debug.apk
2) logcat -c (temizle)
3) Termal taban: dumpsys battery | grep temp
4) Faz B (Senaryo E ise): elde_sabit AÇ → Analiz et → kronometre → bitir → oran tablosu
5) Faz B.2: yurumek ANALİZ + 60 sn oynat → temp farkı
6) Faz C: Ayarlar → "Export A/B (debug)" → logcat -d -s ritim-parity | grep export-ab → summary flagged=0?
7) Faz D: üç klip stabilize önizleme → gözlem tablosu (kenar/zıplama/pan/rozet) → screencap
8) Faz E: MP4 açılıyor mu (tek gözlem);
9) FATAL sayısı: logcat -d | grep -ciE "FATAL|AndroidRuntime" → 0
10) Tabloları doldur → IMAGE_STAB_PLAN §4.1c şablonuna (sahip/ajan ekler).
KABUL: §7 beş madde. Bayrak bu turdan sonra sahibin anahtarla açar.
```
