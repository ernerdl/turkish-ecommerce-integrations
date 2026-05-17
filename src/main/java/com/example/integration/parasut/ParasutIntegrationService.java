/*
 * Adapted from a production Spring Boot ERP system.
 *
 * NOTE: This service depends on entity classes (ApiAyar), enums and
 * services (ApiAyarService, ActivityLogService). You need to provide your
 * own implementations matching the method signatures used here.
 *
 * Paraşüt is a Turkish accounting/invoicing SaaS — see https://parasut.com/api
 * for API documentation.
 *
 * Original author: Mehmet Eren Erdal <m.erenerdal@sevinclidoga.com>
 * License: MIT
 */
package com.example.integration.parasut;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.integration.entity.ApiAyar;
import com.example.integration.enums.ActivityEventTypeEnum;
import com.example.integration.enums.ActivitySourceEnum;
import com.example.integration.enums.ActivityStatusEnum;
import com.example.integration.enums.EntityTypeEnum;
import com.example.integration.service.ActivityLogService;
import com.example.integration.service.ApiAyarService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class ParasutIntegrationService {

    private static final String API_ADI = "parasut";
    private static final String TOKEN_ENDPOINT = "https://api.parasut.com/oauth/token";

    private final ApiAyarService apiAyarService;
    private final ActivityLogService activityLogService;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Geçerli access token döndürür, gerekirse yeniler
     */
    public String getAccessToken() {
        ApiAyar ayar = apiAyarService.getEntityByApiAdi(API_ADI);
        if (ayar == null || !Boolean.TRUE.equals(ayar.getAktif())) {
            log.warn("Paraşüt API ayarları bulunamadı veya pasif");
            return null;
        }

        // Token geçerli mi kontrol et (5 dakika tolerans)
        if (ayar.getAccessToken() != null && ayar.getTokenExpiresAt() != null
                && ayar.getTokenExpiresAt().isAfter(LocalDateTime.now().plusMinutes(5))) {
            return ayar.getAccessToken();
        }

        // Refresh token ile yenile
        if (ayar.getRefreshToken() != null) {
            try {
                return refreshToken(ayar);
            } catch (Exception e) {
                log.warn("Refresh token başarısız, yeniden login deneniyor: {}", e.getMessage());
            }
        }

        // Password grant ile yeni token al
        return authenticateWithPassword(ayar);
    }

    private String refreshToken(ApiAyar ayar) {
        log.info("Paraşüt token yenileniyor (refresh_token ile)...");

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("grant_type", "refresh_token");
        params.add("client_id", ayar.getClientId());
        params.add("client_secret", apiAyarService.getDecryptedClientSecret(API_ADI));
        params.add("refresh_token", ayar.getRefreshToken());

        String token = executeTokenRequest(params);
        if (token == null) {
            throw new RuntimeException("Refresh token ile yenileme başarısız");
        }
        return token;
    }

    private String authenticateWithPassword(ApiAyar ayar) {
        log.info("Paraşüt password grant ile token alınıyor...");

        String password = apiAyarService.getDecryptedPassword(API_ADI);
        if (password == null || ayar.getUsername() == null) {
            log.error("Paraşüt kullanıcı bilgileri eksik");
            apiAyarService.hataKaydet(API_ADI, "Kullanıcı adı veya şifre eksik");
            return null;
        }

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("grant_type", "password");
        params.add("client_id", ayar.getClientId());
        params.add("client_secret", apiAyarService.getDecryptedClientSecret(API_ADI));
        params.add("username", ayar.getUsername());
        params.add("password", password);
        params.add("redirect_uri", "urn:ietf:wg:oauth:2.0:oob");

        return executeTokenRequest(params);
    }

    private String executeTokenRequest(MultiValueMap<String, String> params) {
        try {
            // DEBUG: Gönderilen parametreleri logla (şifre hariç)
            log.info("=== PARAŞÜT TOKEN İSTEĞİ ===");
            log.info("grant_type: {}", params.getFirst("grant_type"));
            log.info("client_id: {}", params.getFirst("client_id"));
            log.info("username: {}", params.getFirst("username"));
            log.debug("client_secret mevcut: {}", params.getFirst("client_secret") != null);
            log.debug("password mevcut: {}", params.getFirst("password") != null);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(params, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(TOKEN_ENDPOINT, request, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode json = objectMapper.readTree(response.getBody());
                String accessToken = json.get("access_token").asText();
                String refreshToken = json.has("refresh_token") ? json.get("refresh_token").asText() : null;
                int expiresIn = json.get("expires_in").asInt();

                LocalDateTime expiresAt = LocalDateTime.now().plusSeconds(expiresIn);
                apiAyarService.tokenKaydet(API_ADI, accessToken, refreshToken, expiresAt);

                log.info("Paraşüt token başarıyla alındı, geçerlilik: {} saniye", expiresIn);
                return accessToken;
            }
        } catch (Exception e) {
            log.error("Paraşüt token alma hatası: {}", e.getMessage());
            // Daha detaylı hata logu
            if (e.getMessage() != null && e.getMessage().contains("400")) {
                log.error("400 Bad Request - muhtemelen yanlış credentials. Client ID veya şifreyi kontrol edin.");
            }
            apiAyarService.hataKaydet(API_ADI, "Token alma hatası: " + e.getMessage());
        }
        return null;
    }

    /**
     * API'ye istek yapar (rate limiting retry ile)
     */
    public JsonNode apiCall(String endpoint, HttpMethod method, Object body) {
        return apiCallWithRetry(endpoint, method, body, 3); // Max 3 retry
    }

    private JsonNode apiCallWithRetry(String endpoint, HttpMethod method, Object body, int retryCount) {
        String token = getAccessToken();
        if (token == null) {
            throw new RuntimeException("Paraşüt token alınamadı");
        }

        ApiAyar ayar = apiAyarService.getEntityByApiAdi(API_ADI);
        String url = ayar.getBaseUrl() + "/" + ayar.getCompanyId() + endpoint;

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<?> request = body != null ? new HttpEntity<>(body, headers) : new HttpEntity<>(headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, method, request, String.class);
            if (response.getBody() != null) {
                return objectMapper.readTree(response.getBody());
            }
        } catch (Exception e) {
            String errorMsg = e.getMessage();

            // Rate limit hatası (429) - bekle ve tekrar dene
            if (errorMsg != null && errorMsg.contains("429") && retryCount > 0) {
                int waitSeconds = 7; // Varsayılan 7 saniye
                // Hata mesajından bekleme süresini çıkarmayı dene
                if (errorMsg.contains("Try again in")) {
                    try {
                        int idx = errorMsg.indexOf("Try again in") + 13;
                        String numStr = errorMsg.substring(idx).replaceAll("[^0-9]", "");
                        if (!numStr.isEmpty()) {
                            waitSeconds = Integer.parseInt(numStr.substring(0, Math.min(2, numStr.length()))) + 1;
                        }
                    } catch (Exception ignored) {}
                }

                log.warn("Paraşüt rate limit (429) - {} saniye bekleniyor, kalan deneme: {}", waitSeconds, retryCount);
                try {
                    Thread.sleep(waitSeconds * 1000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                return apiCallWithRetry(endpoint, method, body, retryCount - 1);
            }

            log.error("Paraşüt API hatası: {} - {}", endpoint, errorMsg);
            throw new RuntimeException("Paraşüt API hatası: " + errorMsg);
        }
        return null;
    }

    /**
     * Bağlantı testi
     */
    public Map<String, Object> baglantiTest() {
        try {
            String token = getAccessToken();
            if (token == null) {
                return Map.of("basarili", false, "mesaj", "Token alınamadı");
            }

            JsonNode company = apiCall("/company", HttpMethod.GET, null);
            if (company != null && company.has("data")) {
                String sirketAdi = company.get("data").get("attributes").get("name").asText();
                return Map.of(
                    "basarili", true,
                    "mesaj", "Bağlantı başarılı",
                    "sirketAdi", sirketAdi
                );
            }
            return Map.of("basarili", false, "mesaj", "Şirket bilgisi alınamadı");
        } catch (Exception e) {
            return Map.of("basarili", false, "mesaj", e.getMessage());
        }
    }

    // ============ SATIŞ FATURALARI ============

    public JsonNode satisFaturalariListele(int page, int size) {
        // sort=-issue_date ile yeniden eskiye sıralama
        String endpoint = "/sales_invoices?page[number]=" + page + "&page[size]=" + size + "&sort=-issue_date";
        return apiCall(endpoint, HttpMethod.GET, null);
    }

    public JsonNode satisFaturasiGetir(String faturaId) {
        return apiCall("/sales_invoices/" + faturaId, HttpMethod.GET, null);
    }

    public JsonNode satisFaturasiOlustur(Map<String, Object> faturaData) {
        return apiCall("/sales_invoices", HttpMethod.POST, faturaData);
    }

    // ============ ALIS FATURALARI ============

    public JsonNode alisFaturalariListele(int page, int size) {
        // sort=-issue_date ile yeniden eskiye sıralama
        String endpoint = "/purchase_bills?page[number]=" + page + "&page[size]=" + size + "&sort=-issue_date";
        return apiCall(endpoint, HttpMethod.GET, null);
    }

    public JsonNode alisFaturalariListeleTarihFiltreli(int page, int size, String baslangicTarih, String bitisTarih) {
        // Tarih filtresi - çift nokta (..) sözdizimi: filter[issue_date]=YYYY-MM-DD..YYYY-MM-DD
        // URL encoding: .. -> %2E%2E
        String dateRange = baslangicTarih + ".." + bitisTarih;
        try {
            dateRange = java.net.URLEncoder.encode(dateRange, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Tarih encode hatası: {}", e.getMessage());
        }
        String endpoint = "/purchase_bills?page[number]=" + page + "&page[size]=" + size
            + "&sort=-issue_date"
            + "&filter[issue_date]=" + dateRange;
        return apiCall(endpoint, HttpMethod.GET, null);
    }

    public JsonNode alisFaturalariIncludeEInvoice(int page, int size) {
        // Gelen e-fatura bilgisi dahil alış faturaları
        String endpoint = "/purchase_bills?page[number]=" + page + "&page[size]=" + size
            + "&sort=-issue_date&include=e_invoice_inbox,supplier";
        return apiCall(endpoint, HttpMethod.GET, null);
    }

    public JsonNode alisFaturasiOlustur(Map<String, Object> faturaData) {
        return apiCall("/purchase_bills", HttpMethod.POST, faturaData);
    }

    public JsonNode alisFaturasiGetir(String faturaId) {
        return apiCall("/purchase_bills/" + faturaId, HttpMethod.GET, null);
    }

    public JsonNode alisFaturasiDetaylariGetir(String faturaId) {
        // Alış faturalarında contact yerine supplier kullanılıyor
        String endpoint = "/purchase_bills/" + faturaId + "?include=details,supplier,payments";
        return apiCall(endpoint, HttpMethod.GET, null);
    }

    // ============ MÜŞTERİLER (CONTACTS) ============

    public JsonNode musterileriListele(int page, int size) {
        String endpoint = "/contacts?page[number]=" + page + "&page[size]=" + size;
        return apiCall(endpoint, HttpMethod.GET, null);
    }

    public JsonNode musteriOlustur(Map<String, Object> musteriData) {
        return apiCall("/contacts", HttpMethod.POST, musteriData);
    }

    public JsonNode musteriGetir(String musteriId) {
        return apiCall("/contacts/" + musteriId, HttpMethod.GET, null);
    }

    // ============ ÜRÜNLER ============

    public JsonNode urunleriListele(int page, int size) {
        String endpoint = "/products?page[number]=" + page + "&page[size]=" + size;
        return apiCall(endpoint, HttpMethod.GET, null);
    }

    public JsonNode urunOlustur(Map<String, Object> urunData) {
        return apiCall("/products", HttpMethod.POST, urunData);
    }

    // ============ GELEN E-FATURALAR ============

    /**
     * Gelen e-faturaları listeler (GİB üzerinden gelen faturalar)
     * Not: Bu endpoint sadece henüz işlenmemiş gelen e-faturaları gösterir
     */
    public JsonNode gelenEFaturalariListele(int page, int size) {
        // Filtre olmadan tüm gelen e-faturaları çek
        String endpoint = "/e_invoice_inboxes?page[number]=" + page + "&page[size]=" + size;
        return apiCall(endpoint, HttpMethod.GET, null);
    }

    /**
     * İçe aktarılabilir gelen e-faturaları listeler
     * Paraşüt önerisi: /e_invoices?scope=importable
     * Bu endpoint ile gelen faturaları inceleyebilirsiniz (giderleştirme hariç)
     */
    public JsonNode iceAktarilabilirEFaturalariListele(int page, int size) {
        String endpoint = "/e_invoices?scope=importable&page[number]=" + page + "&page[size]=" + size + "&sort=-issue_date";
        return apiCall(endpoint, HttpMethod.GET, null);
    }

    /**
     * Tüm e-faturaları listeler (giden e-faturalar)
     */
    public JsonNode tumEFaturalariListele(int page, int size) {
        String endpoint = "/e_invoices?page[number]=" + page + "&page[size]=" + size + "&sort=-created_at";
        return apiCall(endpoint, HttpMethod.GET, null);
    }

    /**
     * Tüm e-arşivleri listeler
     */
    public JsonNode tumEArsivleriListele(int page, int size) {
        String endpoint = "/e_archives?page[number]=" + page + "&page[size]=" + size + "&sort=-created_at";
        return apiCall(endpoint, HttpMethod.GET, null);
    }

    /**
     * Gelen e-fatura detayı
     */
    public JsonNode gelenEFaturaDetay(String id) {
        String endpoint = "/e_invoice_inboxes/" + id;
        return apiCall(endpoint, HttpMethod.GET, null);
    }

    /**
     * İçe aktarılabilir e-fatura detayı (kalemler dahil)
     * scope=importable e-faturaları için detay
     */
    public JsonNode iceAktarilabilirEFaturaDetay(String id) {
        String endpoint = "/e_invoices/" + id + "?include=items";
        JsonNode result = apiCall(endpoint, HttpMethod.GET, null);

        // Debug: included içindeki tipleri logla
        if (result != null && result.has("included")) {
            log.info("E-fatura {} detay - included types:", id);
            for (JsonNode item : result.get("included")) {
                log.info("  - type: {}, id: {}", item.get("type").asText(), item.get("id").asText());
            }
        } else {
            log.warn("E-fatura {} detay - included yok veya boş", id);
        }

        return result;
    }

    /**
     * Gelen e-fatura PDF'ini indirir
     * @param pdfPath Paraşüt API'den gelen PDF path'i (örn: /v4/{companyId}/e_invoices/{id}/pdf)
     * @return PDF binary data
     */
    public byte[] gelenEFaturaPdfIndir(String pdfPath) {
        String token = getAccessToken();
        if (token == null) {
            throw new RuntimeException("Paraşüt token alınamadı");
        }

        try {
            // PDF endpoint URL oluştur
            String pdfEndpoint;
            if (pdfPath.startsWith("/v4/")) {
                pdfEndpoint = "https://api.parasut.com" + pdfPath;
            } else {
                pdfEndpoint = "https://api.parasut.com/v4" + pdfPath;
            }

            log.info("Gelen e-fatura PDF indiriliyor: {}", pdfEndpoint);

            // Adım 1: Paraşüt API'den S3 URL al
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token);
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<?> request = new HttpEntity<>(headers);
            ResponseEntity<String> pdfUrlResponse = restTemplate.exchange(
                pdfEndpoint,
                HttpMethod.GET,
                request,
                String.class
            );

            if (!pdfUrlResponse.getStatusCode().is2xxSuccessful() || pdfUrlResponse.getBody() == null) {
                throw new RuntimeException("PDF URL alınamadı");
            }

            // JSON'dan S3 URL çıkar
            JsonNode responseJson = objectMapper.readTree(pdfUrlResponse.getBody());
            String s3Url = null;
            if (responseJson.has("data") &&
                responseJson.get("data").has("attributes") &&
                responseJson.get("data").get("attributes").has("url")) {
                s3Url = responseJson.get("data").get("attributes").get("url").asText();
            }

            if (s3Url == null || s3Url.isEmpty()) {
                throw new RuntimeException("S3 URL bulunamadı");
            }

            log.info("S3 URL alındı, PDF indiriliyor...");

            // Adım 2: S3'ten gerçek PDF'i indir (authentication gerekmez)
            HttpHeaders pdfHeaders = new HttpHeaders();
            pdfHeaders.setAccept(java.util.List.of(MediaType.APPLICATION_PDF, MediaType.APPLICATION_OCTET_STREAM));

            HttpEntity<?> pdfRequest = new HttpEntity<>(pdfHeaders);
            ResponseEntity<byte[]> pdfResponse = restTemplate.exchange(
                java.net.URI.create(s3Url),
                HttpMethod.GET,
                pdfRequest,
                byte[].class
            );

            if (pdfResponse.getStatusCode().is2xxSuccessful() && pdfResponse.getBody() != null) {
                log.info("Gelen e-fatura PDF başarıyla indirildi: {} bytes", pdfResponse.getBody().length);
                return pdfResponse.getBody();
            }

            throw new RuntimeException("PDF indirilemedi");

        } catch (Exception e) {
            log.error("Gelen e-fatura PDF indirme hatası: {}", e.getMessage());
            throw new RuntimeException("PDF indirilemedi: " + e.getMessage());
        }
    }

    // ============ E-FATURA / E-ARŞİV ============

    /**
     * Müşterinin e-fatura mükellefi olup olmadığını kontrol eder.
     * @param vergiNo Müşteri vergi numarası
     * @return true ise e-fatura mükellefi, false ise e-arşiv
     */
    public boolean eFaturaMukellefMi(String vergiNo) {
        if (vergiNo == null || vergiNo.isEmpty()) {
            return false;
        }
        try {
            String endpoint = "/e_invoice_inboxes?filter[vkn]=" + vergiNo;
            JsonNode response = apiCall(endpoint, HttpMethod.GET, null);
            // Yanıt boş değilse e-fatura mükellefi
            return response != null && response.has("data") && response.get("data").size() > 0;
        } catch (Exception e) {
            log.warn("E-fatura mükellef kontrolü hatası: {}", e.getMessage());
            return false;
        }
    }

    /**
     * E-Fatura gönderir (müşteri e-fatura mükellefi ise)
     */
    public JsonNode eFaturaGonder(String faturaId, String scenario) {
        // scenario: "basic" veya "commercial"
        Map<String, Object> attributes = new java.util.HashMap<>();
        attributes.put("scenario", scenario != null ? scenario : "basic");
        attributes.put("to", "auto"); // Otomatik alıcı belirleme

        Map<String, Object> data = Map.of(
            "data", Map.of(
                "type", "e_invoices",
                "attributes", attributes,
                "relationships", Map.of(
                    "invoice", Map.of(
                        "data", Map.of(
                            "id", faturaId,
                            "type", "sales_invoices"
                        )
                    )
                )
            )
        );
        return apiCall("/e_invoices", HttpMethod.POST, data);
    }

    /**
     * E-Arşiv gönderir (müşteri e-fatura mükellefi değilse)
     */
    public JsonNode eArsivGonder(String faturaId, boolean internetSatisi) {
        Map<String, Object> attributes = new java.util.HashMap<>();
        if (internetSatisi) {
            attributes.put("internet_sale", true);
        }

        Map<String, Object> data = Map.of(
            "data", Map.of(
                "type", "e_archives",
                "attributes", attributes,
                "relationships", Map.of(
                    "sales_invoice", Map.of(
                        "data", Map.of(
                            "id", faturaId,
                            "type", "sales_invoices"
                        )
                    )
                )
            )
        );
        return apiCall("/e_archives", HttpMethod.POST, data);
    }

    /**
     * Faturayı otomatik olarak e-fatura veya e-arşiv olarak resmileştirir.
     * Müşteri e-fatura mükellefi ise e-fatura, değilse e-arşiv gönderir.
     */
    public JsonNode faturaResmilestir(String faturaId, String vergiNo, boolean internetSatisi) {
        long startTime = System.currentTimeMillis();
        String belgeTipi = eFaturaMukellefMi(vergiNo) ? "E-FATURA" : "E-ARSIV";

        try {
            JsonNode result;
            if ("E-FATURA".equals(belgeTipi)) {
                log.info("Müşteri e-fatura mükellefi, e-fatura gönderiliyor: {}", faturaId);
                result = eFaturaGonder(faturaId, "basic");
            } else {
                log.info("Müşteri e-fatura mükellefi değil, e-arşiv gönderiliyor: {}", faturaId);
                result = eArsivGonder(faturaId, internetSatisi);
            }

            long duration = System.currentTimeMillis() - startTime;
            activityLogService.log(
                    EntityTypeEnum.FATURA, faturaId,
                    ActivityEventTypeEnum.INVOICE_SENT,
                    ActivitySourceEnum.PARASUT,
                    ActivityStatusEnum.SUCCESS,
                    duration, null,
                    Map.of("belgeTipi", belgeTipi, "vergiNo", vergiNo != null ? vergiNo : ""),
                    null, null
            );
            return result;

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            activityLogService.log(
                    EntityTypeEnum.FATURA, faturaId,
                    ActivityEventTypeEnum.INVOICE_FAILED,
                    ActivitySourceEnum.PARASUT,
                    ActivityStatusEnum.ERROR,
                    duration, e.getMessage(),
                    Map.of("belgeTipi", belgeTipi),
                    null, null
            );
            throw e;
        }
    }

    // ============ FATURA DETAYLARI ============

    public JsonNode satisFaturasiDetaylariGetir(String faturaId) {
        // details.product ile ürün adlarını da çekiyoruz
        String endpoint = "/sales_invoices/" + faturaId + "?include=details.product,contact,payments,active_e_document";
        return apiCall(endpoint, HttpMethod.GET, null);
    }

    // ============ MÜŞTERİ ARAMA ============

    public JsonNode musteriAra(String query) {
        String endpoint = "/contacts?filter[name]=" + query;
        return apiCall(endpoint, HttpMethod.GET, null);
    }

    // ============ E-BELGE DURUM SORGULAMA ============

    public JsonNode eDocumentDurumuGetir(String faturaId) {
        return apiCall("/sales_invoices/" + faturaId + "/active_e_document", HttpMethod.GET, null);
    }

    // ============ E-BELGE PDF İNDİRME ============

    /**
     * Fatura için e-belge PDF'ini indirir (e-fatura veya e-arşiv)
     * @param faturaId Paraşüt fatura ID'si
     * @return PDF binary data veya null
     */
    public byte[] eDocumentPdfIndir(String faturaId) {
        try {
            // Önce fatura detaylarını çek (e-document dahil)
            JsonNode detay = satisFaturasiDetaylariGetir(faturaId);
            if (detay == null || !detay.has("included")) {
                log.warn("Fatura detayları alınamadı veya included yok: {}", faturaId);
                return null;
            }

            // E-document bilgisini bul
            String pdfPath = null;
            String eDocId = null;
            String eDocType = null;
            for (JsonNode item : detay.get("included")) {
                String type = item.get("type").asText();
                if ("e_invoices".equals(type) || "e_archives".equals(type)) {
                    eDocId = item.get("id").asText();
                    eDocType = type;

                    // links.pdf öncelikli (doğru endpoint)
                    if (item.has("links") && item.get("links").has("pdf")) {
                        pdfPath = item.get("links").get("pdf").asText();
                        log.info("E-belge PDF link bulundu ({}): {}", type, pdfPath);
                        break;
                    }
                    // Fallback: attributes.pdf_url
                    JsonNode attrs = item.get("attributes");
                    if (attrs != null && attrs.has("pdf_url") && !attrs.get("pdf_url").isNull()) {
                        pdfPath = attrs.get("pdf_url").asText();
                        log.info("E-belge PDF yolu bulundu ({}): {}", type, pdfPath);
                        break;
                    }
                }
            }

            if (pdfPath == null || pdfPath.isEmpty()) {
                log.warn("E-belge PDF URL'i bulunamadı: {}", faturaId);
                return null;
            }

            // PDF'i indir
            return downloadPdf(pdfPath, eDocType, eDocId);

        } catch (Exception e) {
            log.error("E-belge PDF indirme hatası: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Paraşüt API'den PDF indirir
     * Paraşüt önce S3 URL döndürür, sonra o URL'den gerçek PDF indirilir
     */
    private byte[] downloadPdf(String pdfPath, String eDocType, String eDocId) {
        String token = getAccessToken();
        if (token == null) {
            throw new RuntimeException("Paraşüt token alınamadı");
        }

        ApiAyar ayar = apiAyarService.getEntityByApiAdi(API_ADI);
        String companyId = ayar.getCompanyId();

        // PDF endpoint URL oluştur
        String pdfEndpoint;
        if (pdfPath.startsWith("/v4/")) {
            pdfEndpoint = "https://api.parasut.com" + pdfPath;
        } else if (eDocType != null && eDocId != null) {
            pdfEndpoint = "https://api.parasut.com/v4/" + companyId + "/" + eDocType + "/" + eDocId + "/pdf";
        } else {
            pdfEndpoint = "https://api.parasut.com/v4" + pdfPath;
        }

        log.info("PDF endpoint çağrılıyor: {}", pdfEndpoint);

        try {
            // Adım 1: Paraşüt API'den S3 URL al
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token);
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<?> request = new HttpEntity<>(headers);
            ResponseEntity<String> pdfUrlResponse = restTemplate.exchange(
                pdfEndpoint,
                HttpMethod.GET,
                request,
                String.class
            );

            if (!pdfUrlResponse.getStatusCode().is2xxSuccessful() || pdfUrlResponse.getBody() == null) {
                throw new RuntimeException("PDF URL alınamadı");
            }

            // JSON'dan S3 URL çıkar
            JsonNode responseJson = objectMapper.readTree(pdfUrlResponse.getBody());
            String s3Url = null;
            if (responseJson.has("data") &&
                responseJson.get("data").has("attributes") &&
                responseJson.get("data").get("attributes").has("url")) {
                s3Url = responseJson.get("data").get("attributes").get("url").asText();
            }

            if (s3Url == null || s3Url.isEmpty()) {
                throw new RuntimeException("S3 URL bulunamadı");
            }

            log.info("S3 URL alındı, PDF indiriliyor: {}...", s3Url.substring(0, Math.min(80, s3Url.length())));

            // Adım 2: S3'ten gerçek PDF'i indir (authentication gerekmez)
            // URI.create kullanarak URL'in tekrar encode edilmesini önlüyoruz
            HttpHeaders pdfHeaders = new HttpHeaders();
            pdfHeaders.setAccept(java.util.List.of(MediaType.APPLICATION_PDF, MediaType.APPLICATION_OCTET_STREAM));

            HttpEntity<?> pdfRequest = new HttpEntity<>(pdfHeaders);
            ResponseEntity<byte[]> pdfResponse = restTemplate.exchange(
                java.net.URI.create(s3Url),
                HttpMethod.GET,
                pdfRequest,
                byte[].class
            );

            if (pdfResponse.getStatusCode().is2xxSuccessful() && pdfResponse.getBody() != null) {
                log.info("PDF başarıyla indirildi: {} bytes", pdfResponse.getBody().length);
                return pdfResponse.getBody();
            }

            throw new RuntimeException("PDF indirilemedi");

        } catch (Exception e) {
            log.error("PDF indirme hatası: {}", e.getMessage());
            throw new RuntimeException("PDF indirilemedi: " + e.getMessage());
        }
    }

    // ============ FATURA SİLME/İPTAL ============

    public JsonNode satisFaturasiSil(String faturaId) {
        return apiCall("/sales_invoices/" + faturaId, HttpMethod.DELETE, null);
    }

    // ============ ÖDEMELİ FATURA OLUŞTURMA (TAM FORMAT) ============

    public JsonNode satisFaturasiOlusturTam(Map<String, Object> musteriData,
                                            java.util.List<Map<String, Object>> kalemler,
                                            String aciklama,
                                            String faturaTarihi) {
        // contactId yoksa önce Paraşüt'te müşteri oluştur
        String contactId = musteriData.get("contactId") != null ?
            musteriData.get("contactId").toString() : null;

        if (contactId == null || contactId.isEmpty()) {
            log.info("ContactId yok, Paraşüt'te yeni müşteri oluşturuluyor...");
            JsonNode yeniMusteri = musteriOlustur(musteriData);
            if (yeniMusteri != null && yeniMusteri.has("data") && yeniMusteri.get("data").has("id")) {
                contactId = yeniMusteri.get("data").get("id").asText();
                log.info("Yeni müşteri oluşturuldu, contactId: {}", contactId);
            } else {
                throw new RuntimeException("Müşteri oluşturulamadı");
            }
        }

        // Paraşüt JSON:API formatında fatura oluştur
        java.util.List<Map<String, Object>> detailsData = new java.util.ArrayList<>();

        for (Map<String, Object> kalem : kalemler) {
            Map<String, Object> detail = new java.util.HashMap<>();
            detail.put("type", "sales_invoice_details");

            Map<String, Object> attributes = new java.util.HashMap<>();
            attributes.put("quantity", kalem.get("miktar"));
            attributes.put("unit_price", kalem.get("birimFiyat"));
            attributes.put("vat_rate", kalem.get("kdvOrani"));
            attributes.put("description", kalem.get("aciklama"));

            detail.put("attributes", attributes);

            // Her fatura kalemi için ürün adıyla Paraşüt'te ürün oluştur/bul
            String urunAdi = kalem.get("aciklama") != null ? kalem.get("aciklama").toString() : "Ürün";
            String productId = kalem.get("productId") != null ?
                kalem.get("productId").toString() : getOrCreateUrunByName(urunAdi);

            detail.put("relationships", Map.of(
                "product", Map.of(
                    "data", Map.of(
                        "id", productId,
                        "type", "products"
                    )
                )
            ));

            detailsData.add(detail);
        }

        Map<String, Object> faturaAttributes = new java.util.HashMap<>();
        faturaAttributes.put("item_type", "invoice");
        faturaAttributes.put("description", aciklama);
        faturaAttributes.put("issue_date", faturaTarihi);
        faturaAttributes.put("due_date", faturaTarihi);
        faturaAttributes.put("invoice_series", "A");
        faturaAttributes.put("currency", "TRL");

        // Müşteri bilgileri (fatura üzerinde de gösterilecek)
        if (musteriData.get("name") != null) {
            faturaAttributes.put("billing_address", musteriData.get("address"));
            faturaAttributes.put("billing_phone", musteriData.get("phone"));
            faturaAttributes.put("city", musteriData.get("city"));
            faturaAttributes.put("district", musteriData.get("district"));
            faturaAttributes.put("tax_office", musteriData.get("taxOffice"));
            faturaAttributes.put("tax_number", musteriData.get("taxNumber"));
        }

        Map<String, Object> relationships = new java.util.HashMap<>();

        // Müşteri ilişkisi
        relationships.put("contact", Map.of(
            "data", Map.of(
                "id", contactId,
                "type", "contacts"
            )
        ));

        // Fatura kalemleri - doğrudan data içinde full object olarak
        relationships.put("details", Map.of("data", detailsData));

        Map<String, Object> requestBody = Map.of(
            "data", Map.of(
                "type", "sales_invoices",
                "attributes", faturaAttributes,
                "relationships", relationships
            )
        );

        return apiCall("/sales_invoices", HttpMethod.POST, requestBody);
    }

    /**
     * Ürün adına göre Paraşüt'te ürün bul veya oluştur.
     * Her fatura kalemi için doğru ürün adıyla gösterim sağlar.
     */
    private final java.util.Map<String, String> urunCache = new java.util.concurrent.ConcurrentHashMap<>();

    private synchronized String getOrCreateUrunByName(String urunAdi) {
        // Cache'de varsa döndür
        if (urunCache.containsKey(urunAdi)) {
            return urunCache.get(urunAdi);
        }

        try {
            // Önce mevcut ürünlerde ara (isim ile filtreleme)
            String encodedName = java.net.URLEncoder.encode(urunAdi, java.nio.charset.StandardCharsets.UTF_8);
            String endpoint = "/products?filter[name]=" + encodedName;
            JsonNode response = apiCall(endpoint, HttpMethod.GET, null);

            if (response != null && response.has("data") && response.get("data").size() > 0) {
                // Tam eşleşme kontrolü
                for (JsonNode urun : response.get("data")) {
                    if (urun.has("attributes")) {
                        String name = urun.get("attributes").has("name") ?
                            urun.get("attributes").get("name").asText() : "";
                        if (urunAdi.equals(name)) {
                            String productId = urun.get("id").asText();
                            urunCache.put(urunAdi, productId);
                            log.info("Paraşüt ürün bulundu: {} -> {}", urunAdi, productId);
                            return productId;
                        }
                    }
                }
            }

            // Ürün bulunamadı, yeni oluştur
            log.info("Paraşüt'te ürün oluşturuluyor: {}", urunAdi);

            Map<String, Object> attributes = new java.util.HashMap<>();
            attributes.put("name", urunAdi);
            attributes.put("vat_rate", 20);
            attributes.put("unit", "Adet");
            attributes.put("currency", "TRL");
            attributes.put("archived", false);

            Map<String, Object> urunData = Map.of(
                "data", Map.of(
                    "type", "products",
                    "attributes", attributes
                )
            );

            JsonNode createResponse = urunOlustur(urunData);
            if (createResponse != null && createResponse.has("data") && createResponse.get("data").has("id")) {
                String productId = createResponse.get("data").get("id").asText();
                urunCache.put(urunAdi, productId);
                log.info("Paraşüt ürün oluşturuldu: {} -> {}", urunAdi, productId);
                return productId;
            }

            throw new RuntimeException("Ürün oluşturulamadı: " + urunAdi);
        } catch (Exception e) {
            log.error("Ürün bulma/oluşturma hatası: {} - {}", urunAdi, e.getMessage());
            throw new RuntimeException("Paraşüt ürün hatası: " + e.getMessage());
        }
    }
}
