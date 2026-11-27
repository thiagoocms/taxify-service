package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import jakarta.persistence.QueryHint;
import org.hibernate.jpa.HibernateHints;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The native queries below are the row locks of the firm and the session's lock wait: Hibernate's PESSIMISTIC_WRITE
 * writes "FOR UPDATE OF alias", which MySQL 5.7 does not accept, and JPQL has no {@code SET SESSION}. The id goes as
 * text (a UUID bound to a native query goes as 16 binary bytes). They never flush the persistence context first
 * ({@code flushMode=COMMIT}): the trial limit is checked before the company or user being written reaches the database.
 */
@Repository
public interface AccountingFirmRepository extends AbstractJpaRepository<AccountingFirm, UUID> {

    /** CNPJ já usado por um escritório - contando os excluídos e os não verificados (F1-RN-02). */
    boolean existsByDocumentNumber(String documentNumber);

    /** O mesmo, sem contar o próprio escritório (o admin da plataforma corrigindo o CNPJ dele). */
    boolean existsByDocumentNumberAndIdNot(String documentNumber, UUID id);

    /**
     * Trava a linha do escritório até o fim da transação (SELECT ... FOR UPDATE): as ações do console, o registro diário
     * do bloqueio e a conferência do limite do teste não se atropelam.
     */
    @QueryHints(@QueryHint(name = HibernateHints.HINT_FLUSH_MODE, value = "COMMIT"))
    @Query(value = "SELECT id FROM accounting_firms WHERE id = :id FOR UPDATE", nativeQuery = true)
    List<String> lockById(@Param("id") String id);

    default void lock(UUID id) {
        lockById(id.toString());
    }

    /**
     * Os dados do console do escritório lidos com a trava (uma leitura com trava: o último estado gravado) - id, name,
     * document_number, contact_phone, billing_email e created_date, nessa ordem. Vazio quando não existe ou foi excluído.
     */
    @QueryHints(@QueryHint(name = HibernateHints.HINT_FLUSH_MODE, value = "COMMIT"))
    @Query(value = "SELECT id, name, document_number, contact_phone, billing_email, created_date"
            + " FROM accounting_firms WHERE id = :id AND deleted = 0 FOR UPDATE", nativeQuery = true)
    List<Object[]> findContactForUpdate(@Param("id") String id);

    /** CNPJ, e-mail financeiro e telefone gravados pelo admin da plataforma (F1-RN-13), com quem e quando. */
    @Modifying
    @Query("update AccountingFirm f set f.documentNumber = :documentNumber, f.billingEmail = :billingEmail,"
            + " f.contactPhone = :contactPhone, f.lastModifiedBy = :actor, f.lastModifiedDate = :now where f.id = :id")
    int updateContact(@Param("id") UUID id, @Param("documentNumber") String documentNumber,
                      @Param("billingEmail") String billingEmail, @Param("contactPhone") String contactPhone,
                      @Param("actor") String actor, @Param("now") LocalDateTime now);

    /**
     * Os escritórios do console, dos mais novos para os mais antigos: com {@code term}, os que têm o termo no nome
     * (minúsculas, já escapado com {@code !}) ou, com {@code digits}, os dígitos no CNPJ.
     */
    @Query("select f from AccountingFirm f where f.deleted = false"
            + " and (:term is null or lower(f.name) like :term escape '!'"
            + " or (:digits is not null and f.documentNumber like :digits))"
            + " order by f.createdDate desc, f.id")
    List<AccountingFirm> searchForConsole(@Param("term") String term, @Param("digits") String digits);

    /** Espera máxima por uma trava nesta sessão do MySQL (o padrão é 50 s). */
    @QueryHints(@QueryHint(name = HibernateHints.HINT_FLUSH_MODE, value = "COMMIT"))
    @Query(value = "SELECT @@SESSION.innodb_lock_wait_timeout", nativeQuery = true)
    Integer findLockWaitTimeout();

    @Modifying
    @QueryHints(@QueryHint(name = HibernateHints.HINT_FLUSH_MODE, value = "COMMIT"))
    @Query(value = "SET SESSION innodb_lock_wait_timeout = :seconds", nativeQuery = true)
    void setLockWaitTimeout(@Param("seconds") int seconds);

    /** Purge of an unverified signup (F1-RN-03): physical delete (LGPD) - the only one; everything else is soft delete. */
    @Modifying
    @Query("delete from AccountingFirm f where f.id = :id")
    int deleteByIdPhysically(@Param("id") UUID id);
}
