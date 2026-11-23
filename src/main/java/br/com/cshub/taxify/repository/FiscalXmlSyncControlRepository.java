package br.com.cshub.taxify.repository;

import java.time.LocalDate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Modifying;
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

    /*
     * Targeted updates: the sync itself saves the same row from another entity instance
     * (FiscalXmlIntegrationService#updateSyncControl) - saving the scheduler's copy here
     * would put back stale counts.
     */
    @Modifying
    @Transactional
    @Query("update FiscalXmlSyncControl c set c.receivedEventsCheckedOn = :day where c.id = :id")
    void markReceivedEventsChecked(@Param("id") UUID id, @Param("day") LocalDate day);

    @Modifying
    @Transactional
    @Query("update FiscalXmlSyncControl c set c.issuedEventsCheckedOn = :day where c.id = :id")
    void markIssuedEventsChecked(@Param("id") UUID id, @Param("day") LocalDate day);

    @Modifying
    @Transactional
    @Query("update FiscalXmlSyncControl c set c.serviceInvoicesCheckedOn = :day where c.id = :id")
    void markServiceInvoicesChecked(@Param("id") UUID id, @Param("day") LocalDate day);
}
