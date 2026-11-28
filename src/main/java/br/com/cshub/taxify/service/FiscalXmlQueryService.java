package br.com.cshub.taxify.service;

import br.com.cshub.taxify.service.fiscalcheck.OperationCfops;
import br.com.cshub.taxify.domain.fiscalrule.ProductRules;
import br.com.cshub.taxify.common.IssuePeriod;
import br.com.cshub.taxify.common.RequestIds;
import br.com.cshub.taxify.common.SortProperties;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.domain.fiscalrule.ProductFiscalRule;
import br.com.cshub.taxify.repository.ProductFiscalRuleRepository;
import br.com.cshub.taxify.service.fiscalcheck.ProductRuleCheck;

import br.com.cshub.taxify.integration.sieg.FiscalXmlFileStorage;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlEventDTO;
import java.util.Locale;
import java.math.BigDecimal;
import br.com.cshub.taxify.enumerated.FiscalDirection;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlTotalsDTO;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.common.TenantAccessService;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.fiscaldocument.DocumentSourceKey;
import br.com.cshub.taxify.domain.fiscaldocument.FiscalDocument;
import br.com.cshub.taxify.domain.fiscaldocument.GoodsInvoice;
import br.com.cshub.taxify.mapper.fiscaldocument.FiscalXmlApiMapper;
import br.com.cshub.taxify.mapper.fiscaldocument.LegacySourceNames;
import br.com.cshub.taxify.repository.FiscalDocumentItemRepository;
import br.com.cshub.taxify.repository.FiscalDocumentRepository;
import br.com.cshub.taxify.repository.GoodsInvoiceRepository;
import org.springframework.data.domain.PageRequest;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import br.com.cshub.taxify.dto.PageRequestDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlDetailDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlProdutoDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlSummaryDTO;
import br.com.cshub.taxify.dto.fiscalxml.XmlSearchFilterDTO;
import br.com.cshub.taxify.exception.ResourceNotFoundException;
import br.com.cshub.taxify.integration.sieg.FiscalXmlMetadata;
import br.com.cshub.taxify.integration.sieg.FiscalXmlMetadataParser;
import br.com.cshub.taxify.validation.CompanyValidation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import br.com.cshub.taxify.enumerated.ReviewStatus;
import br.com.cshub.taxify.service.fiscalcheck.DocumentDivergenceService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read side of the "Notas fiscais" screens, split so each screen/tab only loads what it
 * shows: the list reads DB columns only, while the detail header, the items and the raw
 * XML each have their own call - the old single search re-read and re-parsed every XML
 * file of the page just to render a table row.
 */
@Service
public class FiscalXmlQueryService {

    private static final Logger log = LoggerFactory.getLogger(FiscalXmlQueryService.class);

    private final FiscalXmlEventService eventService;
    private final FiscalXmlFileStorage fileStorage;
    private final ProductFiscalRuleRepository ruleRepository;
    private final DocumentDivergenceService divergenceService;

    private final FiscalDocumentRepository documentRepository;
    private final GoodsInvoiceRepository goodsInvoiceRepository;
    private final FiscalDocumentItemRepository itemRepository;
    private final FiscalXmlMetadataParser metadataParser;
    private final CompanyValidation companyValidation;
    private final TenantAccessService tenantAccessService;
    private final MessageService messageService;

    public FiscalXmlQueryService(FiscalDocumentRepository documentRepository, GoodsInvoiceRepository goodsInvoiceRepository,
                                 FiscalDocumentItemRepository itemRepository,
                                 FiscalXmlMetadataParser metadataParser, CompanyValidation companyValidation,
                                 TenantAccessService tenantAccessService, MessageService messageService,
                                 FiscalXmlEventService eventService, FiscalXmlFileStorage fileStorage,
                                 ProductFiscalRuleRepository ruleRepository, DocumentDivergenceService divergenceService) {
        this.ruleRepository = ruleRepository;
        this.divergenceService = divergenceService;
        this.fileStorage = fileStorage;
        this.eventService = eventService;
        this.documentRepository = documentRepository;
        this.goodsInvoiceRepository = goodsInvoiceRepository;
        this.itemRepository = itemRepository;
        this.metadataParser = metadataParser;
        this.companyValidation = companyValidation;
        this.tenantAccessService = tenantAccessService;
        this.messageService = messageService;
    }

    /** Entries and exits of the period (same company/period filter as the list, other filters ignored). */
    @Transactional(readOnly = true)
    public FiscalXmlTotalsDTO totals(XmlSearchFilterDTO filter) {
        Company company = companyValidation.checkExist(RequestIds.parse(filter.companyId(), messageService));
        LocalDateTime from = IssuePeriod.start(filter.dataEmissaoInicio());
        LocalDateTime to = IssuePeriod.end(filter.dataEmissaoFim());

        FiscalXmlTotalsDTO.DirectionTotals inbound = FiscalXmlTotalsDTO.DirectionTotals.empty();
        FiscalXmlTotalsDTO.DirectionTotals outbound = FiscalXmlTotalsDTO.DirectionTotals.empty();
        for (Object[] row : goodsInvoiceRepository.totalsByDirection(company.getId(), from, to)) {
            FiscalXmlTotalsDTO.DirectionTotals totals = new FiscalXmlTotalsDTO.DirectionTotals(((Number) row[1]).longValue(),
                    decimal(row[2]), decimal(row[3]), decimal(row[4]), decimal(row[5]), decimal(row[6]), decimal(row[7]),
                    ((Number) row[8]).longValue());
            if (row[0] == FiscalDirection.OUTBOUND) {
                outbound = totals;
            } else {
                inbound = totals; // INBOUND, or a legacy row without direction
            }
        }
        return new FiscalXmlTotalsDTO(inbound, outbound);
    }

    /** SUM over a CASE comes back as BigDecimal or Long/Integer depending on the dialect. */
    private static BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
    }

    /** The note's SEFAZ events (cancelamento, carta de correção...) - same access check as the note. */
    @Transactional(readOnly = true)
    public List<FiscalXmlEventDTO> findEvents(UUID documentId) {
        return eventService.eventsOf(findDocument(documentId).getId());
    }

    /** "acme" → "%acme%"; "11.594.485/0001-05" → "%11594485000105%" (documents are stored as digits). */
    static String participantPattern(String participant) {
        if (participant == null || participant.isBlank()) {
            return null;
        }
        String digits = participant.replaceAll("\\D", "");
        boolean looksLikeDocument = !digits.isEmpty() && participant.replaceAll("[\\d./\\-\\s]", "").isEmpty();
        return "%" + (looksLikeDocument ? digits : participant.strip().toLowerCase(Locale.ROOT)) + "%";
    }

    /** A note in one source and not in the other for this long counts as a difference (SIEG x SEFAZ). */
    public static final long SOURCE_COMPARISON_HOURS = 48;

    /** The sort of the list by the old property names too (the entity of the screen was renamed, FD-RN-23). */
    /**
     * {@code sort=} names of the old entity (and of the API) -> properties of the model. {@code documentType} sorts by
     * the model (55 NF-e, 57 CT-e, 65 NFC-e - the discriminator is no property) and {@code ownIssue} by the company's
     * role; anything else unknown is a 400, as in the CRUD base ({@link SortProperties}).
     */
    static final Map<String, String> LEGACY_SORT = Map.ofEntries(
            Map.entry("numero", "number"), Map.entry("modelo", "model"), Map.entry("serie", "series"),
            Map.entry("valorTotalNota", "totalAmount"), Map.entry("emitenteNome", "issuer.name"),
            Map.entry("destinatarioNome", "recipient.name"), Map.entry("naturezaOperacao", "operationNature"),
            Map.entry("documentType", "model"), Map.entry("reviewStatus", "review.status"),
            Map.entry("issuerCnpj", "issuer.document"), Map.entry("recipientCnpj", "recipient.document"),
            Map.entry("ownIssue", "companyRole"), Map.entry("tipoOperacao", "header.operationType"));

    @Transactional(readOnly = true)
    public Page<FiscalXmlSummaryDTO> search(XmlSearchFilterDTO filter) {
        Company company = companyValidation.checkExist(RequestIds.parse(filter.companyId(), messageService));
        Pageable pageable = withCurrentSortNames(PageRequestDTO.toPageable(filter.pagination(), "issueDate", Sort.Direction.DESC));

        LocalDateTime from = IssuePeriod.start(filter.dataEmissaoInicio());
        LocalDateTime to = IssuePeriod.end(filter.dataEmissaoFim());

        // nNF is stored without leading zeros, so "000123" typed by the user still matches.
        String numero = filter.numero() == null ? "" : filter.numero().replaceAll("\\D", "").replaceFirst("^0+(?=\\d)", "");

        LocalDateTime comparisonCutoff = LocalDateTime.now().minusHours(SOURCE_COMPARISON_HOURS);
        DocumentSourceKey origem = filter.origem() == null || filter.origem().isBlank() ? null : sourceOf(filter.origem().strip());
        if (filter.origem() != null && !filter.origem().isBlank() && origem == null) {
            return Page.empty(pageable); // an origin that never existed matches no note, as before
        }
        String cfopFilter = filter.cfop() == null ? "" : filter.cfop().replaceAll("\\D", "");
        Function<Pageable, Page<FiscalDocument>> query = page -> documentRepository.search(company.getId(), from, to,
                numero.isEmpty() ? null : numero, filter.direction(), participantPattern(filter.participant()),
                filter.reviewStatus(), origem,
                XmlSearchFilterDTO.ONLY_SIEG.equals(filter.fonte()) ? comparisonCutoff : null,
                XmlSearchFilterDTO.ONLY_SEFAZ.equals(filter.fonte()) ? comparisonCutoff : null,
                cfopFilter.isEmpty() ? null : cfopFilter,
                DocumentSourceKey.SIEG, DocumentSourceKey.SEFAZ_DFE,
                page);

        Page<FiscalDocument> documents;
        Map<UUID, FiscalXmlSummaryDTO.DivergenceSummary> divergences;
        if (filter.onlyDivergent()) {
            // The checks run in memory, so the filter does too: every note matching the other
            // filters is checked, the divergent ones are kept and paged here.
            List<FiscalDocument> all = query.apply(Pageable.unpaged(pageable.getSort())).getContent();
            divergences = divergenceService.summarize(company.getId(), all);
            List<FiscalDocument> divergent = all.stream()
                    .filter(document -> divergences.containsKey(document.getId()) || document.getReviewStatus() == ReviewStatus.DIVERGENT)
                    .toList();
            int start = (int) Math.min(pageable.getOffset(), divergent.size());
            int end = Math.min(divergent.size(), start + pageable.getPageSize());
            documents = new PageImpl<>(divergent.subList(start, end), pageable, divergent.size());
        } else {
            documents = query.apply(pageable);
            divergences = divergenceService.summarize(company.getId(), documents.getContent());
        }

        Map<UUID, Long> itemCounts = new HashMap<>();
        Map<UUID, SortedSet<String>> cfops = new HashMap<>();
        if (documents.hasContent()) {
            List<UUID> ids = documents.getContent().stream().map(FiscalDocument::getId).toList();
            for (Object[] row : itemRepository.countByDocumentIds(ids)) {
                itemCounts.put((UUID) row[0], (Long) row[1]);
            }
            Map<UUID, FiscalDocument> byId = documents.getContent().stream()
                    .collect(Collectors.toMap(FiscalDocument::getId, d -> d));
            ProductRules rules = rulesIfAnyRectified(company.getId(), documents.getContent());
            for (Object[] row : itemRepository.findDistinctCfopsByDocumentIds(ids)) {
                UUID documentId = (UUID) row[0];
                ProductFiscalRule rule = rules.find((String) row[1], ProductRules.supplierOf(byId.get(documentId))).orElse(null);
                String cfop = effectiveCfop(byId.get(documentId), rule, (String) row[2]);
                if (!cfop.isEmpty()) {
                    cfops.computeIfAbsent(documentId, id -> new TreeSet<>()).add(cfop);
                }
            }
        }

        return documents.map(document -> FiscalXmlApiMapper.toSummary(document, itemCounts.getOrDefault(document.getId(), 0L),
                List.copyOf(cfops.getOrDefault(document.getId(), new TreeSet<>())), divergences.get(document.getId())));
    }

    /** {@code origem} of the API (SIEG, SEFAZ_DIST, UPLOAD) as the source of the model; null when it is no valid source. */
    private static DocumentSourceKey sourceOf(String origem) {
        try {
            return LegacySourceNames.toSource(origem);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    /** The sort with the old names translated and checked on the goods invoice (its properties include the common ones). */
    private Pageable withCurrentSortNames(Pageable pageable) {
        return SortProperties.resolve(pageable, GoodsInvoice.class, LEGACY_SORT)
                .orElseThrow(() -> new BadRequestException(BadRequestException.DEFAULT_CODE,
                        messageService.getMessage(BadRequestException.DEFAULT_CODE)));
    }

    /** The company's rules by product code - only read when some note of the page has a rectified version. */
    private ProductRules rulesIfAnyRectified(UUID companyId, List<FiscalDocument> documents) {
        if (documents.stream().noneMatch(FiscalXmlApiMapper::isRectified)) {
            return ProductRules.none();
        }
        return ProductRules.of(ruleRepository.findByCompany_IdAndDeletedIsFalse(companyId));
    }

    /**
     * The item's CFOP as the note stands now: a rectified version built with the current rule
     * rewrote it to the rule's CFOP (the item column keeps what was issued).
     */
    private static String effectiveCfop(FiscalDocument document, ProductFiscalRule rule, String issued) {
        if (rule != null && rule.getCorrectCfop() != null && document instanceof GoodsInvoice goods
                && ProductRuleCheck.rectifiedUnder(goods, rule)) {
            return OperationCfops.forOperation(rule.getCorrectCfop(), goods.getHeader().destinationType()).strip();
        }
        return issued.strip();
    }

    /** First parser version that stores every header field the detail shows (the header and country columns). */
    private static final int HEADER_EXTRAS_VERSION = 4;

    /**
     * Built from the stored columns - no file read - for NF-e and NFC-e saved (or backfilled) by
     * parser version 4 on, which keeps every header field shown on screen. Older notes and
     * other document types parse the original file; when it is gone (purged by
     * FiscalXmlPurgeScheduler), they fall back to the columns too, just without the few
     * fields those older versions didn't store.
     */
    @Transactional(readOnly = true)
    public FiscalXmlDetailDTO findDetail(UUID documentId) {
        FiscalDocument document = findDocument(documentId);
        long itemCount = itemRepository.countByDocument_Id(document.getId());
        if (hasAllHeaderColumns(document)) {
            return FiscalXmlApiMapper.detailFromColumns(document, itemCount);
        }

        byte[] xmlBytes;
        try {
            xmlBytes = fileStorage.read(document.getXmlFilePath());
        } catch (IOException e) {
            log.info("XML file '{}' for document {} not readable, building detail from stored columns", document.getXmlFilePath(), document.getId());
            return FiscalXmlApiMapper.detailFromColumns(document, itemCount);
        }
        FiscalXmlMetadata metadata = metadataParser.parse(xmlBytes);
        return FiscalXmlApiMapper.detailFromFile(document, metadata, itemCount);
    }

    /** The items as issued (the DANFE prints these). */
    @Transactional(readOnly = true)
    public List<FiscalXmlProdutoDTO> findProdutos(UUID documentId) {
        FiscalDocument document = findDocument(documentId);
        return itemRepository.findByDocument_IdOrderByItemNumberAsc(document.getId()).stream()
                .map(FiscalXmlApiMapper::toProduto)
                .toList();
    }

    /**
     * The items as the note stands now: when it has a rectified version built with the current
     * rule, the CFOP shown is the rectified one and {@code cfopEmitido} keeps what was issued.
     */
    @Transactional(readOnly = true)
    public List<FiscalXmlProdutoDTO> findProdutosAsRectified(UUID documentId) {
        FiscalDocument document = findDocument(documentId);
        ProductRules rules = rulesIfAnyRectified(document.getCompany().getId(), List.of(document));
        return itemRepository.findByDocument_IdOrderByItemNumberAsc(document.getId()).stream()
                .map(item -> {
                    FiscalXmlProdutoDTO produto = FiscalXmlApiMapper.toProduto(item);
                    ProductFiscalRule rule = rules.forItem(item).orElse(null);
                    return item.cfop() == null ? produto
                            : produto.withRectifiedCfop(effectiveCfop(document, rule, item.cfop()));
                })
                .toList();
    }

    public record StoredXml(String fileName, byte[] content) {
    }

    /** {@code rectified}: serves the corrected file when one exists, falling back to the original. */
    @Transactional(readOnly = true)
    public StoredXml findXml(UUID documentId, boolean rectified) {
        FiscalDocument document = findDocument(documentId);
        String path = rectified && document instanceof GoodsInvoice goods && goods.getRectifiedFilePath() != null
                ? goods.getRectifiedFilePath() : document.getXmlFilePath();
        String baseName = document.accessKeyOrId();
        return new StoredXml(baseName + ".xml", readFile(document, path));
    }

    /** An NF-e, NFC-e or CT-e the user can see - an NFS-e id, or another company's note, is "not found" (FD-RN-17). */
    private FiscalDocument findDocument(UUID documentId) {
        FiscalDocument document = documentRepository.findXmlDocument(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("error.entity.not.found", messageService.getMessage("error.entity.not.found")));
        tenantAccessService.assertCompanyAccess(document.getCompany());
        return document;
    }

    private byte[] readFile(FiscalDocument document, String path) {
        try {
            return fileStorage.read(path);
        } catch (IOException e) {
            log.warn("Could not read stored XML file '{}' for document {}", path, document.getId(), e);
            throw new ResourceNotFoundException("error.fiscalXml.file.not.found", messageService.getMessage("error.fiscalXml.file.not.found"));
        }
    }

    private boolean hasAllHeaderColumns(FiscalDocument document) {
        return document instanceof GoodsInvoice
                && document.getMetadataVersion() != null
                && document.getMetadataVersion() >= HEADER_EXTRAS_VERSION;
    }
}
