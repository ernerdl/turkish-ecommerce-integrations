/*
 * Adapted from a production Spring Boot ERP system.
 *
 * NOTE: This component depends on the SatisKanali entity and an IntegrationException class.
 * You need to provide your own implementations matching the method signatures used here.
 *
 * Original author: Mehmet Eren Erdal <m.erenerdal@sevinclidoga.com>
 * License: MIT
 */
package com.example.integration.ikas;

import com.fasterxml.jackson.databind.JsonNode;
import com.example.integration.entity.SatisKanali;
import com.example.integration.exception.IntegrationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * İkas API Token Yönetici Bileşeni
 *
 * OAuth 2.0 token yönetimini merkezi olarak yapar.
 * Token'ı memory'de cache'ler ve gerektiğinde yeniler.
 */
@Component
@Slf4j
public class IkasTokenManager {

    private String accessToken;
    private LocalDateTime tokenExpiry;

    // Token yenileme için güvenlik marjı (saniye)
    private static final int TOKEN_EXPIRY_BUFFER_SECONDS = 60;

    /**
     * Token'ın geçerli olup olmadığını kontrol eder.
     *
     * @return true: token geçerli, false: token geçersiz veya yok
     */
    public boolean isTokenValid() {
        return accessToken != null
                && tokenExpiry != null
                && LocalDateTime.now().isBefore(tokenExpiry);
    }

    /**
     * Mevcut access token'ı döndürür.
     *
     * @return Access token veya null
     */
    public String getAccessToken() {
        return accessToken;
    }

    /**
     * Token'ı ayarlar ve expiry süresini hesaplar.
     *
     * @param token Access token
     * @param expiresInSeconds Token geçerlilik süresi (saniye)
     */
    public void setToken(String token, int expiresInSeconds) {
        this.accessToken = token;
        this.tokenExpiry = LocalDateTime.now().plusSeconds(expiresInSeconds - TOKEN_EXPIRY_BUFFER_SECONDS);
        log.debug("Token ayarlandı, geçerlilik: {} saniye", expiresInSeconds - TOKEN_EXPIRY_BUFFER_SECONDS);
    }

    /**
     * Token'ı geçersiz kılar (logout veya hata durumunda).
     */
    public void invalidate() {
        this.accessToken = null;
        this.tokenExpiry = null;
        log.debug("Token geçersiz kılındı");
    }

    /**
     * Token'ın geçerli olduğundan emin olur, gerekirse yeniler.
     *
     * @param kanal Satış kanalı (API bilgileri için)
     * @return Geçerli access token
     * @throws IntegrationException Token alınamazsa
     */
    public String ensureValidToken(SatisKanali kanal) {
        if (isTokenValid()) {
            return accessToken;
        }

        log.info("İkas yeni token alınıyor...");

        String tokenUrl = "https://" + kanal.getMagazaId() + ".myikas.com/api/admin/oauth/token";

        WebClient client = WebClient.builder()
                .baseUrl(tokenUrl)
                .build();

        Map<String, String> body = new HashMap<>();
        body.put("grant_type", "client_credentials");
        body.put("client_id", kanal.getApiAnahtar());
        body.put("client_secret", kanal.getApiGizliAnahtar());

        try {
            JsonNode response = client.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (response != null && response.has("access_token")) {
                String token = response.get("access_token").asText();
                int expiresIn = response.path("expires_in").asInt(3600);
                setToken(token, expiresIn);
                log.info("İkas Token alındı (geçerlilik: {} saniye)", expiresIn);
                return token;
            } else {
                throw new IntegrationException("IKAS", "TOKEN_ERROR", "Token alınamadı: " + response);
            }
        } catch (IntegrationException e) {
            throw e;
        } catch (Exception e) {
            throw new IntegrationException("IKAS", "TOKEN_ERROR", "Token alınırken hata: " + e.getMessage(), e);
        }
    }

    /**
     * Token'ın kalan geçerlilik süresini döndürür.
     *
     * @return Kalan süre (saniye) veya -1 (geçersiz token)
     */
    public long getRemainingValiditySeconds() {
        if (!isTokenValid()) {
            return -1;
        }
        return java.time.Duration.between(LocalDateTime.now(), tokenExpiry).getSeconds();
    }
}
