package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.certificate.FirmCertificate;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FirmCertificateRepository extends AbstractJpaRepository<FirmCertificate, UUID> {

    Optional<FirmCertificate> findByAccountingFirm_IdAndDeletedIsFalse(UUID accountingFirmId);

    /** Every firm that has the accountant's certificate - the ones the scheduled e-Fisco sync goes through. */
    @Query("select c from FirmCertificate c join fetch c.accountingFirm where c.deleted = false")
    List<FirmCertificate> findAllActive();
}
