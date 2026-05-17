/*
 * Adapted from a production Spring Boot ERP system.
 *
 * NOTE: This config depends on the ApiAyar entity, ApiAyarService, and an
 * EncryptionUtil. You need to provide your own implementations matching
 * the method signatures used here.
 *
 * Original author: Mehmet Eren Erdal <m.erenerdal@sevinclidoga.com>
 * License: MIT
 */
package com.example.integration.paytr.config;

import com.example.integration.entity.ApiAyar;
import com.example.integration.service.ApiAyarService;
import com.example.integration.util.EncryptionUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

@Component
@RequiredArgsConstructor
@Slf4j
public class PayTRConfig {

    private static final String API_ADI = "paytr";
    private static final long CACHE_TTL_MS = 5 * 60 * 1000; // 5 dakika

    private final ApiAyarService apiAyarService;
    private final EncryptionUtil encryptionUtil;

    private final AtomicReference<CachedCredentials> cachedCredentials = new AtomicReference<>();

    public String getMerchantId() {
        return getCredentials().merchantId;
    }

    public String getMerchantKey() {
        return getCredentials().merchantKey;
    }

    public String getMerchantSalt() {
        return getCredentials().merchantSalt;
    }

    public boolean isDebugOn() {
        return getCredentials().testModu;
    }

    private CachedCredentials getCredentials() {
        CachedCredentials cached = cachedCredentials.get();
        if (cached != null && !cached.isExpired()) {
            return cached;
        }

        synchronized (this) {
            cached = cachedCredentials.get();
            if (cached != null && !cached.isExpired()) {
                return cached;
            }

            ApiAyar ayar = apiAyarService.getEntityByApiAdi(API_ADI);
            if (ayar == null) {
                throw new IllegalStateException("PayTR API ayarları veritabanında bulunamadı. " +
                        "API Yönetimi sayfasından 'paytr' adıyla ODEME tipinde bir kayıt oluşturun.");
            }

            if (!Boolean.TRUE.equals(ayar.getAktif())) {
                throw new IllegalStateException("PayTR API ayarları pasif durumda.");
            }

            String merchantId = ayar.getCompanyId();
            String merchantKey = decryptSafe(ayar.getClientSecretEncrypted(), "merchant_key");
            String merchantSalt = decryptSafe(ayar.getApiKeyEncrypted(), "merchant_salt");

            if (merchantId == null || merchantId.isBlank()) {
                throw new IllegalStateException("PayTR Merchant ID (company_id) tanımlı değil.");
            }
            if (merchantKey == null || merchantKey.isBlank()) {
                throw new IllegalStateException("PayTR Merchant Key tanımlı değil.");
            }
            if (merchantSalt == null || merchantSalt.isBlank()) {
                throw new IllegalStateException("PayTR Merchant Salt tanımlı değil.");
            }

            CachedCredentials newCached = new CachedCredentials(
                    merchantId, merchantKey, merchantSalt,
                    Boolean.TRUE.equals(ayar.getTestModu()),
                    System.currentTimeMillis()
            );
            cachedCredentials.set(newCached);
            log.info("PayTR credential'ları DB'den yenilendi (merchantId: {})", merchantId);
            return newCached;
        }
    }

    private String decryptSafe(String encrypted, String fieldName) {
        if (encrypted == null || encrypted.isBlank()) {
            return null;
        }
        try {
            return encryptionUtil.decrypt(encrypted);
        } catch (Exception e) {
            log.error("PayTR {} şifre çözme hatası: {}", fieldName, e.getMessage());
            throw new IllegalStateException("PayTR " + fieldName + " şifresi çözülemedi.");
        }
    }

    public void invalidateCache() {
        cachedCredentials.set(null);
        log.info("PayTR credential cache temizlendi");
    }

    private record CachedCredentials(
            String merchantId,
            String merchantKey,
            String merchantSalt,
            boolean testModu,
            long cachedAt
    ) {
        boolean isExpired() {
            return System.currentTimeMillis() - cachedAt > CACHE_TTL_MS;
        }
    }
}
