package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.certidao.CertidaoEstadual;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CertidaoEstadualRepository extends AbstractJpaRepository<CertidaoEstadual, UUID> {

    /** The company's certidões of a UF, newest validity first (the first one is the one that counts). */
    List<CertidaoEstadual> findByCompany_IdAndUfAndDeletedIsFalseOrderByValidaAteDescImportedAtDesc(UUID companyId, String uf);

    Optional<CertidaoEstadual> findFirstByCompany_IdAndUfAndDeletedIsFalseOrderByValidaAteDescImportedAtDesc(UUID companyId, String uf);

    /** Every certidão of these companies in a UF, newest validity first per company - the carteira's column. */
    @Query("select c from CertidaoEstadual c where c.company.id in :companyIds and c.uf = :uf and c.deleted = false"
            + " order by c.company.id, c.validaAte desc, c.importedAt desc")
    List<CertidaoEstadual> findAllOf(@Param("companyIds") Collection<UUID> companyIds, @Param("uf") String uf);
}
