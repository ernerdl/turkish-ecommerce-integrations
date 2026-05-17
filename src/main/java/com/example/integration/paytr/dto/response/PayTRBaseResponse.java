package com.example.integration.paytr.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class PayTRBaseResponse {

    private String status;

    @JsonProperty("err_msg")
    private String errMsg;

    // PayTR bazen "reason" bazen "err_msg" dönüyor
    private String reason;

    public boolean isSuccess() {
        return "success".equalsIgnoreCase(status);
    }

    public String getErrorMessage() {
        if (errMsg != null && !errMsg.isBlank()) return errMsg;
        if (reason != null && !reason.isBlank()) return reason;
        return "Bilinmeyen hata (status: " + status + ")";
    }
}
