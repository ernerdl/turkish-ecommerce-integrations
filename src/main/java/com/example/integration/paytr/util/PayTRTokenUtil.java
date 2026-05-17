/*
 * Adapted from a production Spring Boot ERP system.
 *
 * Self-contained PayTR HMAC-SHA256 token utility. No external dependencies
 * beyond the JDK standard library.
 *
 * Original author: Mehmet Eren Erdal <m.erenerdal@sevinclidoga.com>
 * License: MIT
 */
package com.example.integration.paytr.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class PayTRTokenUtil {

    private static final String HMAC_SHA256 = "HmacSHA256";

    private PayTRTokenUtil() {
    }

    /**
     * Link Create token.
     * Sıralama: name + price + currency + max_installment + link_type + lang
     * link_type == "product" ise + min_count
     * link_type == "collection" ise + email
     * Son: + merchant_salt
     */
    public static String createLinkToken(String name, int price, String currency,
                                         String maxInstallment, String linkType, String lang,
                                         Integer minCount, String email,
                                         String merchantSalt, String merchantKey) {
        StringBuilder sb = new StringBuilder();
        sb.append(name);
        sb.append(price);
        sb.append(currency);
        sb.append(maxInstallment);
        sb.append(linkType);
        sb.append(lang);

        if ("product".equals(linkType)) {
            sb.append(minCount != null ? minCount : 1);
        } else if ("collection".equals(linkType)) {
            sb.append(email != null ? email : "");
        }

        sb.append(merchantSalt);

        return hmacSha256Base64(sb.toString(), merchantKey);
    }

    /**
     * Link Delete token.
     * Sıralama: id + merchant_id + merchant_salt
     */
    public static String deleteLinkToken(String linkId, String merchantId,
                                         String merchantSalt, String merchantKey) {
        String hashString = linkId + merchantId + merchantSalt;
        return hmacSha256Base64(hashString, merchantKey);
    }

    /**
     * SMS token.
     * Sıralama: id + merchant_id + cell_phone + merchant_salt
     */
    public static String smsToken(String linkId, String merchantId, String cellPhone,
                                  String merchantSalt, String merchantKey) {
        String hashString = linkId + merchantId + cellPhone + merchantSalt;
        return hmacSha256Base64(hashString, merchantKey);
    }

    /**
     * Email token.
     * Sıralama: id + merchant_id + email + merchant_salt
     */
    public static String emailToken(String linkId, String merchantId, String email,
                                    String merchantSalt, String merchantKey) {
        String hashString = linkId + merchantId + email + merchantSalt;
        return hmacSha256Base64(hashString, merchantKey);
    }

    /**
     * Callback hash doğrulama.
     * Sıralama (PayTR dökümanı): merchant_oid + merchant_salt + status + total_amount
     */
    public static String callbackHash(String merchantOid,
                                      String merchantSalt, String status, String totalAmount,
                                      String merchantKey) {
        String hashString = merchantOid + merchantSalt + status + totalAmount;
        return hmacSha256Base64(hashString, merchantKey);
    }

    public static boolean verifyCallbackHash(String merchantOid,
                                             String merchantSalt, String status, String totalAmount,
                                             String merchantKey, String receivedHash) {
        String expectedHash = callbackHash(merchantOid, merchantSalt, status, totalAmount, merchantKey);
        return expectedHash.equals(receivedHash);
    }

    private static String hmacSha256Base64(String data, String key) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            SecretKeySpec secretKeySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), HMAC_SHA256);
            mac.init(secretKeySpec);
            byte[] hmacBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hmacBytes);
        } catch (Exception e) {
            throw new IllegalStateException("PayTR token hesaplama hatası", e);
        }
    }
}
