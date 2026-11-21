package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.billing.ExternalLink;
import br.com.cshub.taxify.enumerated.BillingProviderType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExternalLinkRepository extends AbstractJpaRepository<ExternalLink, UUID> {

    Optional<ExternalLink> findFirstByCompany_IdAndProviderAndDeletedIsFalse(UUID companyId, BillingProviderType provider);

    List<ExternalLink> findByCompany_AccountingFirm_IdAndProviderAndDeletedIsFalse(UUID accountingFirmId, BillingProviderType provider);
}
