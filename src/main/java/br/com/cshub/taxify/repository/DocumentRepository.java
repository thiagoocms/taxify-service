package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.document.Document;
import br.com.cshub.taxify.enumerated.DocumentTypeEnum;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.NativeQuery;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.UUID;

@Repository
public interface DocumentRepository extends AbstractJpaRepository<Document, UUID> {

    @NativeQuery(value = "SELECT COUNT(*) FROM documents WHERE number = :number AND type = :type")
    Long countByNumberAndTypeNative(@Param("number") String number, @Param("type") String type);

    default boolean existsByNumberAndTypeNative(String number, DocumentTypeEnum type) {
        return countByNumberAndTypeNative(number, type.name()) > 0;
    }

    /** Purge of an unverified signup (F1-RN-03): the CPFs of its users, after the users. Physical delete (LGPD). */
    @Modifying
    @Query("delete from Document d where d.id in :ids")
    int deleteByIds(@Param("ids") Collection<UUID> ids);
}
