package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.fronteira.EfiscoMemory;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface EfiscoMemoryRepository extends AbstractJpaRepository<EfiscoMemory, UUID> {

    Optional<EfiscoMemory> findByCompany_IdAndRecordNumber(UUID companyId, String recordNumber);
}
