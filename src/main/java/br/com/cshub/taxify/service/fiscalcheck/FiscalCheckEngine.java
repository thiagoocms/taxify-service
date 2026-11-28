package br.com.cshub.taxify.service.fiscalcheck;

import br.com.cshub.taxify.domain.fiscalrule.ProductRules;
import br.com.cshub.taxify.domain.fiscalrule.ProductFiscalRule;
import br.com.cshub.taxify.domain.reform.IbsCbsRate;
import br.com.cshub.taxify.domain.fiscaldocument.FiscalDocument;
import br.com.cshub.taxify.domain.fiscaldocument.GoodsInvoice;
import br.com.cshub.taxify.domain.fiscaldocument.FiscalDocumentItem;
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

    /**
     * The company's rules and the IBS/CBS rates - two queries. A product code is unique per company
     * (ProductFiscalRuleValidation). Only the years with LAW status and the three rates enter (AL-RN-02): a PENDING or
     * ESTIMATE year is "no reference" for the engine, and IBSCBS_RATE doesn't run for it. Read at every call, no cache
     * (AL-RN-08).
     */
    public FiscalCheckContext contextFor(UUID companyId) {
        ProductRules rules = ProductRules.of(ruleRepository.findByCompany_IdAndDeletedIsFalse(companyId));
        Map<Integer, IbsCbsRate> rates = ibsCbsRateRepository.findFindingRates().stream()
                .collect(Collectors.toMap(IbsCbsRate::getYear, Function.identity()));
        return new FiscalCheckContext(companyId, rules, rates);
    }

    /**
     * Only goods invoices (NF-e, NFC-e) are checked; canceled or deleted notes are listed but never checked (no
     * divergence, no conferência finding). A CT-e has no items and no check (CX-RN-40).
     */
    public static boolean isCheckable(FiscalDocument document) {
        return document instanceof GoodsInvoice && !document.isDeleted() && !document.isCanceled();
    }

    public List<FiscalDivergence> check(FiscalDocumentItem item, FiscalCheckContext context) {
        List<FiscalDivergence> divergences = new ArrayList<>();
        for (FiscalCheck check : checks) {
            divergences.addAll(check.check(item, context));
        }
        return divergences;
    }

    public List<FiscalDivergence> checkDocument(GoodsInvoice document, List<FiscalDocumentItem> items,
                                                FiscalCheckContext context) {
        List<FiscalDivergence> divergences = new ArrayList<>();
        for (FiscalDocumentCheck check : documentChecks) {
            divergences.addAll(check.check(document, items, context));
        }
        return divergences;
    }
}
