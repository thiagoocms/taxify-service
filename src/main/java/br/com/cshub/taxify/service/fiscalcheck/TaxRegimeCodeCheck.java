package br.com.cshub.taxify.service.fiscalcheck;

import br.com.cshub.taxify.domain.xml.FiscalXmlDocument;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocumentItem;
import br.com.cshub.taxify.enumerated.DivergenceSeverity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * The ICMS code the item uses has to fit the issuer's regime (emit/CRT): Simples Nacional
 * (CRT 1) and MEI (CRT 4) report a CSOSN; regime normal (CRT 3) and Simples over the
 * sublimit (CRT 2) report a CST. Items without the tax detail (not backfilled yet) are skipped.
 */
@Component
public class TaxRegimeCodeCheck implements FiscalCheck {

    public static final String CODE = "CRT_CST";

    private static final Set<String> CSOSN_REGIMES = Set.of("1", "4");
    private static final Set<String> CST_REGIMES = Set.of("2", "3");

    @Override
    public List<FiscalDivergence> check(FiscalXmlDocumentItem item, FiscalCheckContext context) {
        FiscalXmlDocument document = item.getDocument();
        if (document == null || document.getEmitenteRegimeTributario() == null
                || item.getTaxDetail() == null || item.getSituacaoTributariaIcms() == null) {
            return List.of();
        }
        String crt = document.getEmitenteRegimeTributario().trim();
        boolean usesCsosn = item.getTaxDetail().csosn() != null;
        String code = item.getSituacaoTributariaIcms();
        if (CSOSN_REGIMES.contains(crt) && !usesCsosn) {
            return List.of(new FiscalDivergence(CODE, "CST_ICMS", code, "CSOSN", DivergenceSeverity.ERROR,
                    "Emitente do Simples Nacional (CRT %s) informou CST %s de ICMS - deveria usar CSOSN".formatted(crt, code)));
        }
        if (CST_REGIMES.contains(crt) && usesCsosn) {
            return List.of(new FiscalDivergence(CODE, "CSOSN", code, "CST", DivergenceSeverity.ERROR,
                    "Emitente do regime normal (CRT %s) informou CSOSN %s - deveria usar CST de ICMS".formatted(crt, code)));
        }
        return List.of();
    }
}
