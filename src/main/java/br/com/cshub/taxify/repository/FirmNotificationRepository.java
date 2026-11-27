package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.firmaccess.FirmNotification;
import br.com.cshub.taxify.enumerated.FirmNotificationType;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.UUID;

@Repository
public interface FirmNotificationRepository extends AbstractJpaRepository<FirmNotification, UUID> {

    /**
     * Aviso já enviado (F1-RN-16). Sem o filtro de {@code deleted} de propósito: espelha a chave única
     * uk_firm_notifications (escritório, tipo, data), que vale para todas as linhas.
     */
    boolean existsByAccountingFirmIdAndTypeAndReferenceDate(UUID accountingFirmId, FirmNotificationType type, LocalDate referenceDate);

    /** Purge of an unverified signup (F1-RN-03): physical delete (LGPD). */
    @Modifying
    @Query("delete from FirmNotification n where n.accountingFirmId = :firmId")
    int deleteByFirm(@Param("firmId") UUID accountingFirmId);
}
