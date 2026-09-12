package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.CurrentUserService;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.common.TenantAccessService;
import br.com.cshub.taxify.domain.fiscalrule.ProductFiscalRule;
import br.com.cshub.taxify.domain.invoice.InvoiceAuditLog;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocument;
import br.com.cshub.taxify.dto.fiscalrule.FiscalXmlRectificationResultDTO;
import br.com.cshub.taxify.enumerated.InvoiceAuditStatus;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.exception.ResourceNotFoundException;
import br.com.cshub.taxify.exception.SiegIntegrationException;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlDomParser;
import br.com.cshub.taxify.integration.sieg.FiscalXmlFileStorage;
import br.com.cshub.taxify.repository.FiscalXmlDocumentRepository;
import br.com.cshub.taxify.repository.InvoiceAuditLogRepository;
import br.com.cshub.taxify.repository.ProductFiscalRuleRepository;
import org.w3c.dom.Comment;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Rewrites one already-downloaded NFe's CFOP (and NCM, when the rule pins one) to what
 * {@link ProductFiscalRule} says is correct for each product, and writes the result as
 * a brand-new file next to the original - the original is never touched.
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
 * {@link FiscalXmlDocument#setRectifiedFilePath} is also updated to point at the new
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

    private final FiscalXmlDocumentRepository documentRepository;
    private final ProductFiscalRuleRepository ruleRepository;
    private final InvoiceAuditLogRepository auditLogRepository;
    private final FiscalXmlFileStorage fileStorage;
    private final CurrentUserService currentUserService;
    private final MessageService messageService;
    private final TenantAccessService tenantAccessService;

    public FiscalXmlRectificationService(FiscalXmlDocumentRepository documentRepository, ProductFiscalRuleRepository ruleRepository,
                                          InvoiceAuditLogRepository auditLogRepository, FiscalXmlFileStorage fileStorage,
                                          CurrentUserService currentUserService, MessageService messageService, TenantAccessService tenantAccessService) {
        this.documentRepository = documentRepository;
        this.ruleRepository = ruleRepository;
        this.auditLogRepository = auditLogRepository;
        this.fileStorage = fileStorage;
        this.currentUserService = currentUserService;
        this.messageService = messageService;
        this.tenantAccessService = tenantAccessService;
    }

    @Transactional
    public FiscalXmlRectificationResultDTO rectify(UUID documentId) {
        FiscalXmlDocument document = documentRepository.findFirstByIdAndDeletedIsFalse(documentId)
                .orElseThrow(() -> new ResourceNotFoundException(messageService.getMessage("error.entity.not.found")));
        tenantAccessService.assertSameFirm(document.getCompany().getAccountingFirm().getId());

        byte[] originalBytes = readOriginal(document.getXmlFilePath());
        Document dom = parse(originalBytes);

        Element infNFe = firstElementIgnoringNamespace(dom, "infNFe");
        if (infNFe == null) {
            throw new BadRequestException(messageService.getMessage("error.fiscalXml.rectification.not.nfe"));
        }

        List<String> corrections = applyRules(infNFe, document.getCompany().getId());

        String rectifiedPath = null;
        if (!corrections.isEmpty()) {
            String externalInvoiceKey = document.getAccessKey() != null ? document.getAccessKey() : document.getId().toString();
            String notes = buildNotes(corrections);

            if (!alreadyLogged(externalInvoiceKey, notes)) {
                markAsRectified(dom, corrections.size());
                byte[] rectifiedBytes = serialize(dom);
                rectifiedPath = fileStorage.storeRectified(document.getCompany().getId(), document.getAccessKey(), rectifiedBytes);
                document.setRectifiedFilePath(rectifiedPath);
                logRectification(document, externalInvoiceKey, notes);
            }
        }
        document.setLastRectificationCheckedAt(LocalDateTime.now());
        documentRepository.save(document);

        return new FiscalXmlRectificationResultDTO(
                document.getId().toString(), document.getAccessKey(), document.getXmlFilePath(), rectifiedPath, corrections);
    }

    private boolean alreadyLogged(String externalInvoiceKey, String notes) {
        return auditLogRepository
                .findFirstByExternalInvoiceKeyAndStatusAndDeletedIsFalseOrderByCreatedDateDesc(externalInvoiceKey, InvoiceAuditStatus.RECTIFIED)
                .map(InvoiceAuditLog::getNotes)
                .filter(notes::equals)
                .isPresent();
    }

    private void logRectification(FiscalXmlDocument document, String externalInvoiceKey, String notes) {
        InvoiceAuditLog log = new InvoiceAuditLog();
        log.setExternalInvoiceKey(externalInvoiceKey);
        log.setCompany(document.getCompany());
        log.setStatus(InvoiceAuditStatus.RECTIFIED);
        log.setNotes(notes);
        log.setDeleted(false);
        currentUserService.getCurrentUser().ifPresent(log::setUser);
        auditLogRepository.save(log);
    }

    private String buildNotes(List<String> corrections) {
        String notes = "Retificacao automatica: " + String.join("; ", corrections);
        return notes.length() > NOTES_MAX_LENGTH ? notes.substring(0, NOTES_MAX_LENGTH) : notes;
    }

    private List<String> applyRules(Element infNFe, UUID companyId) {
        List<String> corrections = new ArrayList<>();
        NodeList detNodes = infNFe.getElementsByTagNameNS("*", "det");

        for (int i = 0; i < detNodes.getLength(); i++) {
            if (!(detNodes.item(i) instanceof Element det)) {
                continue;
            }
            Element prod = firstElementIgnoringNamespace(det, "prod");
            String codigo = textOf(prod, "cProd");
            if (codigo == null) {
                continue;
            }

            Optional<ProductFiscalRule> ruleOpt = ruleRepository.findFirstByCompany_IdAndCodigoProdutoAndDeletedIsFalse(companyId, codigo);
            if (ruleOpt.isEmpty()) {
                continue;
            }
            ProductFiscalRule rule = ruleOpt.get();

            applyField(prod, "CFOP", rule.getCfopCorreto(), codigo, corrections);
            if (rule.getNcmCorreto() != null) {
                applyField(prod, "NCM", rule.getNcmCorreto(), codigo, corrections);
            }
        }
        return corrections;
    }

    private void applyField(Element prod, String fieldName, String correctValue, String codigoProduto, List<String> corrections) {
        Element field = firstElementIgnoringNamespace(prod, fieldName);
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
            return Files.readAllBytes(Path.of(xmlFilePath));
        } catch (IOException e) {
            throw new SiegIntegrationException(messageService.getMessage("error.fiscalXml.rectification.failed"), e);
        }
    }

    private Document parse(byte[] xmlBytes) {
        try {
            return FiscalXmlDomParser.parse(xmlBytes);
        } catch (Exception e) {
            throw new SiegIntegrationException(messageService.getMessage("error.fiscalXml.rectification.failed"), e);
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
            throw new SiegIntegrationException(messageService.getMessage("error.fiscalXml.rectification.failed"), e);
        }
    }

    private String textOf(Element parent, String childLocalName) {
        Element child = firstElementIgnoringNamespace(parent, childLocalName);
        return child == null ? null : child.getTextContent();
    }

    private Element firstElementIgnoringNamespace(Document doc, String localName) {
        NodeList nodes = doc.getElementsByTagNameNS("*", localName);
        return nodes.getLength() > 0 ? (Element) nodes.item(0) : null;
    }

    private Element firstElementIgnoringNamespace(Element parent, String localName) {
        if (parent == null) {
            return null;
        }
        NodeList nodes = parent.getElementsByTagNameNS("*", localName);
        return nodes.getLength() > 0 ? (Element) nodes.item(0) : null;
    }
}
