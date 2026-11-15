package com.proofly.notification;

import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Service
public class SmsSender {

    private static final Logger log = LoggerFactory.getLogger(SmsSender.class);

    private final NotificationProperties props;
    private final RestClient restClient;

    public SmsSender(NotificationProperties props, RestClient.Builder restClientBuilder) {
        this.props = props;
        this.restClient = restClientBuilder.build();
    }

    @Retry(name = "notification")
    public void send(String phoneE164, String textBody, String templateCode) {
        if ("log".equalsIgnoreCase(props.smsProvider()) || props.smsApiUrl() == null || props.smsApiUrl().isBlank()) {
            // Dev mode: log masked recipient & template code (never raw PINs/tokens)
            String masked = phoneE164.length() > 4
                    ? "***" + phoneE164.substring(phoneE164.length() - 4)
                    : "****";
            log.info("SMS [provider=log] to={} template={} length={}", masked, templateCode, textBody.length());
            return;
        }

        restClient.post()
                .uri(props.smsApiUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + props.smsApiKey())
                .body(Map.of(
                        "to", phoneE164,
                        "from", props.smsSenderId(),
                        "sms", textBody
                ))
                .retrieve()
                .toBodilessEntity();
    }
}
