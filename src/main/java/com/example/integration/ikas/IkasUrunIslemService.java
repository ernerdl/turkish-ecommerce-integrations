/*
 * Adapted from a production Spring Boot ERP system.
 *
 * NOTE: This service depends on entity classes (Urun, UrunVaryanti, OlcuBirimi, SatisKanali)
 * and repositories. You need to provide your own implementations matching
 * the method signatures used here.
 *
 * Original author: Mehmet Eren Erdal <m.erenerdal@sevinclidoga.com>
 * License: MIT
 */
package com.example.integration.ikas;

import com.fasterxml.jackson.databind.JsonNode;
import com.example.integration.entity.OlcuBirimi;
import com.example.integration.entity.Urun;
import com.example.integration.entity.UrunVaryanti;
import com.example.integration.repository.SatisKanaliRepository;
import com.example.integration.repository.UrunRepository;
import com.example.integration.repository.UrunVaryantiRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.Locale;

/**
 * İkas ürün işleme servisi.
 * Her ürün ayrı transaction'da işlenir (REQUIRES_NEW).
 * Bu sınıf ayrı bir Spring bean olarak tanımlanmıştır,
 * böylece REQUIRES_NEW propagation Spring proxy üzerinden doğru çalışır.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class IkasUrunIslemService {

    private static final Locale TR = new Locale("tr", "TR");

    private final UrunRepository urunRepository;
    private final UrunVaryantiRepository urunVaryantiRepository;
    private final SatisKanaliRepository satisKanaliRepository;

    /**
     * Tek bir İkas ürününü AYRI TRANSACTİON'da işler.
     * Bir üründeki hata diğer ürünlerin Hibernate session'ını bozmaz.
     *
     * @param product           İkas'tan gelen ürün JSON'u
     * @param defaultBirim      Varsayılan ölçü birimi (KG)
     * @param mevcutSkular      Bellekte tutulan tüm SKU seti (uppercase). Yeni eklenenler de buraya eklenir.
     * @param mevcutUrunKodlari Bellekte tutulan tüm ürün kodları. Yeni eklenenler de buraya eklenir.
     * @param eklenenler        Eklenen varyantların listesi (rapor için)
     * @param guncellenenler    Güncellenen varyantların listesi (rapor için)
     * @param eslesenler        Zaten eşleşen varyantların listesi (rapor için)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processProduct(JsonNode product, OlcuBirimi defaultBirim,
                               Set<String> mevcutSkular, Set<String> mevcutUrunKodlari,
                               List<String> eklenenler, List<String> guncellenenler,
                               List<String> eslesenler) {

        String ikasUrunAdi = product.path("name").asText();
        String ikasUrunId = product.path("id").asText();
        JsonNode variants = product.path("variants");

        if (!variants.isArray() || variants.size() == 0) {
            log.debug("Ürün varyantı yok, atlanıyor: {} (ID: {})", ikasUrunAdi, ikasUrunId);
            return;
        }

        log.info("Ürün işleniyor: {} (ID: {}, {} varyant)", ikasUrunAdi, ikasUrunId, variants.size());

        // Yeni ürün için ana Urun kaydı (lazy oluşturulacak)
        Urun anaUrun = null;

        // Her varyantı işle
        for (JsonNode variant : variants) {
            String sku = variant.path("sku").asText();
            if (sku == null || sku.isEmpty() || sku.equals("-") || sku.equals("null")) {
                log.debug("   Geçersiz SKU, atlanıyor: {}", sku);
                continue;
            }

            // Fiyat bilgisi
            BigDecimal fiyat = extractFiyat(variant);

            // Barkod
            String barkod = extractBarkod(variant);

            // 1. ADIM: Bellekteki Set'ten kontrol et (en hızlı, cache sorunu yok)
            if (mevcutSkular.contains(sku.toUpperCase(TR))) {
                // SKU zaten var - DB'den varyantı çekip güncelle
                Optional<UrunVaryanti> mevcutVaryant = urunVaryantiRepository.findVaryantBySkuNative(sku);

                if (mevcutVaryant.isPresent()) {
                    UrunVaryanti varyant = mevcutVaryant.get();
                    Urun urun = varyant.getUrun();

                    // İsim farklı ise güncelle
                    if (!ikasUrunAdi.equals(urun.getUrunAdi())) {
                        String eskiAd = urun.getUrunAdi();
                        urun.setUrunAdi(ikasUrunAdi);
                        urunRepository.save(urun);
                        guncellenenler.add(sku + " (" + eskiAd + " -> " + ikasUrunAdi + ")");
                        log.info("   Güncellendi: SKU={} | Eski ad: {} -> Yeni ad: {}", sku, eskiAd, ikasUrunAdi);
                    } else {
                        eslesenler.add(sku + " (" + ikasUrunAdi + ")");
                        log.debug("   Eşleşti: SKU={} | {}", sku, ikasUrunAdi);
                    }

                    // Fiyat güncelle
                    if (fiyat.compareTo(BigDecimal.ZERO) > 0 &&
                        (varyant.getSatisFiyati() == null || varyant.getSatisFiyati().compareTo(fiyat) != 0)) {
                        BigDecimal eskiFiyat = varyant.getSatisFiyati();
                        varyant.setSatisFiyati(fiyat);
                        urunVaryantiRepository.save(varyant);
                        log.info("   Fiyat güncellendi: SKU={} | {} -> {}", sku, eskiFiyat, fiyat);
                    }

                    // Pasif varyantı aktif et
                    if (!Boolean.TRUE.equals(varyant.getAktifMi())) {
                        varyant.setAktifMi(true);
                        urunVaryantiRepository.save(varyant);
                        log.info("   Pasif varyant aktif edildi: SKU={}", sku);
                    }
                } else {
                    // Set'te var ama DB'de bulunamadı (veri tutarsızlığı)
                    log.warn("   SKU bellekte var ama DB'de bulunamadı, yeniden oluşturulacak: {}", sku);
                    mevcutSkular.remove(sku.toUpperCase(TR));
                    // Devam et, aşağıdaki "yeni varyant" bloğuna düşecek
                }

                // SKU mevcut ve işlendi, sonraki varyanta geç
                if (mevcutSkular.contains(sku.toUpperCase(TR))) {
                    continue;
                }
            }

            // 2. ADIM: SKU yok - yeni varyant oluştur

            // Ana ürün yoksa oluştur veya mevcut olanı bul
            if (anaUrun == null) {
                String urunKodu = sku.replaceAll("[0-9]", "");
                if (urunKodu.isEmpty()) {
                    urunKodu = sku;
                }

                // Mevcut ürüne bağlamayı dene
                if (mevcutUrunKodlari.contains(urunKodu)) {
                    Optional<Urun> mevcutUrun = urunRepository.findByUrunKodu(urunKodu);
                    if (mevcutUrun.isPresent()) {
                        anaUrun = mevcutUrun.get();
                        log.info("   Mevcut ürüne bağlanıyor: {} ({})", urunKodu, anaUrun.getUrunAdi());
                    }
                }

                if (anaUrun == null) {
                    // Benzersiz ürün kodu oluştur
                    String baseKod = urunKodu;
                    int suffix = 0;
                    while (mevcutUrunKodlari.contains(urunKodu)) {
                        suffix++;
                        urunKodu = baseKod + "-" + ikasUrunId.substring(0, Math.min(8, ikasUrunId.length()));
                        if (suffix > 1) {
                            urunKodu = baseKod + "-" + ikasUrunId.substring(0, Math.min(8, ikasUrunId.length())) + "-" + suffix;
                        }
                        if (suffix > 10) {
                            urunKodu = baseKod + "-" + ikasUrunId;
                            break;
                        }
                    }

                    anaUrun = new Urun();
                    anaUrun.setUrunKodu(urunKodu);
                    anaUrun.setUrunAdi(ikasUrunAdi);
                    anaUrun.setUrunTipi("nihai_urun");
                    anaUrun.setTemelBirim(defaultBirim);
                    anaUrun.setAktifMi(true);
                    anaUrun.setAciklama("İkas'tan otomatik eklendi. ID: " + ikasUrunId);

                    anaUrun = urunRepository.save(anaUrun);
                    mevcutUrunKodlari.add(anaUrun.getUrunKodu());
                    log.info("   Yeni ürün oluşturuldu: {} - {}", anaUrun.getUrunKodu(), ikasUrunAdi);
                }
            }

            // Varyant adını SKU'dan çıkar
            String varyantAdi = extractVaryantAdiFromSku(sku);

            // Varyant oluştur
            UrunVaryanti yeniVaryant = new UrunVaryanti();
            yeniVaryant.setUrun(anaUrun);
            yeniVaryant.setSku(sku);
            yeniVaryant.setVaryantKodu(sku);
            yeniVaryant.setVaryantAdi(varyantAdi);
            yeniVaryant.setBirim(defaultBirim);
            yeniVaryant.setMiktar(extractMiktarFromVaryantAdi(varyantAdi));
            yeniVaryant.setSatisFiyati(fiyat);
            yeniVaryant.setBarkod(barkod);
            yeniVaryant.setAktifMi(true);

            urunVaryantiRepository.save(yeniVaryant);

            // Bellekteki Set'e ekle (sonraki ürünlerde duplicate oluşmasını önler)
            mevcutSkular.add(sku.toUpperCase(TR));

            eklenenler.add(sku + " (" + ikasUrunAdi + ")");
            log.info("   Yeni varyant eklendi: SKU={} | Varyant={} | Fiyat={} | Barkod={}",
                    sku, varyantAdi, fiyat, barkod);
        }
    }

    /**
     * Son senkron tarihini ayrı transaction'da günceller.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateSonSenkronTarihi(Integer kanalId) {
        satisKanaliRepository.findById(kanalId).ifPresent(kanal -> {
            kanal.setSonUrunSenkron(LocalDateTime.now());
            satisKanaliRepository.save(kanal);
        });
    }

    // === Yardımcı metodlar ===

    private BigDecimal extractFiyat(JsonNode variant) {
        JsonNode prices = variant.path("prices");
        if (prices.isArray() && prices.size() > 0) {
            return BigDecimal.valueOf(prices.get(0).path("sellPrice").asDouble(0));
        }
        return BigDecimal.ZERO;
    }

    private String extractBarkod(JsonNode variant) {
        JsonNode barcodes = variant.path("barcodeList");
        if (barcodes.isArray() && barcodes.size() > 0) {
            return barcodes.get(0).asText();
        }
        return null;
    }

    private String extractVaryantAdiFromSku(String sku) {
        if (sku == null || sku.length() < 3) {
            return "Standart";
        }

        String son3 = sku.substring(sku.length() - 3);

        if (son3.matches("\\d{3}")) {
            try {
                int miktar = Integer.parseInt(son3);
                if (miktar == 0) {
                    return "Standart";
                }
                if (miktar == 1 || miktar == 2 || miktar == 5 || miktar == 10 || miktar == 25) {
                    return miktar + "KG";
                }
                if (miktar >= 30) {
                    return miktar + "GR";
                }
                return miktar + "KG";
            } catch (NumberFormatException e) {
                // ignore
            }
        }

        return "Standart";
    }

    private BigDecimal extractMiktarFromVaryantAdi(String varyantAdi) {
        if (varyantAdi == null || varyantAdi.isEmpty()) {
            return BigDecimal.ONE;
        }

        String normalized = varyantAdi.toUpperCase().replace(" ", "");

        if (normalized.endsWith("KG")) {
            try {
                String sayi = normalized.replace("KG", "");
                return new BigDecimal(sayi);
            } catch (NumberFormatException e) {
                // ignore
            }
        }

        if (normalized.endsWith("GR")) {
            try {
                String sayi = normalized.replace("GR", "");
                return new BigDecimal(sayi).divide(BigDecimal.valueOf(1000));
            } catch (NumberFormatException e) {
                // ignore
            }
        }

        return BigDecimal.ONE;
    }
}
