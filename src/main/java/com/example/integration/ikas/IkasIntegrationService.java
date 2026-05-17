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
package com.example.integration.ikas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.integration.entity.SatisKanali;
import com.example.integration.repository.SatisKanaliRepository;
import com.example.integration.service.NotificationService;
import com.example.integration.service.SyncHataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * İkas API Entegrasyon Servisi
 *
 * İkas'tan siparişleri çeker ve veritabanına kaydeder.
 * GraphQL API kullanır, OAuth 2.0 token ile kimlik doğrulama yapar.
 *
 * v5.0 Güncellemeler:
 * - Pagination desteği (hasNext, page kontrolü ile tüm siparişler çekilir)
 * - Self-invocation sorunu çözüldü (ayrı servis: IkasSiparisKaydediciService)
 * - Atomik kayıt (önce kalemler hazırlanır, sonra birlikte kaydedilir)
 *
 * @version 5.0 - Pagination + Atomik Kayıt
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class IkasIntegrationService {

    private final SatisKanaliRepository satisKanaliRepository;
    private final IkasSiparisKaydediciService siparisKaydedici;
    private final SyncHataService syncHataService;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;
    private final IkasTokenManager tokenManager;

    // API sabitleri
    private static final int SAYFA_LIMIT = 200;  // İkas max 200
    private static final int MAX_SAYFA_LIMITI = 100;  // Sonsuz döngü önleme

    /**
     * İkas'tan siparişleri çeker ve kaydeder.
     * Pagination ile tüm sayfalar otomatik çekilir.
     *
     * @param gunGeri Kaç gün geriye gidilecek
     * @return İşlenen sipariş sayısı
     */
    public int siparisleriCek(int gunGeri) {
        // İkas kanalını bul
        Optional<SatisKanali> kanalOpt = satisKanaliRepository.findByKanalKodu("IKAS");
        if (kanalOpt.isEmpty()) {
            log.error("IKAS kanalı tanımlı değil! Önce satis_kanallari tablosuna ekleyin.");
            return 0;
        }

        SatisKanali kanal = kanalOpt.get();

        // API bilgilerini kontrol et
        if (kanal.getApiAnahtar() == null || kanal.getApiGizliAnahtar() == null || kanal.getMagazaId() == null) {
            log.error("İkas API bilgileri eksik! api_anahtar, api_gizli_anahtar, magaza_id alanlarını doldurun.");
            return 0;
        }

        log.info("İkas Senkronizasyon Başladı (Son {} gün)", gunGeri);

        try {
            // Token al veya mevcut token'ı kullan
            String accessToken = tokenManager.ensureValidToken(kanal);

            // Tarih hesaplama (Unix Timestamp ms)
            long sinceMs = System.currentTimeMillis() - ((long) gunGeri * 24L * 60L * 60L * 1000L);

            WebClient webClient = WebClient.builder()
                    .baseUrl("https://api.myikas.com/api/v1/admin/graphql")
                    .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                    .build();

            int toplamIslenen = 0;
            int basarisiz = 0;
            int atlanan = 0;
            int sayfa = 1;
            boolean devamEt = true;

            // ========== PAGİNATİON DÖNGÜSÜ ==========
            while (devamEt) {
                log.info("Sayfa {} çekiliyor...", sayfa);

                String query = buildOrderQuery(sinceMs, sayfa, SAYFA_LIMIT);

                Map<String, Object> requestBody = new HashMap<>();
                requestBody.put("query", query);

                JsonNode response = webClient.post()
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(requestBody)
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .block();

                if (response == null || !response.has("data") || !response.get("data").has("listOrder")) {
                    log.warn("İkas yanıtı boş veya format hatalı (sayfa {})", sayfa);
                    break;
                }

                JsonNode listOrderNode = response.get("data").get("listOrder");
                JsonNode ordersNode = listOrderNode.path("data");

                // Pagination bilgileri
                boolean hasNext = listOrderNode.path("hasNext").asBoolean(false);
                int count = listOrderNode.path("count").asInt(0);
                int currentPage = listOrderNode.path("page").asInt(sayfa);

                if (!ordersNode.isArray() || ordersNode.size() == 0) {
                    log.info("Sayfa {}: Sipariş bulunamadı", sayfa);
                    break;
                }

                log.info("Sayfa {}: {} sipariş bulundu (Toplam: {})", sayfa, ordersNode.size(), count);

                // Her siparişi işle
                for (JsonNode orderNode : ordersNode) {
                    String orderNumber = orderNode.path("orderNumber").asText("?");
                    try {
                        boolean islendi = siparisKaydedici.tekSiparisKaydet(orderNode, kanal);
                        if (islendi) {
                            toplamIslenen++;
                        } else {
                            atlanan++;
                        }
                    } catch (Exception e) {
                        basarisiz++;
                        log.error("Sipariş #{} kaydedilemedi: {}", orderNumber, e.getMessage());

                        // Hatayı kaydet
                        try {
                            String platformSiparisId = orderNode.path("id").asText();
                            String hataTipi = syncHataService.hataTipiBelirle(e);
                            String hamVeri = objectMapper.writeValueAsString(orderNode);
                            syncHataService.hataKaydet("IKAS", platformSiparisId, orderNumber,
                                    hataTipi, e.getMessage(), hamVeri);
                        } catch (Exception ex) {
                            log.error("Hata kaydedilemedi: {}", ex.getMessage());
                        }
                    }
                }

                // Sonraki sayfa var mı?
                if (hasNext) {
                    sayfa++;
                    // Güvenlik: Sonsuz döngü önleme
                    if (sayfa > MAX_SAYFA_LIMITI) {
                        log.warn("{} sayfa limitine ulaşıldı, döngü sonlandırılıyor", MAX_SAYFA_LIMITI);
                        break;
                    }
                } else {
                    devamEt = false;
                }
            }

            // Son senkronizasyon tarihini güncelle
            kanal.setSonSiparisSenkron(LocalDateTime.now());
            kanal.setSonSenkronHatasi(basarisiz > 0 ? basarisiz + " sipariş başarısız" : null);
            satisKanaliRepository.save(kanal);

            log.info("İkas Senkronizasyon Tamamlandı:");
            log.info("   Toplam sayfa: {}", sayfa);
            log.info("   İşlenen: {}", toplamIslenen);
            log.info("   Atlanan (final durum): {}", atlanan);
            log.info("   Başarısız: {}", basarisiz);

            // WebSocket bildirimi gönder
            if (toplamIslenen > 0) {
                notificationService.notifySyncComplete("ikas", toplamIslenen);
            }

            return toplamIslenen;

        } catch (Exception e) {
            log.error("İkas API Hatası: {}", e.getMessage(), e);
            kanal.setSonSenkronHatasi(e.getMessage());
            satisKanaliRepository.save(kanal);
            return 0;
        }
    }

    /**
     * GraphQL sorgusu oluşturur.
     *
     * İkas API pagination formatı:
     * - page: Sayfa numarası (1'den başlar)
     * - limit: Sayfa başına kayıt (max 200)
     *
     * Response'da hasNext, count, page alanları döner.
     */
    private String buildOrderQuery(long sinceMs, int page, int limit) {
        return "{ listOrder(" +
                "sort: \"updatedAt:desc\", " +
                "updatedAt: { gte: " + sinceMs + " }, " +
                "pagination: { page: " + page + ", limit: " + limit + " }" +
                ") { " +
                "count hasNext page limit " +
                "data { " +
                "id orderNumber totalFinalPrice orderedAt status orderPackageStatus orderPaymentStatus " +
                "customer { firstName lastName email phone } " +
                "shippingAddress { firstName lastName addressLine1 phone city { name } district { name } postalCode } " +
                "orderLineItems { quantity price finalPrice variant { id name sku weight barcodeList productId } } " +
                "orderPackages { id orderPackageNumber trackingInfo { trackingNumber trackingLink barcode cargoCompany } } " +
                "paymentMethods { type price paymentGatewayId paymentGatewayName } " +
                "} } }";
    }

}
