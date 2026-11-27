package br.com.cshub.taxify.mapper;

import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.domain.firmaccess.FirmSubscription;
import br.com.cshub.taxify.dto.firmaccess.FirmAdminContactDTO;
import br.com.cshub.taxify.dto.firmaccess.PlatformFirmDTO;
import br.com.cshub.taxify.service.firmaccess.FirmAccess;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Uma linha do console da plataforma (F1-RN-14, F1-RN-15): o escritório, a situação de acesso efetiva, a revisão da
 * migração e as contagens. Nunca traz nome, CNPJ ou dado fiscal das empresas clientes, nem colaboradores que não sejam
 * ADMIN. Não estende AbstractMapper: a linha não é a entidade sozinha (precisa da situação de acesso e das contagens),
 * e o console não cria nem edita escritórios por este DTO - só lê.
 */
@Component
public class PlatformFirmMapper {

    /** O uso do escritório na linha: empresas em uso e inativas, usuários em uso e os contatos dos ADMIN. */
    public record Usage(long companiesInUse, long companiesInactive, long usersInUse, List<FirmAdminContactDTO> admins) {

        public static final Usage NONE = new Usage(0, 0, 0, List.of());
    }

    public PlatformFirmDTO toModel(AccountingFirm firm, FirmSubscription subscription, FirmAccess access, Usage usage) {
        return toModel(firm.getId(), firm.getName(), firm.getDocumentNumber(), firm.getCreatedDate(), firm.getContactPhone(),
                firm.getBillingEmail(), subscription, access, usage);
    }

    /** Os dados do escritório vindos de uma leitura com trava (a edição do contato), não da entidade. */
    public PlatformFirmDTO toModel(UUID id, String name, String documentNumber, LocalDateTime createdDate, String contactPhone,
                                   String billingEmail, FirmSubscription subscription, FirmAccess access, Usage usage) {
        return new PlatformFirmDTO(id.toString(), name, documentNumber, createdDate,
                access.status().name(), access.exempt(), access.readOnly(),
                access.reason() == null ? null : access.reason().name(), access.trialEndsOn(), access.accessUntil(),
                access.blockedSince(), subscription != null && subscription.isMigrated(),
                subscription != null && subscription.getReviewedAt() != null,
                subscription == null ? null : subscription.getReviewedAt(),
                subscription == null ? null : subscription.getReviewedBy(),
                usage.companiesInUse(), usage.companiesInactive(), usage.usersInUse(), contactPhone, billingEmail,
                usage.admins());
    }
}
