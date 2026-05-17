/*
 * Adapted from a production Spring Boot ERP system.
 *
 * NOTE: This service depends on the PaymentLink entity and PaymentLinkRepository.
 * You need to provide your own implementations matching the method signatures used here.
 *
 * Original author: Mehmet Eren Erdal <m.erenerdal@sevinclidoga.com>
 * License: MIT
 */
package com.example.integration.paytr;

import com.example.integration.entity.PaymentLink;
import com.example.integration.repository.PaymentLinkRepository;
import com.example.integration.paytr.config.PayTRConfig;
import com.example.integration.paytr.dto.request.LinkCreateRequest;
import com.example.integration.paytr.dto.request.LinkDeleteRequest;
import com.example.integration.paytr.dto.request.LinkEmailRequest;
import com.example.integration.paytr.dto.request.LinkSmsRequest;
import com.example.integration.paytr.dto.response.LinkCreateResponse;
import com.example.integration.paytr.dto.response.PayTRBaseResponse;
import com.example.integration.paytr.util.PayTRTokenUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
@Slf4j
public class PayTRLinkService {

    private static final String BASE_URL = "https://www.paytr.com/odeme/api/link";
    private static final DateTimeFormatter EXPIRY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final PayTRConfig payTRConfig;
    private final PaymentLinkRepository paymentLinkRepository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public PayTRLinkService(PayTRConfig payTRConfig,
                            PaymentLinkRepository paymentLinkRepository,
                            ObjectMapper objectMapper) {
        this.payTRConfig = payTRConfig;
        this.paymentLinkRepository = paymentLinkRepository;
        this.objectMapper = objectMapper;

        // Netty yerine JDK standart HttpURLConnection kullan (Cloudflare uyumluluğu)
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(20_000);
        factory.setReadTimeout(20_000);
        this.restTemplate = new RestTemplate(factory);
    }

    @Transactional
    public PaymentLink createLink(LinkCreateRequest request) {
        String merchantId = payTRConfig.getMerchantId();
        String merchantKey = payTRConfig.getMerchantKey();
        String merchantSalt = payTRConfig.getMerchantSalt();

        int priceKurus = request.getPriceInKurus();

        String token = PayTRTokenUtil.createLinkToken(
                request.getName(),
                priceKurus,
                request.getCurrency(),
                request.getMaxInstallment(),
                request.getLinkType(),
                request.getLang(),
                request.getMinCount(),
                request.getEmail(),
                merchantSalt,
                merchantKey
        );

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("merchant_id", merchantId);
        params.add("name", request.getName());
        params.add("price", String.valueOf(priceKurus));
        params.add("currency", request.getCurrency());
        params.add("max_installment", request.getMaxInstallment());
        params.add("link_type", request.getLinkType());
        params.add("lang", request.getLang());
        params.add("paytr_token", token);
        params.add("debug_on", payTRConfig.isDebugOn() ? "1" : "0");

        // Tip'e göre zorunlu opsiyonel alanlar
        if ("product".equals(request.getLinkType())) {
            params.add("min_count", String.valueOf(request.getMinCount() != null ? request.getMinCount() : 1));
        } else if ("collection".equals(request.getLinkType())) {
            params.add("email", request.getEmail());
        }

        // Opsiyonel alanlar
        if (request.getExpiryDate() != null && !request.getExpiryDate().isBlank()) {
            params.add("expiry_date", request.getExpiryDate());
        }
        if (request.getMaxCount() != null) {
            params.add("max_count", String.valueOf(request.getMaxCount()));
        }
        if (request.getCallbackLink() != null && !request.getCallbackLink().isBlank()) {
            params.add("callback_link", request.getCallbackLink());
        }
        if (request.getCallbackId() != null && !request.getCallbackId().isBlank()) {
            params.add("callback_id", request.getCallbackId());
        }
        if (Boolean.TRUE.equals(request.getGetQr())) {
            params.add("get_qr", "1");
        }
        if (request.getPft() != null) {
            params.add("pft", String.valueOf(request.getPft()));
        }

        log.info("PayTR link oluşturma isteği: name={}, price={} kuruş, currency={}, linkType={}",
                request.getName(), priceKurus, request.getCurrency(), request.getLinkType());

        LinkCreateResponse response = postToPayTR(BASE_URL + "/create", params, LinkCreateResponse.class);

        // DB'ye kaydet
        PaymentLink paymentLink = new PaymentLink();
        paymentLink.setPaytrLinkId(response.getId());
        paymentLink.setLink(response.getLink());
        paymentLink.setCallbackId(request.getCallbackId());
        paymentLink.setName(request.getName());
        paymentLink.setAmount(request.getPrice());
        paymentLink.setCurrency(request.getCurrency());
        paymentLink.setLinkType(request.getLinkType());
        paymentLink.setStatus("CREATED");

        if (request.getExpiryDate() != null && !request.getExpiryDate().isBlank()) {
            paymentLink.setExpiryDate(LocalDateTime.parse(request.getExpiryDate(), EXPIRY_FORMAT));
        }
        if (response.getQrCode() != null) {
            paymentLink.setQrCode(response.getQrCode());
        }

        paymentLink = paymentLinkRepository.save(paymentLink);
        log.info("PayTR link oluşturuldu: paytrId={}, link={}", response.getId(), response.getLink());

        return paymentLink;
    }

    public void deleteLink(LinkDeleteRequest request) {
        String merchantId = payTRConfig.getMerchantId();
        String merchantKey = payTRConfig.getMerchantKey();
        String merchantSalt = payTRConfig.getMerchantSalt();

        String token = PayTRTokenUtil.deleteLinkToken(
                request.getId(), merchantId, merchantSalt, merchantKey
        );

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("merchant_id", merchantId);
        params.add("id", request.getId());
        params.add("paytr_token", token);
        params.add("debug_on", payTRConfig.isDebugOn() ? "1" : "0");

        log.info("PayTR link silme isteği: id={}", request.getId());

        postToPayTR(BASE_URL + "/delete", params, PayTRBaseResponse.class);

        // DB'den de güncelle
        paymentLinkRepository.findByPaytrLinkId(request.getId()).ifPresent(link -> {
            link.setStatus("DELETED");
            paymentLinkRepository.save(link);
        });

        log.info("PayTR link silindi: id={}", request.getId());
    }

    public void sendSms(LinkSmsRequest request) {
        String merchantId = payTRConfig.getMerchantId();
        String merchantKey = payTRConfig.getMerchantKey();
        String merchantSalt = payTRConfig.getMerchantSalt();

        String token = PayTRTokenUtil.smsToken(
                request.getId(), merchantId, request.getCellPhone(), merchantSalt, merchantKey
        );

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("merchant_id", merchantId);
        params.add("id", request.getId());
        params.add("cell_phone", request.getCellPhone());
        params.add("paytr_token", token);
        params.add("debug_on", payTRConfig.isDebugOn() ? "1" : "0");

        log.info("PayTR SMS gönderme isteği: linkId={}, telefon={}", request.getId(), request.getCellPhone());

        postToPayTR(BASE_URL + "/send-sms", params, PayTRBaseResponse.class);

        log.info("PayTR SMS gönderildi: linkId={}", request.getId());
    }

    public void sendEmail(LinkEmailRequest request) {
        String merchantId = payTRConfig.getMerchantId();
        String merchantKey = payTRConfig.getMerchantKey();
        String merchantSalt = payTRConfig.getMerchantSalt();

        String token = PayTRTokenUtil.emailToken(
                request.getId(), merchantId, request.getEmail(), merchantSalt, merchantKey
        );

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("merchant_id", merchantId);
        params.add("id", request.getId());
        params.add("email", request.getEmail());
        params.add("paytr_token", token);
        params.add("debug_on", payTRConfig.isDebugOn() ? "1" : "0");

        log.info("PayTR email gönderme isteği: linkId={}, email={}", request.getId(), request.getEmail());

        postToPayTR(BASE_URL + "/send-email", params, PayTRBaseResponse.class);

        log.info("PayTR email gönderildi: linkId={}", request.getId());
    }

    private <T extends PayTRBaseResponse> T postToPayTR(String url, MultiValueMap<String, String> params, Class<T> responseType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(params, headers);

        // PayTR text/html content-type ile JSON dönüyor, bu yüzden önce String olarak alıp parse ediyoruz
        ResponseEntity<String> responseEntity;
        try {
            responseEntity = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
        } catch (Exception e) {
            log.error("PayTR API çağrısı başarısız: url={}, hata={}", url, e.getMessage());
            throw new RuntimeException("PayTR API bağlantı hatası: " + e.getMessage(), e);
        }

        String body = responseEntity.getBody();
        if (body == null || body.isBlank()) {
            log.error("PayTR API boş yanıt döndü: url={}", url);
            throw new RuntimeException("PayTR API boş yanıt döndü");
        }

        log.debug("PayTR API raw yanıt: url={}, body={}", url, body);

        T response;
        try {
            response = objectMapper.readValue(body, responseType);
        } catch (Exception e) {
            log.error("PayTR API yanıt parse hatası: url={}, body={}, hata={}", url, body, e.getMessage());
            throw new RuntimeException("PayTR API yanıt parse hatası: " + body, e);
        }

        if (!response.isSuccess()) {
            String errorMsg = response.getErrorMessage();
            log.error("PayTR API hata: url={}, status={}, mesaj={}", url, response.getStatus(), errorMsg);
            throw new RuntimeException("PayTR API hatası: " + errorMsg);
        }

        log.debug("PayTR API başarılı: url={}", url);
        return response;
    }
}
