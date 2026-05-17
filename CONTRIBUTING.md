# Katkıda Bulunma / Contributing

## Türkçe

Bu repo gerçek bir ERP'den çıkarılmış kod. Mükemmel değil — pattern'ler iyileştirilebilir, eksik testler var, bazı yerler refactor isteyebilir.

### Nasıl katkıda bulunabilirsin

- **Bug bulduysan**: Issue aç, detayları paylaş
- **Yeni bir Türk e-ticaret entegrasyonu eklemek istiyorsan** (Hepsiburada, N11, Çiçeksepeti, Pazarama vs.): PR aç, aynı patternleri takip et
- **Kodu iyileştirmek istiyorsan**: PR aç, neden değiştirdiğini commit mesajında açıkla
- **Soru sormak istiyorsan**: Issue aç veya direkt mail at: m.erenerdal@sevinclidoga.com

### Code style

- Türkçe değişken ve method isimlerini koru (bu codebase Türkçe yazılmış)
- Log mesajları Türkçe (kullanıcı deneyimi için)
- JavaDoc Türkçe açıklamalı
- Public API isimleri İngilizce olabilir

### Hardcoded credential koyma

Hiçbir API key, secret, password veya production URL commit ETME. Hepsi `@Value` ile config'ten okunmalı veya DB'den gelmeli.

---

## English

This repo is code extracted from a real ERP. It's not perfect — patterns can be improved, tests are missing, some places need refactoring.

### How to contribute

- **Found a bug**: Open an issue with details
- **Want to add another Turkish e-commerce integration** (Hepsiburada, N11, Çiçeksepeti, Pazarama, etc.): Open a PR following the same patterns
- **Want to improve the code**: Open a PR, explain the reasoning in the commit message
- **Want to ask something**: Open an issue or email directly: m.erenerdal@sevinclidoga.com

### Code style

- Keep Turkish variable/method names (this codebase is in Turkish)
- Log messages in Turkish (for user experience)
- JavaDoc with Turkish descriptions
- Public API names can be English

### No hardcoded credentials

Don't commit any API key, secret, password, or production URL. Everything should be read from config via `@Value` or come from DB.
