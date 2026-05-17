package com.example.integration.paytr.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class LinkCreateResponse extends PayTRBaseResponse {

    private String id;
    private String link;

    @JsonProperty("qr_code")
    private String qrCode;
}
