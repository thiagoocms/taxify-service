package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.firmaccess.TermsAcceptance;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TermsAcceptanceRepository extends AbstractJpaRepository<TermsAcceptance, UUID> {

    /** Cadastros de um IP num intervalo [from, to) - o limite por IP por dia (F1-RN-06). */
    long countByIpAndAcceptedAtGreaterThanEqualAndAcceptedAtLessThanAndDeletedIsFalse(String ip, LocalDateTime from,
                                                                                       LocalDateTime to);

    /** Quando o escritório se cadastrou (o primeiro aceite) - a contagem do expurgo (F1-RN-03). */
    @Query("select min(t.acceptedAt) from TermsAcceptance t where t.accountingFirmId = :firmId and t.deleted = false")
    Optional<LocalDateTime> findSignupAt(@Param("firmId") UUID firmId);

    /** Purge of an unverified signup (F1-RN-03): physical delete (LGPD). */
    @Modifying
    @Query("delete from TermsAcceptance t where t.accountingFirmId = :firmId")
    int deleteByFirm(@Param("firmId") UUID accountingFirmId);
}
