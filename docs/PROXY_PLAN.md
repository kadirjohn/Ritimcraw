# F2 — arka plan proxy: tasarım notu (onay bekliyor)

Durum: tasarım, 2026-11-25. Kod yok. Sahip onayı olmadan uygulanmaz. İlgili: `docs/ENCODED_GPU_DECODE_PLAN.md` §6 (iki yol, proxy ayrı iş).
Okunan referans: Klipp `engine/Proxies.kt` (imza, kuyruk, `.tmp` -> rename, export'ta durdur), `engine/Headroom.kt` (süper-beyaz/siyah).
Fark: Klipp proxy'yi Media3 Transformer ile üretir; **bizde Media3 yok**, yazıcı `MediaCodec` tabanlı olacak (§3.2).
Bu dosyadaki hiçbir sayı ölçülmüş değildir; "ölçülecek" diyen yerde yöntem yazılıdır.

## 1. Hedef / hedef-dışı
| Hedef | Hedef-dışı |
|---|---|
| 8K ve 4K60 encoded klipler ile MCRAW için preview / scrub / seek hızı | Proxy'den export (export **her zaman orijinalden**) |
| Seek ucuz: 1 sn anahtar kare aralığı | Proxy'yi kullanıcıya medya olarak sunmak / paylaşmak |
| Arka planda, export ve oynatmayı bozmadan, telefonu ısıtmadan | Ses (ses orijinalden çalar, proxy'de ses yok) |
| **Yarım proxy asla oynatılmaz** | Küçük klipler için otomatik proxy (varsayılan kapalı, §6) |
| Renk etiketi doğru (`EncodedColorSpec` dürüst) | HDR (HLG/PQ) için yeni renk işleme (bugünkü yaklaşım aynen) |

## 2. Ne proxy'lenir
### 2.1 Encoded 4K60 / 8K -> 1080p HEVC
| Alan | Öneri | Not |
|---|---|---|
| Çözünürlük | kısa kenar 1080 (ayar: 720/1080/1440) | küçültme kutu-ortalama; mevcut `EncodedYuvPassParams.reduce` geçişi yeniden kullanılır (`ENCODED_GPU_DECODE_PLAN` §8) |
| Codec | HEVC (`video/hevc`); 10-bit kaynakta Main10, 8-bit kaynakta Main | H.264 geri dönüşü yalnız HEVC encoder yoksa; hangisi kullanıldı imzaya yazılır |
| Anahtar kare | **1 sn** (`KEY_I_FRAME_INTERVAL = 1`) | Klipp 0,5 sn; biz sahip kararı 1 sn. Seek maliyeti ölçülecek (§7) |
| Bitrate | VBR, başlangıç önerisi 1080p'de ~20 Mbit/s (HEVC); kesin değer **ölçülerek** seçilir | `BitrateTable` / `EncoderFacts` / `DeviceEncoderFacts` mevcut tabloları kullanılır; sayı burada karar değil |
| B-kare | kapalı | seek/decode gecikmesi, sıralı PTS |
| Ses | yok | orijinalden |
| Fps | kaynağın gerçek fps'i, PTS'ler orijinalle birebir (`PtsTimeline`) | proxy ile orijinal aynı kare indeksi = aynı PTS; aksi halde grade/keyframe eşleşmez |

**10-bit ve renk etiketi (doğruluk kuralı).** Proxy, kaynağın `EncodedColorSpec`'ini **değiştirmeden** taşır: aynı matrix (BT.2020 NCL),
aynı range (Mi-Log klibi: full), aynı transfer (Mi-Log: etiketsiz, kodda "untagged -> MiLog"), aynı primaries. Yazıcı bu alanları
`KEY_COLOR_STANDARD/RANGE/TRANSFER` ile **kaynağın okunan değerleriyle** yazar (Mi-Log'da transfer etiketsiz kalır; biz bir etiket uydurmayız).
Böylece proxy açılınca `EncodedColorSpec.fromMediaFormat` kaynakla **aynı** spec'i üretir; proxy için ayrı bir renk kuralı yoktur.
Sidecar'daki imza ayrıca `colorSpecKey` (matrix/range/transfer/primaries/bit) saklar; açılışta proxy'den okunan spec ile eşleşmezse proxy **geçersiz** sayılır
(sessizce yanlış renk oynatmaktansa proxy'siz oynat).
10-bit kaynak için P010 yolu (cihaz probu: P010 ImageReader çalışıyor, YUV_420_888 10-bit'te görüntü vermiyor) kullanılır; 8-bit kaynak 8-bit kalır
(8->10 yükseltme yapılmaz, etiket gerçeği bozulmasın). Açık soru: 8-bit kaynakta küçültme sonrası banding için 10-bit proxy gerekir mi (§7).

**Süper-beyaz / süper-siyah (Headroom karşılığı).** Klipp bunu kaynak değerlerini afin olarak `[LO,HI]` aralığına sıkıştırıp ters çevirerek
yapar (`.h.mp4` soneki + decode efekti). Bizde daha basit: proxy **kodları kaynakla aynı range/matrix ile, yeniden ölçeklemeden** yazar (kutu-ortalama
yalnız uzamsal), yani limited-range kaynakta 1..254 / 64..940 dışındaki kodlar encoder'a zaten girer; decode tarafı `EncodedColorSpec`'ten aynı
formülle çözer, ters efekt gerekmez. Garanti edilecek şey: yazıcı girişinde kod kırpması (clamp) yok. Bu **doğrulanacak** (§7 aşama P1: kaynakta
sınır dışı kod içeren bir kare ile proxy decode'u karşılaştırılır, birim: LSB, yön: küçük iyi). Encoder kendisi limited-range'de dışarı kodu
koruyorsa yeterli, korumuyorsa Klipp'in afin yöntemi (imzaya `h` bayrağı) geri dönüş seçeneğidir. Mi-Log klibi full-range olduğundan ana
vaka bu değil; asıl risk 8-bit limited H.264/HEVC kamera klipleri.

### 2.2 MCRAW "develop proxy"
Repo gerçeği: MCRAW kare başına CFA'dan geliştirilir (GPU pass-1 CFA -> RGBA32F, sonra node/tail pass'leri); kaynak çözümü + develop pahalı olan kısımdır.
Karar: **nötr develop proxy** (klibin güncel grade'i **pişirilmez**).
- Nötr = demosaic + kameranın WB'si + kaynak profilinin (`SourceProfile`) çalışma uzayına dönüşümü; poz/ton/LUT/node yok. Sabit, **bizim sahip olduğumuz**
  bir log benzeri proxy transferiyle 10-bit HEVC Main10'a yazılır (tek transfer, imzada versiyonlu); oynatmada ters transferle lineer çalışma uzayına döner.
- Neden nötr: grade değişince proxy yeniden üretilmez (dakikalar sürer, §3 imza grade içermez); aynı proxy tüm grade'lerde geçerli.
  Pişirilmiş proxy her slider hareketinde geçersiz olurdu ve "yarım proxy asla oynatılmaz" kuralıyla birleşince proxy neredeyse hiç kullanılmazdı.
- **Grade düzenlemelerinin sonucu (açıkça):** proxy üzerinde yalnız develop-sonrası aşamalar çalışır (poz, kontrast, ton eğrisi, LUT, v2 node zinciri, birincil).
  CFA'ya bağlı aşamalar proxy'de **tam aynı değildir**: ham WB çarpanı (proxy'de nötr WB'den göreli yaklaşım), highlight recovery, demosaic keskinliği,
  CFA-öncesi gürültü giderme. Proxy preview'i bu yüzden bir **yaklaşımdır**; duraklatınca tam çözünürlük orijinalden "settle" ile doğru kare gelir (§5).
  Proxy'de 10-bit + log kodlama kayıp getirir: proxy-önizleme ile tam develop farkı **raporlanır, eşik konmaz** (§7 P3).
- Aksi seçenek (grade pişirme, "preview-cache") bu planın kapsamı dışında; sahibe soru (§7).

## 3. Önbellek
### 3.1 İmza ve yerleşim
İmza (proxy dosyasının geçerliliği) şu alanların birleşimidir; biri değişirse proxy eskidir:
| Alan | Kaynak |
|---|---|
| Kaynak parmak izi | `SourceRepository.fingerprintOf` (baştan N bayt SHA-256 + boyut): aynı bayt = aynı proxy, URI değişse de |
| Kaynak değişim koruması | boyut + son değişiklik zamanı, açılışta tekrar bakılır |
| Ayarlar | kısa kenar, codec, bit derinliği, bitrate sınıfı, anahtar kare aralığı |
| Renk | `colorSpecKey` (§2.1) |
| Tür | `encoded` veya `mcraw-neutral` (+ proxy transfer sürümü) |
| Encoder sürümü | sabit `PROXY_ENCODER_VERSION` (yazıcı/küçültme/transfer değişince artırılır) |

Yerleşim: `filesDir/cache/proxies/<fingerprint>-<sigHash>.mp4` + yanında `<aynı ad>.json` (imza alanlarının tamamı, boyut, süre, kare sayısı, oluşturma zamanı,
son kullanım zamanı). `filesDir`, `cacheDir` değil: OS temizlemesin (`ImageStabCache` ile aynı gerekçe ve düzen: `filesDir/cache/imagestab`).
`ImageStabCache` kalıbı (saf JVM, dosya tabanlı, `fileFor/isComplete/targetPath`) kopyalanır; ayrı `ProxyCache` sınıfı.

### 3.2 Yazıcı
`MediaCodecMp4Writer` bugün yalnız H.264 + AAC (`Mp4MediaWriter` arayüzü; `ExportPlan` mime/profile/keyframe alanlarını zaten taşır; HEVC modeli
`export/hevc/HevcPreflight`, `HevcProfile` içinde ve **henüz bağlı değil**). Proxy için yeni ince bir `ProxyMp4Writer` (aynı MediaCodec + MediaMuxer
kuralları: track'ler `INFO_OUTPUT_FORMAT_CHANGED`'den eklenir, muxer hepsi eklenince başlar, EGL Surface girişi `requestEncoderSurfaceInput` sırası) ya da
`MediaCodecMp4Writer`'a HEVC + "sesiz" ayarı. Karar uygulamada; tercih: mevcut writer'ı HEVC'ye genişletmek (E0b-3/10-bit export ile ortak iş, tekrar yok).
Girdi: encoded için decode -> küçültme geçişi -> encoder Surface; MCRAW için develop (nötr) -> proxy transferi -> encoder Surface.

### 3.3 Atomik yazma, süpürme, asla-yarım
- Yazım yalnız `<ad>.mp4.tmp` + `<ad>.json.tmp`'ye. Bitince sırayla: muxer `stop`/`release`, dosyayı **yeniden aç ve doğrula** (süre ≈ kaynak süresi, kare sayısı = beklenen,
  ilk ve son kare decode edilir), sonra `.mp4.tmp` -> `.mp4` rename, **en son** JSON rename. Okuyucu yalnız JSON'u olan proxy'yi tanır; JSON yoksa dosya yoktur.
- Açılışta süpürme: tüm `*.tmp` ve JSON'suz `.mp4` silinir (çökme/öldürülme artığı). Süpürme proxy yazan iş başlamadan önce, kuyruktan bağımsız çalışır.
- Asla-yarım kuralı kodda tek kapı: `ProxyCache.playable(fingerprint, sig)`; oynatma yalnız bundan döner. Tamamlanana kadar eski imzalı proxy (varsa) çalar, yoksa orijinal.
- İptal: iş iptal edilirse `.tmp` silinir. **Devam (resume) yok** (HEVC muxer'ı yarım dosyadan güvenilir devam etmez); iş baştan kuyruğa döner. MCRAW için ileride
  kare aralığı checkpoint düşünülebilir, ilk sürümde değil. (`ImageStabCache`'in `.part` resume'u analiz içindir, kodek çıktısı için kopyalanmaz.)

## 4. Arka plan kuyruğu
- **Tek iş aynı anda**; FIFO, kullanıcının şu an açtığı klip öne alınır.
- Çalışma ortamı: export'un bugünkü mekanizması `ExportForegroundService` (`dataSync` türü, düşük önemli bildirim, `ExportForeground` arayüzü; süreç öncelik
  kaybı bulgusu 2026-11-14). WorkManager repoda **kullanılmıyor**. Öneri: aynı foreground service desteğini genelleyip proxy işi için de kullanmak (aynı bildirim
  kanalı, "proxy üretiliyor n/N"); WorkManager eklemek yeni bağımlılık ve kısıt (Wi-Fi/şarj) için gerekirse sonra. Karar sahibe (§7).
- Öncelik: **aktif export > oynatma > proxy**. Export başlarsa çalışan proxy işi iptal edilir ve kuyruğun başına döner (Klipp `holdForExport` kalıbı), `.tmp` silinir;
  export bitince devam. Oynatma sırasında yeni iş başlatılmaz (`ImageStabActivity.playing` bayrağı mevcut).
- Isı/pil: `PowerManager.getThermalHeadroom` (zaten `AndroidImageStabSignals`'ta, 10 sn tahmin; NaN = itiraz yok) ve `ImageStabSignals` arayüzü yeniden kullanılır.
  Kural önerisi: headroom eşiği aşılınca iş **duraklatılır/iptal edilir** ve soğuyunca yeniden başlar; düşük pil (ör. `ACTION_BATTERY_LOW`) ve güç tasarrufu modunda başlamaz.
  **Eşik değerleri ölçülmeden yazılmaz**: cihazda proxy üretirken `getThermalHeadroom` ve yüzey sıcaklığı kaydedilip seçilir (§7 P2). Sahibin telefonu ısınıyor; varsayılan
  muhafazakâr olacak.
- Hata: bir klip iki kez başarısız olursa o oturumda tekrar denenmez, sebep export raporundaki gibi dürüst yazılır (hangi yol, hangi encoder). HEVC yoksa H.264 geri dönüşü imzaya yazılır.

## 5. Oynatma entegrasyonu
| Nokta | Bugün | Proxy ile |
|---|---|---|
| Encoded kaynak | `EncodedSource` (extractor + `MediaCodecFrameDecoder`), `EncodedPreviewSession` | oturum açılırken `ProxyCache.playable` varsa `EncodedSource` **proxy dosyasından** açılır; `EncodedColorSpec` aynı (§2.1). Export yolu (`EncodedMp4Session`) hiçbir koşulda proxy görmez |
| MCRAW | `NativeSource` + GPU develop (`GpuPreviewSession`) | proxy açıksa pass-1 yerine proxy kare -> ters transfer -> `developTex`; CFA aşamaları atlanır (§2.2). Yoksa bugünkü yol |
| Seçim | | tek karar noktası `EditorPreviewSession` (`PreviewSessions` fabrikası): kaynak + imza -> {orijinal, proxy}; hangi yolun kullanıldığı `PlaybackTrace`'e ve raporlara yazılır |
| Grade değişimi | slider -> yeniden render | encoded: proxy zaten grade-sonrası; her grade proxy üstünde çalışır, proxy yeniden üretilmez. MCRAW: nötr proxy, grade-sonrası aşamalar geçerli; CFA'ya bağlı kontroller sürülürken proxy'den yaklaşık, bırakınca settle |
| Duraklat / settle | `SETTLE` sınıfı tam çözünürlük takip kare | duraklatınca ve grade settle'da **orijinalden** tam çözünürlük kare (mevcut `SETTLE` + `SOURCE_MAX_DIM` takibi); proxy yalnız `PLAYBACK`/`DRAG` sınıfları için |
| Geçiş | | proxy tamamlanınca oturum **kare sınırında** değil, bir sonraki seek/play'de değiştirilir (çalarken kaynak değiştirip yeşil glitch riski almayız; Klipp'in dersi) |
| Zaman | | proxy PTS'leri orijinalle aynı, kare indeksi eşleşir; seek orijinal zaman çizelgesi (`PtsTimeline`) üzerinden |

## 6. Ayarlar / Depolama sayfası
- Klip başına boyut, toplam, "bunu sil" / "hepsini sil", üretiliyor/kuyrukta durumu (Klipp `statusOf` gibi dürüst metin).
- **Üst sınır** (varsayılan öneri: cihazdaki boş alanın bir yüzdesi; kesin sayı sahip kararı) ve **LRU tahliye**: son kullanım zamanı JSON'dan; tahliye yalnız o an
  oynatılmayan ve üretilmeyen proxy'leri siler; sınırdan büyük tek proxy üretilmez (rapora yazılır).
- Varsayılanlar (öneri, sahibe soru): otomatik üretim **açık**, yalnız eşiği aşan klipler için (kısa kenar > hedef, ya da fps > 50 ve > 720p, ya da MCRAW);
  küçük/hafif klipler için **kapalı**; "yalnız Wi-Fi/şarjda" encoded için anlamsız (yerel iş), "yalnız şarjda" seçeneği sunulur, varsayılan kapalı; ısı kuralı her zaman açık.
- Ayar değişince (çözünürlük, kalite) yeni imzalı proxy arka planda üretilir, eskisi yenisi bitene kadar kullanılır (Klipp ile aynı).

## 7. Riskler, açık sorular, aşamalar
**Riskler:** (1) HEVC encoder / Main10 cihazda varsa da sürekli yük altında ısınma ve throttling; (2) MCRAW proxy'sinde proxy transferinin dar dinamik aralıkta bant/kırpma
yapması; (3) Surface girişli encoder'a P010/10-bit RGB yazma cihazda **doğrulanmadı** (`tenBitContext` kuralı ve HEVC profil modeli bağlı değil); (4) 8K decode + küçültme + encode
aynı anda GPU/kodek paylaşımı (Klipp'te proxy yanında develop'ın decode'unu yavaşlattığı bulgusu: bizde de `exporting`/`playing` kapıları şart); (5) proxy ile tam çözünürlük arasında
renk/keskinlik farkı kullanıcıya "settle'da kayma" gibi görünebilir.

**Sahibe sorular:**
1. MCRAW proxy nötr mü (öneri) yoksa grade pişirilmiş "preview cache" mi?
2. Foreground service'i genelleyelim mi, WorkManager (Wi-Fi/şarj kısıtları için) ister misin?
3. Cap varsayılanı ve otomatik üretim varsayılanı (§6).
4. 8-bit kaynak için 8-bit proxy yeterli mi, yoksa banding'e karşı 10-bit mi?
5. Bitrate ve ısı eşikleri cihazda ölçülsün (aşağıdaki aşamalarda), kabul?

**Aşamalar** (her biri ayrı merge; ölçümler cihazda, sahip klipleriyle: Mi-Log 4K 10-bit 60p `VID_20261124_175141.mp4`, 8K 30p `VID_20261124_175209_8K.mp4`, bir MCRAW; rapor: yön, birim, kaynak yazılır):
| Aşama | İçerik | Kabul ölçümü |
|---|---|---|
| P0 | `ProxyCache` (imza, yerleşim, `.tmp`->rename, süpürme, `playable`) saf JVM + testler | JVM testleri: yarım dosya hiçbir koşulda `playable` olmaz (çökme simülasyonu: rename öncesi/sonrası), imza alanı değişince geçersiz; birim: test sayısı, kaynak: gradle test xml |
| P1 | Encoded proxy yazıcı (HEVC, 1 sn GOP, renk etiketleri, sınır-dışı kod koruma) + doğrulama | (a) proxy `EncodedColorSpec` == kaynak spec (JVM + cihaz); (b) sınır-dışı kod karesi: proxy decode vs kaynak decode (aynı küçültme kuralıyla CPU referans), birim LSB, yön: küçük iyi, eşik `GradeParitySweep` ile aynı ve gevşetilmez; (c) üretim süresi / klip süresi, birim: "gerçek zamanın N katı hızlı/yavaş", kaynak: iş logu |
| P2 | Kuyruk + foreground + ısı/pil/export önceliği | export başlayınca proxy iş gecikmesi (ms, iptal-başlangıç logu); üretim sırasında `getThermalHeadroom` ve yüzey sıcaklığı zaman serisi (birim: °C ve headroom, yön: düşük iyi) -> eşik bu veriden seçilir; proxy yüzünden export süresi değişimi (yüzde, yön: düşük iyi) |
| P3 | Oynatma entegrasyonu (encoded önce, sonra MCRAW nötr proxy) | akıcılık: düşen kare yüzdesi ve p95 kare aralığı (ms), proxy'siz vs proxy'li, aynı klip; seek: scrub bırakma -> kare gösterme gecikmesi p95 (ms); MCRAW: proxy preview ile tam develop arası fark LSB/% uyuşmazlık olarak **raporlanır, eşik yok** |
| P4 | Ayarlar depolama sayfası, cap + LRU | cap aşılınca tahliye sırası ve boş dosya testi (JVM), toplam boyutun UI değeri = dizin toplamı (birim: bayt) |
