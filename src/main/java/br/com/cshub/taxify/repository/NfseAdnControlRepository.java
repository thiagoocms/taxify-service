package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.xml.NfseAdnControl;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NfseAdnControlRepository extends AbstractJpaRepository<NfseAdnControl, UUID> {

    Optional<NfseAdnControl> findByCompany_Id(UUID companyId);

    @Query("select c from NfseAdnControl c join fetch c.company where c.company.id in :companyIds")
    List<NfseAdnControl> findByCompany_IdIn(@Param("companyIds") Collection<UUID> companyIds);
}
