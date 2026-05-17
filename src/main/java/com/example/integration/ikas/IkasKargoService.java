/*
 * Adapted from a production Spring Boot ERP system.
 *
 * NOTE: This service depends on entity classes (Siparis, SatisKanali, etc.)
 * and repositories. You need to provide your own implementations matching
 * the method signatures used here.
 *
 * The MAGAZA_KODU constant below should be configured to match your İkas store code.
 *
 * Original author: Mehmet Eren Erdal <m.erenerdal@sevinclidoga.com>
 * License: MIT
 */
package com.example.integration.ikas;

import com.fasterxml.jackson.databind.JsonNode;
import com.example.integration.entity.SatisKanali;
import com.example.integration.entity.Siparis;
import com.example.integration.exception.IntegrationException;
import com.example.integration.repository.SatisKanaliRepository;
import com.example.integration.repository.SiparisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * İkas Kargo Entegrasyon Servisi
 *
 * İkas siparişlerini "Kargoya Hazır" durumuna çeker ve
 * kargo etiketlerini oluşturur.
 *
 * Barkod Formatı: IK-{MAGAZA_KODU}-{platform_siparis_no}-1
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class IkasKargoService {

    private final SiparisRepository siparisRepository;
    private final SatisKanaliRepository satisKanaliRepository;
    private final IkasTokenManager tokenManager;

    // Sabit değerler
    private static final String MAGAZA_KODU = ""; // TODO: configure - İkas mağaza kodunuz
    private static final String IKAS_GRAPHQL_URL = "https://api.myikas.com/api/v1/admin/graphql";

    // Retry ayarları
    private static final int MAX_RETRY = 3;
    private static final long RETRY_BEKLEME_MS = 2000; // 2 saniye
    private static final Duration API_TIMEOUT = Duration.ofMinutes(2);

    /**
     * Siparişi İkas'ta "Kargoya Hazır" durumuna çeker.
     *
     * @param siparisId Sipariş ID
     * @return Oluşturulan barkod
     * @throws IntegrationException Hata durumunda
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String kargoyaHazirYap(Integer siparisId) {
        Siparis siparis = siparisRepository.findById(siparisId)
                .orElseThrow(() -> new IllegalArgumentException("Sipariş bulunamadı: " + siparisId));

        // Sipariş İkas'tan mı kontrol et
        if (siparis.getKanal() == null || !"IKAS".equals(siparis.getKanal().getKanalKodu())) {
            throw new IllegalArgumentException("Bu sipariş İkas siparişi değil!");
        }

        // Platform bilgileri kontrolü
        if (siparis.getPlatformSiparisId() == null || siparis.getPlatformSiparisId().isEmpty()) {
            throw new IntegrationException("IKAS", "MISSING_DATA", "Platform sipariş ID'si bulunamadı");
        }

        // Her zaman İkas'tan güncel paket durumunu çek (iptal edilmiş paket olabilir)
        // Bu sayede eski/geçersiz paket ID'si varsa yenisi alınır
        log.info("İkas'tan güncel paket bilgisi çekiliyor...");
        String guncelPaketId = paketIdCek(siparis);
        if (guncelPaketId == null || guncelPaketId.isEmpty()) {
            throw new IntegrationException("IKAS", "MISSING_DATA",
                "Platform paket ID'si bulunamadı. Sipariş İkas'ta UNFULFILLED durumunda olabilir.");
        }

        // Paket ID değiştiyse güncelle
        if (!guncelPaketId.equals(siparis.getPlatformPaketId())) {
            log.info("Paket ID güncellendi: {} -> {}", siparis.getPlatformPaketId(), guncelPaketId);
            siparis.setPlatformPaketId(guncelPaketId);
        }

        // Zaten kargoya verilmiş mi kontrol et (bizim sistemde)
        if ("GONDERILDI".equals(siparis.getKargoIslemDurumu()) ||
            "BASARILI".equals(siparis.getKargoIslemDurumu())) {
            log.warn("Sipariş #{} zaten kargoya hazır durumunda, İkas'tan güncel barkod kontrol ediliyor...", siparis.getSiparisNo());
            // İkas'tan güncel barkodu çek - değişmiş olabilir
            String guncelBarkod = getTrackingInfoFromIkas(siparis);
            if (guncelBarkod != null && !guncelBarkod.isEmpty() && !guncelBarkod.equals(siparis.getKargoBarkodu())) {
                log.info("Barkod güncellendi: {} -> {}", siparis.getKargoBarkodu(), guncelBarkod);
                siparis.setKargoBarkodu(guncelBarkod);
                siparisRepository.save(siparis);
            }
            return siparis.getKargoBarkodu();
        }

        try {
            String ikasBarkod = null;

            // İkas'ta zaten READY_FOR_SHIPMENT ise API çağrısı yapma
            if ("ZATEN_HAZIR".equals(siparis.getKargoIslemDurumu())) {
                log.info("Sipariş İkas'ta zaten hazır, updateOrderPackageStatus atlanıyor");
                // Barkodu İkas'tan çekmeyi dene
                ikasBarkod = getTrackingInfoFromIkas(siparis);
            } else {
                // İkas API'ye updateOrderPackageStatus gönder ve yanıttan barkodu al
                ikasBarkod = updateOrderPackageStatus(siparis);
            }

            // İkas'tan barkod geldiyse onu kullan, gelmediyse kendimiz oluştur
            // İkas barkodunda kontrol karakterleri olabiliyor (\n vb.), temizle
            String barkod;
            if (ikasBarkod != null && !ikasBarkod.replaceAll("[\\p{Cntrl}]", "").trim().isEmpty()) {
                barkod = ikasBarkod.replaceAll("[\\p{Cntrl}]", "").trim();
                log.info("İkas'tan gelen barkod kullanılıyor: {}", barkod);
            } else {
                barkod = barkodOlustur(siparis.getPlatformSiparisNo());
                log.info("İkas barkod vermedi, kendimiz oluşturduk: {}", barkod);
            }

            // Siparişi güncelle - KARGOYA_HAZIR durumu (etiket basılana kadar listede kalır)
            siparis.setKargoBarkodu(barkod);
            siparis.setKargoIslemDurumu("KARGOYA_HAZIR");
            siparis.setKargoIslemZamani(LocalDateTime.now());
            siparis.setKargoHataMesaji(null);
            siparisRepository.save(siparis);

            log.info("Sipariş #{} kargoya hazır yapıldı. Barkod: {}", siparis.getSiparisNo(), barkod);
            return barkod;

        } catch (Exception e) {
            // Hatayı kaydet
            siparis.setKargoIslemDurumu("BASARISIZ");
            siparis.setKargoIslemZamani(LocalDateTime.now());
            siparis.setKargoHataMesaji(e.getMessage());
            siparisRepository.save(siparis);

            log.error("Sipariş #{} kargoya hazır yapılamadı: {}", siparis.getSiparisNo(), e.getMessage());
            throw new IntegrationException("IKAS", "KARGO_ERROR", "Kargoya hazır yapılamadı: " + e.getMessage(), e);
        }
    }

    /**
     * Barkod formatı: IK-{MAGAZA_KODU}-{platform_siparis_no}-1
     *
     * @param platformSiparisNo İkas sipariş numarası
     * @return Oluşturulan barkod
     */
    public String barkodOlustur(String platformSiparisNo) {
        return String.format("IK-%s-%s-1", MAGAZA_KODU, platformSiparisNo);
    }

    /**
     * İkas API'ye updateOrderPackageStatus mutation gönderir.
     * Yanıttan trackingInfo bilgilerini alıp siparişe kaydeder.
     *
     * @param siparis Sipariş
     * @return İkas'tan gelen barkod (varsa)
     */
    private String updateOrderPackageStatus(Siparis siparis) {
        SatisKanali kanal = getIkasKanal();
        String accessToken = tokenManager.ensureValidToken(kanal);

        String mutation = buildUpdatePackageStatusMutation(
                siparis.getPlatformSiparisId(),
                siparis.getPlatformPaketId()
        );

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("query", mutation);

        // Retry mekanizması ile API çağrısı
        JsonNode response = iksaApiCagriWithRetry(accessToken, requestBody, "updateOrderPackageStatus");

        // Hata kontrolü
        if (response.has("errors")) {
            String errorMessage = response.path("errors").toString();
            log.error("İkas API hatası: {}", errorMessage);
            throw new IntegrationException("IKAS", "API_ERROR", "İkas API hatası: " + errorMessage);
        }

        log.info("İkas updateOrderPackageStatus yanıtı: {}", response);

        // Yanıttan trackingInfo bilgilerini çıkar
        String ikasBarkod = null;
        try {
            JsonNode data = response.path("data").path("updateOrderPackageStatus");
            JsonNode packages = data.path("orderPackages");
            if (packages.isArray() && packages.size() > 0) {
                JsonNode trackingInfo = packages.get(0).path("trackingInfo");
                ikasBarkod = trackingInfo.path("barcode").asText(null);
                String trackingNumber = trackingInfo.path("trackingNumber").asText(null);
                String cargoCompany = trackingInfo.path("cargoCompany").asText(null);

                log.info("İkas trackingInfo - barkod: {}, takipNo: {}, kargoFirması: {}",
                        ikasBarkod, trackingNumber, cargoCompany);

                // Takip numarası varsa kaydet
                if (trackingNumber != null && !trackingNumber.isEmpty()) {
                    siparis.setKargoTakipNo(trackingNumber);
                }
            }
        } catch (Exception e) {
            log.warn("trackingInfo parse hatası: {}", e.getMessage());
        }

        return ikasBarkod;
    }

    /**
     * İkas API çağrısını retry mekanizması ile yapar.
     * Connection reset, timeout gibi geçici hatalarda MAX_RETRY kadar tekrar dener.
     */
    private JsonNode iksaApiCagriWithRetry(String accessToken, Map<String, Object> requestBody, String islemAdi) {
        WebClient webClient = WebClient.builder()
                .baseUrl(IKAS_GRAPHQL_URL)
                .build();

        Exception sonHata = null;
        for (int deneme = 1; deneme <= MAX_RETRY; deneme++) {
            try {
                JsonNode response = webClient.post()
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(requestBody)
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .timeout(API_TIMEOUT)
                        .block();

                if (response == null) {
                    throw new IntegrationException("IKAS", "API_ERROR", "İkas API yanıt vermedi");
                }

                if (deneme > 1) {
                    log.info("{} - {}. denemede başarılı oldu", islemAdi, deneme);
                }
                return response;

            } catch (Exception e) {
                sonHata = e;
                String hataMesaji = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                log.warn("{} - Deneme {}/{} başarısız: {}", islemAdi, deneme, MAX_RETRY, hataMesaji);

                if (deneme < MAX_RETRY) {
                    try {
                        Thread.sleep(RETRY_BEKLEME_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        // Tüm denemeler başarısız
        log.error("{} - {} deneme sonrası başarısız", islemAdi, MAX_RETRY);
        throw new IntegrationException("IKAS", "API_ERROR",
                islemAdi + " başarısız (" + MAX_RETRY + " deneme): " + (sonHata != null ? sonHata.getMessage() : "Bilinmeyen hata"), sonHata);
    }

    /**
     * İkas'tan mevcut trackingInfo bilgisini çeker (sipariş zaten READY_FOR_SHIPMENT ise).
     */
    private String getTrackingInfoFromIkas(Siparis siparis) {
        try {
            SatisKanali kanal = getIkasKanal();
            String accessToken = tokenManager.ensureValidToken(kanal);

            String query = "{ listOrder(id: { eq: \"" + siparis.getPlatformSiparisId() + "\" }) { " +
                    "data { " +
                    "orderPackages { " +
                    "trackingInfo { barcode trackingNumber cargoCompany } " +
                    "} " +
                    "} " +
                    "} }";

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("query", query);

            // Retry mekanizması ile çağır
            JsonNode response = iksaApiCagriWithRetry(accessToken, requestBody, "getTrackingInfo");

            if (response.has("data")) {
                JsonNode data = response.path("data").path("listOrder").path("data");
                if (data.isArray() && data.size() > 0) {
                    JsonNode packages = data.get(0).path("orderPackages");
                    if (packages.isArray() && packages.size() > 0) {
                        JsonNode trackingInfo = packages.get(0).path("trackingInfo");
                        String barcode = trackingInfo.path("barcode").asText(null);
                        String trackingNumber = trackingInfo.path("trackingNumber").asText(null);

                        log.info("İkas'tan mevcut trackingInfo - barkod: {}, takipNo: {}", barcode, trackingNumber);

                        if (trackingNumber != null && !trackingNumber.isEmpty()) {
                            siparis.setKargoTakipNo(trackingNumber);
                        }

                        return barcode;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("getTrackingInfoFromIkas hatası: {}", e.getMessage());
        }
        return null;
    }

    /**
     * updateOrderPackageStatus GraphQL mutation oluşturur.
     */
    private String buildUpdatePackageStatusMutation(String orderId, String packageId) {
        return "mutation { " +
                "updateOrderPackageStatus(input: { " +
                "orderId: \"" + orderId + "\", " +
                "packages: [{ " +
                "packageId: \"" + packageId + "\", " +
                "status: READY_FOR_SHIPMENT " +
                "}] " +
                "}) { " +
                "id orderPackageStatus " +
                "orderPackages { " +
                "id orderPackageNumber " +
                "trackingInfo { barcode trackingNumber cargoCompany } " +
                "} " +
                "} " +
                "}";
    }

    /**
     * Sipariş için paket ID'yi İkas'tan çeker.
     * Eğer paket yoksa (UNFULFILLED sipariş), fulfillOrder ile paket oluşturur.
     */
    private String paketIdCek(Siparis siparis) {
        try {
            SatisKanali kanal = getIkasKanal();
            String accessToken = tokenManager.ensureValidToken(kanal);

            // Önce sipariş detaylarını çek (orderPackages ve orderLineItems)
            String query = "{ listOrder(id: { eq: \"" + siparis.getPlatformSiparisId() + "\" }) { " +
                    "data { " +
                    "id orderPackageStatus " +
                    "orderPackages { id orderPackageNumber } " +
                    "orderLineItems { id quantity } " +
                    "} " +
                    "} }";

            log.info("Sipariş detayları çekiliyor - platformSiparisId: {}", siparis.getPlatformSiparisId());

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("query", query);

            // Retry mekanizması ile çağır
            JsonNode response = iksaApiCagriWithRetry(accessToken, requestBody, "paketIdCek");

            log.info("İkas yanıtı: {}", response);

            if (response != null && response.has("data")) {
                JsonNode data = response.path("data").path("listOrder").path("data");
                if (data.isArray() && data.size() > 0) {
                    JsonNode order = data.get(0);
                    String packageStatus = order.path("orderPackageStatus").asText("");
                    JsonNode packages = order.path("orderPackages");

                    log.info("İkas sipariş durumu: {}, paket sayısı: {}",
                            packageStatus, packages.isArray() ? packages.size() : 0);

                    // Paket varsa ID'sini döndür
                    if (packages.isArray() && packages.size() > 0) {
                        String paketId = packages.get(0).path("id").asText(null);
                        log.info("Mevcut paket ID bulundu: {} (durum: {})", paketId, packageStatus);

                        // Eğer sipariş zaten READY_FOR_SHIPMENT veya SHIPPED ise
                        // updateOrderPackageStatus çağırmaya gerek yok, direkt barkod oluştur
                        if ("READY_FOR_SHIPMENT".equals(packageStatus) || "SHIPPED".equals(packageStatus)) {
                            log.info("Sipariş zaten {} durumunda, paket güncellemeye gerek yok", packageStatus);
                            // Siparişe flag koy ki updateOrderPackageStatus çağrılmasın
                            siparis.setKargoIslemDurumu("ZATEN_HAZIR");
                        }

                        return paketId;
                    }

                    // Paket yok - fulfillOrder ile yeni paket oluştur
                    // (iptal edilmiş paket varsa veya hiç paket oluşturulmamışsa)
                    log.info("Paket bulunamadı (durum: {}), fulfillOrder ile yeni paket oluşturulacak...", packageStatus);
                    JsonNode lineItems = order.path("orderLineItems");
                    if (lineItems.isArray() && lineItems.size() > 0) {
                        return fulfillOrderAndGetPackageId(siparis.getPlatformSiparisId(), lineItems, accessToken);
                    } else {
                        log.warn("orderLineItems boş - sipariş kalemleri İkas'tan alınamadı");
                    }
                } else {
                    log.warn("listOrder.data boş veya yok");
                }
            } else {
                log.warn("İkas yanıtında data yok: {}", response);
            }

            return null;
        } catch (Exception e) {
            log.error("Paket ID çekme hatası: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * fulfillOrder mutation ile siparişi paketler ve paket ID döndürür.
     *
     * @param orderId Sipariş UUID
     * @param lineItems Sipariş kalemleri (id ve quantity)
     * @param accessToken İkas access token
     * @return Oluşturulan paket ID
     */
    private String fulfillOrderAndGetPackageId(String orderId, JsonNode lineItems, String accessToken) {
        // Lines array oluştur
        StringBuilder linesBuilder = new StringBuilder();
        for (int i = 0; i < lineItems.size(); i++) {
            JsonNode item = lineItems.get(i);
            String lineItemId = item.path("id").asText();
            int quantity = item.path("quantity").asInt(1);

            if (i > 0) linesBuilder.append(", ");
            linesBuilder.append("{ orderLineItemId: \"").append(lineItemId)
                       .append("\", quantity: ").append(quantity).append(" }");
        }

        String mutation = "mutation { " +
                "fulfillOrder(input: { " +
                "orderId: \"" + orderId + "\", " +
                "lines: [" + linesBuilder.toString() + "] " +
                "}) { " +
                "id orderPackageStatus " +
                "orderPackages { id orderPackageNumber } " +
                "} " +
                "}";

        log.info("fulfillOrder mutation: {}", mutation);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("query", mutation);

        // Retry mekanizması ile çağır
        JsonNode response = iksaApiCagriWithRetry(accessToken, requestBody, "fulfillOrder");

        log.info("fulfillOrder yanıtı: {}", response);

        if (response.has("errors")) {
            String errorMessage = response.path("errors").toString();
            throw new IntegrationException("IKAS", "FULFILL_ERROR", "fulfillOrder hatası: " + errorMessage);
        }

        // Paket ID'yi çıkar
        JsonNode fulfillData = response.path("data").path("fulfillOrder");
        JsonNode packages = fulfillData.path("orderPackages");

        if (packages.isArray() && packages.size() > 0) {
            String paketId = packages.get(0).path("id").asText(null);
            String paketNo = packages.get(0).path("orderPackageNumber").asText("");
            log.info("fulfillOrder başarılı! Paket ID: {}, Paket No: {}", paketId, paketNo);
            return paketId;
        }

        throw new IntegrationException("IKAS", "FULFILL_ERROR", "fulfillOrder sonrası paket ID bulunamadı");
    }

    /**
     * İkas satış kanalını getirir.
     */
    private SatisKanali getIkasKanal() {
        return satisKanaliRepository.findByKanalKodu("IKAS")
                .orElseThrow(() -> new IntegrationException("IKAS", "CONFIG_ERROR", "İkas kanalı tanımlı değil"));
    }

    /**
     * Toplu kargoya hazırlama.
     *
     * @param siparisIds Sipariş ID listesi
     * @return Başarılı/başarısız sayıları
     */
    @Transactional
    public Map<String, Integer> topluKargoyaHazirla(List<Integer> siparisIds) {
        int basarili = 0;
        int basarisiz = 0;

        for (Integer siparisId : siparisIds) {
            try {
                kargoyaHazirYap(siparisId);
                basarili++;
            } catch (Exception e) {
                log.error("Sipariş {} kargoya hazırlanamadı: {}", siparisId, e.getMessage());
                basarisiz++;
            }
        }

        Map<String, Integer> sonuc = new HashMap<>();
        sonuc.put("basarili", basarili);
        sonuc.put("basarisiz", basarisiz);
        return sonuc;
    }

    /**
     * Sipariş için kargo durumunu kontrol eder.
     * KargoKontrolService tarafından kullanılır.
     *
     * @param siparis Sipariş
     * @return true: başarılı, false: hata veya bekleniyor
     */
    public boolean kargoDurumKontrol(Siparis siparis) {
        try {
            SatisKanali kanal = getIkasKanal();
            String accessToken = tokenManager.ensureValidToken(kanal);

            String query = "{ listOrder(id: { eq: \"" + siparis.getPlatformSiparisId() + "\" }) { " +
                    "data { " +
                    "id orderPackageStatus " +
                    "orderPackages { " +
                    "id trackingInfo { barcode trackingNumber cargoCompany } " +
                    "} " +
                    "} " +
                    "} }";

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("query", query);

            JsonNode response = iksaApiCagriWithRetry(accessToken, requestBody, "kargoDurumKontrol");

            if (response.has("data")) {
                JsonNode data = response.path("data").path("listOrder").path("data");
                if (data.isArray() && data.size() > 0) {
                    JsonNode order = data.get(0);
                    String packageStatus = order.path("orderPackageStatus").asText("");

                    // READY_FOR_SHIPMENT veya SHIPPED durumunda başarılı
                    if ("READY_FOR_SHIPMENT".equals(packageStatus) || "SHIPPED".equals(packageStatus)) {
                        // Tracking info kontrolü
                        JsonNode packages = order.path("orderPackages");
                        if (packages.isArray() && packages.size() > 0) {
                            JsonNode trackingInfo = packages.get(0).path("trackingInfo");
                            String barcode = trackingInfo.path("barcode").asText(null);
                            String trackingNumber = trackingInfo.path("trackingNumber").asText(null);

                            // Barcode veya tracking number varsa başarılı
                            if ((barcode != null && !barcode.isEmpty()) ||
                                (trackingNumber != null && !trackingNumber.isEmpty())) {
                                // Kargo takip numarasını güncelle
                                if (trackingNumber != null && !trackingNumber.isEmpty()) {
                                    siparis.setKargoTakipNo(trackingNumber);
                                }
                                // Barkod değişmişse güncelle (İkas tarafında -1 -> -2 olmuş olabilir)
                                if (barcode != null && !barcode.isEmpty() && !barcode.equals(siparis.getKargoBarkodu())) {
                                    log.info("Barkod güncellendi (polling): {} -> {}", siparis.getKargoBarkodu(), barcode);
                                    siparis.setKargoBarkodu(barcode);
                                }
                                return true;
                            }
                        }
                    }
                }
            }

            return false;

        } catch (Exception e) {
            log.error("Kargo durum kontrolü başarısız (sipariş {}): {}", siparis.getSiparisNo(), e.getMessage());
            return false;
        }
    }
}
