package br.com.cshub.taxify.service;

import br.com.cshub.taxify.service.fiscalcheck.EffectiveCfops;
import br.com.cshub.taxify.service.fiscalcheck.OperationCfops;
import br.com.cshub.taxify.domain.fiscalrule.ProductRules;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.common.TenantAccessService;
import br.com.cshub.taxify.domain.fiscalrule.ProductFiscalRule;
import br.com.cshub.taxify.domain.invoice.InvoiceAuditLog;
import br.com.cshub.taxify.domain.fiscaldocument.FiscalDocument;
import br.com.cshub.taxify.domain.fiscaldocument.GoodsInvoice;
import br.com.cshub.taxify.dto.fiscalrule.FiscalXmlRectificationResultDTO;
import br.com.cshub.taxify.enumerated.InvoiceAuditStatus;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.exception.ResourceNotFoundException;
import br.com.cshub.taxify.exception.SiegIntegrationException;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlDomParser;
import br.com.cshub.taxify.integration.sieg.FiscalXmlFileStorage;
import br.com.cshub.taxify.repository.FiscalDocumentRepository;
import br.com.cshub.taxify.repository.InvoiceAuditLogRepository;
import br.com.cshub.taxify.repository.ProductFiscalRuleRepository;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.xml.XMLConstants;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static br.com.cshub.taxify.integration.fiscalxml.FiscalXmlDom.first;
import static br.com.cshub.taxify.integration.fiscalxml.FiscalXmlDom.text;

/**
 * Rewrites one already-downloaded NFe's CFOP (and NCM, when the rule pins one) to what
 * {@link ProductFiscalRule} says is correct for each product - plus, when the rule has a
 * tax classification, the tax codes that can be swapped without changing the XML's shape
 * ({@link TaxCodeRectifier}; rates and values never) - and writes the result as a
 * brand-new file next to the original - the original is never touched.
 *
 * <p>IMPORTANT: this only edits the XML content. It does NOT re-sign the note - NFe's
 * digital signature covers {@code infNFe} byte-for-byte, so any edit invalidates the
 * original signature. A comment is added to the rectified file saying so; actually
 * re-signing (or getting the correction accepted) has to happen at the issuing system
 * with a valid certificate - that's the "confirm in practice" gap flagged earlier.
 *
 * <p>Every time a rectification actually changes something, an {@link InvoiceAuditLog}
 * row is written ({@link InvoiceAuditStatus#RECTIFIED}) - {@code user} is only set when
 * called from an authenticated request (see {@link CurrentUserService}); a background
 * trigger with no security context still logs the event, just without a user attached.
 * {@link GoodsInvoice#setRectifiedFilePath} is also updated to point at the new
 * file, so consumers (e.g. the export endpoint) know to serve the corrected version
 * instead of the original without having to scan the filesystem for it.
 *
 * <p>The original file is never modified, so re-running this against an unchanged rule
 * set would otherwise find the exact same divergence forever and write a new file/log
 * on every call - {@link FiscalXmlRectificationScheduler} in particular would do this
 * every run. To avoid that, a rectification is skipped (no file, no log) when the most
 * recent {@code RECTIFIED} log for this note already lists the exact same corrections;
 * a real change (a rule was added/edited) still gets rectified and logged normally.
 */
@Service
public class FiscalXmlRectificationService {

    private static final int NOTES_MAX_LENGTH = 500;
    /** First parser version whose item columns hold every tax code the rectifier touches (IPI CST came in 3). */
    private static final int ITEM_COLUMNS_VERSION = 3;

    private final FiscalDocumentRepository documentRepository;
    private final ProductFiscalRuleRepository ruleRepository;
    private final InvoiceAuditLogRepository auditLogRepository;
    private final InvoiceAuditTrail auditTrail;
    private final FiscalXmlFileStorage fileStorage;
    private final MessageService messageService;
    private final TenantAccessService tenantAccessService;

    public FiscalXmlRectificationService(FiscalDocumentRepository documentRepository, ProductFiscalRuleRepository ruleRepository,
                                          InvoiceAuditLogRepository auditLogRepository, FiscalXmlFileStorage fileStorage,
                                          MessageService messageService, TenantAccessService tenantAccessService,
                                          InvoiceAuditTrail auditTrail) {
        this.auditTrail = auditTrail;
        this.documentRepository = documentRepository;
        this.ruleRepository = ruleRepository;
        this.auditLogRepository = auditLogRepository;
        this.fileStorage = fileStorage;
        this.messageService = messageService;
        this.tenantAccessService = tenantAccessService;
    }

    @Transactional
    public FiscalXmlRectificationResultDTO rectify(UUID documentId) {
        // NF-e, NFC-e and CT-e (the notes screen); an NFS-e id is "not found" (FD-RN-17)
        FiscalDocument found = documentRepository.findXmlDocument(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("error.entity.not.found", messageService.getMessage("error.entity.not.found")));
        tenantAccessService.assertCompanyAccess(found.getCompany());
        if (found.isCanceled()) {
            throw new BadRequestException("error.fiscalXml.rectification.canceled", messageService.getMessage("error.fiscalXml.rectification.canceled"));
        }
        if (found.isOwnIssue()) {
            // An authorized note the company issued can't be rewritten - that's a carta de correção.
            throw new BadRequestException("error.fiscalXml.rectification.own.issue", messageService.getMessage("error.fiscalXml.rectification.own.issue"));
        }

        if (!(found instanceof GoodsInvoice document)) {
            throw new BadRequestException("error.fiscalXml.rectification.not.nfe", messageService.getMessage("error.fiscalXml.rectification.not.nfe"));
        }

        // Only reads the XML (from S3) when the stored items show something a rule would change.
        ProductRules rules = ProductRules.of(ruleRepository.findByCompany_IdAndDeletedIsFalse(document.getCompany().getId()));
        Document dom = null;
        List<String> corrections = List.of();
        if (mayNeedCorrection(document, rules)) {
            dom = parse(readOriginal(document.getXmlFilePath()));
            Element infNFe = first(dom, "infNFe");
            if (infNFe == null) {
                throw new BadRequestException("error.fiscalXml.rectification.not.nfe", messageService.getMessage("error.fiscalXml.rectification.not.nfe"));
            }
            corrections = applyRules(infNFe, document, rules);
        }

        String rectifiedPath = null;
        if (!corrections.isEmpty()) {
            String externalInvoiceKey = document.accessKeyOrId();
            String notes = buildNotes(corrections);

            // Same corrections already logged AND still in effect: nothing to do. When the rectified
            // version was dropped meanwhile (rule removed, then added back), generate it again.
            if (!alreadyLogged(externalInvoiceKey, notes) || document.getRectifiedFilePath() == null) {
                markAsRectified(dom, corrections.size());
                byte[] rectifiedBytes = serialize(dom);
                rectifiedPath = fileStorage.storeRectified(document.getCompany().getId(), document.getAccessKey(), rectifiedBytes);
                document.setRectifiedFilePath(rectifiedPath);
                logRectification(document, externalInvoiceKey, notes);
            }
        } else if (document.getRectifiedFilePath() != null) {
            // No rule diverges any more (the rule was removed or now matches the note): exports go back
            // to the original. The rectified file stays in storage as history; only the pointer is dropped.
            document.setRectifiedFilePath(null);
            String externalInvoiceKey = document.accessKeyOrId();
            auditTrail.record(document.getCompany(), externalInvoiceKey, InvoiceAuditStatus.RECTIFICATION_REVERTED, null);
        }
        // FD-RN-57: the items' CFOP of the version in effect (the rectified file's, or their own when there is none)
        document.refreshEffectiveCfops(item -> EffectiveCfops.rectified(rules, item));
        document.setLastRectificationCheckedAt(LocalDateTime.now());
        documentRepository.save(document);

        return new FiscalXmlRectificationResultDTO(
                document.getId().toString(), document.getAccessKey(), document.getXmlFilePath(), rectifiedPath, corrections);
    }

    /**
     * Whether reading the file could turn up a correction, judged from the item columns
     * ({@link RectificationPrecheck}). Notes whose columns came from a parser before version
     * 3 (tax codes incomplete) or that have no stored items are always read.
     */
    private boolean mayNeedCorrection(GoodsInvoice document, ProductRules rules) {
        Integer version = document.getMetadataVersion();
        if (version == null || version < ITEM_COLUMNS_VERSION || document.getItems().isEmpty()) {
            return true;
        }
        return document.getItems().stream()
                .anyMatch(item -> rules.forItem(item).map(rule -> RectificationPrecheck.mayChange(item, rule)).orElse(false));
    }

    private boolean alreadyLogged(String externalInvoiceKey, String notes) {
        return auditLogRepository
                .findFirstByExternalInvoiceKeyAndStatusAndDeletedIsFalseOrderByCreatedDateDesc(externalInvoiceKey, InvoiceAuditStatus.RECTIFIED)
                .map(InvoiceAuditLog::getNotes)
                .filter(notes::equals)
                .isPresent();
    }

    private void logRectification(GoodsInvoice document, String externalInvoiceKey, String notes) {
        auditTrail.record(document.getCompany(), externalInvoiceKey, InvoiceAuditStatus.RECTIFIED, notes);
    }

    private String buildNotes(List<String> corrections) {
        String notes = "Retificacao automatica: " + String.join("; ", corrections);
        return notes.length() > NOTES_MAX_LENGTH ? notes.substring(0, NOTES_MAX_LENGTH) : notes;
    }

    /**
     * The rule of each item: by its code and the note's supplier ({@link ProductRules}); the CFOP
     * takes the first digit of the note's operation (ide/idDest).
     */
    private List<String> applyRules(Element infNFe, GoodsInvoice document, ProductRules rules) {
        String supplier = ProductRules.supplierOf(document);
        String idDest = text(first(infNFe, "ide"), "idDest");
        List<String> corrections = new ArrayList<>();
        NodeList detNodes = infNFe.getElementsByTagNameNS("*", "det");

        for (int i = 0; i < detNodes.getLength(); i++) {
            if (!(detNodes.item(i) instanceof Element det)) {
                continue;
            }
            Element prod = first(det, "prod");
            String codigo = text(prod, "cProd");
            if (codigo == null) {
                continue;
            }

            Optional<ProductFiscalRule> ruleOpt = rules.find(codigo, supplier);
            if (ruleOpt.isEmpty()) {
                continue;
            }
            ProductFiscalRule rule = ruleOpt.get();

            applyField(prod, "CFOP", OperationCfops.forOperation(rule.getCorrectCfop(), idDest), codigo, corrections);
            if (rule.getCorrectNcm() != null) {
                applyField(prod, "NCM", rule.getCorrectNcm(), codigo, corrections);
            }
            if (rule.getTaxRule() != null) {
                TaxCodeRectifier.apply(det, rule.getTaxRule(), codigo, corrections);
            }
        }
        return corrections;
    }

    private void applyField(Element prod, String fieldName, String correctValue, String codigoProduto, List<String> corrections) {
        Element field = first(prod, fieldName);
        if (field == null) {
            return;
        }
        String currentValue = field.getTextContent();
        if (Objects.equals(trim(currentValue), trim(correctValue))) {
            return;
        }
        corrections.add("Item %s: %s %s -> %s".formatted(codigoProduto, fieldName, currentValue, correctValue));
        field.setTextContent(correctValue);
    }

    private void markAsRectified(Document dom, int correctionCount) {
        String text = (" Retificado por taxify em %s - %d campo(s) ajustado(s) conforme regra cadastrada. "
                + "ATENCAO: a assinatura digital original deste XML pode estar invalida apos esta alteracao. ")
                .formatted(LocalDateTime.now(), correctionCount);
        Element root = dom.getDocumentElement();
        root.insertBefore(dom.createComment(text), root.getFirstChild());
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    private byte[] readOriginal(String xmlFilePath) {
        try {
            return fileStorage.read(xmlFilePath);
        } catch (IOException e) {
            throw new SiegIntegrationException("error.fiscalXml.rectification.failed", messageService.getMessage("error.fiscalXml.rectification.failed"), e);
        }
    }

    private Document parse(byte[] xmlBytes) {
        try {
            return FiscalXmlDomParser.parse(xmlBytes);
        } catch (Exception e) {
            throw new SiegIntegrationException("error.fiscalXml.rectification.failed", messageService.getMessage("error.fiscalXml.rectification.failed"), e);
        }
    }

    private byte[] serialize(Document document) {
        try {
            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            transformerFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            Transformer transformer = transformerFactory.newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            transformer.transform(new DOMSource(document), new StreamResult(out));
            return out.toByteArray();
        } catch (Exception e) {
            throw new SiegIntegrationException("error.fiscalXml.rectification.failed", messageService.getMessage("error.fiscalXml.rectification.failed"), e);
        }
    }
}
