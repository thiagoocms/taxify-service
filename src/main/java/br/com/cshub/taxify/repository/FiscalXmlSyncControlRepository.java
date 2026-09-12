package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.xml.FiscalXmlSyncControl;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FiscalXmlSyncControlRepository extends AbstractJpaRepository<FiscalXmlSyncControl, UUID> {

    Optional<FiscalXmlSyncControl> findByCompany_Id(UUID companyId);

    /**
     * Company eagerly fetched so {@link br.com.cshub.taxify.service.FiscalXmlSyncScheduler}
     * can read {@code control.getCompany()} outside a transaction without a
     * LazyInitializationException.
     */
    @Query("select fxsc from FiscalXmlSyncControl fxsc join fetch fxsc.company where fxsc.deleted = false")
    List<FiscalXmlSyncControl> findAllWithCompany();

    @Query("select fxsc.company.id from FiscalXmlSyncControl fxsc where fxsc.deleted = false")
    List<UUID> findAllCompanyIds();
}
