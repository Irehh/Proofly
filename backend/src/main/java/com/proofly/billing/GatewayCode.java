package com.proofly.billing;

public enum GatewayCode {
    PAYSTACK,
    STRIPE;

    public static GatewayCode fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Gateway code is required");
        }
        return GatewayCode.valueOf(raw.trim().toUpperCase());
    }
}
