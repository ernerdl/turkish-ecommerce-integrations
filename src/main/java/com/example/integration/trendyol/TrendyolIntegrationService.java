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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.integration.entity.SatisKanali;
import com.example.integration.entity.Siparis;
import com.example.integration.entity.SiparisKalemi;
import com.example.integration.repository.SatisKanaliRepository;
import com.example.integration.repository.SiparisKalemiRepository;
import com.example.integration.service.NotificationService;
import com.example.integration.service.SyncHataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Trendyol API Entegrasyon Servisi
 *
 * Trendyol'dan siparişleri çeker ve veritabanına kaydeder.
 * SKU üzerinden ürün/varyant eşleştirmesi yapar.
 *
 * v5.0 Güncellemeler:
 * - Self-invocation sorunu çözüldü (ayrı servis: TrendyolSiparisKaydediciService)
 * - Atomik kayıt (önce kalemler hazırlanır, sonra birlikte kaydedilir)
 * - Her sipariş bağımsız transaction'da işlenir
 *
 * @version 5.0 - Atomik Kayıt
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TrendyolIntegrationService {

    private final SatisKanaliRepository satisKanaliRepository;
    private final SiparisKalemiRepository siparisKalemiRepository;
    private final TrendyolSiparisKaydediciService siparisKaydedici;
    private final SyncHataService syncHataService;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    private static final String BASE_URL = "https://api.trendyol.com/sapigw/suppliers";
    private static final String INTEGRATION_URL = "https://apigw.trendyol.com/integration/order/sellers";
    private static final int SAYFA_BOYUTU = 200;
    private final AtomicBoolean syncDevamEdiyor = new AtomicBoolean(false);

    /**
     * Trendyol'dan siparişleri çeker ve kaydeder.
     *
     * @param gunGeri Kaç gün geriye gidilecek
     * @return İşlenen sipariş sayısı
     */
    public int siparisleriCek(int gunGeri) {
        // Aynı anda iki sync çalışmasını engelle
        if (!syncDevamEdiyor.compareAndSet(false, true)) {
            log.warn("Trendyol senkronizasyonu zaten devam ediyor, atlanıyor.");
            return 0;
        }

        try {
            return siparisleriCekInternal(gunGeri);
        } finally {
            syncDevamEdiyor.set(false);
        }
    }

    private int siparisleriCekInternal(int gunGeri) {
        // Trendyol kanalını bul
        Optional<SatisKanali> kanalOpt = satisKanaliRepository.findByKanalKodu("TRENDYOL");
        if (kanalOpt.isEmpty()) {
            log.error("TRENDYOL kanalı tanımlı değil! Önce satis_kanallari tablosuna ekleyin.");
            return 0;
        }

        SatisKanali kanal = kanalOpt.get();

        // API bilgilerini kontrol et
        if (kanal.getSaticiId() == null || kanal.getApiAnahtar() == null || kanal.getApiGizliAnahtar() == null) {
            log.error("Trendyol API bilgileri eksik! satici_id, api_anahtar, api_gizli_anahtar alanlarını doldurun.");
            return 0;
        }

        log.info("Trendyol Senkronizasyon Başladı (Son {} gün) [THREAD={}]",
                gunGeri, Thread.currentThread().getName());

        // Basic Auth Header
        String authString = kanal.getApiAnahtar() + ":" + kanal.getApiGizliAnahtar();
        String authHeader = "Basic " + Base64.getEncoder().encodeToString(authString.getBytes(StandardCharsets.UTF_8));

        // Tarih hesaplama (Unix Timestamp ms)
        long startDate = System.currentTimeMillis() - (gunGeri * 24L * 60 * 60 * 1000);

        WebClient webClient = WebClient.builder()
                .baseUrl(BASE_URL)
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();

        int sayfa = 0;
        int toplamSayfa = 1;
        int toplamIslenen = 0;
        int basarisiz = 0;
        int atlanan = 0;

        while (sayfa < toplamSayfa) {
            try {
                log.info("Sayfa {} çekiliyor...", sayfa + 1);

                final int mevcutSayfa = sayfa;
                JsonNode response = webClient.get()
                        .uri(uriBuilder -> uriBuilder
                                .path("/{supplierId}/orders")
                                .queryParam("startDate", startDate)
                                .queryParam("page", mevcutSayfa)
                                .queryParam("size", SAYFA_BOYUTU)
                                .build(kanal.getSaticiId()))
                        .header("Authorization", authHeader)
                        .header("User-Agent", kanal.getSaticiId() + " - SelfIntegration")
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .timeout(Duration.ofSeconds(30))
                        .block();

                if (response == null) {
                    log.warn("Trendyol yanıtı boş (sayfa {})", sayfa + 1);
                    break;
                }

                // İlk sayfada toplam sayfa sayısını al
                if (sayfa == 0 && response.has("totalPages")) {
                    toplamSayfa = response.get("totalPages").asInt();
                    int toplamKayit = response.has("totalElements") ? response.get("totalElements").asInt() : 0;
                    log.info("Toplam {} sipariş, {} sayfa bulundu", toplamKayit, toplamSayfa);
                }

                // Siparişleri işle
                if (response.has("content") && response.get("content").isArray()) {
                    JsonNode content = response.get("content");
                    int sayfadakiSiparis = content.size();

                    log.info("Sayfa {}/{}: {} sipariş işleniyor", sayfa + 1, toplamSayfa, sayfadakiSiparis);

                    for (JsonNode orderNode : content) {
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
                            log.error("Trendyol Sipariş #{} kaydedilemedi: {}", orderNumber, e.getMessage());

                            // Hatayı kaydet
                            try {
                                String platformSiparisId = orderNode.path("id").asText();
                                String hataTipi = syncHataService.hataTipiBelirle(e);
                                String hamVeri = objectMapper.writeValueAsString(orderNode);
                                syncHataService.hataKaydet("TRENDYOL", platformSiparisId, orderNumber,
                                        hataTipi, e.getMessage(), hamVeri);
                            } catch (Exception ex) {
                                log.error("Hata kaydedilemedi: {}", ex.getMessage());
                            }
                        }
                    }
                }

                sayfa++;

                // Güvenlik: Sonsuz döngü önleme
                if (sayfa > 100) {
                    log.warn("100 sayfa limitine ulaşıldı, döngü sonlandırılıyor");
                    break;
                }

            } catch (Exception e) {
                log.error("Trendyol API Hatası (Sayfa {}): {}", sayfa + 1, e.getMessage());
                kanal.setSonSenkronHatasi(e.getMessage());
                satisKanaliRepository.save(kanal);
                break;
            }
        }

        // Son senkronizasyon tarihini güncelle
        kanal.setSonSiparisSenkron(LocalDateTime.now());
        kanal.setSonSenkronHatasi(basarisiz > 0 ? basarisiz + " sipariş başarısız" : null);
        satisKanaliRepository.save(kanal);

        log.info("Trendyol Senkronizasyon Tamamlandı:");
        log.info("   Toplam sayfa: {}", sayfa);
        log.info("   İşlenen: {}", toplamIslenen);
        log.info("   Atlanan: {}", atlanan);
        log.info("   Başarısız: {}", basarisiz);

        // WebSocket bildirimi gönder
        if (toplamIslenen > 0) {
            notificationService.notifySyncComplete("trendyol", toplamIslenen);
        }

        return toplamIslenen;
    }

    /**
     * Trendyol siparişini "İşleme Alındı" (Picking) durumuna günceller.
     * Etiket basıldığında otomatik olarak çağrılır.
     *
     * PUT /sapigw/suppliers/{supplierId}/shipment-packages/{shipmentPackageId}
     *
     * @param siparis Sipariş entity
     * @return true: API çağrısı başarılı, false: timeout/hata/atlandı
     */
    public boolean siparisiPickingYap(Siparis siparis) {
        String platformPaketId = siparis.getPlatformPaketId();
        if (platformPaketId == null || platformPaketId.isEmpty()) {
            log.warn("Trendyol Picking: platformPaketId boş, sipariş #{}", siparis.getSiparisNo());
            return false;
        }

        Optional<SatisKanali> kanalOpt = satisKanaliRepository.findByKanalKodu("TRENDYOL");
        if (kanalOpt.isEmpty()) {
            log.error("Trendyol Picking: TRENDYOL kanalı tanımlı değil!");
            return false;
        }

        SatisKanali kanal = kanalOpt.get();
        if (kanal.getSaticiId() == null || kanal.getApiAnahtar() == null || kanal.getApiGizliAnahtar() == null) {
            log.error("Trendyol Picking: API bilgileri eksik!");
            return false;
        }

        // Sipariş kalemlerinden lineId ve quantity'leri topla
        List<SiparisKalemi> kalemler = siparisKalemiRepository.findBySiparisId(siparis.getId());
        List<Map<String, Object>> lines = kalemler.stream()
                .filter(k -> k.getPlatformKalemId() != null && !k.getPlatformKalemId().isEmpty())
                .map(k -> {
                    Map<String, Object> line = new HashMap<>();
                    line.put("lineId", Long.parseLong(k.getPlatformKalemId()));
                    line.put("quantity", k.getMiktar().intValue());
                    return line;
                })
                .collect(Collectors.toList());

        if (lines.isEmpty()) {
            log.warn("Trendyol Picking: platformKalemId'si olan kalem bulunamadı, sipariş #{}", siparis.getSiparisNo());
            return false;
        }

        // Request body
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("lines", lines);
        requestBody.put("params", Map.of());
        requestBody.put("status", "Picking");

        // Auth header
        String authString = kanal.getApiAnahtar() + ":" + kanal.getApiGizliAnahtar();
        String authHeader = "Basic " + Base64.getEncoder().encodeToString(authString.getBytes(StandardCharsets.UTF_8));

        try {
            log.info("Trendyol Picking isteği gönderiliyor: sipariş #{}, paketId={}, {} kalem, body={}",
                    siparis.getSiparisNo(), platformPaketId, lines.size(), objectMapper.writeValueAsString(requestBody));

            WebClient webClient = WebClient.builder()
                    .baseUrl(INTEGRATION_URL)
                    .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                    .build();

            String response = webClient.put()
                    .uri("/{sellerId}/shipment-packages/{packageId}",
                            kanal.getSaticiId(), platformPaketId)
                    .header("Authorization", authHeader)
                    .header("User-Agent", kanal.getSaticiId() + " - SelfIntegration")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .onStatus(status -> status.isError(), clientResponse ->
                            clientResponse.bodyToMono(String.class)
                                    .flatMap(body -> {
                                        log.error("Trendyol Picking API hata: HTTP {}, body: {}",
                                                clientResponse.statusCode().value(), body);
                                        return Mono.error(new RuntimeException(
                                                "Trendyol Picking API hata: " + clientResponse.statusCode().value() + " - " + body));
                                    })
                    )
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(60))
                    .block();

            log.info("Trendyol Picking başarılı: sipariş #{}, paketId={}", siparis.getSiparisNo(), platformPaketId);
            return true;

        } catch (Exception e) {
            log.error("Trendyol Picking hatası: sipariş #{}, paketId={}, hata: {}",
                    siparis.getSiparisNo(), platformPaketId, e.getMessage());
            return false;
        }
    }
}
