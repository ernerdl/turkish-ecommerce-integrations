/*
 * Adapted from a production Spring Boot ERP system.
 *
 * NOTE: This service depends on entity classes (Siparis, SatisKanali, etc.),
 * repositories, the IkasWebhookPayload DTO, and other services. You need to
 * provide your own implementations matching the method signatures used here.
 *
 * Configure ikas.webhook.callback-url and ikas.webhook.token in your
 * application properties. The defaults below are placeholders.
 *
 * Original author: Mehmet Eren Erdal <m.erenerdal@sevinclidoga.com>
 * License: MIT
 */
package com.example.integration.ikas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.integration.dto.webhook.IkasWebhookPayload;
import com.example.integration.entity.OlcuBirimi;
import com.example.integration.entity.SatisKanali;
import com.example.integration.exception.IntegrationException;
import com.example.integration.repository.OlcuBirimiRepository;
import com.example.integration.repository.SatisKanaliRepository;
import com.example.integration.repository.SiparisRepository;
import com.example.integration.repository.UrunVaryantiRepository;
import com.example.integration.repository.UrunRepository;
import com.example.integration.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.*;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * İkas Webhook Servisi
 *
 * Webhook event'lerini işler ve İkas'a webhook kaydı yapar.
 *
 * Desteklenen Scope'lar:
 * - store/order/created: Yeni sipariş geldiğinde tetiklenir -> siparişi çeker ve kaydeder
 * - store/product/created: Yeni ürün geldiğinde tetiklenir -> ürünü çeker ve ERP'ye kaydeder
 * - store/customer/created: Yeni müşteri (şimdilik sadece log)
 * - store/customer/updated: Müşteri güncelleme (şimdilik sadece log)
 *
 * NOT: store/order/updated ve store/order/cancelled scope'ları İkas'ta YOK!
 * Bu yüzden sipariş güncellemeleri için hala polling gerekli.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class IkasWebhookService {

    private final SatisKanaliRepository satisKanaliRepository;
    private final SiparisRepository siparisRepository;
    private final IkasSiparisKaydediciService siparisKaydedici;
    private final IkasUrunIslemService ikasUrunIslemService;
    private final IkasTokenManager tokenManager;
    private final NotificationService notificationService;
    private final OlcuBirimiRepository olcuBirimiRepository;
    private final UrunVaryantiRepository urunVaryantiRepository;
    private final UrunRepository urunRepository;
    private final ObjectMapper objectMapper;

    // TODO: configure - set your webhook callback URL in application properties
    @Value("${ikas.webhook.callback-url:}")
    private String callbackBaseUrl;

    // TODO: configure - set your webhook verification token in application properties
    @Value("${ikas.webhook.token:}")
    private String webhookToken;

    private static final Locale TR = new Locale("tr", "TR");
    private static final String IKAS_GRAPHQL_URL = "https://api.myikas.com/api/v1/admin/graphql";

    /**
     * Webhook event'ini işler.
     *
     * @param payload Webhook payload
     */
    public void processWebhook(IkasWebhookPayload payload) {
        String scope = payload.getScope();
        log.info("Webhook işleniyor - Scope: {}", scope);

        switch (scope) {
            case "store/order/created":
                handleOrderCreated(payload);
                break;

            case "store/customer/created":
                log.info("Yeni müşteri webhook alındı - CustomerId: {}",
                        payload.getData() != null ? payload.getData().getCustomerId() : "null");
                // İleride müşteri senkronizasyonu eklenebilir
                break;

            case "store/customer/updated":
                log.info("Müşteri güncelleme webhook alındı - CustomerId: {}",
                        payload.getData() != null ? payload.getData().getCustomerId() : "null");
                break;

            case "store/product/created":
                handleProductCreated(payload);
                break;

            default:
                log.warn("Bilinmeyen webhook scope: {}", scope);
        }
    }

    /**
     * Yeni sipariş webhook'unu işler.
     * İkas'tan siparişi çeker ve ERP'ye kaydeder.
     */
    private void handleOrderCreated(IkasWebhookPayload payload) {
        if (payload.getData() == null || payload.getData().getOrderId() == null) {
            log.warn("store/order/created webhook'u data veya orderId içermiyor!");
            return;
        }

        String orderId = payload.getData().getOrderId();
        log.info("Yeni sipariş webhook: OrderId = {}", orderId);

        // Sipariş zaten var mı kontrol et (platformSiparisId = İkas orderId)
        if (siparisRepository.findByPlatformSiparisId(orderId).isPresent()) {
            log.info("Sipariş zaten mevcut: {}", orderId);
            return;
        }

        try {
            // İkas kanalını bul
            Optional<SatisKanali> kanalOpt = satisKanaliRepository.findByKanalKodu("IKAS");
            if (kanalOpt.isEmpty()) {
                log.error("IKAS kanalı tanımlı değil!");
                return;
            }

            SatisKanali kanal = kanalOpt.get();
            String accessToken = tokenManager.ensureValidToken(kanal);

            // Siparişi İkas'tan çek
            JsonNode orderData = fetchOrderById(accessToken, orderId);

            if (orderData == null) {
                log.error("Sipariş İkas'tan çekilemedi: {}", orderId);
                return;
            }

            // Siparişi kaydet (tekSiparisKaydet boolean döner)
            boolean kaydedildi = siparisKaydedici.tekSiparisKaydet(orderData, kanal);

            if (kaydedildi) {
                // Sipariş numarasını al (kayıt sonrası)
                String siparisNo = orderData.path("orderNumber").asText(orderId);
                log.info("Webhook ile sipariş kaydedildi: {} -> ERP #{}", orderId, siparisNo);

                // Kaydedilen siparişi bul ve bildirim gönder
                siparisRepository.findByPlatformSiparisId(orderId).ifPresent(siparis ->
                    notificationService.notifyNewOrder((long) siparis.getId(), siparis.getSiparisNo(), "IKAS")
                );
            } else {
                log.warn("Sipariş kaydedilemedi veya atlandı: {}", orderId);
            }

        } catch (Exception e) {
            log.error("Webhook sipariş işleme hatası: {} - {}", orderId, e.getMessage(), e);
            throw new IntegrationException("IKAS", "Webhook sipariş işleme hatası: " + e.getMessage());
        }
    }

    /**
     * Yeni ürün webhook'unu işler.
     * İkas'tan ürünü çeker ve ERP'ye kaydeder.
     * Aynı duplicate-safe mekanizmayı kullanır (IkasUrunIslemService).
     */
    private void handleProductCreated(IkasWebhookPayload payload) {
        if (payload.getData() == null || payload.getData().getProductId() == null) {
            log.warn("store/product/created webhook'u data veya productId içermiyor!");
            return;
        }

        String productId = payload.getData().getProductId();
        log.info("Yeni ürün webhook: ProductId = {}", productId);

        try {
            // İkas kanalını bul
            Optional<SatisKanali> kanalOpt = satisKanaliRepository.findByKanalKodu("IKAS");
            if (kanalOpt.isEmpty()) {
                log.error("IKAS kanalı tanımlı değil!");
                return;
            }

            SatisKanali kanal = kanalOpt.get();
            String accessToken = tokenManager.ensureValidToken(kanal);

            // Ürünü İkas'tan çek
            JsonNode productData = fetchProductById(accessToken, productId);

            if (productData == null) {
                log.error("Ürün İkas'tan çekilemedi: {}", productId);
                return;
            }

            String urunAdi = productData.path("name").asText("?");
            JsonNode variants = productData.path("variants");
            int varyantSayisi = variants.isArray() ? variants.size() : 0;
            log.info("İkas'tan ürün çekildi: {} ({} varyant)", urunAdi, varyantSayisi);

            // Default birim (KG) al
            OlcuBirimi kgBirim = olcuBirimiRepository.findByBirimKodu("KG")
                    .orElseThrow(() -> new RuntimeException("KG birimi bulunamadı!"));

            // Mevcut SKU'ları ve ürün kodlarını yükle (duplicate önleme)
            Set<String> mevcutSkular = urunVaryantiRepository.findAllSkus().stream()
                    .map(s -> s.toUpperCase(TR))
                    .collect(Collectors.toCollection(HashSet::new));

            Set<String> mevcutUrunKodlari = urunRepository.findAllUrunKodlari().stream()
                    .collect(Collectors.toCollection(HashSet::new));

            // Rapor listeleri
            List<String> eklenenler = new ArrayList<>();
            List<String> guncellenenler = new ArrayList<>();
            List<String> eslesenler = new ArrayList<>();

            // Ürünü işle (ayrı transaction'da, duplicate-safe)
            ikasUrunIslemService.processProduct(productData, kgBirim,
                    mevcutSkular, mevcutUrunKodlari,
                    eklenenler, guncellenenler, eslesenler);

            // Sonuç logla
            if (!eklenenler.isEmpty()) {
                log.info("Webhook ile ürün kaydedildi: {}", String.join(", ", eklenenler));
            }
            if (!guncellenenler.isEmpty()) {
                log.info("Webhook ile ürün güncellendi: {}", String.join(", ", guncellenenler));
            }
            if (!eslesenler.isEmpty()) {
                log.info("Ürün zaten mevcut: {}", String.join(", ", eslesenler));
            }

        } catch (Exception e) {
            log.error("Webhook ürün işleme hatası: ProductId={} | Sebep: {}", productId, e.getMessage(), e);
            throw new IntegrationException("IKAS", "Webhook ürün işleme hatası: " + e.getMessage());
        }
    }

    /**
     * İkas'tan tek ürün çeker (ID ile).
     * listProduct query'si id filtresi ile kullanılır.
     */
    private JsonNode fetchProductById(String accessToken, String productId) {
        String query = "{ listProduct(id: { eq: \"" + productId + "\" }) { " +
                "data { id name type " +
                "variants { id sku barcodeList weight prices { sellPrice } } " +
                "} } }";

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("query", query);

        try {
            WebClient webClient = WebClient.builder()
                    .baseUrl(IKAS_GRAPHQL_URL)
                    .build();

            String response = webClient.post()
                    .header("Authorization", "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode root = objectMapper.readTree(response);

            // Hata kontrolü
            if (root.has("errors")) {
                log.error("İkas GraphQL hatası (ürün çekme): {}", root.get("errors"));
                return null;
            }

            JsonNode dataArray = root.path("data").path("listProduct").path("data");
            if (dataArray.isArray() && dataArray.size() > 0) {
                return dataArray.get(0);
            }

            log.warn("Ürün İkas'ta bulunamadı: {}", productId);
            return null;

        } catch (Exception e) {
            log.error("İkas ürün çekme hatası: ProductId={} | Sebep: {}", productId, e.getMessage(), e);
            return null;
        }
    }

    /**
     * İkas'tan tek sipariş çeker (ID ile).
     */
    private JsonNode fetchOrderById(String accessToken, String orderId) {
        String query = """
            query GetOrder($orderId: StringFilterInput!) {
              listOrder(id: $orderId) {
                data {
                  id
                  orderNumber
                  status
                  currencyCode
                  orderLineItemTotal
                  orderPackageStatus
                  createdAt
                  customer {
                    id
                    firstName
                    lastName
                    email
                    phone
                  }
                  shippingAddress {
                    firstName
                    lastName
                    phone
                    addressLine1
                    city {
                      name
                    }
                    district {
                      name
                    }
                    postalCode
                  }
                  orderLineItems {
                    id
                    quantity
                    finalPrice
                    variant {
                      id
                      sku
                      name
                      mainImageUrl
                    }
                  }
                  orderPackages {
                    id
                    status
                    trackingInfo {
                      trackingNumber
                      barcode
                    }
                  }
                }
              }
            }
            """;

        Map<String, Object> variables = new HashMap<>();
        variables.put("orderId", Map.of("eq", orderId));

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("query", query);
        requestBody.put("variables", variables);

        try {
            WebClient webClient = WebClient.builder()
                    .baseUrl(IKAS_GRAPHQL_URL)
                    .build();

            String response = webClient.post()
                    .header("Authorization", "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode root = objectMapper.readTree(response);

            // Hata kontrolü
            if (root.has("errors")) {
                log.error("İkas GraphQL hatası: {}", root.get("errors"));
                return null;
            }

            JsonNode dataArray = root.path("data").path("listOrder").path("data");
            if (dataArray.isArray() && dataArray.size() > 0) {
                return dataArray.get(0);
            }

            return null;

        } catch (Exception e) {
            log.error("İkas sipariş çekme hatası: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * İkas'a webhook kaydı yapar.
     * saveWebhook mutation kullanır.
     *
     * @return Kayıt sonuçları
     */
    public Map<String, Object> registerWebhooks() {
        Map<String, Object> result = new HashMap<>();

        Optional<SatisKanali> kanalOpt = satisKanaliRepository.findByKanalKodu("IKAS");
        if (kanalOpt.isEmpty()) {
            throw new IntegrationException("IKAS", "IKAS kanalı tanımlı değil!");
        }

        SatisKanali kanal = kanalOpt.get();
        String accessToken = tokenManager.ensureValidToken(kanal);

        // Callback URL (token ile)
        String callbackUrl = callbackBaseUrl + "?token=" + webhookToken;

        // Kayıt edilecek scope'lar
        List<String> scopes = List.of(
                "store/order/created",
                "store/product/created"
        );

        for (String scope : scopes) {
            try {
                boolean success = registerSingleWebhook(accessToken, scope, callbackUrl);
                result.put(scope, success ? "KAYIT_BASARILI" : "KAYIT_HATASI");
                log.info("Webhook kayıt: {} -> {}", scope, success ? "BAŞARILI" : "HATA");
            } catch (Exception e) {
                result.put(scope, "HATA: " + e.getMessage());
                log.error("Webhook kayıt hatası ({}): {}", scope, e.getMessage());
            }
        }

        result.put("callbackUrl", callbackUrl);
        return result;
    }

    /**
     * Tek bir webhook scope'u için kayıt yapar.
     */
    private boolean registerSingleWebhook(String accessToken, String scope, String callbackUrl) {
        String mutation = """
            mutation SaveWebhook($input: WebhookInput!) {
              saveWebhook(input: $input) {
                id
                scope
                endpoint
                createdAt
              }
            }
            """;

        Map<String, Object> input = new HashMap<>();
        input.put("scope", scope);
        input.put("endpoint", callbackUrl);

        Map<String, Object> variables = new HashMap<>();
        variables.put("input", input);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("query", mutation);
        requestBody.put("variables", variables);

        try {
            WebClient webClient = WebClient.builder()
                    .baseUrl(IKAS_GRAPHQL_URL)
                    .build();

            String response = webClient.post()
                    .header("Authorization", "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode root = objectMapper.readTree(response);

            if (root.has("errors")) {
                log.error("Webhook kayıt hatası: {}", root.get("errors"));
                return false;
            }

            JsonNode saveResult = root.path("data").path("saveWebhook");
            if (saveResult != null && saveResult.has("id")) {
                log.info("Webhook kaydedildi: ID={}, Scope={}",
                        saveResult.get("id").asText(), scope);
                return true;
            }

            return false;

        } catch (Exception e) {
            log.error("Webhook kayıt isteği hatası: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Kayıtlı webhook'ları listeler.
     */
    public Map<String, Object> listWebhooks() {
        Map<String, Object> result = new HashMap<>();

        Optional<SatisKanali> kanalOpt = satisKanaliRepository.findByKanalKodu("IKAS");
        if (kanalOpt.isEmpty()) {
            throw new IntegrationException("IKAS", "IKAS kanalı tanımlı değil!");
        }

        SatisKanali kanal = kanalOpt.get();
        String accessToken = tokenManager.ensureValidToken(kanal);

        String query = """
            query ListWebhooks {
              listWebhook {
                id
                scope
                endpoint
                createdAt
              }
            }
            """;

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("query", query);

        try {
            WebClient webClient = WebClient.builder()
                    .baseUrl(IKAS_GRAPHQL_URL)
                    .build();

            String response = webClient.post()
                    .header("Authorization", "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode root = objectMapper.readTree(response);

            if (root.has("errors")) {
                result.put("error", root.get("errors").toString());
                return result;
            }

            JsonNode webhooks = root.path("data").path("listWebhook");
            result.put("webhooks", objectMapper.treeToValue(webhooks, List.class));
            result.put("count", webhooks.size());

            return result;

        } catch (Exception e) {
            log.error("Webhook listeleme hatası: {}", e.getMessage(), e);
            result.put("error", e.getMessage());
            return result;
        }
    }
}
