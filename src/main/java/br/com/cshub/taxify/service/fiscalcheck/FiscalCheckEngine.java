package br.com.cshub.taxify.service.fiscalcheck;

import br.com.cshub.taxify.domain.fiscalrule.ProductRules;
import br.com.cshub.taxify.domain.fiscalrule.ProductFiscalRule;
import br.com.cshub.taxify.domain.reform.IbsCbsRate;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocument;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocumentItem;
import br.com.cshub.taxify.repository.IbsCbsRateRepository;
import br.com.cshub.taxify.repository.ProductFiscalRuleRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Runs every {@link FiscalCheck} bean over note items and every {@link FiscalDocumentCheck}
 * bean over whole notes. Build the {@link FiscalCheckContext} once per company
 * ({@link #contextFor}) and reuse it for all of that company's notes.
 */
@Service
public class FiscalCheckEngine {

    private final List<FiscalCheck> checks;
    private final List<FiscalDocumentCheck> documentChecks;
    private final ProductFiscalRuleRepository ruleRepository;
    private final IbsCbsRateRepository ibsCbsRateRepository;

    public FiscalCheckEngine(List<FiscalCheck> checks, List<FiscalDocumentCheck> documentChecks,
                             ProductFiscalRuleRepository ruleRepository, IbsCbsRateRepository ibsCbsRateRepository) {
        this.checks = List.copyOf(checks);
        this.documentChecks = List.copyOf(documentChecks);
        this.ruleRepository = ruleRepository;
        this.ibsCbsRateRepository = ibsCbsRateRepository;
    }

    /** The company's rules and the IBS/CBS rates - two queries. A product code is unique per company (ProductFiscalRuleValidation). */
    public FiscalCheckContext contextFor(UUID companyId) {
        ProductRules rules = ProductRules.of(ruleRepository.findByCompany_IdAndDeletedIsFalse(companyId));
        Map<Integer, IbsCbsRate> rates = ibsCbsRateRepository.findAll().stream()
                .collect(Collectors.toMap(IbsCbsRate::getAno, Function.identity()));
        return new FiscalCheckContext(companyId, rules, rates);
    }

    /** Canceled or deleted notes are listed but never checked (no divergence, no conferência finding). */
    public static boolean isCheckable(FiscalXmlDocument document) {
        return document != null && !document.isDeleted() && !document.isCanceled();
    }

    public List<FiscalDivergence> check(FiscalXmlDocumentItem item, FiscalCheckContext context) {
        List<FiscalDivergence> divergences = new ArrayList<>();
        for (FiscalCheck check : checks) {
            divergences.addAll(check.check(item, context));
        }
        return divergences;
    }

    public List<FiscalDivergence> checkDocument(FiscalXmlDocument document, List<FiscalXmlDocumentItem> items,
                                                FiscalCheckContext context) {
        List<FiscalDivergence> divergences = new ArrayList<>();
        for (FiscalDocumentCheck check : documentChecks) {
            divergences.addAll(check.check(document, items, context));
        }
        return divergences;
    }
}
