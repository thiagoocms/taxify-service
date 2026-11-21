package br.com.cshub.taxify.rest;

import br.com.cshub.taxify.security.AdminOnly;
import br.com.cshub.taxify.security.RequiresPermission;
import br.com.cshub.taxify.enumerated.Permission;
import br.com.cshub.taxify.constants.AppConstants;
import br.com.cshub.taxify.dto.billing.BillingCategoryDTO;
import br.com.cshub.taxify.dto.billing.BillingSettingsDTO;
import br.com.cshub.taxify.dto.billing.BillingSettingsUpdateDTO;
import br.com.cshub.taxify.dto.billing.BillingSyncResultDTO;
import br.com.cshub.taxify.dto.billing.CompanyBillingDTO;
import br.com.cshub.taxify.dto.integration.SyncJobStatusDTO;
import br.com.cshub.taxify.service.BillingService;
import br.com.cshub.taxify.service.BillingSyncService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Contracts / fees of the current user's firm, provider-agnostic - ADMIN only, see SecurityConfig. */
@RequiresPermission(Permission.BILLING_SYNC)
@RestController
@RequestMapping(AppConstants.API + AppConstants.V1 + "/billing")
public class BillingResource {

    private final BillingService billingService;
    private final BillingSyncService syncService;

    public BillingResource(BillingService billingService, BillingSyncService syncService) {
        this.billingService = billingService;
        this.syncService = syncService;
    }

    @GetMapping("/settings")
    public ResponseEntity<BillingSettingsDTO> settings() {
        return ResponseEntity.ok(billingService.getSettings());
    }

    @AdminOnly
    @PutMapping("/settings")
    public ResponseEntity<BillingSettingsDTO> updateSettings(@Valid @RequestBody BillingSettingsUpdateDTO body) {
        return ResponseEntity.ok(billingService.updateSettings(body));
    }

    @GetMapping("/categories")
    public ResponseEntity<List<BillingCategoryDTO>> categories() {
        return ResponseEntity.ok(billingService.listCategories());
    }

    /** Starts the sync in the background - 202 with the job status; poll {@code GET /billing/sync}. */
    @PostMapping("/sync")
    public ResponseEntity<SyncJobStatusDTO<BillingSyncResultDTO>> startSync() {
        return ResponseEntity.accepted().body(syncService.startForCurrentFirm());
    }

    @GetMapping("/sync")
    public ResponseEntity<SyncJobStatusDTO<BillingSyncResultDTO>> syncStatus() {
        return ResponseEntity.ok(syncService.statusForCurrentFirm());
    }

    @RequiresPermission(Permission.BILLING_VIEW)
    @GetMapping("/companies/{companyId}")
    public ResponseEntity<CompanyBillingDTO> companyBilling(@PathVariable UUID companyId) {
        return ResponseEntity.ok(billingService.getCompanyBilling(companyId));
    }
}
