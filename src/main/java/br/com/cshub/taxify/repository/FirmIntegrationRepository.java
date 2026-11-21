package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.billing.FirmIntegration;
import br.com.cshub.taxify.enumerated.IntegrationType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FirmIntegrationRepository extends AbstractJpaRepository<FirmIntegration, UUID> {

    Optional<FirmIntegration> findFirstByAccountingFirm_IdAndTypeAndDeletedIsFalse(UUID accountingFirmId, IntegrationType type);

    List<FirmIntegration> findByAccountingFirm_IdAndDeletedIsFalse(UUID accountingFirmId);

    /** Every active firm's integration of one type - what the schedulers iterate over. */
    List<FirmIntegration> findByTypeAndDeletedIsFalseAndAccountingFirm_DeletedIsFalse(IntegrationType type);
}
