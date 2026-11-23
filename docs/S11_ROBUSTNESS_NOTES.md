# S11 dayanıklılık ön-tasarım — ölçek birikmesi (drift) ve kayıp çift kapanışı

Durum: ÖN-TASARIM NOTLARI (docs-only; uygulama kodu yok). Tarih: 2026-11-24 gece. Dal: `s11-drift-notes`.
Amaç: `docs/IMAGE_STAB_PLAN.md` §5 S11 satırının ("960 px 2. geçiş, çift modlu hareket ayrımı, drift (atlamalı çift)") somut tasarımı — sabah sahibi onaylarsa plana işlenir. Bu dosya TEK dosyadır; `IMAGE_STAB_PLAN.md` bilinçli olarak dokunulmadı (tek yazar disiplini).

Veri: 2026-11-24 gece ölçümünden (`s-measure-handheld` dalı, commit `1943f2e`/`4fb4efe`):
analiz dosyaları `/tmp/yurumek.ristab` (923 kare), `/tmp/yavas_pan.ristab` (280), `/tmp/elde_sabit.ristab` (251) — `image_stab_measure analyze` çıktıları; plan §4.1b tablolarının kaynağı. Bu notlardaki her sayı ya bu üç dosyadan ya da onlardan türetilen geçici simülasyondan gelir (simülasyon kodu bu gece yazıldı, koşuldu, silindi; tekrar üretmek için aynı ölçümler + `ImageStabPlan::build` yeterli).

---

## 0. Yönetici özeti (5 madde)

1. **Drift'in kökeni kayıp çift DEĞİL, ölçümün kendisi:** yürüyüşte Ok (güvenilir!) çiftlerin ölçülen `logS` ortalaması **+0.0122** (çift başına ~%1,2 sapma); kayıp çiftlerin kayıtta değeri yok (d=identity), katkıları sıfır. Birikme `exp(922·0.0122) ≈ 18007x` — planın ölçtüğü 22493x ile aynı mertebe (fark: medyan/dağılım). Köken: **yürüyüş parallax'ı** — ilerleyen kamera sahneyi akıtır; 4-DOF similarity uydurması derinlik gradyanını kısmi ölçek artışı olarak emer. Ölçek kanalı v1'de serbest olduğundan bu gerçek geometri üstel birikir.
2. **Pan ve elde-sabit kliplerde ölçek yanlılığı yok** (yavaş pan +0.0004, elde ≈ 0.000): drift yürüyüş tipi (kamera ötelemeli) hareketin spesifik sorunudur; dönmeli sallantıda ölçek kanalı zaten gerekmez.
3. **Bantlı kelepçeler (|logS| ≤ S) çare değil:** S=0.005 bandında bile birikim 28.7x (yanlılık bandın içinde); **ölçek kanalını tamamen kilitlemek** (logS=0) ise yürüyüşü kurtarır — counterfactual plan: Smooth'ta `limit% 100→59`, `meanA 0.15→0.70`, `zoomAv 1.108→1.066`.
4. **Lock modu (medyan hedef) kaymayı emiyor ama çalışmaz:** Lock hedefi yol medyanı olduğundan birikimle birlikte "yüzer"; zoom talebi düşer (1.108→1.022) ama `meanA=0` (bütçe hâlâ tükendi). Ölçek-kilitli counterfactual'da bile Lock `meanA=0.031` — Lock, parallax-drift'ine karşı Smooth'tan DAHA KÖTÜ (hedef sabitken yol dramatik değişiyor).
5. **960 px 2. geçiş drift'i çözmez** (parallax ölçümü çözünürlükten bağımsız geometri), ama Weak yoğunluğu için hâlâ değerli: yürüyüşte 122 Weak çiftin bir kısmı Ok'a yükselir. Drift çözümü ölçek politika + (ileride) parallax modeli; 960 geçişi çift kalitesi pozisyonu ayrı iş.

**S11 kapsam önerisi:** plan satırı "M-L" diyor; analizimiz bunu İKİYE bölünmesini öneriyor: **S11a ölçek politikası (M — drift'in asıl çözümü, küçük)** + **S11b 960 px tetikli geçiş (L — çift kalitesi, drift değil)**. Loop closure S11'den çıkarılır (madde 3: parallax kökeninde ters etki riski; ölçüm-gürültüsü kökenimiz yok). Drift'in ~%90'ı tek parametreyle karşılanıyor; ağır mimarilere gerek yok.

---

## 1. Kayıp çift araya değer yazma

**Mevcut:** `priorFromRecords` LK prior'unu son ≤2 kare önceden alır (ölçüm zincirine değil, aramaya); `ImageStabPlan.cpp` yol biriktirirken kayıp çiftte son iyi delta'yı ≤2 kare uygular, sonra `d = identity` (dur).

**Ölçüm (yurumek.ristab):** 20 kayıp koşusu: 12× tek, 2× çift, **6× ≥3 kare (en uzun 16 kare)**. Yani >2 kare kuralların ötesi 6 yerde tetikleniyor; uzun koşularda duruş (identity) yol köprüsünde bir "dondurulmuş" kesit yaratıyor — bu davranış *dürüst* (uydurma yok) ama köprü çaresiz köşe doğurur.

Seçenekler:

| Seçenek | Tanım | Artı | Eksi |
|---|---|---|---|
| (a) Sabit duruş (bugün) | >2 kare d=identity | Uydurma yok; deterministik | Uzun aralıkta keskin yol kesiti; zoom penceresi (±1 s) kenarında gereksiz bütçe |
| (b) Pencere parametresi | holdKare ∈ [0..4] kullanıcıya/klip sınıfına | Basit düzeltme | Parallax drift'ine etkisi ~0 (köken ölçüm, madde 4) |
| (c) Döndürme kanalı korunur, öteleme dondurulur | Kayıpta θ ve logS son değer, tx/ty=0 | Roll sürekliliği korunur | logS korumak drift'i büyütür — **yasaklamalı** |
| (d) Gyro varsa gyro prior | gcsv varsa kayıp çiftin delta'sı gyro'dan entegre | Gerçek fiziksel tahmin; senkron gerekir (gyrocheck: ±25 ms sonrası 0.6–5 px çift düzeyde) | Sadece MCRAW+gcsv; şema/analiz bağımlılığı büyür |

**Öneri:** (a) ana yol kalır; (b) `holdKare` sabiti 2'den 3'e çekilir ve parametreye bağlanır (etkisi küçük, maliyeti sıfır); (c) reddedilir (logS'yi korumak drift'i büyütür); (d) **S11d opsiyonel v1.5 maddesi**: gyro analize eşlik ediyorsa kayıp çiftin gyro delta'sı kayda yazılır (`status = Lost` + `gyroPrior` bayrağı), plan bunu tutarlı biçimde kullanır — v1 dışı, plan API'yi genişletmez (madde 7'deki odak kısıtı nedeniyle yalnız θ kanalı).

**Doğrulanabilirlik:** yurumek'te holdKare 2/3/4 için plan `hardFallbackFrames`, `limit%`, ivme oranı farkı (beklenen: <2% fark).

## 2. Göreli hareket modeli (birikim yerine pencere-içi birikim)

**Mevcut:** `P_i = Δ_i ∘ P_{i-1}`, referans = kaynak kare 0 (§3.3); plan tüm yolda çalışır, aralık yalnız Lock hedefini etkiler (§3.6, aralık bağımsızlığı testi var).

**Bağlantı ama köken değil:** plan *hedef-farkı* düzeltir (`corr = Tc ∘ P⁻¹`); Smooth'un hedefi de aynı yoldan türediğinden kaymış ölçek T ve P'de MÜŞTEREK görünür → hedef-farkında büyük ölçüde iptal olur. Ama yumuşatma penceresi (±1 s) içinde exp-birikim doğrusal değildir: pencere içinde fark küçük kalır, klip boyunca kökten büyür. Ölçek birikimi Smooth'ta "yavaş zehir" işi görür: kısa sürelerde sinsice az, uçta kökten. Mimariyi değiştirmek çağrışsa da asıl sorun dört kanaldan biri olan ölçektir — öteleme birikimi hedef-farkıyla zaten dengeleniyor; ölçek dengelenemiyor, çünkü hedef (yumuşatılmış yol) aynı üstel büyümeden pay alır ve pencere sınırlarında fark süreksiz biçimde büyür.

Seçenekler:

| Seçenek | Tanım | Artı | Eksi |
|---|---|---|---|
| (a) Bugün: mutlak yol, kare-0 referans | Tam kayıt; basit | Kayıt/aralık sözleşmesi sağlam | Ölçekte üstel drift (22k x) |
| (b) Kayan anchor'lı görece birikim | Yol her ~2 s'de yeni referansa sıfırlanır; düzeltme anchor-içi fark | Üstel → parça-parça lineer | Anchor sıçramaları warp'ta süreksizlik üretir; §3.6 aralık-bağımsızlık sözleşmesini BOZAR (preview/export/timeline farklı aralıklarda farklı matris); büyük mimari değişiklik |
| (c) Ölçek birikimden çıkarılır (madde 4'e bağlanır) | logS/θ zaten Lie-cebri temsiliyle birikiyor (`simCompose`: loglar toplam, açı toplanır); kök önlem ölçek kanalını politikayla kapatmak | Tek alanlık değişiklik; counterfactual'da ölçülü kazanç (madde 4 tablosu) | Gerçek dolly-zoom içeriği yumuşatılır (v1 kabulü; parallax modeli S10'dan sonra) |

**Öneri:** (b) REDDİEDİLİR (süreksizlik + sözleşme bozumu, kazanç küçük); (c) BENİMSENİR → madde 4'ün tasarımıdır. Yol birikimi mimarisi ve kayıt formatı DOKUNULMAZ.

## 3. Loop closure (klip içinde uzak çift eşleme)

Klip içinde güvenilir (i, j) çiftleri (uzak zaman) eşleşip ikinci geçişte yolun bağıl hatasını düzeltmesi (MeshFlow/gyroflow "pose graph" hafifletilmiş hali).

**Sorun maliyet/karmaşıklık:** analiz sırasında özellik saklamak = kayıt formatı büyür (32 B/kare bugün; özellik haritası ~10-50 KB/kare eğilimi) VEYA ikinci analiz geçişi (maliyet 0.37x → ~0.75x). Eşleşme araması (yakın komşular, klip boyutu ~1000) ucuz; asıl maliyet özellik saklama.

**yurumek'e getireceği:** parallax gerçek olduğundan uzak çiftler GERÇEKTEN farklı ölçekte görünür — loop closure "düzeltmesi" burada YANLIŞ iş yapar: fiziksel dolly-zoom ölçeğini "hata" sanıp geri iter; sonuç görüntüde wobble. **Parallax-drift için loop closure ters etki yapar**; o yalnız ölçüm gürültüsü kaynaklı drift'te anlamlı (bizim kökenimiz ölçüm önyargısı — parallax modeli gerektirir, S10).

**Öneri:** S11 kapsamından ÇIKARILSIN; yerine "parallax işareti" maddesi: ok çift logS medyanının |0.003| üstü + monotonik işaret KLİP PARALLAX bayrağı (analiz özeti; UI'a "bu klipte kamera ilerliyor" bilgisi). v2'de parallax modeli (özellik.tabanlı derinlik) ayrı paket.

**Doğrulanabilirlik:** parallax bayrağı üç klip üzerinde: yurumek ✓ (+0.0122), yavas_pan ✗ (+0.0004), elde_sabit ✗ (−0.000005) — temiz ayrım.

## 4. Ölçek kanalı kilidi / güven eşikleri

**Ölçüm:** yurumek Ok çiftlerinde logS ort **+0.0122**, medyan +0.0117, |maks| 0.072 — sistemli-gerçek parallax. elde_sabit ±0.0007 gürültü tabanı; yavaş pan +0.0004 (+maks 0.015 tek sıçrama).

**Politika seçenekleri:**

| Politika | yurumek counterfactual | Risk |
|---|---|---|
| (a) Kilidi yok (bugün) | 18007x birikim; plan %100 limit | — |
| (b) Bant S=0.01 | 492x — yetersiz | yanlılık bandın içinde |
| (c) Bant S=0.005 | 28.7x — yetersiz | aynı |
| (d) Bant S=0.002 | 3.95x — sınırda | Ok çiftlerin yarısını kırpıyor |
| (e) **Tam kilit (logS=0)** | **1.00x**; plan limit% **59**, meanA **0.70** | Zoom yoksa optik yakınlaştırma kaybı (gerçek dolly-zoom hissettirir) |

**Öneri (v1, S11-çekirdek):** analiz sırasında kayıtta ölçek kanalı AYNEN kalır (kayıt kaybolmaz); **plan parametresi `scaleLock: Int`**:
- `0 = free` (bugünkü davranış; elde/pan klipler için doğru);
- `1 = lock` (logS=0 zorla; parallax klip için);
- `2 = trust` (yalnız |logS|<0.002 çiftler logS taşır, gerisi 0 — karışık).

Otomatik seçim önerisi: analiz bitince `clipParallax = |median(Ok logS)| > 0.003` → plan default free yerine lock önerir; UI "Kamera ilerlemeli (parallax): ölçek kilidi açık" satırı + kullanıcı override. Smooth + lock kombinasyonu counterfactual'da yürüyüş planını ÇALIŞTIRIR (limit% 59); ivme oranı ölçümü S11 uygulanınca hedeflenen <0.5 makul görünür (doğrudan ölçüm gerekiyor — kabul testi maddesi, aşağıda).

**Doğrulanabilirlik:** üç klip × (free, lock) plan metrikleri; parallax bayrağı klip başına doğru.

## 5. 960 px ikinci geçiş

**Tetik:** analiz istatistiği `weakFraction > 0.10 && okFraction < 0.9` (yurumek: 122/922 = %13.2 Weak, %79.3 Ok → TETİKLENİR; elde/pan: %0 → tetiklenmez).

**Maliyet (masaüstü piramit ölçümünden ekstrapolasyon):** bençh sayıları (§4.1) 640×360 piramit ~0.68 ms/çift; 960×540 piramit alanda 2.25x, ~1.5 ms/çift; köşe ~3.2→~7 ms; LK ~2.2→~5 ms (nokta sayısı aynı); decode+luma boyutsuz sabit (CFA'dan küçültme 640'ta zaten ~6x, 960'ta ~4x — biraz pahalı, ~2.9→3.5 ms). Toplam ~13.9 → ~22 ms/kare ≈ **1.6x** analizi, yani **0.37x → ~0.6x** gerçek zaman (hedef 1.0x içinde kalır). Sadece tetiklenen klipte ödenir.

**Ne getirir:** Weak çiftlerin çoğu Ok'a yükselir (kontrast çözünürlüğü); lostRun'lar kısalır. **Drift'e etkisi:** parallax geometrisi çözünürlükten sayısal bağımsız → Ok logS önyargısı azalmaz. yürüyüşte limit% %100→59'un motoru ölçek-kilit, 960 değil.

**Öneri:** S11'de kalır (bağımsız değer: çift kalitesi), tetik eşiği yukarıdaki, tekrar analiz çözünürlük anahtarını `kImageStabAnalysisVersion` sürümüne bağlar (eski analiz geçersiz).

## 6. Lock modu medyan-direnci

**Ölçüm (yurumek, counterfactual dahil):**

| Konfig | zoomAv | zoomMx | meanA | limit% |
|---|---|---|---|---|
| Smooth + free ölçek (bugün) | 1.108 | 1.148 | 0.149 | 100 |
| **Lock + free ölçek** | **1.022** | **1.099** | **0.000** | 100 |
| Smooth + ölçek-kilit | 1.066 | 1.129 | 0.697 | 59.2 |
| **Lock + ölçek-kilit** | 1.131 | 1.150 | 0.031 | 100 |

**Analiz:** Lock'un hedefi yol medyanı olduğundan birikim hedefe de işler: medyan ölçek değeri "dev"dir, aralığın sonuna doğru yol medyandan kat kat yukarıdadır; düzeltme (hedef−yol farkı) bu yüzden Lock'ta da büyüktür ve bütçe yine tükenir (meanA = 0). Lock hedefe kısmen "yüzdüğü" için zoom TALEBİ düşer (1.108→1.022) — bu bir direnç işareti ama hiçbir kareye tam düzeltme uygulayamadığı için işlevsel kurtarma değildir; ölçek-kilitli counterfactual'da bile Lock meanA = 0.031 (Smooth 0.697). **Lock, parallax-drift'ine karşı yapısal olarak Smooth'tan daha dirençsizdir:** medyan tek bir nokta hedefi iken Smooth hedefi yolun hız-uyarlamalı yumuşatmasıdır ve kayan ölçeği pencere içinde kısmen izler. Öneri: parallax bayrağı açıkken UI Lock yerine Smooth ÖNERİR ("Bu klipte Kilit yerine Yumuşak önerilir"); Lock'a kod dokunulmaz, yalnız öneri mantığı eklenir.

## 7. Gyro-prior beslemenin drift'e katkısı (odak bulgusuyla bağlantı)

Gyrocheck (§4.1b.5): x kanalı korelasyon 0.97 ama slope 1.22 (odak ~%22 varsayım hatası), tarama δ −65/−85/−90 ms. **Kayıp çiftleri gyro öngörüsüyle doldurmak (madde 1d) drift'i azaltır mı?** Gyro delta'sı ölçüm gürültüsünden bağımsız, küçük ve önyargısız olduğundan kayıp aralıklarında öteleme kanalına EKLENEN birikim üzerinde net olumlu etki yapar. ANCAK: gyro delta'sı yalnız ROTASYON modeli çıkarır (parallax ötelemesini içermez) → ölçek kanalına gyro'dan değer katmaz; drift'in asıl kökeni olan ölçek önyargısına etkisi NÖTR. θ (roll) kanalına küçük iyileştirme sağlar (69 kayıp çiftin roll sürekliliği). **Slope 1.22 uyarısı:** gyro prior tx/ty'ye de uygulanırsa, odak varsayım hatası öteleme öngörüsüne ~%22 sistematik ölçek yanlılığı taşır — bu, kayıp-aralık doldurmasında ölçümden DAHA yanlı değer enjekte eder. **Öneri:** S11d gyro prior yalnız θ (roll) kanalına uygulanır; tx/ty gyro beslemesi odak kalibre edilene kadar kapalı tutulur (S10 girişi; §8 soru 11 bulgusunun takibi).

## 8. Kabul testleri (S11 sonrası yurumek üzerinde beklenen sayılar — sahibin onayına)

| Ölçüm | Bugün (free) | Beklenen (scaleLock=1 + 960 ikinci geçiş) |
|---|---|---|
| limit% | 100 | ≤ 65 (yalnız ölçek-kilit counterfactual'ı %59) |
| meanA | 0.149 | ≥ 0.6 |
| ivme oranı | 1.000 (düzeltme yok) | **< 0.5** (hedef; ölçülecek) |
| jerk oranı | 1.000 | < 0.15 |
| zoom ort/maks | 1.108 / 1.148 | ≤ 1.10 / ≤ 1.15 (bütçeye sığarsa) |
| invalidSampleFrames | 0 | 0 (korunmalı) |
| analiz faktörü | 0.37x | ≤ 0.7x (960 geçiş tetiklenirse ~1.6x tek analiz) |
| Ok oranı (960 sonrası) | %79.3 | ≥ %90 (tek geçişin ölçüm sonrası) |
| hardFallbackFrames | 0 | 0 |

Bu tablo S11'in kabul kriteri önerisidir (her sayı öneri, sahip onayı bekliyor).

## 9. S11 kapsam önerisinin revizyonu (plan §5 satırına işlenmek üzere)

Plan satırı bugün: "960 px 2. geçiş, çift modlu hareket ayrımı, drift (atlamalı çift)" — boyut M-L.

Önerilen yeni kırılım:

1. **S11a (M): ölçek kanalı politikası** — `scaleLock` parametresi (0/1/2), parallax bayrağı (analiz özeti: `|median(Ok logS)| > 0.003`), UI önerisi Lock yerine Smooth. Kod: `ImageStabPlan.cpp` + analiz özeti birkaç bayt + plan API tek alan. Testler: counterfactual yurumek sayıları (bu notun 4/6 maddeleri) + üç klip regresyonu.
2. **S11b (L): 960 px tetikli 2. geçiş** — tetik eşikleri, çözünürlük anahtarı `analysisVersion+1`, maliyet ~1.6x tek geçiş (~0.6x klip süresi toplam).
3. **S11c (v1 DIŞI, ertelemeli): çift modlu hareket ayrımı** — zaten plan; parallax modeli S10'dan sonra değerlendirilir.
4. **S11d (v1.5 opsiyon, gyro'lu klipler): kayıp çift gyro prior (yalnız θ)** — madde 1d/7; odak kalibrasyonundan ÖNCE yalnız roll kanalı.
5. **Loop closure: S11'den ÇIKARILDI** (bu notun 3. maddesi: parallax'ta ters etki riski; ölçüm-gürültüsü drift kökenimiz yok).

---

## Sonuç

Yürüyüş kliplerinin kullanılabilirliği S11'in tek parametrelik en küçük parçasıyla (ölçek kilidi + parallax bayrağı) plan counterfactual'ında ölçülebilir biçimde düzeliyor (%100→%59 limit, meanA 0.15→0.70). Loop closure ve göreli-model gibi ağır mimariler bu kökende hem pahalı hem ters etki riski taşıyor; 960 geçişi çift kalitesi işi olarak ayrılır, drift işi olarak değil. Sahibin onayına: S11a/S11b kırılımı + kabul tablosu (madde 8).
