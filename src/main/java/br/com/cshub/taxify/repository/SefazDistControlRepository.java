package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.xml.SefazDistControl;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface SefazDistControlRepository extends AbstractJpaRepository<SefazDistControl, UUID> {

    Optional<SefazDistControl> findByCompany_Id(UUID companyId);
}
