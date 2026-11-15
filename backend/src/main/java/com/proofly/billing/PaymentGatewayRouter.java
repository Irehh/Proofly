package com.proofly.billing;

import com.proofly.common.ErrorCode;
import com.proofly.common.ProoflyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class PaymentGatewayRouter {

    private final Map<GatewayCode, PaymentGateway> gatewaysByCode = new EnumMap<>(GatewayCode.class);
    private final Map<String, GatewayCode> currencyToGateway = new LinkedHashMap<>();
    private final String defaultCurrency;

    public PaymentGatewayRouter(List<PaymentGateway> gateways, BillingProperties props) {
        for (PaymentGateway gateway : gateways) {
            gatewaysByCode.put(gateway.gatewayCode(), gateway);
        }
        this.defaultCurrency = normalizeCurrency(
                props.defaultCurrency() != null && !props.defaultCurrency().isBlank()
                        ? props.defaultCurrency()
                        : "NGN"
        );
        if (props.paystack() != null && props.paystack().currencies() != null) {
            for (String cur : props.paystack().currencies()) {
                currencyToGateway.put(normalizeCurrency(cur), GatewayCode.PAYSTACK);
            }
        }
        if (props.stripe() != null && props.stripe().currencies() != null) {
            for (String cur : props.stripe().currencies()) {
                currencyToGateway.put(normalizeCurrency(cur), GatewayCode.STRIPE);
            }
        }
    }

    public String defaultCurrency() {
        return defaultCurrency;
    }

    public String resolveCurrencyOrDefault(String requestedCurrency) {
        if (requestedCurrency == null || requestedCurrency.isBlank()) {
            return defaultCurrency;
        }
        String normalized = normalizeCurrency(requestedCurrency);
        if (!currencyToGateway.containsKey(normalized)) {
            throw new ProoflyException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                    "Unsupported billing currency: " + normalized + ". Supported: " + currencyToGateway.keySet());
        }
        return normalized;
    }

    public PaymentGateway routeForCurrency(String currency) {
        String normalized = normalizeCurrency(currency);
        GatewayCode code = currencyToGateway.get(normalized);
        if (code == null) {
            throw new ProoflyException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                    "No payment gateway configured for currency: " + normalized);
        }
        return byCode(code);
    }

    public GatewayCode gatewayCodeForCurrency(String currency) {
        return routeForCurrency(currency).gatewayCode();
    }

    public PaymentGateway byCode(GatewayCode code) {
        PaymentGateway gw = gatewaysByCode.get(code);
        if (gw == null) {
            throw new ProoflyException(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.PAYMENT_PROVIDER_ERROR,
                    "Payment gateway adapter not registered: " + code);
        }
        return gw;
    }

    public List<String> supportedCurrencies() {
        return List.copyOf(currencyToGateway.keySet());
    }

    public static String normalizeCurrency(String currency) {
        if (currency == null || currency.isBlank()) {
            throw new ProoflyException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, "Currency code is required");
        }
        return currency.trim().toUpperCase(Locale.ROOT);
    }
}
