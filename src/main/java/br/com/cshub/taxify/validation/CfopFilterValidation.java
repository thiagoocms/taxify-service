package br.com.cshub.taxify.validation;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.domain.fiscaldocument.CfopFilter;
import br.com.cshub.taxify.exception.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Entry rule of the CFOP filter of the notes screen and of the Fronteira (spec 2026-11-27, CF-RN-01 to 05 and 09):
 * {@link CfopFilter} reads it; a broken rule is a 400 with {@code invalidValues} or {@code maxItems} at the top of the
 * body. The 400 logs nothing beyond the handler's default - the value goes in the response, never in the log.
 */
@Component
public class CfopFilterValidation {

    static final String INVALID = "error.cfopFilter.invalid";
    static final String TOO_MANY = "error.cfopFilter.tooMany";

    private static final Logger log = LoggerFactory.getLogger(CfopFilterValidation.class);

    private final MessageService messageService;

    public CfopFilterValidation(MessageService messageService) {
        this.messageService = messageService;
    }

    /** CF-RN-20: the Fronteira's {@code cfops} (no old field there). */
    public CfopFilter check(Collection<String> cfops) {
        try {
            return CfopFilter.of(cfops);
        } catch (CfopFilter.Invalid invalid) {
            throw toBadRequest(invalid);
        }
    }

    /**
     * CF-RN-09: the notes screen's {@code cfops}, or - only when it came empty - the old {@code cfop}, never checked.
     * The old field is logged (INFO, the company only) to know when it can be removed (B-CF-06).
     */
    public CfopFilter check(Collection<String> cfops, String legacyCfop, UUID companyId) {
        if (CfopFilter.usesLegacy(cfops, legacyCfop)) {
            log.info("legacy cfop filter used: company {}", companyId);
            return CfopFilter.legacy(legacyCfop);
        }
        return check(cfops);
    }

    private BadRequestException toBadRequest(CfopFilter.Invalid invalid) {
        Map<String, Object> extras = new LinkedHashMap<>();
        if (invalid.reason() == CfopFilter.Invalid.Reason.TOO_MANY) {
            extras.put("maxItems", CfopFilter.MAX_ITEMS);
            return new BadRequestException(TOO_MANY, messageService.getMessage(TOO_MANY, String.valueOf(CfopFilter.MAX_ITEMS)), extras);
        }
        extras.put("invalidValues", invalid.invalidValues());
        return new BadRequestException(INVALID, messageService.getMessage(INVALID, quoted(invalid.invalidValues())), extras);
    }

    /** "11", "" - an empty value inside the list still shows. */
    private static String quoted(Collection<String> values) {
        return "\"" + String.join("\", \"", values) + "\"";
    }
}
