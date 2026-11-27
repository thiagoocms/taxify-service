package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.taxclearance.StateTaxClearance;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StateTaxClearanceRepository extends AbstractJpaRepository<StateTaxClearance, UUID> {

    /** The company's certidões of a UF, newest validity first (the first one is the one that counts). */
    List<StateTaxClearance> findByCompany_IdAndUfAndDeletedIsFalseOrderByValidUntilDescImportedAtDesc(UUID companyId, String uf);

    Optional<StateTaxClearance> findFirstByCompany_IdAndUfAndDeletedIsFalseOrderByValidUntilDescImportedAtDesc(UUID companyId, String uf);

    /** Every certidão of these companies in a UF, newest validity first per company - the carteira's column. */
    @Query("select c from StateTaxClearance c where c.company.id in :companyIds and c.uf = :uf and c.deleted = false"
            + " order by c.company.id, c.validUntil desc, c.importedAt desc")
    List<StateTaxClearance> findAllOf(@Param("companyIds") Collection<UUID> companyIds, @Param("uf") String uf);
}
