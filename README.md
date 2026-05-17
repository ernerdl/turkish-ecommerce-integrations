# Turkish E-Commerce Integrations

Production-tested Java/Spring Boot integrations for Turkish e-commerce platforms and thermal label printers. Adapted from a real ERP system processing daily orders.

[🇹🇷 Türkçe](#türkçe) | [🇬🇧 English](#english)

---

## English

### What's inside

| Integration | What it does |
|-------------|-------------|
| **Trendyol** | Fetch orders from Trendyol Marketplace, update shipment status (Picking) |
| **İkas** | Order sync (GraphQL), product sync, cargo tracking, webhook handler, OAuth token management |
| **Paraşüt** | Customer & invoice sync to Paraşüt accounting |
| **PayTR** | Payment link creation, delete, SMS/email sending, token utility |
| **Thermal Printers** | PPLB (Argox) and TSPL (XPrinter/TSC) protocol support — PNG → label conversion, RAW_SOCKET and CUPS/SMB printing |

### Why this exists

Turkish e-commerce integrations are scattered across blog posts, half-broken Gists, and outdated SDK examples. This repo is the actual production code from a working ERP — battle-tested with real orders, real edge cases, real bugs already fixed.

### What you get

- Real WebClient/Spring patterns (timeouts, error handling, retries)
- Auth flows (OAuth2 for İkas, Basic Auth for Trendyol, custom token for PayTR)
- Pagination handling
- Idempotent order processing (avoiding duplicate inserts)
- Thermal printer protocols (PPLB / TSPL) — including PNG → bitmap conversion
- Turkish log messages (because that's where the value is in Turkish ERP contexts)

### What's NOT included

This is **integration code**, not a full ERP. You provide:
- Your entity classes (`Siparis`, `SatisKanali`, `Yazici`, etc.)
- Your repositories
- Spring Boot setup, security, DB

Each service has a TODO comment explaining what interfaces it expects.

### Getting started

```bash
git clone https://github.com/ernerdl/turkish-ecommerce-integrations.git
```

Look at `src/main/java/com/example/integration/` — copy the parts you need into your own project. Rename packages, wire up your entities.

See `examples/application.example.yml` for required config keys.

### Contact

If you build something useful with this, or want to chat about ERP/integrations:

📧 **Mehmet Eren Erdal** — m.erenerdal@sevinclidoga.com

I'm not a professional developer — built this entire ERP with Claude Code while running an operations business. Always happy to connect with other builders, especially those tackling Turkish market problems.

### License

MIT — use it, modify it, sell it. No warranty. If it breaks, you keep both pieces.

---

## Türkçe

### Ne var bu repoda

| Entegrasyon | Ne yapıyor |
|-------------|-----------|
| **Trendyol** | Trendyol Pazaryeri'nden sipariş çekme, kargo durumu güncelleme (Picking) |
| **İkas** | Sipariş senkronizasyonu (GraphQL), ürün senkronizasyonu, kargo takibi, webhook handler, OAuth token yönetimi |
| **Paraşüt** | Müşteri ve fatura senkronizasyonu |
| **PayTR** | Ödeme linki oluşturma, silme, SMS/email gönderimi, token utility |
| **Termal Yazıcılar** | PPLB (Argox) ve TSPL (XPrinter/TSC) protokol desteği — PNG → etiket dönüşümü, RAW_SOCKET ve CUPS/SMB ile yazdırma |

### Neden bu repo

Türk e-ticaret entegrasyonları blog yazılarına, yarı kırık Gist'lere ve eski SDK örneklerine dağılmış durumda. Bu repo gerçek bir ERP'nin canlı kodu — gerçek siparişlerle test edilmiş, gerçek edge case'leri görmüş, gerçek bug'ları çözülmüş.

### Ne kazanıyorsun

- Gerçek WebClient/Spring patternleri (timeout, hata yönetimi, retry)
- Auth flow'ları (İkas için OAuth2, Trendyol için Basic Auth, PayTR için custom token)
- Pagination yönetimi
- Idempotent sipariş işleme (duplicate insert'leri önleme)
- Termal yazıcı protokolleri (PPLB / TSPL) — PNG → bitmap dönüşümü dahil
- Türkçe log mesajları (Türk ERP bağlamında değerli olan kısım)

### Ne YOK

Bu **entegrasyon kodu**, komple ERP değil. Sen sağlıyorsun:
- Entity sınıfların (`Siparis`, `SatisKanali`, `Yazici`, vb.)
- Repository'lerin
- Spring Boot kurulumu, security, DB

Her servisin başında ne interface beklediğini açıklayan TODO yorumu var.

### Nasıl başlanır

```bash
git clone https://github.com/ernerdl/turkish-ecommerce-integrations.git
```

`src/main/java/com/example/integration/` altına bak — ihtiyacın olan kısımları kendi projene kopyala. Paket adlarını değiştir, kendi entity'lerine bağla.

Gerekli config key'leri için `examples/application.example.yml`'a bak.

### İletişim

Bunu kullanarak yararlı bir şey yapmak veya ERP/entegrasyonlar üzerine sohbet etmek istersen:

📧 **Mehmet Eren Erdal** — m.erenerdal@sevinclidoga.com

Profesyonel yazılımcı değilim — operasyon işletirken bu ERP'yi tamamen Claude Code ile inşa ettim. Diğer üretkenlerle, özellikle Türk pazarı problemleri üzerine çalışanlarla, tanışmaktan keyif alırım.

### Lisans

MIT — kullan, değiştir, sat. Garanti yok. Bozulursa parçalarını sen toplarsın.
