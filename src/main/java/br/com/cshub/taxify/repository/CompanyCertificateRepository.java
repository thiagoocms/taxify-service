package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.certificate.CompanyCertificate;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CompanyCertificateRepository extends AbstractJpaRepository<CompanyCertificate, UUID> {

    Optional<CompanyCertificate> findByCompany_Id(UUID companyId);

    /** Every certificate of a firm, company fetched - to find one of the same CNPJ root for a branch. */
    @Query("select c from CompanyCertificate c join fetch c.company co where co.accountingFirm.id = :firmId and c.deleted = false")
    List<CompanyCertificate> findAllOfFirm(@Param("firmId") UUID firmId);

    @Query("select c from CompanyCertificate c join fetch c.company where c.deleted = false")
    List<CompanyCertificate> findAllWithCompany();
}
