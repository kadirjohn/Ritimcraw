# F1-8 — Renk UI'ı: node şeridi, Develop / Output kartları (tasarım, onay bekliyor)

Durum: öneri, 2026-11-25. Kod yok. Motor, şema v2 ve parity hazır (F1-1…F1-7); eksik olan yalnız **kullanıcının görebilmesi**.

## 1. Bugün ne var
- **Motor/şema (hazır):** `ClipGradeV2` = `develop` (EV, sıcaklık/tint, NR, temporal, sharpness, detail), `legacy` (v1'in tonlama/tekerlek alanları), `nodes[]` (en çok **8**, `kMaxNodes`; her node: lift/gamma/gain/offset tekerlekleri, contrast+pivot, sat, hue, temp/tint, Y/R/G/B eğrileri, log shadow/mid/hi tekerlekleri, node LUT + mix, enable, mix), `output` (`legacy` tonemap+LUT **ya da** `opendrt` {look, tonescale, cwp, cwpLimit, grey, display}), `transform`. GPU zinciri ve CPU referansı parity'li (F1-6/F1-7).
- **UI (v1):** editörde alt gezinme Light / Color / Detail / Frame, her biri v1 kaydırıcıları. Node, eğri, OpenDRT için **hiçbir görünür kontrol yok**.

## 2. Klipp'ten alınan düzen (`ColorPage.kt`'de doğrulandı)
Üstte klip şeridi, `rawControls` (RAW develop: EI/WB), sonra **NodeRow** (node çipleri ince tellerle bağlı, "+" ekle, "ızgara/daha fazla", giriş/EI çipi), altında yuvarlak köşeli **araç sayfası**: seçili node için sekmeler (tekerlekler, eğriler, ayar, LUT, qualifier, window, film); node uzun basış menüsü (yeniden adlandır, kapat/aç, cache, sıfırla, sil); "daha fazla" panelinde still galerisi, klip derecesi, timeline look'u. Biz **düzeni** alıyoruz, Klipp kodunu değil; qualifier/window/film (F1 N2/N3) ve cache/still (F2) bu iş dışında.

## 3. Önerilen düzen (Color sekmesi)
```
[ Develop ] — [ N1 ] — [ N2 ] — [ + ]  …          [ Output: OpenDRT ]
 ───────────────────────────────────────────────────────────────
 seçili öğenin araç sayfası:
   Develop  → EI/exposure, WB (sıcaklık/tint), (Light ve Detail sekmeleri aynen kalır; burada kısayol)
   Node     → sekmeler: Tekerlekler | Eğriler | Ayar | LUT
   Output   → [Legacy | OpenDRT]; OpenDRT: Look · Tonescale · Renk ağırlığı (cwp + sınır) · Gri (nit) · Ekran
```
- **Node şeridi:** yatay kaydırılır; çip = numara + (varsa) ad; kapalı node soluk; seçili çip renk + ince çerçeve (kalın yazı yok); "+" 8'de kaybolur ve nedenini gösterir (sınır). Uzun basış: yeniden adlandır · kapat/aç · sıfırla · çoğalt · sil.
- **Develop kartı** (şeridin başı, silinemez): v1'in develop alanlarının kısa görünümü; tam kontroller Light/Detail'de kalır (tekrar yazmıyoruz).
- **Output kartı** (şeridin sonu, silinemez): mod segmenti **Legacy | OpenDRT**; OpenDRT'de Look, Tonescale, cwp (+limit), Gri nit (3–25), Ekran (sRGB 2.2 / Rec.1886 / HLG 1000). Legacy: bugünkü tonemap + LUT satırı (v1 projeler hiç değişmez).
- **Araç sayfaları:** Tekerlekler (mevcut `ColorWheel.kt` yeniden kullanılır; lift/gamma/gain/offset + log shadow/mid/hi), Eğriler (yeni Compose Canvas düzenleyici, Y/R/G/B), Ayar (contrast+pivot, sat, hue, temp, tint, mix; `CompactSlider`), LUT (mevcut LUT kitaplığı + mix).
- **İkonlar:** yalnız ince Material Symbols Outlined weight 300, vector drawable olarak vendor'lanır (`add`, `more_vert`, `tune`, `palette`, `show_chart` (eğri), `contrast`, `visibility_off`, `edit`, `restart_alt`, `delete`; `ic_duplicate` var); dolgulu `Icons.Filled.*` yok; emoji yok; dokunma hedefi ≥ 48dp; nötr gri düzenleyici şeması.
- **Metinler:** en + tr; Türkçe metinler sahibin gözden geçirmesine açık (ajan yazdığı Türkçeyi işaretler).

## 4. Varsayılanlar (plan K3) ve veri güvenliği
- **Eski projeler = legacy**, hiçbir piksel değişmez (migrasyon açık nötr legacy kaydı yazar, 6.3.1). **Yeni içe aktarma = OpenDRT SDR** (`look 0, tonescale 0, cwp 0, grey 10, display 0`); karar işareti: ekran varsayılanı sRGB 2.2 mi Rec.1886 mı (aşağıda soru).
- Her düzenleme `ClipGradeV2` → mevcut adaptör + `chainWire` yoluyla preview'a gider (yeni render yolu yok); **geri al/yinele** (`GradeHistory`), **kopyala/yapıştır** (`GradeClipboard`) node'ları ve output'u kapsar.
- Parity: UI saf arayüz işi; her dilim için node listesi işlemleri (ekle/sil/aç-kapa/sıfırla/çoğalt, 8 sınırı) ve OpenDRT parametre aralıkları **saf fonksiyon + JVM testi**; cihazda ekran görüntüsü.

## 5. Dilimler (her biri ayrı dal + merge)
| Dilim | İçerik | Kabul |
|---|---|---|
| F1-8a | Node şeridi + Develop/Output çipleri + ekle/sil/aç-kapa/sıfırla/çoğalt + **Ayar** sayfası | node eklenince preview değişir, 8 sınırı, geri al çalışır; v1 projeler piksel-aynı |
| F1-8b | Tekerlekler sayfası (lift/gamma/gain/offset + log) | tekerlek hareketi preview'a canlı yansır; CPU/GPU parity zaten kilitli |
| F1-8c | Eğri düzenleyici (Y/R/G/B) | nokta ekle/sürükle/sil, tek geçişte preview |
| F1-8d | Node LUT (kitaplık + mix) | mevcut LUT yolu aynı |
| F1-8e | Output kartı: Legacy ⇄ OpenDRT, tüm parametreler | OpenDRT açılınca preview/export aynı (parity kilitli) |
| F1-8f | Yeni içe aktarma varsayılanı (K3), geri al/clipboard entegrasyonu, boş durumlar | eski proje bayt-aynı, yeni proje OpenDRT SDR |

## 6. Onayınızı beklediğim sorular
1. Develop çipi gerekli mi, yoksa Color sekmesi yalnız node + Output ile başlasın (Develop zaten Light/Detail'de)? (Öneri: çip kalsın, kısayol olarak.)
2. Yeni içe aktarmalarda OpenDRT **display** varsayılanı: sRGB 2.2 mi, Rec.1886 mı?
3. "Cache" çipi (Klipp'te var) bu işe girmesin, F2-C2'ye kalsın: uygun mu?
4. Qualifier / window / film sekmeleri F1-8'de **gizli/yok** kalsın (N2/N3'te eklenir): uygun mu?
5. Node adı: serbest metin (40 karakter) yeterli mi?
