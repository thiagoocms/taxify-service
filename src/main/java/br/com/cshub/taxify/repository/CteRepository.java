package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.fiscaldocument.Cte;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * CT-e only ({@code from Cte}, FD-RN-04). Today the CT-e comes only from the upload and is listed with the XMLs
 * ({@link FiscalDocumentRepository}); the capture of phase 3 reads and writes it here.
 */
@Repository
public interface CteRepository extends AbstractJpaRepository<Cte, UUID> {
}
