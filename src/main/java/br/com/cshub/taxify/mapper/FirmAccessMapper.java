package br.com.cshub.taxify.mapper;

import br.com.cshub.taxify.dto.firmaccess.FirmAccessDTO;
import br.com.cshub.taxify.dto.firmaccess.FirmAccessLimitsDTO;
import br.com.cshub.taxify.service.firmaccess.FirmAccess;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * A situação de acesso do escritório do usuário logado para as faixas da tela (F1-RN-07, F1-RN-09, F1-RN-16).
 * Não estende AbstractMapper: {@link FirmAccess} é calculada (FirmAccessRules), não é uma entidade.
 */
@Component
public class FirmAccessMapper {

    /** A SEFAZ guarda os documentos da distribuição por cerca de 90 dias (decisão 4). */
    public static final int SEFAZ_RETENTION_DAYS = 90;

    public FirmAccessDTO toModel(FirmAccess access, FirmAccessLimitsDTO limits, boolean platformAdmin,
                                 List<String> recoveryMonths) {
        return new FirmAccessDTO(access.status().name(), access.exempt(), access.readOnly(),
                access.reason() == null ? null : access.reason().name(), access.blockedSince(), access.trialEndsOn(),
                access.accessUntil(), access.trialDaysLeft(), access.capturesStoppedDays(), SEFAZ_RETENTION_DAYS,
                limits, platformAdmin, !recoveryMonths.isEmpty(), recoveryMonths);
    }
}
