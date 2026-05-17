package com.example.integration.paytr.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LinkSmsRequest {
    private String id;
    private String cellPhone;
}
