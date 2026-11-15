package com.proofly.billing;

import com.proofly.common.ErrorCode;
import com.proofly.common.ProoflyException;
import com.proofly.tenancy.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@Tag(name = "Billing & Payment Gateways", description = "Multi-currency subscription plans, gateway-routed checkout, verification, and signed webhooks (Paystack & Stripe)")
public class BillingController {

    public record CheckoutRequest(
            @NotBlank String planCode,
            @NotBlank String billingCycle,
            @NotBlank @Email String payerEmail,
            String currency
    ) {}

    private final SubscriptionAndBillingService billingService;

    public BillingController(SubscriptionAndBillingService billingService) {
        this.billingService = billingService;
    }

    @GetMapping("/api/v1/billing/plans")
    @Operation(summary = "List the 4 active subscription plans with multi-currency gateway pricing")
    public List<SubscriptionAndBillingService.PlanView> listPlans(
            @RequestParam(value = "currency", required = false) String currency
    ) {
        return billingService.listPlans(currency);
    }

    @GetMapping("/api/v1/billing/subscription")
    @PreAuthorize("@policyAuth.isAdminOrAbove()")
    @Operation(summary = "Get current tenant subscription and exact-currency ledger balances")
    public SubscriptionAndBillingService.SubscriptionView currentSubscription() {
        var tenantId = TenantContext.requireCurrent();
        return billingService.getTenantSubscription(tenantId.value());
    }

    @GetMapping("/api/v1/billing/payments")
    @PreAuthorize("@policyAuth.isAdminOrAbove()")
    @Operation(summary = "List tenant payment receipts in original settled currency without FX conversion")
    public List<SubscriptionAndBillingService.PaymentReceiptView> listPayments(
            @RequestParam(value = "limit", defaultValue = "25") int limit
    ) {
        var tenantId = TenantContext.requireCurrent();
        return billingService.listTenantPayments(tenantId.value(), limit);
    }

    @PostMapping("/api/v1/billing/checkout")
    @PreAuthorize("@policyAuth.isAdminOrAbove()")
    @Operation(summary = "Initialize gateway-routed multi-currency subscription checkout (idempotent)")
    public SubscriptionAndBillingService.CheckoutResponse checkout(
            @Valid @RequestBody CheckoutRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ProoflyException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                    "Idempotency-Key header is required for checkout");
        }
        var tenantId = TenantContext.requireCurrent();
        return billingService.initiateCheckout(
                tenantId.value(),
                req.payerEmail(),
                req.planCode(),
                req.billingCycle(),
                req.currency(),
                idempotencyKey.trim()
        );
    }

    @GetMapping("/api/v1/billing/verify")
    @PreAuthorize("@policyAuth.isAdminOrAbove()")
    @Operation(summary = "Verify payment settlement status against routed gateway for callback page")
    public SubscriptionAndBillingService.VerifyPaymentResponse verify(@RequestParam String reference) {
        var tenantId = TenantContext.requireCurrent();
        return billingService.verifyPaymentStatus(tenantId.value(), reference);
    }

    @PostMapping("/api/v1/webhooks/paystack")
    @Operation(summary = "Receive and process HMAC-SHA512 verified Paystack webhook events")
    public ResponseEntity<Void> paystackWebhook(
            @RequestBody byte[] rawBody,
            @RequestHeader(value = "x-paystack-signature", required = false) String signature
    ) {
        billingService.processGatewayWebhook(GatewayCode.PAYSTACK, rawBody, signature);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/api/v1/webhooks/stripe")
    @Operation(summary = "Receive and process HMAC-SHA256 verified Stripe webhook events")
    public ResponseEntity<Void> stripeWebhook(
            @RequestBody byte[] rawBody,
            @RequestHeader(value = "Stripe-Signature", required = false) String signature
    ) {
        billingService.processGatewayWebhook(GatewayCode.STRIPE, rawBody, signature);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/api/v1/webhooks/payments/{gateway}")
    @Operation(summary = "Generic gateway webhook dispatcher routed by gateway code")
    public ResponseEntity<Void> gatewayWebhook(
            @PathVariable String gateway,
            @RequestBody byte[] rawBody,
            @RequestHeader(value = "x-paystack-signature", required = false) String paystackSig,
            @RequestHeader(value = "Stripe-Signature", required = false) String stripeSig
    ) {
        GatewayCode code;
        try {
            code = GatewayCode.fromString(gateway);
        } catch (IllegalArgumentException e) {
            throw new ProoflyException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, "Unknown gateway: " + gateway);
        }
        String sig = code == GatewayCode.STRIPE ? stripeSig : paystackSig;
        billingService.processGatewayWebhook(code, rawBody, sig);
        return ResponseEntity.ok().build();
    }
}
