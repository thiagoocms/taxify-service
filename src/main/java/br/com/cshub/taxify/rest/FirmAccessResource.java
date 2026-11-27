package br.com.cshub.taxify.rest;

import br.com.cshub.taxify.common.CurrentFirmService;
import br.com.cshub.taxify.common.PlatformAdminService;
import br.com.cshub.taxify.constants.AppConstants;
import br.com.cshub.taxify.dto.firmaccess.FirmAccessDTO;
import br.com.cshub.taxify.mapper.FirmAccessMapper;
import br.com.cshub.taxify.security.AllowedWhenBlocked;
import br.com.cshub.taxify.service.firmaccess.BlockedPeriodRecoveryService;
import br.com.cshub.taxify.service.firmaccess.FirmAccess;
import br.com.cshub.taxify.service.firmaccess.FirmAccessService;
import br.com.cshub.taxify.service.firmaccess.PlanLimitService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A situação do escritório do usuário logado, para as faixas de "teste grátis: faltam X dias" e de "somente leitura"
 * (F1-RN-07, F1-RN-09, F1-RN-16). Qualquer usuário logado; liberado mesmo com o escritório bloqueado.
 */
@AllowedWhenBlocked
@RestController
@RequestMapping(AppConstants.API + AppConstants.V1 + "/firm-access")
public class FirmAccessResource {

    private final FirmAccessService accessService;
    private final PlanLimitService planLimitService;
    private final PlatformAdminService platformAdminService;
    private final CurrentFirmService currentFirmService;
    private final BlockedPeriodRecoveryService recoveryService;
    private final FirmAccessMapper mapper;

    public FirmAccessResource(FirmAccessService accessService, PlanLimitService planLimitService,
                              PlatformAdminService platformAdminService, CurrentFirmService currentFirmService,
                              BlockedPeriodRecoveryService recoveryService, FirmAccessMapper mapper) {
        this.accessService = accessService;
        this.planLimitService = planLimitService;
        this.platformAdminService = platformAdminService;
        this.currentFirmService = currentFirmService;
        this.recoveryService = recoveryService;
        this.mapper = mapper;
    }

    @GetMapping
    public ResponseEntity<FirmAccessDTO> current() {
        FirmAccess access = accessService.currentAccess()
                .orElseGet(() -> accessService.accessOf(currentFirmService.requireId()));
        return ResponseEntity.ok(mapper.toModel(access, planLimitService.limitsFor(access),
                platformAdminService.isCurrentUserPlatformAdmin(), recoveryService.pendingMonths(access.firmId())));
    }
}
