package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.document.Document;
import br.com.cshub.taxify.enumerated.DocumentTypeEnum;
import org.springframework.data.jpa.repository.NativeQuery;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface DocumentRepository extends AbstractJpaRepository<Document, UUID> {

    @NativeQuery(value = "SELECT COUNT(*) FROM documents WHERE number = :number AND type = :typeOrdinal")
    Long countByNumberAndTypeNative(@Param("number") String number, @Param("typeOrdinal") int typeOrdinal);

    default boolean existsByNumberAndTypeNative(String number, DocumentTypeEnum type) {
        return countByNumberAndTypeNative(number, type.ordinal()) > 0;
    }
}
