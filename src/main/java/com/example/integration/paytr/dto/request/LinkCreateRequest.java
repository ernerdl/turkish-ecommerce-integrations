package com.example.integration.paytr.dto.request;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class LinkCreateRequest {

    private String name;
    private BigDecimal price; // TL cinsinden (ör: 14.45)
    @Builder.Default
    private String currency = "TL";
    @Builder.Default
    private String maxInstallment = "1";
    @Builder.Default
    private String linkType = "product";
    @Builder.Default
    private String lang = "tr";

    // product tipinde zorunlu
    @Builder.Default
    private Integer minCount = 1;

    // collection tipinde zorunlu
    private String email;

    // Opsiyonel
    private String expiryDate; // "2026-05-31 17:00:00"
    private Integer maxCount;
    private String callbackLink;
    private String callbackId;
    @Builder.Default
    private Boolean getQr = false;
    private Integer pft; // 2-12 arası, peşin fiyatına taksit

    public int getPriceInKurus() {
        return price.multiply(BigDecimal.valueOf(100)).intValue();
    }
}
