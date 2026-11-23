package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.xml.RetentionReview;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RetentionReviewRepository extends AbstractJpaRepository<RetentionReview, UUID> {

    List<RetentionReview> findByCompany_IdInAndNaturalKeyIn(Collection<UUID> companyIds, Collection<String> naturalKeys);

    Optional<RetentionReview> findByCompany_IdAndNaturalKey(UUID companyId, String naturalKey);
}
