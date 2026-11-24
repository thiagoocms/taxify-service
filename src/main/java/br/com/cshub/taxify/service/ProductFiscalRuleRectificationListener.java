package br.com.cshub.taxify.service;

import br.com.cshub.taxify.dto.fiscalrule.FiscalXmlRectificationResultDTO;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlRectificationProperties;
import br.com.cshub.taxify.repository.FiscalXmlDocumentItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Rectifies, as soon as a product fiscal rule is saved, the company's notes that have that product
 * (from that supplier, when the rule names one): NF-e received in the job's look-back window, not
 * canceled. Runs after the commit, on a virtual thread - the save answers right away. The scheduled
 * job still goes over everything else; this only spares the wait for the notes the rule is about.
 */
@Component
public class ProductFiscalRuleRectificationListener {

    private static final Logger log = LoggerFactory.getLogger(ProductFiscalRuleRectificationListener.class);

    private final FiscalXmlDocumentItemRepository itemRepository;
    private final FiscalXmlRectificationService rectificationService;
    private final FiscalXmlRectificationProperties properties;

    public ProductFiscalRuleRectificationListener(FiscalXmlDocumentItemRepository itemRepository,
                                                  FiscalXmlRectificationService rectificationService,
                                                  FiscalXmlRectificationProperties properties) {
        this.itemRepository = itemRepository;
        this.rectificationService = rectificationService;
        this.properties = properties;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRuleSaved(ProductFiscalRuleSavedEvent event) {
        if (!properties.isEnabled() || event.companyId() == null || event.codigoProduto() == null || event.codigoProduto().isBlank()) {
            return;
        }
        Thread.ofVirtual().name("rule-rectification-" + event.companyId()).start(() -> rectify(event));
    }

    void rectify(ProductFiscalRuleSavedEvent event) {
        String supplier = event.documentoFornecedor() == null || event.documentoFornecedor().isBlank()
                ? null : event.documentoFornecedor().replaceAll("\\D", "");
        List<UUID> documents = itemRepository.findRectifiableDocumentIdsWithProduct(event.companyId(), event.codigoProduto(),
                supplier, LocalDate.now().minusDays(properties.getLookbackDays()).atStartOfDay());
        int rectified = 0;
        int failed = 0;
        for (UUID documentId : documents) {
            try {
                FiscalXmlRectificationResultDTO result = rectificationService.rectify(documentId);
                if (!result.correcoesAplicadas().isEmpty()) {
                    rectified++;
                }
            } catch (Exception e) {
                failed++;
                log.warn("Rectification after rule save failed for document {}", documentId, e);
            }
        }
        log.info("Rule for product {} saved (company {}): {} note(s) with it, {} rectified, {} failed",
                event.codigoProduto(), event.companyId(), documents.size(), rectified, failed);
    }
}
