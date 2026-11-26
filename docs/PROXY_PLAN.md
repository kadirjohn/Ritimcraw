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

## Kararlar 2026-11-26 (Decisions 2026-11-26)
Sahip: "küçük teknik olanları kendin seç; 20 GB cap ONAYLI". Gece ajanının beş açık sorusu (B12) kapatıldı; kod ve testler dalda `feat-proxy-p1-decisions`. Hepsi saf JVM, UI/cihaz yok. Tasarım sınırı korunur: P2/P3 bağlama başlamadı.

**(a) Cap varsayılanı = 20 GiB (sahip onayı).** `PROXY_CAP_DEFAULT_BYTES = 20L shl 30` (21 474 836 480 bayt) adlı sabit; `ProxyPolicy.capBytes` ve `ProxyCache` varsayılanı tek kaynaktan okur (`capDefaultIs20GibAndIsTheCacheDefault` testi). "GB" ikili (GiB) okundu: dosya sistemi ve `StatFs` ikili boyut bildirir, bir ayar sayfası aynı birimle gösterir; ondalık 20 GB (20e9) ile fark %7 ve yönü güvenli tarafta değil (daha büyük), bu yüzden bu bilinçli bir yorumdur, sahip isterse tek sabit değişir.

**(b) Manifest biçimi = JSON (`<ad>.json`), Properties bırakıldı.** Gerekçe: projenin kalıcı dosyalarının hepsi (project.json: GradeStore/SourceRepository/ExportStore/LutStore, CodecMeasureStore) repo içi JSON yardımcılarını kullanır (`ProjectJson.quote` yazma, `JsonParser` okuma; kotlinx-serialization-json çevrimdışı önbellekte yok). `JsonParser` saf Kotlin olduğundan JVM testleri çalışır (Properties gerekçesi "JVM'de JSON yok" yanlıştı). Sürümlü (`"v":1`), alan eklemek geriye uyumlu, kaçışlar (imza metni `"`/`\` içerebilir) doğru; Properties'in `\` ve unicode tuzakları yok. Çökme güvenliği: yazım `<ad>.json.tmp` -> fsync (`DirProxyStorage.write` artık `fd.sync()` yapar) -> `ATOMIC_MOVE` rename, yani `AtomicFiles.writeText` ile aynı sözleşme; okuyucu yalnız `.json`'u olan proxy'yi tanır. Tolerant okuma: kesik/çöp/yabancı metin, her kesik ön ek, `null`/`[]`/`{}`/sürüm 2/sondaki çöp -> `ProxyManifestCodec.parse` `null` döner, asla istisna (testler: her ön ek denenir). Ayrıştırılamayan manifest yoksayılır (oynatılmaz) ve `sweep()` onu, ardından yetim `.mp4`'ü siler. Son erişim zamanı değişmedi: manifest dosyasının mtime'ı (yeniden yazma yok).

**(c) Ad: `playable`.** "Asla-yarım" tek kapısı kodda ve belgede aynı ad: `ProxyCache.playable(key)` (isabet son erişimi günceller, LRU için) ve `playable(signature)` (ek olarak kayıtlı imza metni eşleşmezse reddeder: hash çakışması bile yanlış ayarlı proxy açamaz); dokunmayan okuma `peekPlayable(key)`. Eski kod adları `lookup`/`peek` kaldırıldı (kodda ve testlerde tek seferde yeniden adlandırıldı). Neden bu ad: plan §3.3 ve P0 kabul cümlesi zaten "playable" diyor, davranışı (yalnız tam, doğrulanmış, oynatılabilir) tarif eder, `ProxyState.Ready` ile karışmaz (`Ready` = bu oturumda iş bitti durumu; `playable` = diskte doğrulandı). Kullanıcıya görünen metin henüz yok; P4 sayfasında kullanıcı dili "hazır" olur, kod/dokümanda `playable`.

**(d) İmza hash uzunluğu = 16 hex (64 bit), değişmedi.** Sabit `PROXY_SIG_HASH_HEX_CHARS = 16`. Doğum günü sınırı p ~ n^2 / 2^65: dosya adı `<fingerprint>-<sigHash>` ve fingerprint 64 hex SHA-256 olduğundan çakışma yalnız AYNI kaynağın imzaları arasında anlamlıdır. Tek klip için ömür boyu 100 imza varyantı (çözünürlük x codec x bitrate sınıfı x kodlayıcı sürümü x renk; eski olanlar LRU ile zaten silinir) -> p = 2,7e-16. 10 000 klip x 100 varyant = 1 milyon anahtar toplam kabul edilip hash'ler tüm klipler arasında karşılaştırılsa bile (gerçekte gerekmez) p = 2,7e-8; 10 milyon anahtarda 2,7e-6. Dosya adı uzunluğu ek yük getirmez. Sonuç yönü: çakışma çok düşük olasılık, etkisi de sınırlı (yanlış ayarlı proxy), ek koruma da var: imza yüklemeli `playable(signature)` kayıtlı tam imza metnini karşılaştırır. 16'dan değişmek için neden yok (testler: uzunluk 16, sınır sayıları).

**(e) LRU protect seti arayüzü (P3 yalnız kaynağı takacak).** `ProxyProtectSource` (`fun interface`, `protectedKeys(): Set<ProxyKey>`, saf, kilit altında çağrılır, önbelleğe geri çağrı yasak); `ProxyProtectSource.NONE` ve `union(...)`. `ProxyCache(storage, capBytes, protectSource, clock)` kurucusu bunu `evictToCap()` içinde (çağrıdaki `protect` kümesi, açık yazma tutamaçları ve kaynak birleşimi) kullanır. Hazır kaynaklar: `ProxyInUse` (referans sayımlı `acquire/release`, P3 oynatma oturumu açılış/kapanışına bağlar) ve `ProxyStateMap.buildingSource()` (Building durumundaki anahtarlar = yeniden üretilenler). Testler: koruma gerçekten tahliyeyi engeller, bırakılınca tahliye olur, birleşim, sayaç.

### Sahip kararı gerekenler (ürün davranışı, ben karar vermedim)
1. **Oynatılan / üretilen proxy'yi kullanıcı "bunu sil" / "hepsini sil" ile silerse.** Bugünkü `clear`/`clearClip`/`clearAll` koruma setine bakmaz (yalnız LRU bakar). Seçenekler: (A) silme anında çalan klip orijinale düşer, proxy silinir (kullanıcı ne istediyse o; oturum bir sonraki seek/play'de geçer, §5); (B) çalan/üretilen proxy atlanır ve "kullanımda, şimdi silinmedi" yazılır; (C) silmeyi oturum kapanana kadar ertele (silindi gibi göster, gizli kuyruk). Öneri: A, ama onay sende.
2. **Cap kullanıcı tarafından düşürülürse ve korunan proxy'ler tek başına cap'i aşarsa.** Bugün LRU korunanları atlar, toplam geçici olarak cap üstünde kalır. Seçenekler: (A) böyle kalsın, oynatma bitince tahliye (öneri); (B) kullanıcıya "şu an kullanımda, X GB üstünde" göster; (C) oynatmayı bırakıp tahliye.
3. **Cap'in birimi: GiB (20 x 2^30) yerine ondalık 20 GB (20e9) istenir mi?** Tek sabit; şimdilik GiB.

## Sahip kararları 2026-11-26 (kapanan üç soru) ve boyut ölçümü
Sahip, yukarıdaki üç "Sahip kararı gerekenler" maddesini kapattı. Dal: `feat-proxy-p1-sizing`.

1. **Silme (madde 1) = A.** Kullanıcı çalan/üretilen proxy'yi "bunu sil" / "hepsini sil" ile silerse proxy silinir; çalan klip bir sonraki seek/play'de orijinale düşer (§5 geçiş kuralı: çalarken kaynak değişmez). Üretilmekte olan proxy'de iş iptal edilir, `.tmp` silinir.
2. **Cap düşürme (madde 2) = A + UI göstergesi.** Cap korunan proxy'lerin toplamının altına indirilirse tahliye oynatma bitince yapılır (korunanlar atlanır, bugünkü davranış). Buna ek olarak toplam cap'i aşarken UI "X GB cap üstünde" gösterir.
   **P3 UI gereksinimi:** satır, `ProxyCapUsage(totalBytes, capBytes, protectedBytes)` değerinden beslenir: `totalBytes` = `ProxyCache.totalBytes()`, `capBytes` = güncel cap, `protectedBytes` = tahliyenin atladığı proxy'lerin bayt toplamı (`ProxyInUse` + `Building` anahtarlarının `ProxyEntry.bytes` toplamı). Satır `overCapBytes > 0` iken görünür; sayı `overCapBytes` (saf, `>= 0`); biçimlendirme (GiB, yuvarlama, yerel metin) UI katmanında, pakette metin yok. Saf yardımcılar kodda: `overCapBytes(total, cap)` (negatif girdi 0 sayılır, taşma yok) ve `ProxyCapUsage` (`overCapBytes`, `isOverCap`, `overCapBecauseProtectedBytes`), `ProxyOverCapTest` 7 test.
3. **Birim (madde 3) = GiB kalır;** 20 GiB cap onaylı (`PROXY_CAP_DEFAULT_BYTES`).

### Gerçek proxy boyutu: MB/dk
Birim notu: "MB" burada MiB (2^20 bayt), sahibin "night.mcraw 15153 MB" sayısıyla aynı okuma (15 888 842 131 / 2^20 = 15 152,8). Ondalık MB (1e6) ayrıca verildi.
Yöntem: boyut = `stat -f %z`; kare sayısı ve PTS = `build/desktop/rawcli info` (`frameCount`, ilk/son `ptsUs`); kare periyodu = (son-ilk PTS)/(frameCount-1); süre = frameCount x periyot; fps = 1e6/periyot. Hepsi komut çıktısıdır, aritmetik python ile.

**Ham MCRAW (ölçüldü: boyut + metadata komutlarından, dosya/süre):**
| Klip | Boyut (bayt) | MiB | Çözünürlük | Kare | fps | Süre (sn) | Ham MiB/dk | Ham MB/dk (1e6) |
|---|---|---|---|---|---|---|---|---|
| night.mcraw | 15 888 842 131 | 15 152,8 | 3840x2160 | 1749 | 29,809 | 58,674 | 15 495,2 | 16 247,9 |
| cam24b.mcraw | 4 249 603 511 | 4 052,7 | 4096x3072 | 688 | 59,994 | 11,468 | 21 203,9 | 22 233,9 |
| cam72.mcraw | 485 055 500 | 462,6 | 3840x2160 | 73 | 30,052 | 2,429 | 11 426,0 | 11 981,1 |
(Klipler kısa dilimler; ham akış 11-21 GiB/dk mertebesinde, kaynak: yukarıdaki komutlar. Bu üç klibin süresi kısa olduğundan fps tahmini ilk/son PTS farkından gelir.)

**Planlanan proxy (aritmetik, ölçülmüş dosya değil):** kaynak satırı: bu dosya §2.1 tablosu, "Bitrate" satırı: 1080p HEVC VBR ~20 Mbit/s ("başlangıç önerisi, kesin değer ölçülerek seçilir"); kodda bitrate yok (`ProxySettings.bitrateClass` opak etiket, `ProxySignature.kt:16-23`), ses yok. Çözünürlük kısa kenar 1080: 3840x2160 -> 1920x1080, 4096x3072 -> 1440x1080.
- 20 Mbit/s = 2,5 MB/s (1e6) = 150,0 MB/dk (1e6) = 143,05 MiB/dk. Bitrate çözünürlük/fps'den bağımsız alındığı için üç klip için de aynı (gerçek VBR çıktısı içeriğe göre sapar).
- 20 GiB (21 474 836 480 bayt) / 143,05 MiB/dk = 143,2 dk (~2 sa 23 dk) proxy. Kaynak, aynı aritmetik: 21 474 836 480 / (20e6/8 x 60).
- Karşılaştırma: ham/proxy boyut oranı night 15 495,2/143,05 = 108x, cam24b 21 203,9/143,05 = 148x, cam72 11 426,0/143,05 = 80x (aritmetik).
- Hassasiyet: 10 Mbit/s ile 286,3 dk, 40 Mbit/s ile 71,6 dk sığar (aynı formül).

**Gerçek kodlama (ölçüldü, ama MCRAW değil):** MCRAW'ı ffmpeg açamaz ve nötr develop proxy kodu henüz yok; bu yüzden gerçek 4K60 10-bit HEVC encoded kaynak (`/tmp/claude-501/m0/e0-src4k.mp4`, 3840x2160, 60 fps, yuv420p10le, Mi-Log) ilk 10 sn'si 1920x1080'e küçültüldü, 10-bit HEVC, 20 Mbit/s hedef, GOP 60, B-kare yok, ses yok (`ffmpeg` 8.1.1, `-vf scale=1920:1080`):
| Kodlayıcı | Çıktı bayt | MiB | Süre (sn) | Kare | Gerçek bitrate (Mbit/s) | MiB/dk |
|---|---|---|---|---|---|---|
| `hevc_videotoolbox` Main10, `-b:v 20M` | 23 581 651 | 22,49 | 10,017 | 597 | 18,83 | 134,7 |
| `libx265` fast, VBV 20000 kbit, 10-bit | 25 250 212 | 24,08 | 10,017 | 597 | 20,17 | 144,2 |
Okuma: iki kodlayıcı hedefin %94-%101'inde (18,83 ve 20,17 / 20 Mbit/s); yani 143 MiB/dk aritmetiği gerçek çıktıyla 135-144 MiB/dk aralığında örtüşüyor. Sınırlar: tek içerik (Mi-Log 4K60 klibi), 10 sn, masaüstü kodlayıcı; telefonun `MediaCodec` HEVC'si ve MCRAW nötr proxy (log benzeri transfer, farklı içerik/gürültü) **ölçülmedi**, P1/P3 cihaz ölçümünde (§7) yapılır. Kaynak MCRAW'dan proxy boyutu bu yüzden hâlâ yalnız bitrate aritmetiğidir.
