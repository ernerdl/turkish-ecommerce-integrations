/*
 * Adapted from a production Spring Boot ERP system.
 *
 * NOTE: This service depends on entity classes (Siparis, SatisKanali, etc.),
 * repositories, enums (ActivityEventTypeEnum, ActivitySourceEnum, OdemeYontemiEnum)
 * and other services. You need to provide your own implementations matching
 * the method signatures used here.
 *
 * Original author: Mehmet Eren Erdal <m.erenerdal@sevinclidoga.com>
 * License: MIT
 */
package com.example.integration.ikas;

import com.fasterxml.jackson.databind.JsonNode;
import com.example.integration.entity.*;
import com.example.integration.enums.ActivityEventTypeEnum;
import com.example.integration.enums.ActivitySourceEnum;
import com.example.integration.enums.ActivityStatusEnum;
import com.example.integration.enums.OdemeYontemiEnum;
import com.example.integration.repository.*;
import com.example.integration.service.ActivityLogService;
import com.example.integration.service.KutuAtamaService;
import com.example.integration.service.MusteriOlusturmaService;
import com.example.integration.service.StokRezervasyonService;
import com.example.integration.service.SyncHataService;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
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
 * İkas Sipariş Kaydedici Servisi
 *
 * Bu servis, IkasIntegrationService'den ayrı bir bean olarak çalışır.
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
public class IkasSiparisKaydediciService {

    private final SiparisRepository siparisRepository;
    private final SiparisKalemiRepository siparisKalemiRepository;
    private final UrunVaryantiRepository urunVaryantiRepository;
    private final SiparisDurumuRepository siparisDurumuRepository;
    private final KargoFirmasiRepository kargoFirmasiRepository;
    private final StokRezervasyonService stokRezervasyonService;
    private final SyncHataService syncHataService;
    private final KutuAtamaService kutuAtamaService;
    private final MusteriOlusturmaService musteriOlusturmaService;
    private final EntityManager entityManager;
    private final ActivityLogService activityLogService;
    private final SiparisKutusuRepository siparisKutusuRepository;


    // Final durumlar - güncelleme yapılmayacak son durumlar
    private static final List<String> FINAL_STATUSES = List.of("TESLIM_EDILDI", "IPTAL");

    /**
     * Tek bir siparişi atomik olarak kaydeder.
     *
     * REQUIRES_NEW: Her sipariş kendi transaction'ında işlenir.
     * Hata olursa sadece bu sipariş rollback olur, diğerleri etkilenmez.
     *
     * Atomik Kayıt Mantığı:
     * 1. Önce API'den gelen kalemleri parse et ve hazırla
     * 2. Kalemler boşsa ve yeni kayıtsa -> hata ver, kaydetme
     * 3. Her şey hazırsa sipariş + kalemler birlikte kaydet
     *
     * @param orderNode Sipariş JSON verisi
     * @param kanal Satış kanalı
     * @return true: işlendi, false: atlandı
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tekSiparisKaydet(JsonNode orderNode, SatisKanali kanal) {
        String platformSiparisId = orderNode.path("id").asText(null);
        String orderNumber = orderNode.path("orderNumber").asText(null);

        // Null kontrolü - zorunlu alanlar
        if (platformSiparisId == null || platformSiparisId.isEmpty()) {
            log.error("Sipariş ID'si boş geldi! Sipariş atlanıyor.");
            return false;
        }
        if (orderNumber == null || orderNumber.isEmpty()) {
            orderNumber = platformSiparisId; // Fallback: ID'yi kullan
            log.warn("Sipariş numarası boş, ID kullanılıyor: {}", platformSiparisId);
        }

        // ========== 1. MEVCUT SİPARİŞ KONTROLÜ ==========
        Optional<Siparis> mevcutOpt = siparisRepository.findByPlatformSiparisIdWithDurum(platformSiparisId);

        Siparis siparis;
        boolean yeniKayit = false;
        boolean kalemGuncellemesiGerekli = false;
        String eskiDurumKodu = null;
        String eskiOdemeDurumu = null;

        if (mevcutOpt.isPresent()) {
            siparis = mevcutOpt.get();
            String mevcutDurumKodu = siparis.getDurum() != null ? siparis.getDurum().getDurumKodu() : "";
            eskiDurumKodu = mevcutDurumKodu;
            eskiOdemeDurumu = siparis.getOdemeDurumu();

            // Final durumlardaki siparişleri atla ve varsa sync hatasını çözüldü işaretle
            if (FINAL_STATUSES.contains(mevcutDurumKodu)) {
                syncHataService.hataCozuldu("IKAS", platformSiparisId);
                return false;
            }

            // Kalem sayısı kontrolü
            int apiKalemSayisi = 0;
            JsonNode lineItemsNode = orderNode.path("orderLineItems");
            if (lineItemsNode.isArray()) {
                apiKalemSayisi = lineItemsNode.size();
            }
            long dbKalemSayisi = siparisKalemiRepository.countBySiparisId(siparis.getId());

            if (dbKalemSayisi != apiKalemSayisi) {
                kalemGuncellemesiGerekli = true;
                log.info("Sipariş #{} kalem uyuşmazlığı: DB={}, API={}", orderNumber, dbKalemSayisi, apiKalemSayisi);
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
        List<SiparisKalemiDTO> hazirlananKalemler = new ArrayList<>();
        BigDecimal hesaplananAraToplam = BigDecimal.ZERO;

        JsonNode lineItems = orderNode.path("orderLineItems");
        if (lineItems.isArray() && lineItems.size() > 0) {
            int sira = 1;

            for (JsonNode line : lineItems) {
                // Varyant bilgileri
                JsonNode variantNode = line.path("variant");
                String urunAdi = variantNode.path("name").asText("Bilinmeyen Ürün");
                String sku = variantNode.path("sku").asText(null);

                // Barkod
                String barkod = null;
                JsonNode barcodeListNode = variantNode.path("barcodeList");
                if (barcodeListNode.isArray() && barcodeListNode.size() > 0) {
                    barkod = barcodeListNode.get(0).asText(null);
                }

                // Miktar ve fiyat
                BigDecimal miktar = BigDecimal.valueOf(line.path("quantity").asInt(1));
                BigDecimal birimFiyat = BigDecimal.valueOf(line.path("price").asDouble(0));
                BigDecimal satirToplami = BigDecimal.valueOf(line.path("finalPrice").asDouble(0));

                SiparisKalemiDTO kalemDto = SiparisKalemiDTO.builder()
                        .sira(sira++)
                        .urunAdi(urunAdi)
                        .sku(sku)
                        .barkod(barkod)
                        .miktar(miktar)
                        .birimFiyat(birimFiyat)
                        .satirToplami(satirToplami)
                        .build();

                hesaplananAraToplam = hesaplananAraToplam.add(satirToplami);
                hazirlananKalemler.add(kalemDto);
            }
        }

        // ========== 3. YENİ SİPARİŞTE KALEM KONTROLÜ ==========
        if (yeniKayit && hazirlananKalemler.isEmpty()) {
            log.warn("Sipariş #{} API'den kalemsiz geldi! Sipariş atlanıyor.", orderNumber);
            return false;
        }

        // ========== 4. KARGO BİLGİLERİ ==========
        String kargoTakipNo = null;
        String kargoBarkodu = null;
        String kargoFirmaAdi = null;
        String platformPaketId = null;  // İkas orderPackages.id - kargo güncellemesi için

        JsonNode orderPackages = orderNode.path("orderPackages");
        if (orderPackages.isArray() && orderPackages.size() > 0) {
            // İlk paketi al (genellikle tek paket olur)
            JsonNode firstPackage = orderPackages.get(0);
            platformPaketId = firstPackage.path("id").asText(null);

            for (JsonNode pkg : orderPackages) {
                JsonNode trackingInfo = pkg.path("trackingInfo");
                if (!trackingInfo.isMissingNode() && !trackingInfo.isNull()) {

                    String takipNo = trackingInfo.path("trackingNumber").asText(null);
                    if (takipNo != null && !takipNo.isEmpty()) {
                        kargoTakipNo = takipNo;
                    }

                    String barkod = trackingInfo.path("barcode").asText(null);
                    if (barkod != null && !barkod.isEmpty()) {
                        kargoBarkodu = barkod;
                    }

                    String firma = trackingInfo.path("cargoCompany").asText(null);
                    if (firma != null && !firma.isEmpty()) {
                        kargoFirmaAdi = firma;
                    }

                    if (kargoTakipNo != null || kargoBarkodu != null) {
                        break;
                    }
                }
            }
        }

        // ========== 5. DURUM EŞLEŞTİRME ==========
        String ikasDurum = mapIkasStatus(
                orderNode.path("status").asText(),
                orderNode.path("orderPackageStatus").asText(),
                orderNode.path("orderPaymentStatus").asText(),
                kargoTakipNo
        );
        String yeniDurumKodu = ikasDurum;

        if (eskiDurumKodu != null && !ikasDurum.equals(eskiDurumKodu)) {
            log.info("Sipariş #{} durum değişti: {} -> {} (İkas status={}, pkgStatus={}, payStatus={})",
                    orderNumber, eskiDurumKodu, ikasDurum,
                    orderNode.path("status").asText(),
                    orderNode.path("orderPackageStatus").asText(),
                    orderNode.path("orderPaymentStatus").asText());
        }

        Optional<SiparisDurumu> durumOpt = siparisDurumuRepository.findByDurumKodu(ikasDurum);
        durumOpt.ifPresent(siparis::setDurum);

        // Ödeme durumu
        String payStatus = orderNode.path("orderPaymentStatus").asText("");
        if (!payStatus.isEmpty()) {
            String yeniOdemeDurumu = mapPaymentStatus(payStatus);
            if (!yeniOdemeDurumu.equals(siparis.getOdemeDurumu())) {
                log.info("Sipariş #{} ödeme durumu değişti: {} -> {} (İkas: {})",
                        orderNumber, siparis.getOdemeDurumu(), yeniOdemeDurumu, payStatus);
            }
            siparis.setOdemeDurumu(yeniOdemeDurumu);
        }

        // Toplam tutar
        siparis.setToplamTutar(BigDecimal.valueOf(orderNode.path("totalFinalPrice").asDouble(0)));

        // ========== 6. YENİ KAYIT BİLGİLERİ ==========
        if (yeniKayit) {
            // Sipariş tarihi
            long orderedAtMs = orderNode.path("orderedAt").asLong(0);
            if (orderedAtMs > 0) {
                siparis.setSiparisTarihi(LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(orderedAtMs), ZoneOffset.UTC));
            } else {
                siparis.setSiparisTarihi(LocalDateTime.now());
            }

            // Müşteri bilgileri
            JsonNode customer = orderNode.path("customer");
            if (!customer.isMissingNode() && !customer.isNull()) {
                String ad = customer.path("firstName").asText("");
                String soyad = customer.path("lastName").asText("");
                String adSoyad = (ad + " " + soyad).trim();
                siparis.setTeslimatAdSoyad(adSoyad.isEmpty() ? "Misafir Müşteri" : adSoyad);
            }

            // Teslimat adresi
            JsonNode adres = orderNode.path("shippingAddress");
            if (!adres.isMissingNode() && !adres.isNull()) {
                String adresLine1 = adres.path("addressLine1").asText(null);
                String adresLine2 = adres.path("addressLine2").asText(null);
                String tamAdres = "";
                if (adresLine1 != null && !adresLine1.isEmpty()) {
                    tamAdres = adresLine1;
                }
                if (adresLine2 != null && !adresLine2.isEmpty()) {
                    tamAdres = tamAdres.isEmpty() ? adresLine2 : tamAdres + " " + adresLine2;
                }
                if (!tamAdres.isEmpty()) {
                    siparis.setTeslimatAdres(tamAdres);
                }

                String cityName = adres.path("city").path("name").asText(null);
                if (cityName != null && !cityName.isEmpty()) {
                    siparis.setTeslimatIl(cityName);
                }

                String districtName = adres.path("district").path("name").asText(null);
                if (districtName != null && !districtName.isEmpty()) {
                    siparis.setTeslimatIlce(districtName);
                }

                String postaKodu = adres.path("postalCode").asText(null);
                if (postaKodu != null && !postaKodu.isEmpty()) {
                    siparis.setTeslimatPostaKodu(postaKodu);
                }

                String telefon = adres.path("phone").asText(null);
                if (telefon != null && !telefon.isEmpty()) {
                    siparis.setTeslimatTelefon(telefon);
                }
            }

            // Ödeme bilgileri
            JsonNode paymentMethods = orderNode.path("paymentMethods");
            if (paymentMethods.isArray() && paymentMethods.size() > 0) {
                JsonNode firstPayment = paymentMethods.get(0);
                String paymentType = firstPayment.path("type").asText(null);
                if (paymentType != null && !paymentType.isEmpty()) {
                    siparis.setOdemeYontemi(mapPaymentType(paymentType));
                }
                String gatewayName = firstPayment.path("paymentGatewayName").asText(null);
                String gatewayId = firstPayment.path("paymentGatewayId").asText(null);
                if (gatewayName != null && !gatewayName.isEmpty()) {
                    siparis.setOdemeSaglayici(gatewayName);
                } else if (gatewayId != null && !gatewayId.isEmpty()) {
                    siparis.setOdemeSaglayici(gatewayId);
                }
            }
        }

        // ========== 7. KARGO BİLGİLERİ KAYDET ==========
        // Platform paket ID (kargo entegrasyonu için gerekli)
        if (platformPaketId != null && !platformPaketId.isEmpty()) {
            siparis.setPlatformPaketId(platformPaketId);
        }
        if (kargoTakipNo != null) {
            siparis.setKargoTakipNo(kargoTakipNo);
        }
        if (kargoBarkodu != null) {
            siparis.setKargoBarkodu(kargoBarkodu);
        }
        if (kargoFirmaAdi != null && !kargoFirmaAdi.isEmpty() && siparis.getKargoFirma() == null) {
            Optional<KargoFirmasi> kargoOpt = kargoFirmasiRepository.findByFirmaAdiContainingIgnoreCase(kargoFirmaAdi);
            if (kargoOpt.isPresent()) {
                siparis.setKargoFirma(kargoOpt.get());
            } else {
                String mevcutNot = siparis.getDahiliNot();
                String kargoNotu = "Kargo: " + kargoFirmaAdi;
                if (mevcutNot == null || !mevcutNot.contains(kargoNotu)) {
                    siparis.setDahiliNot(mevcutNot != null ? mevcutNot + " | " + kargoNotu : kargoNotu);
                }
            }
        }

        // Kapıda ödeme + Teslim edildi
        if (siparis.getDurum() != null && "TESLIM_EDILDI".equals(siparis.getDurum().getDurumKodu())) {
            String odemeYontemi = siparis.getOdemeYontemi();
            if ("KAPIDA_NAKIT".equals(odemeYontemi) || "KAPIDA_KREDI_KARTI".equals(odemeYontemi)) {
                siparis.setOdemeDurumu("ODENDI");
            }
        }

        // ========== 8. SİPARİŞİ KAYDET ==========
        siparis = siparisRepository.save(siparis);
        final Siparis kaydedilenSiparis = siparis;

        // ========== 9. KALEMLERİ KAYDET ==========
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
                siparisKalemiRepository.flush();
                entityManager.clear();
                log.info("Sipariş #{} mevcut kalemler silindi", orderNumber);
            }

            // Hazırlanan kalemleri kaydet
            for (SiparisKalemiDTO dto : hazirlananKalemler) {
                SiparisKalemi kalem = new SiparisKalemi();
                kalem.setSiparis(kaydedilenSiparis);
                kalem.setSira(dto.getSira());
                kalem.setUrunAdi(dto.getUrunAdi());
                kalem.setSku(dto.getSku());
                kalem.setBarkod(dto.getBarkod());
                kalem.setMiktar(dto.getMiktar());
                kalem.setBirimFiyat(dto.getBirimFiyat());
                kalem.setSatirToplami(dto.getSatirToplami());

                // SKU ile varyant eşleştirme (aktif varyantı öncelikli al)
                boolean skuEslesti = false;
                if (dto.getSku() != null && !dto.getSku().isEmpty()) {
                    Optional<UrunVaryanti> varyantOpt = urunVaryantiRepository.findAktifVaryantBySkuIgnoreCase(dto.getSku());
                    if (varyantOpt.isPresent()) {
                        UrunVaryanti varyant = varyantOpt.get();
                        kalem.setVaryant(varyant);
                        kalem.setUrun(varyant.getUrun());
                        kalem.setBirim(varyant.getBirim());
                        skuEslesti = true;

                        BigDecimal kdvOrani = varyant.getUrun().getKdvOrani();
                        if (kdvOrani != null) {
                            kalem.setKdvOrani(kdvOrani);
                            BigDecimal kdvTutari = dto.getSatirToplami().multiply(kdvOrani)
                                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                            kalem.setKdvTutari(kdvTutari);
                        }
                    }
                }

                // Kalem her durumda kaydedilir
                siparisKalemiRepository.save(kalem);

                // SKU eşleşmediyse sync hatasına kaydet
                if (!skuEslesti && dto.getSku() != null && !dto.getSku().isEmpty()) {
                    syncHataService.hataKaydet(
                            "IKAS",
                            platformSiparisId,
                            orderNumber,
                            "SKU_BULUNAMADI",
                            "Eşleşmeyen SKU: " + dto.getSku() + " | Ürün: " + dto.getUrunAdi(),
                            null
                    );
                    log.warn("İkas #{} - SKU eşleşmedi: {} ({})", orderNumber, dto.getSku(), dto.getUrunAdi());
                }
            }

            // Sipariş toplamlarını güncelle
            siparis.setAraToplam(hesaplananAraToplam);
            siparis.setToplamKalemSayisi(hazirlananKalemler.size());
            siparisRepository.save(siparis);

            log.info("Sipariş #{} için {} kalem kaydedildi", orderNumber, hazirlananKalemler.size());
        }

        // ========== 10. OTOMATİK MÜŞTERİ OLUŞTURMA ==========
        if (yeniKayit) {
            try {
                musteriOlusturmaService.siparisIcinMusteriOlusturVeyaBagla(siparis);
            } catch (Exception e) {
                log.warn("Otomatik müşteri oluşturma hatası (İkas {}): {}", orderNumber, e.getMessage());
            }
        }

        // ========== 11. STOK REZERVASYON ==========
        rezervasyonYonet(siparis, yeniKayit, eskiDurumKodu, yeniDurumKodu, eskiOdemeDurumu);

        // ========== 12. ACTIVITY LOG ==========
        if (yeniKayit) {
            activityLogService.logForSiparis(
                    siparis.getId().longValue(),
                    ActivityEventTypeEnum.ORDER_CREATED,
                    ActivitySourceEnum.IKAS,
                    java.util.Map.of(
                            "siparisNo", siparis.getSiparisNo(),
                            "platformSiparisNo", orderNumber,
                            "kalemSayisi", hazirlananKalemler.size(),
                            "toplamTutar", siparis.getToplamTutar() != null ? siparis.getToplamTutar().toString() : "0"
                    )
            );
        } else if (kalemGuncellemesiGerekli || !yeniDurumKodu.equals(eskiDurumKodu)) {
            activityLogService.logForSiparis(
                    siparis.getId().longValue(),
                    ActivityEventTypeEnum.ORDER_UPDATED,
                    ActivitySourceEnum.IKAS,
                    java.util.Map.of(
                            "siparisNo", siparis.getSiparisNo(),
                            "kalemGuncellendi", kalemGuncellemesiGerekli,
                            "eskiDurum", eskiDurumKodu != null ? eskiDurumKodu : "",
                            "yeniDurum", yeniDurumKodu
                    )
            );
        }

        return true;
    }

    /**
     * Kalem bilgilerini geçici olarak tutan DTO
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    private static class SiparisKalemiDTO {
        private int sira;
        private String urunAdi;
        private String sku;
        private String barkod;
        private BigDecimal miktar;
        private BigDecimal birimFiyat;
        private BigDecimal satirToplami;
    }

    /**
     * Stok rezervasyonunu ve kutu atamasını yönetir
     *
     * Ödeme Yöntemi Bazlı Mantık:
     * - Havale/EFT: Ödeme onaylanana kadar rezervasyon YAPILMAZ
     * - Kredi Kartı/Kapıda Ödeme/Platform: Direkt rezervasyon yapılır
     */
    private void rezervasyonYonet(Siparis siparis, boolean yeniKayit, String eskiDurumKodu, String yeniDurumKodu, String eskiOdemeDurumu) {
        try {
            OdemeYontemiEnum odemeYontemi = OdemeYontemiEnum.fromString(siparis.getOdemeYontemi());

            if (yeniKayit) {
                // Havale/EFT siparişlerinde rezervasyon yapma - ödeme onayı bekle
                if (odemeYontemi.requiresManualPaymentApproval()) {
                    log.info("Havale/EFT siparişi - ödeme onayı bekleniyor, rezervasyon yapılmadı: {}",
                            siparis.getSiparisNo());
                    return;
                }

                // Diğer ödeme yöntemleri için stok rezervasyonu
                if (odemeYontemi.shouldReserveStock()) {
                    // Önce transaction'ı commit et ki sipariş veritabanına yazılsın
                    entityManager.flush();

                    StokRezervasyonService.RezervasyonSonucu sonuc =
                            stokRezervasyonService.siparisiRezerveEt(siparis.getId());

                    // Stok yetersiz kalemler logla (üretim emirleri emirleriSenkronize() ile yönetilir)
                    if (sonuc.stokYetersizKalemler != null && !sonuc.stokYetersizKalemler.isEmpty()) {
                        log.info("Stok yetersiz {} kalem tespit edildi (İkas {}), üretim emirleri senkronizasyonda güncellenecek",
                                sonuc.stokYetersizKalemler.size(), siparis.getSiparisNo());
                    }

                    // Kutu ataması ve rezervasyonu (zaten kutu varsa tekrar atama)
                    if (siparisKutusuRepository.findBySiparisId(siparis.getId()).isEmpty()) {
                        try {
                            kutuAtamaService.sipariseKutuAta(siparis.getId());
                            log.info("Yeni İkas siparişi için stok ve kutu rezerve edildi: {} ({})",
                                    siparis.getSiparisNo(), odemeYontemi.getDisplayName());
                        } catch (Exception e) {
                            log.warn("Kutu atama hatası (İkas {}): {}", siparis.getSiparisNo(), e.getMessage());
                        }
                    }
                }
            } else if (eskiDurumKodu != null) {
                // Havale/EFT ödemesi platform üzerinden onaylandıysa stok rezervasyonu yap
                if (odemeYontemi.requiresManualPaymentApproval()
                        && "BEKLEMEDE".equals(eskiOdemeDurumu)
                        && "ODENDI".equals(siparis.getOdemeDurumu())) {
                    entityManager.flush();
                    StokRezervasyonService.RezervasyonSonucu sonuc =
                            stokRezervasyonService.siparisiRezerveEt(siparis.getId());
                    log.info("Havale ödemesi onaylandı (platform), stok rezerve edildi: {} (rezerve: {}, bekleyen: {})",
                            siparis.getSiparisNo(),
                            sonuc.rezerveEdilenKalem,
                            sonuc.bekleyenKalem);

                    // Kutu ataması (zaten kutu varsa tekrar atama)
                    if (siparisKutusuRepository.findBySiparisId(siparis.getId()).isEmpty()) {
                        try {
                            kutuAtamaService.sipariseKutuAta(siparis.getId());
                        } catch (Exception e) {
                            log.warn("Kutu atama hatası (havale onay {}): {}", siparis.getSiparisNo(), e.getMessage());
                        }
                    }
                }

                // Mevcut sipariş durum değişikliği
                if (("KARGODA".equals(yeniDurumKodu) || "TESLIM_EDILDI".equals(yeniDurumKodu))
                        && !yeniDurumKodu.equals(eskiDurumKodu)) {
                    stokRezervasyonService.siparisRezervasyonuTamamla(siparis.getId());
                    log.info("Sipariş kargoya verildi, rezervasyon tamamlandı: {}", siparis.getSiparisNo());
                } else if (("IPTAL".equals(yeniDurumKodu) || "IADE".equals(yeniDurumKodu))
                        && !yeniDurumKodu.equals(eskiDurumKodu)) {
                    stokRezervasyonService.siparisRezervasyonuIptalEt(siparis.getId());
                    log.info("Sipariş iptal/iade, rezervasyon serbest bırakıldı: {}", siparis.getSiparisNo());
                }
            }
        } catch (Exception e) {
            log.error("Rezervasyon yönetimi hatası (sipariş: {}): {}", siparis.getSiparisNo(), e.getMessage());
        }
    }

    // ========== YARDIMCI METODLAR ==========

    private String siparisNoUret() {
        String tarih = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
        String rastgele = String.format("%05d", (int) (Math.random() * 100000));
        return "SIP-" + tarih + "-" + rastgele;
    }

    private String mapPaymentType(String ikasType) {
        if (ikasType == null) return null;
        switch (ikasType.toUpperCase()) {
            case "CREDIT_CARD": return "KREDI_KARTI";
            case "MONEY_ORDER": return "HAVALE_EFT";
            case "CASH_ON_DELIVERY": return "KAPIDA_NAKIT";
            case "CREDIT_CARD_ON_DELIVERY": return "KAPIDA_KREDI_KARTI";
            default: return ikasType;
        }
    }

    private String mapPaymentStatus(String status) {
        if (status == null || status.isEmpty()) return "BEKLEMEDE";
        switch (status.toUpperCase()) {
            case "PAID": return "ODENDI";
            case "AWAITING_PAYMENT":
            case "PENDING": return "BEKLEMEDE";
            case "REFUNDED": return "IADE_EDILDI";
            case "PARTIALLY_REFUNDED": return "KISMI_IADE";
            case "FAILED": return "BASARISIZ";
            default: return "BEKLEMEDE";
        }
    }

    private String mapIkasStatus(String status, String pkgStatus, String payStatus, String kargoTakipNo) {
        // Kısmi iade kontrolü
        boolean kismiIade = (status != null && status.toUpperCase().contains("PARTIALLY_REFUNDED"))
                || (pkgStatus != null && pkgStatus.toUpperCase().contains("PARTIALLY_REFUNDED"));

        if (kismiIade) {
            return (kargoTakipNo != null && !kargoTakipNo.isEmpty()) ? "KARGODA" : "BEKLEMEDE";
        }

        // Package status
        if (pkgStatus != null && !pkgStatus.isEmpty()) {
            switch (pkgStatus.toUpperCase()) {
                case "FULFILLED":
                case "DELIVERED": return "TESLIM_EDILDI";
                case "READY_FOR_SHIPMENT":
                case "SHIPPED": return "KARGODA";
                case "PROCESSING":
                case "PREPARING": return "HAZIRLANIYOR";
                case "UNFULFILLED": return "BEKLEMEDE";
            }
        }

        // Payment status
        if (payStatus != null && "REFUNDED".equalsIgnoreCase(payStatus)) {
            return "IADE";
        }

        // Ana status
        if (status != null && !status.isEmpty()) {
            switch (status.toUpperCase()) {
                case "CREATED":
                case "CONFIRMED":
                case "AWAITING_PAYMENT":
                case "PAID": return "BEKLEMEDE";
                case "PROCESSING": return "HAZIRLANIYOR";
                case "SHIPPED": return "KARGODA";
                case "DELIVERED": return "TESLIM_EDILDI";
                case "CANCELLED": return "IPTAL";
            }
        }

        return "BEKLEMEDE";
    }
}
