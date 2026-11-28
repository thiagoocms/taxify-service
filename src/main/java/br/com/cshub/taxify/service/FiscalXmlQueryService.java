package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.IssuePeriod;
import br.com.cshub.taxify.common.RequestIds;
import br.com.cshub.taxify.common.SortProperties;
import br.com.cshub.taxify.exception.BadRequestException;

import br.com.cshub.taxify.integration.sieg.FiscalXmlFileStorage;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlEventDTO;
import java.util.Locale;
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
import java.util.SortedSet;
import java.util.TreeSet;
import br.com.cshub.taxify.dto.PageRequestDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlDetailDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlProdutoDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlSummaryDTO;
import br.com.cshub.taxify.dto.fiscalxml.XmlSearchFilterDTO;
import br.com.cshub.taxify.exception.ResourceNotFoundException;
import br.com.cshub.taxify.integration.sieg.FiscalXmlMetadata;
import br.com.cshub.taxify.integration.sieg.FiscalXmlMetadataParser;
import br.com.cshub.taxify.validation.CfopFilterValidation;
import br.com.cshub.taxify.validation.CompanyValidation;
import br.com.cshub.taxify.domain.fiscaldocument.CfopFilter;
import br.com.cshub.taxify.repository.FiscalDocumentSearchFilter;
import java.time.Clock;
import java.util.ArrayList;
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
    private final DocumentDivergenceService divergenceService;

    private final FiscalDocumentRepository documentRepository;
    private final GoodsInvoiceRepository goodsInvoiceRepository;
    private final FiscalDocumentItemRepository itemRepository;
    private final FiscalXmlMetadataParser metadataParser;
    private final CompanyValidation companyValidation;
    private final TenantAccessService tenantAccessService;
    private final MessageService messageService;
    private final CfopFilterValidation cfopFilterValidation;
    private final Clock clock;

    public FiscalXmlQueryService(FiscalDocumentRepository documentRepository, GoodsInvoiceRepository goodsInvoiceRepository,
                                 FiscalDocumentItemRepository itemRepository,
                                 FiscalXmlMetadataParser metadataParser, CompanyValidation companyValidation,
                                 TenantAccessService tenantAccessService, MessageService messageService,
                                 FiscalXmlEventService eventService, FiscalXmlFileStorage fileStorage,
                                 DocumentDivergenceService divergenceService, CfopFilterValidation cfopFilterValidation,
                                 Clock clock) {
        this.divergenceService = divergenceService;
        this.cfopFilterValidation = cfopFilterValidation;
        this.clock = clock;
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

    /**
     * The cards: entries and exits of the notes matching every filter of the list but the tab - the same predicate
     * ({@link FiscalDocumentSearchFilter#WHERE}), the whole note of each one (CF-RN-10 to 12); with a CFOP filter, also
     * the items in those CFOPs (CF-RN-13). {@code divergencia} keeps, in memory, the same notes the list keeps (CF-RN-15).
     */
    @Transactional(readOnly = true)
    public FiscalXmlTotalsDTO totals(XmlSearchFilterDTO dto) {
        long started = System.nanoTime();
        Company company = companyValidation.checkExist(RequestIds.parse(dto.companyId(), messageService));
        ScreenFilter screen = screenFilter(dto, company);
        FiscalDocumentSearchFilter filter = screen.filter().withoutDirection();
        boolean filtered = screen.narrowsTheScope() || dto.onlyDivergent();
        boolean withCfops = !filter.noCfops();

        NoteCardTotals.Cards cards;
        FiscalXmlTotalsDTO.CfopItems items = withCfops ? NoteCardTotals.emptyItems() : null;
        if (screen.matchesNothing()) {
            cards = NoteCardTotals.Cards.EMPTY;
        } else if (dto.onlyDivergent()) {
            List<GoodsInvoice> kept = divergentNotes(company, documentRepository.search(filter, Pageable.unpaged()).getContent())
                    .stream().filter(GoodsInvoice.class::isInstance).map(GoodsInvoice.class::cast).toList();
            cards = NoteCardTotals.sum(kept);
            if (withCfops) {
                items = cfopItemsOf(kept, filter);
            }
        } else {
            cards = NoteCardTotals.fromRows(goodsInvoiceRepository.totalsByDirectionFiltered(filter));
            if (withCfops) {
                items = NoteCardTotals.addItemRows(items, itemRepository.cfopItemTotalsByDirection(filter));
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("note totals: company {}, {} cfop(s), filters [{}], divergencia {}, {} ms", company.getId(),
                    filter.cfops().values().size(), screen.activeFilterNames(), dto.onlyDivergent(),
                    (System.nanoTime() - started) / 1_000_000);
        }
        return new FiscalXmlTotalsDTO(cards.inbound(), cards.outbound(), filtered, items);
    }

    /** CF-RN-13 on the "só divergentes" path: the items of the kept, non-canceled notes, in batches of 500. */
    private FiscalXmlTotalsDTO.CfopItems cfopItemsOf(List<GoodsInvoice> notes, FiscalDocumentSearchFilter filter) {
        List<UUID> ids = notes.stream().filter(note -> !note.isCanceled()).map(GoodsInvoice::getId).toList();
        FiscalXmlTotalsDTO.CfopItems items = NoteCardTotals.emptyItems();
        for (int start = 0; start < ids.size(); start += ID_BATCH) {
            items = NoteCardTotals.addItemRows(items, itemRepository.cfopItemTotalsOfDocuments(
                    ids.subList(start, Math.min(ids.size(), start + ID_BATCH)), filter.cfops().values()));
        }
        return items;
    }

    private static final int ID_BATCH = 500;

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
    public Page<FiscalXmlSummaryDTO> search(XmlSearchFilterDTO dto) {
        Company company = companyValidation.checkExist(RequestIds.parse(dto.companyId(), messageService));
        Pageable pageable = withCurrentSortNames(PageRequestDTO.toPageable(dto.pagination(), "issueDate", Sort.Direction.DESC));
        ScreenFilter screen = screenFilter(dto, company);
        if (screen.matchesNothing()) {
            return Page.empty(pageable); // an origin that never existed matches no note, as before
        }

        Page<FiscalDocument> documents;
        Map<UUID, FiscalXmlSummaryDTO.DivergenceSummary> divergences;
        if (dto.onlyDivergent()) {
            // The checks run in memory, so the filter does too: every note matching the other
            // filters is checked, the divergent ones are kept and paged here.
            List<FiscalDocument> all = documentRepository.search(screen.filter(), Pageable.unpaged(pageable.getSort())).getContent();
            divergences = divergenceService.summarize(company.getId(), all);
            List<FiscalDocument> divergent = keepDivergent(all, divergences);
            int start = (int) Math.min(pageable.getOffset(), divergent.size());
            int end = Math.min(divergent.size(), start + pageable.getPageSize());
            documents = new PageImpl<>(divergent.subList(start, end), pageable, divergent.size());
        } else {
            documents = documentRepository.search(screen.filter(), pageable);
            divergences = divergenceService.summarize(company.getId(), documents.getContent());
        }

        Map<UUID, Long> itemCounts = new HashMap<>();
        Map<UUID, SortedSet<String>> cfops = new HashMap<>();
        if (documents.hasContent()) {
            List<UUID> ids = documents.getContent().stream().map(FiscalDocument::getId).toList();
            for (Object[] row : itemRepository.countByDocumentIds(ids)) {
                itemCounts.put((UUID) row[0], (Long) row[1]);
            }
            // FD-RN-57: the CFOP of the version in effect, as stored - the one the filter finds and the exported
            // rectified file has
            for (Object[] row : itemRepository.findDistinctEffectiveCfopsByDocumentIds(ids)) {
                UUID documentId = (UUID) row[0];
                String cfop = ((String) row[1]).strip();
                if (!cfop.isEmpty()) {
                    cfops.computeIfAbsent(documentId, id -> new TreeSet<>()).add(cfop);
                }
            }
        }

        return documents.map(document -> FiscalXmlApiMapper.toSummary(document, itemCounts.getOrDefault(document.getId(), 0L),
                List.copyOf(cfops.getOrDefault(document.getId(), new TreeSet<>())), divergences.get(document.getId())));
    }

    /** CF-RN-15: the notes the checks found something on, or marked "com divergência" - the list and the cards alike. */
    private List<FiscalDocument> divergentNotes(Company company, List<FiscalDocument> all) {
        return keepDivergent(all, divergenceService.summarize(company.getId(), all));
    }

    private static List<FiscalDocument> keepDivergent(List<FiscalDocument> all,
                                                      Map<UUID, FiscalXmlSummaryDTO.DivergenceSummary> divergences) {
        return all.stream()
                .filter(document -> divergences.containsKey(document.getId()) || document.getReviewStatus() == ReviewStatus.DIVERGENT)
                .toList();
    }

    /**
     * The body read once, for the list and the cards alike. {@code matchesNothing}: an origin that never existed - no
     * note matches, as before.
     */
    private record ScreenFilter(FiscalDocumentSearchFilter filter, boolean matchesNothing) {

        boolean narrowsTheScope() {
            return matchesNothing || filter.narrowsTheScope();
        }

        /** The names (never the values) of the filters on - the debug log of the cards. */
        String activeFilterNames() {
            List<String> names = new ArrayList<>();
            if (filter.number() != null) {
                names.add("numero");
            }
            if (filter.participant() != null) {
                names.add("participant");
            }
            if (filter.reviewStatus() != null) {
                names.add("reviewStatus");
            }
            if (filter.source() != null || matchesNothing) {
                names.add("origem");
            }
            if (filter.onlySiegBefore() != null || filter.onlySefazBefore() != null) {
                names.add("fonte");
            }
            if (!filter.noCfops()) {
                names.add("cfops");
            }
            return String.join(",", names);
        }
    }

    /** The body as the predicate's filter (CF-RN-01 to 09 for the CFOPs: a 400 on a broken {@code cfops}). */
    private ScreenFilter screenFilter(XmlSearchFilterDTO dto, Company company) {
        CfopFilter cfops = cfopFilterValidation.check(dto.cfops(), dto.cfop(), company.getId());
        // nNF is stored without leading zeros, so "000123" typed by the user still matches.
        String number = dto.numero() == null ? "" : dto.numero().replaceAll("\\D", "").replaceFirst("^0+(?=\\d)", "");
        boolean withOrigin = dto.origem() != null && !dto.origem().isBlank();
        DocumentSourceKey source = withOrigin ? sourceOf(dto.origem().strip()) : null;
        LocalDateTime comparisonCutoff = LocalDateTime.now(clock).minusHours(SOURCE_COMPARISON_HOURS);
        FiscalDocumentSearchFilter filter = new FiscalDocumentSearchFilter(company.getId(),
                IssuePeriod.start(dto.dataEmissaoInicio()), IssuePeriod.endExclusive(dto.dataEmissaoFim()),
                number.isEmpty() ? null : number, dto.direction(), participantPattern(dto.participant()), dto.reviewStatus(),
                source,
                XmlSearchFilterDTO.ONLY_SIEG.equals(dto.fonte()) ? comparisonCutoff : null,
                XmlSearchFilterDTO.ONLY_SEFAZ.equals(dto.fonte()) ? comparisonCutoff : null,
                cfops);
        return new ScreenFilter(filter, withOrigin && source == null);
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
        // FD-RN-57: the stored effective CFOP - the rectified file's, the same the list shows and the filter finds
        return itemRepository.findByDocument_IdOrderByItemNumberAsc(document.getId()).stream()
                .map(item -> {
                    FiscalXmlProdutoDTO produto = FiscalXmlApiMapper.toProduto(item);
                    String effective = item.getEffectiveCfop() == null ? item.cfop() : item.getEffectiveCfop();
                    return item.cfop() == null ? produto : produto.withRectifiedCfop(effective.strip());
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
