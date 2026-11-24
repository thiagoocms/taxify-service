package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.fronteira.EfiscoExtract;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EfiscoExtractRepository extends AbstractJpaRepository<EfiscoExtract, UUID> {

    Optional<EfiscoExtract> findByCompany_IdAndPeriodoAndDeletedIsFalse(UUID companyId, String periodo);

    /** The company's imported periods, latest first. */
    List<EfiscoExtract> findByCompany_IdAndDeletedIsFalseOrderByPeriodoDesc(UUID companyId);
}
