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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.integration.entity.OlcuBirimi;
import com.example.integration.entity.SatisKanali;
import com.example.integration.repository.OlcuBirimiRepository;
import com.example.integration.repository.SatisKanaliRepository;
import com.example.integration.repository.UrunRepository;
import com.example.integration.repository.UrunVaryantiRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.*;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * İkas Ürün Senkronizasyon Servisi
 *
 * İkas'tan ürünleri çeker ve ERP'ye senkronize eder.
 * Her ürün ayrı transaction'da işlenir (IkasUrunIslemService).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class IkasUrunSenkronService {

    private final SatisKanaliRepository satisKanaliRepository;
    private final UrunRepository urunRepository;
    private final UrunVaryantiRepository urunVaryantiRepository;
    private final OlcuBirimiRepository olcuBirimiRepository;
    private final IkasTokenManager tokenManager;
    private final IkasUrunIslemService ikasUrunIslemService;
    private final ObjectMapper objectMapper;

    private static final Locale TR = new Locale("tr", "TR");
    private static final int SAYFA_LIMIT = 100;
    private static final int MAX_SAYFA_LIMITI = 50;

    /**
     * İkas'tan tüm ürünleri çeker ve konsola yazdırır.
     */
    public void urunleriListele() {
        Optional<SatisKanali> kanalOpt = satisKanaliRepository.findByKanalKodu("IKAS");
        if (kanalOpt.isEmpty()) {
            log.error("IKAS kanalı tanımlı değil!");
            return;
        }

        SatisKanali kanal = kanalOpt.get();

        if (kanal.getApiAnahtar() == null || kanal.getApiGizliAnahtar() == null || kanal.getMagazaId() == null) {
            log.error("İkas API bilgileri eksik!");
            return;
        }

        log.info("İkas Ürün Listesi Çekiliyor...");

        try {
            String accessToken = tokenManager.ensureValidToken(kanal);

            WebClient webClient = WebClient.builder()
                    .baseUrl("https://api.myikas.com/api/v1/admin/graphql")
                    .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                    .build();

            int toplamUrun = 0;
            int sayfa = 1;
            boolean devamEt = true;

            log.info("===================================================================");
            log.info("                    IKAS URUN LISTESI                              ");
            log.info("===================================================================");

            while (devamEt) {
                String query = buildProductQuery(sayfa, SAYFA_LIMIT);
                log.debug("GraphQL Query: {}", query);

                Map<String, Object> requestBody = new HashMap<>();
                requestBody.put("query", query);

                JsonNode response = webClient.post()
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(requestBody)
                        .exchangeToMono(clientResponse -> {
                            log.info("Response status: {}", clientResponse.statusCode());
                            return clientResponse.bodyToMono(JsonNode.class);
                        })
                        .block();

                log.info("Response: {}", response);

                if (response == null || !response.has("data") || !response.get("data").has("listProduct")) {
                    log.warn("İkas yanıtı boş veya format hatalı");
                    if (response != null && response.has("errors")) {
                        log.error("GraphQL Hata: {}", response.get("errors"));
                    }
                    break;
                }

                JsonNode listProductNode = response.get("data").get("listProduct");
                JsonNode productsNode = listProductNode.path("data");

                boolean hasNext = listProductNode.path("hasNext").asBoolean(false);
                int count = listProductNode.path("count").asInt(0);

                if (!productsNode.isArray() || productsNode.size() == 0) {
                    if (sayfa == 1) {
                        log.info("Hiç ürün bulunamadı");
                    }
                    break;
                }

                log.info("\nSayfa {}/{} ({} ürün)", sayfa, (count / SAYFA_LIMIT) + 1, productsNode.size());
                log.info("-------------------------------------------------------------------");

                for (JsonNode product : productsNode) {
                    toplamUrun++;
                    printProduct(product, toplamUrun);
                }

                if (hasNext && sayfa < MAX_SAYFA_LIMITI) {
                    sayfa++;
                } else {
                    devamEt = false;
                }
            }

            log.info("===================================================================");
            log.info("Toplam {} ürün listelendi", toplamUrun);
            log.info("===================================================================");

        } catch (Exception e) {
            log.error("İkas API Hatası: {}", e.getMessage(), e);
        }
    }

    /**
     * Tek ürünü formatla ve yazdır
     */
    private void printProduct(JsonNode product, int index) {
        String id = product.path("id").asText("-");
        String name = product.path("name").asText("-");
        String type = product.path("type").asText("-");

        log.info("\n{}. {}", index, name);
        log.info("   ID: {}", id);
        log.info("   Tip: {}", type);

        // Varyantları listele
        JsonNode variants = product.path("variants");
        if (variants.isArray() && variants.size() > 0) {
            log.info("   Varyantlar ({}):", variants.size());
            for (JsonNode variant : variants) {
                String sku = variant.path("sku").asText("-");
                double weight = variant.path("weight").asDouble(0);

                // Fiyat nested olabilir
                JsonNode prices = variant.path("prices");
                double price = 0;
                if (prices.isArray() && prices.size() > 0) {
                    price = prices.get(0).path("sellPrice").asDouble(0);
                }

                JsonNode barcodes = variant.path("barcodeList");
                String barkod = barcodes.isArray() && barcodes.size() > 0 ? barcodes.get(0).asText("-") : "-";

                log.info("      SKU: {} | Barkod: {} | Ağırlık: {} | Fiyat: {}",
                    sku, barkod, weight, price);
            }
        }
    }

    /**
     * GraphQL ürün sorgusu oluşturur.
     * İkas API v1 şemasına göre düzenlenmiştir.
     */
    private String buildProductQuery(int page, int limit) {
        return "{ listProduct(" +
                "pagination: { page: " + page + ", limit: " + limit + " }" +
                ") { " +
                "count hasNext page limit " +
                "data { " +
                "id name type " +
                "variants { id sku barcodeList weight " +
                "prices { sellPrice } " +
                "} " +
                "} } }";
    }

    /**
     * İkas ürünlerini ERP ile senkronize eder.
     * - Mevcut ürünlerin isimleri güncellenir
     * - Eksik ürünler eklenir
     * - Diğer bilgiler (reçete, stok vb.) korunur
     *
     * NOT @Transactional: Her ürün IkasUrunIslemService üzerinden
     * ayrı transaction'da işlenir. Bu sayede bir üründeki hata
     * diğerlerini etkilemez.
     *
     * @return Senkronizasyon sonuç raporu
     */
    public Map<String, Object> senkronizeEt() {
        Map<String, Object> sonuc = new HashMap<>();
        List<String> eklenenler = new ArrayList<>();
        List<String> guncellenenler = new ArrayList<>();
        List<String> eslesenler = new ArrayList<>();
        List<String> hatalar = new ArrayList<>();

        Optional<SatisKanali> kanalOpt = satisKanaliRepository.findByKanalKodu("IKAS");
        if (kanalOpt.isEmpty()) {
            sonuc.put("hata", "IKAS kanalı tanımlı değil!");
            return sonuc;
        }

        SatisKanali kanal = kanalOpt.get();

        if (kanal.getApiAnahtar() == null || kanal.getApiGizliAnahtar() == null || kanal.getMagazaId() == null) {
            sonuc.put("hata", "İkas API bilgileri eksik!");
            return sonuc;
        }

        log.info("===================================================================");
        log.info("İkas Ürün Senkronizasyonu Başlıyor...");
        log.info("===================================================================");

        try {
            String accessToken = tokenManager.ensureValidToken(kanal);

            WebClient webClient = WebClient.builder()
                    .baseUrl("https://api.myikas.com/api/v1/admin/graphql")
                    .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                    .build();

            // Default birim (KG) al
            OlcuBirimi kgBirim = olcuBirimiRepository.findByBirimKodu("KG")
                    .orElseThrow(() -> new RuntimeException("KG birimi bulunamadı!"));

            // ÖNEMLİ: Tüm mevcut SKU'ları önceden yükle (Hibernate cache sorununu önler)
            Set<String> mevcutSkular = urunVaryantiRepository.findAllSkus().stream()
                    .map(s -> s.toUpperCase(TR))
                    .collect(Collectors.toCollection(HashSet::new));
            log.info("Veritabanında {} mevcut SKU bulundu", mevcutSkular.size());

            // Mevcut ürün kodlarını da önceden yükle
            Set<String> mevcutUrunKodlari = urunRepository.findAllUrunKodlari().stream()
                    .collect(Collectors.toCollection(HashSet::new));
            log.info("Veritabanında {} mevcut ürün kodu bulundu", mevcutUrunKodlari.size());

            int sayfa = 1;
            int toplamIslenen = 0;
            boolean devamEt = true;

            while (devamEt) {
                String query = buildProductQuery(sayfa, SAYFA_LIMIT);

                Map<String, Object> requestBody = new HashMap<>();
                requestBody.put("query", query);

                JsonNode response = webClient.post()
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(requestBody)
                        .exchangeToMono(clientResponse -> clientResponse.bodyToMono(JsonNode.class))
                        .block();

                if (response == null || !response.has("data") || !response.get("data").has("listProduct")) {
                    log.warn("İkas yanıtı boş veya format hatalı (sayfa {})", sayfa);
                    break;
                }

                JsonNode listProductNode = response.get("data").get("listProduct");
                JsonNode productsNode = listProductNode.path("data");
                boolean hasNext = listProductNode.path("hasNext").asBoolean(false);

                if (!productsNode.isArray() || productsNode.size() == 0) {
                    break;
                }

                log.info("-------------------------------------------------------------------");
                log.info("Sayfa {} işleniyor ({} ürün)...", sayfa, productsNode.size());

                // Her ürünü ayrı transaction'da işle
                for (JsonNode product : productsNode) {
                    toplamIslenen++;
                    String urunAdi = product.path("name").asText("?");
                    try {
                        ikasUrunIslemService.processProduct(product, kgBirim,
                                mevcutSkular, mevcutUrunKodlari,
                                eklenenler, guncellenenler, eslesenler);
                    } catch (Exception e) {
                        hatalar.add(urunAdi + ": " + e.getMessage());
                        log.error("HATA - Ürün işlenemedi: {} | Sebep: {}", urunAdi, e.getMessage(), e);
                    }
                }

                if (hasNext && sayfa < MAX_SAYFA_LIMITI) {
                    sayfa++;
                } else {
                    devamEt = false;
                }
            }

            // Son senkron tarihini güncelle (ayrı transaction)
            ikasUrunIslemService.updateSonSenkronTarihi(kanal.getId());

            log.info("===================================================================");
            log.info("İkas Ürün Senkronizasyonu Tamamlandı");
            log.info("   Toplam işlenen ürün: {}", toplamIslenen);
            log.info("   Yeni eklenen: {}", eklenenler.size());
            log.info("   Güncellenen: {}", guncellenenler.size());
            log.info("   Zaten eşleşen: {}", eslesenler.size());
            log.info("   Hata: {}", hatalar.size());
            if (!hatalar.isEmpty()) {
                log.info("   Hatalı ürünler:");
                hatalar.forEach(h -> log.info("      - {}", h));
            }
            log.info("===================================================================");

        } catch (Exception e) {
            log.error("İkas Senkronizasyon Genel Hatası: {}", e.getMessage(), e);
            sonuc.put("hata", e.getMessage());
        }

        sonuc.put("eklenen", eklenenler);
        sonuc.put("guncellenen", guncellenenler);
        sonuc.put("eslesen", eslesenler);
        sonuc.put("hatalar", hatalar);
        sonuc.put("toplam", eklenenler.size() + guncellenenler.size() + eslesenler.size());

        return sonuc;
    }
}
