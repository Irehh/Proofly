package com.proofly.tenancy;

import com.proofly.common.ErrorCode;
import com.proofly.common.ProoflyException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tenants")
@Tag(name = "Tenants", description = "Current workspace tenancy metadata")
public class TenantController {

    private final TenantDirectoryService directoryService;

    public TenantController(TenantDirectoryService directoryService) {
        this.directoryService = directoryService;
    }

    @GetMapping("/me")
    @PreAuthorize("@policyAuth.isAuthenticated()")
    @Operation(summary = "Get current authenticated tenant profile")
    public TenantSummary currentTenant() {
        var tenantId = TenantContext.requireCurrent();
        return directoryService.findTenantById(tenantId.value())
                .orElseThrow(() -> new ProoflyException(
                        HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND, "Tenant not found"));
    }
}
