/*
 * Adapted from a production Spring Boot ERP system.
 *
 * NOTE: This service depends on entity classes (Siparis, SatisKanali, etc.)
 * and repositories. You need to provide your own implementations matching
 * the method signatures used here.
 *
 * Original author: Mehmet Eren Erdal <m.erenerdal@sevinclidoga.com>
 * License: MIT
 */
package com.example.integration.trendyol;

import com.fasterxml.jackson.databind.JsonNode;
import com.example.integration.entity.*;
import com.example.integration.repository.*;
import com.example.integration.service.KutuAtamaService;
import com.example.integration.service.MusteriOlusturmaService;
import com.example.integration.service.StokRezervasyonService;
import com.example.integration.service.SyncHataService;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Lazy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Trendyol Sipariş Kaydedici Servisi
 *
 * Bu servis, TrendyolIntegrationService'den ayrı bir bean olarak çalışır.
 * Böylece @Transactional(propagation = REQUIRES_NEW) düzgün çalışır.
 *
 * Her sipariş atomik olarak işlenir:
 * - Önce kalemler hazırlanır ve doğrulanır
 * - Sonra sipariş ve kalemler birlikte kaydedilir
 * - Hata olursa tüm işlem geri alınır
 *
 * @version 5.0 - Atomik Kayıt + Self-Invocation Çözümü
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TrendyolSiparisKaydediciService {

    private final SiparisRepository siparisRepository;
    private final SiparisKalemiRepository siparisKalemiRepository;
    private final UrunVaryantiRepository urunVaryantiRepository;
    private final SiparisDurumuRepository siparisDurumuRepository;
    private final KargoFirmasiRepository kargoFirmasiRepository;
    @Lazy
    private final StokRezervasyonService stokRezervasyonService;
    private final SyncHataService syncHataService;
    private final KutuAtamaService kutuAtamaService;
    private final MusteriOlusturmaService musteriOlusturmaService;

    private final EntityManager entityManager;

    /**
     * Tek bir siparişi atomik olarak kaydeder.
     *
     * REQUIRES_NEW: Her sipariş kendi transaction'ında işlenir.
     * Hata olursa sadece bu sipariş rollback olur, diğerleri etkilenmez.
     *
     * @param orderNode Sipariş JSON verisi
     * @param kanal Satış kanalı
     * @return true: işlendi, false: atlandı
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tekSiparisKaydet(JsonNode orderNode, SatisKanali kanal) {
        String platformSiparisId = orderNode.get("id").asText();
        String orderNumber = orderNode.get("orderNumber").asText();

        // ========== 1. MEVCUT SİPARİŞ KONTROLÜ ==========
        Optional<Siparis> mevcutOpt = siparisRepository.findByPlatformSiparisId(platformSiparisId);

        Siparis siparis;
        boolean yeniKayit = false;
        boolean kalemGuncellemesiGerekli = false;
        String eskiDurumKodu = null;

        if (mevcutOpt.isPresent()) {
            siparis = mevcutOpt.get();
            eskiDurumKodu = siparis.getDurum() != null ? siparis.getDurum().getDurumKodu() : null;

            // Kalem sayısı kontrolü
            int apiKalemSayisi = orderNode.has("lines") ? orderNode.get("lines").size() : 0;
            long dbKalemSayisi = siparisKalemiRepository.countBySiparisId(siparis.getId());

            if (dbKalemSayisi != apiKalemSayisi) {
                kalemGuncellemesiGerekli = true;
                log.info("Trendyol Sipariş #{} kalem uyuşmazlığı: DB={}, API={}",
                        orderNumber, dbKalemSayisi, apiKalemSayisi);
            }
        } else {
            siparis = new Siparis();
            siparis.setSiparisNo(siparisNoUret());
            siparis.setPlatformSiparisId(platformSiparisId);
            siparis.setPlatformSiparisNo(orderNumber);
            siparis.setKanal(kanal);
            yeniKayit = true;
        }

        // ========== 2. ÖNCELİKLE KALEMLERİ HAZIRLA (Atomik Kayıt) ==========
        List<TrendyolKalemDTO> hazirlananKalemler = new ArrayList<>();
        BigDecimal hesaplananAraToplam = BigDecimal.ZERO;
        BigDecimal hesaplananKdvToplam = BigDecimal.ZERO;

        if (orderNode.has("lines") && orderNode.get("lines").isArray()) {
            int sira = 1;
            for (JsonNode line : orderNode.get("lines")) {
                TrendyolKalemDTO dto = new TrendyolKalemDTO();
                dto.sira = sira++;

                // Trendyol line ID (Picking API için gerekli)
                dto.platformKalemId = line.has("id") ? line.get("id").asText() : null;

                // Ürün adı
                dto.urunAdi = line.has("productName") ? line.get("productName").asText() : "Bilinmeyen Ürün";

                // SKU
                dto.sku = line.has("merchantSku") ? line.get("merchantSku").asText() : null;

                // Barkod
                dto.barkod = line.has("barcode") ? line.get("barcode").asText() : null;

                // Miktar
                dto.miktar = BigDecimal.valueOf(line.has("quantity") ? line.get("quantity").asInt() : 1);

                // Fiyat
                dto.birimFiyat = line.has("price")
                        ? BigDecimal.valueOf(line.get("price").asDouble())
                        : BigDecimal.ZERO;

                // Satır toplamı
                dto.satirToplami = dto.birimFiyat.multiply(dto.miktar);
                hesaplananAraToplam = hesaplananAraToplam.add(dto.satirToplami);

                hazirlananKalemler.add(dto);
            }
        }

        // ========== 3. YENİ SİPARİŞTE KALEM KONTROLÜ ==========
        // NOT: Kalem boş olsa bile siparişi kaydet (sonradan güncellenebilir)
        if (yeniKayit && hazirlananKalemler.isEmpty()) {
            log.warn("Trendyol Sipariş #{} API'den kalemsiz geldi! Sipariş yine de kaydediliyor.", orderNumber);
        }

        // ========== 4. DURUM EŞLEŞTİRME ==========
        String trendyolDurum = orderNode.get("status").asText();
        String bizimDurumKodu = mapTrendyolStatus(trendyolDurum);

        Optional<SiparisDurumu> durumOpt = siparisDurumuRepository.findByDurumKodu(bizimDurumKodu);
        durumOpt.ifPresent(siparis::setDurum);

        // ========== 5. SİPARİŞ BİLGİLERİ ==========
        // Sipariş tarihi
        if (orderNode.has("orderDate")) {
            long timestamp = orderNode.get("orderDate").asLong();
            siparis.setSiparisTarihi(LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(timestamp), ZoneOffset.UTC));
        }

        // Tutarlar
        if (orderNode.has("totalPrice")) {
            siparis.setToplamTutar(BigDecimal.valueOf(orderNode.get("totalPrice").asDouble()));
        }

        // Teslimat adresi
        if (orderNode.has("shipmentAddress")) {
            JsonNode adres = orderNode.get("shipmentAddress");

            String ad = adres.has("firstName") ? adres.get("firstName").asText() : "";
            String soyad = adres.has("lastName") ? adres.get("lastName").asText() : "";
            siparis.setTeslimatAdSoyad((ad + " " + soyad).trim());

            // Telefon numarası - müşteri eşleştirmesi için kritik
            if (adres.has("phone") && !adres.get("phone").asText().isBlank()) {
                siparis.setTeslimatTelefon(adres.get("phone").asText().trim());
            } else if (adres.has("mobilePhone") && !adres.get("mobilePhone").asText().isBlank()) {
                siparis.setTeslimatTelefon(adres.get("mobilePhone").asText().trim());
            }

            String tamAdres = "";
            if (adres.has("address1")) {
                tamAdres = adres.get("address1").asText();
            }
            if (adres.has("address2") && !adres.get("address2").asText().isBlank()) {
                tamAdres = tamAdres.isEmpty() ? adres.get("address2").asText() : tamAdres + " " + adres.get("address2").asText();
            }
            if (!tamAdres.isEmpty()) {
                siparis.setTeslimatAdres(tamAdres);
            }
            if (adres.has("city")) {
                siparis.setTeslimatIl(adres.get("city").asText());
            }
            if (adres.has("district")) {
                siparis.setTeslimatIlce(adres.get("district").asText());
            }
            if (adres.has("postalCode")) {
                siparis.setTeslimatPostaKodu(adres.get("postalCode").asText());
            }
        }

        // Trendyol shipmentPackageId (Picking API için gerekli)
        if (orderNode.has("shipmentPackageId")) {
            siparis.setPlatformPaketId(String.valueOf(orderNode.get("shipmentPackageId").asLong()));
        }

        // Kargo bilgileri
        if (orderNode.has("cargoProviderName")) {
            String kargoAdi = orderNode.get("cargoProviderName").asText();
            Optional<KargoFirmasi> kargoOpt = kargoFirmasiRepository.findByFirmaAdiContainingIgnoreCase(kargoAdi);
            kargoOpt.ifPresent(siparis::setKargoFirma);
        }

        // Kargo takip numarası
        if (orderNode.has("cargoTrackingNumber") && !orderNode.get("cargoTrackingNumber").isNull()) {
            siparis.setKargoTakipNo(orderNode.get("cargoTrackingNumber").asText());
        } else if (orderNode.has("cargoSenderNumber") && !orderNode.get("cargoSenderNumber").isNull()) {
            siparis.setKargoTakipNo(orderNode.get("cargoSenderNumber").asText());
        }

        // Trendyol'da sipariş oluştuğunda ödeme zaten alınmıştır
        siparis.setOdemeDurumu("ODENDI");

        // Kargoya verilme son tarihi (agreedDeliveryDate)
        if (orderNode.has("agreedDeliveryDate") && !orderNode.get("agreedDeliveryDate").isNull()) {
            long agreedTs = orderNode.get("agreedDeliveryDate").asLong(0);
            if (agreedTs > 0) {
                siparis.setKargoyaVerilmeSonTarih(LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(agreedTs), ZoneId.of("Europe/Istanbul")));
            }
        }

        // ========== 6. SİPARİŞİ KAYDET ==========
        siparis = siparisRepository.save(siparis);
        final Siparis kaydedilenSiparis = siparis;

        // ========== 7. KALEMLERİ KAYDET ==========
        if (yeniKayit || kalemGuncellemesiGerekli) {
            // Güncelleme ise önce rezervasyonları temizle, sonra kalemleri sil
            if (kalemGuncellemesiGerekli) {
                // Kalemler silinmeden ÖNCE bağlı rezervasyonları iptal et (yetim rezervasyon önleme)
                try {
                    stokRezervasyonService.siparisRezervasyonuIptalEt(siparis.getId());
                } catch (Exception e) {
                    log.warn("Kalem silme öncesi rezervasyon temizleme hatası: siparisId={}, hata={}", siparis.getId(), e.getMessage());
                }
                siparisKalemiRepository.deleteBySiparisId(siparis.getId());
                log.info("Trendyol Sipariş #{} mevcut kalemler silindi", orderNumber);
            }

            // Hazırlanan kalemleri kaydet
            for (TrendyolKalemDTO dto : hazirlananKalemler) {
                SiparisKalemi kalem = new SiparisKalemi();
                kalem.setSiparis(kaydedilenSiparis);
                kalem.setSira(dto.sira);
                kalem.setPlatformKalemId(dto.platformKalemId);
                kalem.setUrunAdi(dto.urunAdi);
                kalem.setSku(dto.sku);
                kalem.setBarkod(dto.barkod);
                kalem.setMiktar(dto.miktar);
                kalem.setBirimFiyat(dto.birimFiyat);
                kalem.setSatirToplami(dto.satirToplami);

                // SKU ile varyant eşleştirme (aktif varyantı öncelikli al)
                boolean skuEslesti = false;
                if (dto.sku != null && !dto.sku.isEmpty()) {
                    Optional<UrunVaryanti> varyantOpt = urunVaryantiRepository.findAktifVaryantBySkuIgnoreCase(dto.sku);
                    if (varyantOpt.isPresent()) {
                        UrunVaryanti varyant = varyantOpt.get();
                        kalem.setVaryant(varyant);
                        kalem.setUrun(varyant.getUrun());
                        kalem.setBirim(varyant.getBirim());
                        skuEslesti = true;

                        BigDecimal kdvOrani = varyant.getUrun().getKdvOrani();
                        if (kdvOrani != null) {
                            kalem.setKdvOrani(kdvOrani);
                            BigDecimal kdvTutari = dto.satirToplami.multiply(kdvOrani)
                                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                            kalem.setKdvTutari(kdvTutari);
                            hesaplananKdvToplam = hesaplananKdvToplam.add(kdvTutari);
                        }
                    }
                }

                // Kalem her durumda kaydedilir
                siparisKalemiRepository.save(kalem);

                // SKU eşleşmediyse sync hatasına kaydet
                if (!skuEslesti && dto.sku != null && !dto.sku.isEmpty()) {
                    syncHataService.hataKaydet(
                            "TRENDYOL",
                            platformSiparisId,
                            orderNumber,
                            "SKU_BULUNAMADI",
                            "Eşleşmeyen SKU: " + dto.sku + " | Ürün: " + dto.urunAdi,
                            null
                    );
                    log.warn("Trendyol #{} - SKU eşleşmedi: {} ({})", orderNumber, dto.sku, dto.urunAdi);
                }
            }

            // Sipariş toplamlarını güncelle
            siparis.setAraToplam(hesaplananAraToplam);
            siparis.setKdvTutari(hesaplananKdvToplam);
            siparis.setToplamKalemSayisi(hazirlananKalemler.size());
            siparisRepository.save(siparis);

            log.info("Trendyol Sipariş #{} için {} kalem kaydedildi", orderNumber, hazirlananKalemler.size());
        }

        // ========== 8. OTOMATİK MÜŞTERİ OLUŞTURMA ==========
        if (yeniKayit) {
            try {
                musteriOlusturmaService.siparisIcinMusteriOlusturVeyaBagla(siparis);
            } catch (Exception e) {
                log.warn("Otomatik müşteri oluşturma hatası (Trendyol {}): {}", orderNumber, e.getMessage());
            }
        }

        // ========== 9. STOK REZERVASYON ==========
        rezervasyonYonet(siparis, yeniKayit, eskiDurumKodu, bizimDurumKodu);

        return true;
    }

    /**
     * Kalem bilgilerini geçici olarak tutan DTO
     */
    private static class TrendyolKalemDTO {
        int sira;
        String platformKalemId;
        String urunAdi;
        String sku;
        String barkod;
        BigDecimal miktar;
        BigDecimal birimFiyat;
        BigDecimal satirToplami;
    }

    /**
     * Stok rezervasyonunu ve kutu atamasını yönetir
     */
    private void rezervasyonYonet(Siparis siparis, boolean yeniKayit, String eskiDurumKodu, String yeniDurumKodu) {
        try {
            if ("BEKLEMEDE".equals(yeniDurumKodu)) {
                // Önce transaction'ı commit et ki sipariş veritabanına yazılsın
                entityManager.flush();

                // Stok rezervasyonu
                StokRezervasyonService.RezervasyonSonucu sonuc =
                        stokRezervasyonService.siparisiRezerveEt(siparis.getId());

                // Stok yetersiz kalemler logla (üretim emirleri emirleriSenkronize() ile yönetilir)
                if (sonuc.stokYetersizKalemler != null && !sonuc.stokYetersizKalemler.isEmpty()) {
                    log.info("Stok yetersiz {} kalem tespit edildi (Trendyol {}), üretim emirleri senkronizasyonda güncellenecek",
                            sonuc.stokYetersizKalemler.size(), siparis.getSiparisNo());
                }

                // Kutu ataması ve rezervasyonu (yeni sipariş için)
                if (yeniKayit) {
                    try {
                        kutuAtamaService.sipariseKutuAta(siparis.getId());
                        log.info("Yeni Trendyol siparişi için stok ve kutu rezerve edildi: {}", siparis.getSiparisNo());
                    } catch (Exception e) {
                        log.warn("Kutu atama hatası (Trendyol {}): {}", siparis.getSiparisNo(), e.getMessage());
                    }
                }
            } else if (!yeniKayit && eskiDurumKodu != null) {
                if (("KARGODA".equals(yeniDurumKodu) || "TESLIM_EDILDI".equals(yeniDurumKodu))
                        && !yeniDurumKodu.equals(eskiDurumKodu)) {
                    stokRezervasyonService.siparisRezervasyonuTamamla(siparis.getId());
                    log.info("Trendyol siparişi kargoya verildi: {}", siparis.getSiparisNo());
                } else if (("IPTAL".equals(yeniDurumKodu) || "IADE".equals(yeniDurumKodu))
                        && !yeniDurumKodu.equals(eskiDurumKodu)) {
                    stokRezervasyonService.siparisRezervasyonuIptalEt(siparis.getId());
                    log.info("Trendyol siparişi iptal/iade: {}", siparis.getSiparisNo());
                }
            }
        } catch (Exception e) {
            log.error("Rezervasyon hatası (Trendyol {}): {}", siparis.getSiparisNo(), e.getMessage());
        }
    }

    // ========== YARDIMCI METODLAR ==========

    private String siparisNoUret() {
        String tarih = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
        String rastgele = String.format("%05d", (int) (Math.random() * 100000));
        return "SIP-" + tarih + "-" + rastgele;
    }

    private String mapTrendyolStatus(String trendyolStatus) {
        if (trendyolStatus == null) return "BEKLEMEDE";
        return switch (trendyolStatus) {
            case "Awaiting" -> "BEKLEMEDE";
            case "Created" -> "BEKLEMEDE";
            case "Picking" -> "BEKLEMEDE"; // Picking bizim gönderdiğimiz durum, ERP'de hala beklemede
            case "Invoiced" -> "HAZIRLANIYOR";
            case "ReadyToShip", "Shipped" -> "KARGODA";
            case "AtCollectionPoint" -> "KARGODA";
            case "Delivered" -> "TESLIM_EDILDI";
            case "Cancelled" -> "IPTAL";
            // UnPacked: paket çözüldü/yeni pakete bölündü; UnSupplied: tedarik edilemedi
            case "UnPacked", "UnSupplied" -> "IPTAL";
            case "UnDelivered", "Returned" -> "IADE";
            default -> "BEKLEMEDE";
        };
    }
}
