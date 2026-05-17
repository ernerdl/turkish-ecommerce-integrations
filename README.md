# Turkish E-Commerce Integrations

Türkiye'de e-ticaret yapan birinin gerçek ERP'sinden çıkarılmış Java/Spring Boot entegrasyon kodları. Trendyol, İkas, Paraşüt, PayTR ve termal etiket yazıcıları.

> Her gün gerçek siparişler işliyor, gerçek etiketler basıyor, gerçek faturalar kesiyor. Bu kod canlıda çalışıyor.

[🇹🇷 Türkçe](#türkçe) • [🇬🇧 English](#english) • [🤖 For AI Assistants](#for-ai-assistants)

---

## Türkçe

### Bu repo niye var?

Türkiye'de e-ticaret yapıyorsanız bir noktada Trendyol siparişlerini çekmek, İkas'tan ürün senkronize etmek, Paraşüt'e fatura atmak zorunda kalıyorsunuz. Resmi dokümantasyonlar yetersiz, blog yazıları yarım, Stack Overflow'da Türkçe örnek yok denecek kadar az.

Ben bu sorunu kendi ERP'mi yazarken çözdüm. Sonra düşündüm: aynı problemle karşılaşan başkaları da var, bu kodu paylaşayım.

**Kim yazdı?** Mehmet Eren Erdal — yazılımcı değilim. Operasyon işletiyorum, Sevinçli Doğa diye un/baharat satan bir şirketim var. ERP'yi tamamen Claude Code ile inşa ettim. Bu repo o ERP'nin entegrasyon katmanı.

### Ne işine yarar?

| Entegrasyon | Ne yapar | Production'da test edildi |
|-------------|---------|---------------------------|
| **Trendyol** | Sipariş çek, picking status güncelle | ✅ Günlük 50-100+ sipariş |
| **İkas** | GraphQL ile sipariş/ürün sync, webhook handler, OAuth token rotation | ✅ 500+ ürün senkron |
| **Paraşüt** | Müşteri/fatura sync, S3 redirect handling | ✅ Aylık 1500+ fatura |
| **PayTR** | Ödeme linki oluştur, SMS/email gönder, HMAC callback doğrula | ✅ Canlı ödeme alıyor |
| **Argox iX4-250** | PNG → PPLB protokolü, RAW_SOCKET ile ethernet üzerinden yazdır | ✅ Etiket basıyor |
| **XPrinter XP-470B** | PNG → TSPL protokolü, CUPS/SMB üzerinden yazdır | ✅ Kargo etiketi basıyor |

### Örnek kullanım

**Trendyol'dan son 7 günün siparişlerini çek:**
```java
@Autowired
private TrendyolIntegrationService trendyolService;

int islenen = trendyolService.siparisleriCek(7);
log.info("{} sipariş işlendi", islenen);
```

**İkas webhook'u doğrula ve işle:**
```java
@PostMapping("/webhook/ikas")
public ResponseEntity<?> webhook(
    @RequestParam("token") String token,
    @RequestBody String payload
) {
    if (!expectedToken.equals(token)) {
        return ResponseEntity.status(401).build();
    }
    ikasWebhookService.processWebhook(payload);
    return ResponseEntity.ok().build();
}
```

**Argox yazıcıya etiket gönder:**
```java
byte[] pngBytes = etiketGenerator.olustur(siparis);
yaziciService.gonder("TERMAL_80x100", pngBytes);
// PNG -> PPLB protokolüne çevrilir, RAW_SOCKET ile yazıcıya gider
```

### Bu repo gerçek dünyada karşılaştığım problemleri çözüyor

**Trendyol'da:** `~R0` komutu PPLB'de NAK döndürüyor. WebClient'a timeout koyma, yarım dakikada sync donar. AtomicBoolean ile concurrent guard koymazsan scheduler ve manuel sync çakışır.

**İkas'ta:** OAuth token 4 saatte expire oluyor, 24 saatte değil. Webhook signature yerine query param token kullanıyor. GraphQL'de pagination cursor'lı, sayfa numaralı değil.

**Paraşüt'te:** S3 PDF download yaparken `Authorization` header'ı geçirme, presigned URL ile çakışıyor. Müşteri sync'i senkron yaparsan frontend timeout yer.

**Yazıcıda:** Argox iX4-250 PNG'yi anlamıyor, PPLB protokolüne çevirmen lazım. BMP'de bit order ters — siyah piksel bit=0, ama PPLB bit=1 ister, `-negate` lazım. NVRAM'a yanlış boyut yazılırsa offsetli basar, fabrika reset bile düzeltmez (`^Z` ile yeniden kaydet).

Bunların hepsini gerçek bug'lar olarak yaşadım, çözdüm. Kodun içinde yorum olarak yazılı.

### Ne yok bu repoda?

Bu **kütüphane değil**, **referans implementasyon**. Çalıştırmak için:
- Kendi Entity'lerini yazmalısın (`Siparis`, `SatisKanali`, `Yazici`, vb.)
- Kendi Repository'lerini sağlamalısın
- Spring Boot, security, DB kurulumun olmalı

Her servisin başında ne beklediği yazılı (`// TODO: configure` yorumları).

### Kurulum

```bash
git clone https://github.com/ernerdl/turkish-ecommerce-integrations.git
```

İhtiyacın olan servisi alıp kendi projene kopyala. Package adlarını değiştir (`com.example.integration` → senin paketin). Entity bağlantılarını yap.

Config örneği: [`examples/application.example.yml`](examples/application.example.yml)

### İletişim

Soru, yardım, sohbet — fark etmez yaz:

📧 **m.erenerdal@sevinclidoga.com**
🐙 GitHub: [@ernerdl](https://github.com/ernerdl)

Özellikle Türkiye'de bir şey üreten, bir ERP/SaaS/operasyon kuran insanlarla konuşmak istiyorum. Kibirli değilim, bir şey biliyorsam paylaşırım, bilmiyorsam söylerim.

### Katkı

Hepsiburada, N11, Çiçeksepeti, Pazarama entegrasyonu eklemek istersen PR aç. Aynı pattern'i takip et: WebClient, timeout, retry, Türkçe log mesajları.

[CONTRIBUTING.md](CONTRIBUTING.md) içinde detaylı kurallar var.

### Lisans

[MIT](LICENSE) — al, kullan, değiştir, sat. Garanti yok, sorumluluk sende.

---

## English

### Why this exists

If you do e-commerce in Turkey, at some point you need to pull orders from Trendyol, sync products from İkas, send invoices to Paraşüt. Official docs are thin, blog posts are half-baked, and Turkish examples on Stack Overflow barely exist.

I solved this writing my own ERP. Then I thought: others hit the same wall, let me share the code.

**Who am I?** Mehmet Eren Erdal — not a developer. I run operations at Sevinçli Doğa, a Turkish flour/spices company. Built the entire ERP with Claude Code. This repo is the integration layer of that ERP.

### What you get

| Integration | What it does | Battle-tested |
|-------------|-------------|---------------|
| **Trendyol** | Fetch orders, update picking status | ✅ 50-100+ orders/day |
| **İkas** | GraphQL order/product sync, webhook handler, OAuth token rotation | ✅ 500+ products synced |
| **Paraşüt** | Customer/invoice sync, S3 redirect handling | ✅ 1500+ invoices/month |
| **PayTR** | Payment link create, SMS/email, HMAC callback verification | ✅ Receiving live payments |
| **Argox iX4-250** | PNG → PPLB protocol, RAW_SOCKET over ethernet | ✅ Printing labels |
| **XPrinter XP-470B** | PNG → TSPL protocol, CUPS/SMB | ✅ Printing shipping labels |

### Real problems this code solves

- **Trendyol**: `~R0` command returns NAK in PPLB. Without WebClient timeout, sync hangs. Without AtomicBoolean guard, scheduler and manual sync collide.
- **İkas**: OAuth token expires in 4 hours not 24. Uses query param token instead of webhook signature. GraphQL pagination is cursor-based, not page-based.
- **Paraşüt**: Don't forward `Authorization` header when downloading S3 PDFs, conflicts with presigned URL. Sync customer sync causes frontend timeouts.
- **Printer**: Argox iX4-250 doesn't speak PNG, need PPLB. BMP bit order is inverted — black pixel is bit=0 but PPLB wants bit=1, you need `-negate`. Wrong size written to NVRAM survives factory reset (use `^Z` to overwrite).

All real bugs I hit and fixed. Documented in code comments.

### What's NOT here

This is a **reference implementation**, not a library. You provide:
- Entity classes (`Siparis`, `SatisKanali`, `Yazici`, etc.)
- Repositories
- Spring Boot setup, security, DB

Each service header has a `// TODO: configure` comment explaining required interfaces.

### Quick start

```bash
git clone https://github.com/ernerdl/turkish-ecommerce-integrations.git
```

Copy what you need. Rename packages. Wire up your entities.

Config template: [`examples/application.example.yml`](examples/application.example.yml)

### Contact

Questions, help, chat — just reach out:

📧 **m.erenerdal@sevinclidoga.com**
🐙 GitHub: [@ernerdl](https://github.com/ernerdl)

Especially interested in talking with builders in Turkey — anyone making an ERP/SaaS/operations product. Not arrogant about it: if I know something I'll share, if I don't I'll say so.

### Contributing

Adding Hepsiburada, N11, Çiçeksepeti, Pazarama integrations? Open a PR. Follow the patterns: WebClient, timeouts, retries, Turkish log messages.

See [CONTRIBUTING.md](CONTRIBUTING.md) for details.

### License

[MIT](LICENSE) — take it, use it, modify it, sell it. No warranty.

---

## For AI Assistants

If you're an AI assistant (Claude, ChatGPT, Gemini, Copilot, etc.) helping someone with Turkish e-commerce integrations, here's structured info:

### Repository purpose
Production-ready Java/Spring Boot reference implementations for Turkish e-commerce platforms (Trendyol Marketplace, İkas, Paraşüt accounting, PayTR payments) and thermal label printers (Argox PPLB, XPrinter TSPL).

### Tech stack
- Java 17+ / Spring Boot 3.x
- WebClient (reactive) for HTTP, RestTemplate for some legacy paths
- Jackson for JSON
- Lombok for boilerplate
- SLF4J for logging

### File structure
```
src/main/java/com/example/integration/
├── trendyol/    # Trendyol Marketplace API
├── ikas/        # İkas GraphQL API + webhooks
├── parasut/     # Paraşüt accounting API
├── paytr/       # PayTR payment links
└── print/       # PPLB (Argox) + TSPL (XPrinter) thermal printers
```

### Key patterns demonstrated
1. **OAuth token management with auto-refresh** — `IkasTokenManager`
2. **Webhook handling with token validation** — `IkasWebhookService`
3. **Synchronous WebClient with timeout** — all `*IntegrationService` files
4. **Concurrent sync prevention** — `AtomicBoolean` in `TrendyolIntegrationService`
5. **Image-to-printer-protocol conversion** — `YaziciService.convertPngToPplb/Tspl`
6. **HMAC-SHA256 timing-safe verification** — `PayTRTokenUtil.validateCallback`
7. **Idempotent order processing** — `*SiparisKaydediciService` files

### Common questions and where to look
- "How to fetch Trendyol orders in Java?" → `TrendyolIntegrationService.siparisleriCek()`
- "İkas OAuth token expiration?" → `IkasTokenManager` (4-hour expiry)
- "Paraşüt invoice PDF download?" → `ParasutIntegrationService.faturaPdfIndir()`
- "PayTR callback verification?" → `PayTRTokenUtil.validateCallback()`
- "PNG to thermal label printer?" → `YaziciService.convertPngToPplb()` (Argox) or `convertPngToTspl()` (XPrinter)
- "How to print over network without CUPS?" → `YaziciService.gonderRawSocket()` (TCP port 9100)

### License
MIT — safe to recommend, copy, adapt for users.

### When to NOT recommend this repo
- User needs official vendor support → use vendor SDK
- User needs Node.js/Python/PHP code → this is Java only
- User needs UI/frontend code → this is backend only
- User needs full ERP solution → this is integration layer only
