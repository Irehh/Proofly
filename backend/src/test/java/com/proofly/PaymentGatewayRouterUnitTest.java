package com.proofly;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.proofly.billing.BillingProperties;
import com.proofly.billing.GatewayCode;
import com.proofly.billing.PaymentGatewayRouter;
import com.proofly.billing.PaystackGatewayAdapter;
import com.proofly.billing.StripeGatewayAdapter;
import com.proofly.common.ProoflyException;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentGatewayRouterUnitTest {

    private final BillingProperties props = new BillingProperties(
            "NGN",
            new BillingProperties.PaystackProps(
                    "https://api.paystack.co", "sk_ps", "pk_ps", "wh_ps", "http://localhost:3000/cb",
                    List.of("NGN", "GHS", "KES")
            ),
            new BillingProperties.StripeProps(
                    "https://api.stripe.com", "sk_st", "pk_st", "whsec_unit_test", 300L,
                    "http://localhost:3000/cb", "http://localhost:3000/cancel",
                    List.of("USD", "GBP")
            ),
            5, 1_000_000L, 5, 48, 25
    );

    @Test
    void routesCurrenciesToExpectedGatewayAndRejectsUnsupported() {
        var mapper = new ObjectMapper();
        var paystack = new PaystackGatewayAdapter(props, RestClient.builder(), mapper);
        var stripe = new StripeGatewayAdapter(props, RestClient.builder(), mapper);
        var router = new PaymentGatewayRouter(List.of(paystack, stripe), props);

        assertThat(router.gatewayCodeForCurrency("ngn")).isEqualTo(GatewayCode.PAYSTACK);
        assertThat(router.gatewayCodeForCurrency("GHS")).isEqualTo(GatewayCode.PAYSTACK);
        assertThat(router.gatewayCodeForCurrency("KES")).isEqualTo(GatewayCode.PAYSTACK);
        assertThat(router.gatewayCodeForCurrency("usd")).isEqualTo(GatewayCode.STRIPE);
        assertThat(router.gatewayCodeForCurrency("GBP")).isEqualTo(GatewayCode.STRIPE);

        assertThatThrownBy(() -> router.routeForCurrency("JPY"))
                .isInstanceOf(ProoflyException.class);
    }

    @Test
    void stripeAdapterValidatesTimestampedHmacSha256Signature() throws Exception {
        var stripe = new StripeGatewayAdapter(props, RestClient.builder(), new ObjectMapper());
        byte[] body = "{\"id\":\"evt_1\",\"type\":\"checkout.session.completed\"}".getBytes(StandardCharsets.UTF_8);

        long nowTs = Instant.now().getEpochSecond();
        String validHeader = "t=" + nowTs + ",v1=" + hmacSha256(nowTs + "." + new String(body, StandardCharsets.UTF_8), "whsec_unit_test");
        assertThat(stripe.isValidWebhookSignature(body, validHeader)).isTrue();

        long expiredTs = nowTs - 600;
        String expiredHeader = "t=" + expiredTs + ",v1=" + hmacSha256(expiredTs + "." + new String(body, StandardCharsets.UTF_8), "whsec_unit_test");
        assertThat(stripe.isValidWebhookSignature(body, expiredHeader)).isFalse();
    }

    private static String hmacSha256(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }
}
