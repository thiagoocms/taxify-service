package br.com.cshub.taxify.service;

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
import br.com.cshub.taxify.domain.xml.FiscalXmlDocument;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocumentItem;
import br.com.cshub.taxify.domain.xml.FiscalXmlHeaderExtras;
import br.com.cshub.taxify.dto.PageRequestDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlDestinatarioDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlDetailDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlEmitenteDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlEnderecoDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlIdentificacaoDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlImpostoItemDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlProdutoDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlSummaryDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlTotaisDTO;
import br.com.cshub.taxify.dto.fiscalxml.XmlSearchFilterDTO;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.exception.ResourceNotFoundException;
import br.com.cshub.taxify.integration.sieg.FiscalXmlMetadata;
import br.com.cshub.taxify.integration.sieg.FiscalXmlMetadataParser;
import br.com.cshub.taxify.repository.FiscalXmlDocumentItemRepository;
import br.com.cshub.taxify.repository.FiscalXmlDocumentRepository;
import br.com.cshub.taxify.validation.CompanyValidation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.LocalTime;
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

    private final FiscalXmlDocumentRepository documentRepository;
    private final FiscalXmlDocumentItemRepository itemRepository;
    private final FiscalXmlMetadataParser metadataParser;
    private final CompanyValidation companyValidation;
    private final TenantAccessService tenantAccessService;
    private final MessageService messageService;

    public FiscalXmlQueryService(FiscalXmlDocumentRepository documentRepository, FiscalXmlDocumentItemRepository itemRepository,
                                 FiscalXmlMetadataParser metadataParser, CompanyValidation companyValidation,
                                 TenantAccessService tenantAccessService, MessageService messageService,
                                 FiscalXmlEventService eventService, FiscalXmlFileStorage fileStorage,
                                 ProductFiscalRuleRepository ruleRepository) {
        this.ruleRepository = ruleRepository;
        this.fileStorage = fileStorage;
        this.eventService = eventService;
        this.documentRepository = documentRepository;
        this.itemRepository = itemRepository;
        this.metadataParser = metadataParser;
        this.companyValidation = companyValidation;
        this.tenantAccessService = tenantAccessService;
        this.messageService = messageService;
    }

    /** Entries and exits of the period (same company/period filter as the list, other filters ignored). */
    @Transactional(readOnly = true)
    public FiscalXmlTotalsDTO totals(XmlSearchFilterDTO filter) {
        Company company = companyValidation.checkExist(parseUuid(filter.companyId()));
        LocalDateTime from = filter.dataEmissaoInicio() != null ? filter.dataEmissaoInicio().atStartOfDay() : LocalDateTime.MIN;
        LocalDateTime to = filter.dataEmissaoFim() != null ? filter.dataEmissaoFim().atTime(LocalTime.MAX) : LocalDateTime.MAX;

        FiscalXmlTotalsDTO.DirectionTotals inbound = FiscalXmlTotalsDTO.DirectionTotals.empty();
        FiscalXmlTotalsDTO.DirectionTotals outbound = FiscalXmlTotalsDTO.DirectionTotals.empty();
        for (Object[] row : documentRepository.totalsByDirection(company.getId(), from, to)) {
            FiscalXmlTotalsDTO.DirectionTotals totals = new FiscalXmlTotalsDTO.DirectionTotals(((Number) row[1]).longValue(),
                    decimal(row[2]), decimal(row[3]), decimal(row[4]), decimal(row[5]), decimal(row[6]), decimal(row[7]),
                    ((Number) row[8]).longValue());
            if (row[0] == FiscalDirection.OUTBOUND) {
                outbound = totals;
            } else {
                inbound = totals; // INBOUND, or a legacy row the backfill hasn't reached yet
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

    @Transactional(readOnly = true)
    public Page<FiscalXmlSummaryDTO> search(XmlSearchFilterDTO filter) {
        Company company = companyValidation.checkExist(parseUuid(filter.companyId()));
        Pageable pageable = PageRequestDTO.toPageable(filter.pagination(), "issueDate", Sort.Direction.DESC);

        LocalDateTime from = filter.dataEmissaoInicio() != null ? filter.dataEmissaoInicio().atStartOfDay() : LocalDateTime.MIN;
        LocalDateTime to = filter.dataEmissaoFim() != null ? filter.dataEmissaoFim().atTime(LocalTime.MAX) : LocalDateTime.MAX;

        // nNF is stored without leading zeros, so "000123" typed by the user still matches.
        String numero = filter.numero() == null ? "" : filter.numero().replaceAll("\\D", "").replaceFirst("^0+(?=\\d)", "");

        Page<FiscalXmlDocument> documents = documentRepository.search(company.getId(), from, to,
                numero.isEmpty() ? null : numero, filter.direction(), participantPattern(filter.participant()),
                filter.reviewStatus(), pageable);

        Map<UUID, Long> itemCounts = new HashMap<>();
        Map<UUID, java.util.SortedSet<String>> cfops = new HashMap<>();
        if (documents.hasContent()) {
            List<UUID> ids = documents.getContent().stream().map(FiscalXmlDocument::getId).toList();
            for (Object[] row : itemRepository.countByDocumentIds(ids)) {
                itemCounts.put((UUID) row[0], (Long) row[1]);
            }
            Map<UUID, FiscalXmlDocument> byId = documents.getContent().stream()
                    .collect(java.util.stream.Collectors.toMap(FiscalXmlDocument::getId, d -> d));
            Map<String, ProductFiscalRule> rules = rulesIfAnyRectified(company.getId(), documents.getContent());
            for (Object[] row : itemRepository.findDistinctCfopsByDocumentIds(ids)) {
                UUID documentId = (UUID) row[0];
                ProductFiscalRule rule = row[1] == null ? null : rules.get((String) row[1]);
                String cfop = effectiveCfop(byId.get(documentId), rule, (String) row[2]);
                if (!cfop.isEmpty()) {
                    cfops.computeIfAbsent(documentId, id -> new java.util.TreeSet<>()).add(cfop);
                }
            }
        }

        return documents.map(document -> toSummary(document, itemCounts.getOrDefault(document.getId(), 0L),
                List.copyOf(cfops.getOrDefault(document.getId(), new java.util.TreeSet<>()))));
    }

    /** The company's rules by product code - only read when some note of the page has a rectified version. */
    private Map<String, ProductFiscalRule> rulesIfAnyRectified(UUID companyId, List<FiscalXmlDocument> documents) {
        if (documents.stream().noneMatch(document -> document.getRectifiedFilePath() != null)) {
            return Map.of();
        }
        return ruleRepository.findByCompany_IdAndDeletedIsFalse(companyId).stream()
                .filter(rule -> rule.getCodigoProduto() != null)
                .collect(java.util.stream.Collectors.toMap(ProductFiscalRule::getCodigoProduto, rule -> rule, (first, second) -> first));
    }

    /**
     * The item's CFOP as the note stands now: a rectified version built with the current rule
     * rewrote it to the rule's CFOP (the item column keeps what was issued).
     */
    private static String effectiveCfop(FiscalXmlDocument document, ProductFiscalRule rule, String issued) {
        if (rule != null && rule.getCfopCorreto() != null && ProductRuleCheck.rectifiedUnder(document, rule)) {
            return rule.getCfopCorreto().strip();
        }
        return issued.strip();
    }

    /** First parser version that stores every header field the detail shows ({@link FiscalXmlHeaderExtras}). */
    private static final int HEADER_EXTRAS_VERSION = 4;

    /**
     * Built from the stored columns - no file read - for NFes saved (or backfilled) by
     * parser version 4 on, which keeps every header field shown on screen. Older notes and
     * other document types parse the original file; when it is gone (purged by
     * FiscalXmlPurgeScheduler), they fall back to the columns too, just without the few
     * fields those older versions didn't store.
     */
    @Transactional(readOnly = true)
    public FiscalXmlDetailDTO findDetail(UUID documentId) {
        FiscalXmlDocument document = findDocument(documentId);
        if (hasAllHeaderColumns(document)) {
            return detailFromColumns(document);
        }

        byte[] xmlBytes;
        try {
            xmlBytes = fileStorage.read(document.getXmlFilePath());
        } catch (IOException e) {
            log.info("XML file '{}' for document {} not readable, building detail from stored columns", document.getXmlFilePath(), document.getId());
            return detailFromColumns(document);
        }
        FiscalXmlMetadata metadata = metadataParser.parse(xmlBytes);

        return new FiscalXmlDetailDTO(
                document.getId(),
                document.getCompany().getId(),
                metadata.accessKey(),
                metadata.documentType(),
                metadata.issuerCnpj(),
                metadata.recipientCnpj(),
                metadata.issueDate(),
                document.getRectifiedFilePath() != null,
                itemRepository.countByDocument_Id(document.getId()),
                metadata.identificacao(),
                metadata.emitente(),
                metadata.destinatario(),
                metadata.totais(),
                document.getDirection(),
                document.isOwnIssue(),
                document.getStatus(),
                document.getCanceledAt(),
                document.getReviewStatus(),
                document.getReviewedAt(),
                document.getReviewedBy(),
                document.getReviewNote()
        );
    }

    /** The items as issued (the DANFE prints these). */
    @Transactional(readOnly = true)
    public List<FiscalXmlProdutoDTO> findProdutos(UUID documentId) {
        FiscalXmlDocument document = findDocument(documentId);
        return itemRepository.findByDocument_IdOrderByNumeroItemAsc(document.getId()).stream()
                .map(this::toProduto)
                .toList();
    }

    /**
     * The items as the note stands now: when it has a rectified version built with the current
     * rule, the CFOP shown is the rectified one and {@code cfopEmitido} keeps what was issued.
     */
    @Transactional(readOnly = true)
    public List<FiscalXmlProdutoDTO> findProdutosAsRectified(UUID documentId) {
        FiscalXmlDocument document = findDocument(documentId);
        Map<String, ProductFiscalRule> rules = rulesIfAnyRectified(document.getCompany().getId(), List.of(document));
        return itemRepository.findByDocument_IdOrderByNumeroItemAsc(document.getId()).stream()
                .map(item -> {
                    FiscalXmlProdutoDTO produto = toProduto(item);
                    ProductFiscalRule rule = item.getCodigo() == null ? null : rules.get(item.getCodigo());
                    return item.getCfop() == null ? produto
                            : produto.withRectifiedCfop(effectiveCfop(document, rule, item.getCfop()));
                })
                .toList();
    }

    public record StoredXml(String fileName, byte[] content) {
    }

    /** {@code rectified}: serves the corrected file when one exists, falling back to the original. */
    @Transactional(readOnly = true)
    public StoredXml findXml(UUID documentId, boolean rectified) {
        FiscalXmlDocument document = findDocument(documentId);
        String path = rectified && document.getRectifiedFilePath() != null
                ? document.getRectifiedFilePath() : document.getXmlFilePath();
        String baseName = document.getAccessKey() != null && !document.getAccessKey().isBlank()
                ? document.getAccessKey() : document.getId().toString();
        return new StoredXml(baseName + ".xml", readFile(document, path));
    }

    private FiscalXmlDocument findDocument(UUID documentId) {
        FiscalXmlDocument document = documentRepository.findFirstByIdAndDeletedIsFalse(documentId)
                .orElseThrow(() -> new ResourceNotFoundException(messageService.getMessage("error.entity.not.found")));
        tenantAccessService.assertCompanyAccess(document.getCompany());
        return document;
    }

    private byte[] readFile(FiscalXmlDocument document, String path) {
        try {
            return fileStorage.read(path);
        } catch (IOException e) {
            log.warn("Could not read stored XML file '{}' for document {}", path, document.getId(), e);
            throw new ResourceNotFoundException(messageService.getMessage("error.fiscalXml.file.not.found"));
        }
    }

    private boolean hasAllHeaderColumns(FiscalXmlDocument document) {
        return "NFE".equals(document.getDocumentType())
                && document.getMetadataVersion() != null
                && document.getMetadataVersion() >= HEADER_EXTRAS_VERSION;
    }

    private FiscalXmlDetailDTO detailFromColumns(FiscalXmlDocument d) {
        FiscalXmlHeaderExtras x = d.getHeaderExtras() != null ? d.getHeaderExtras()
                : new FiscalXmlHeaderExtras(null, null, null, null, null, null, null, null, null);
        FiscalXmlIdentificacaoDTO identificacao = new FiscalXmlIdentificacaoDTO(
                d.getCodigoUf(), d.getNaturezaOperacao(), d.getModelo(), d.getSerie(), d.getNumero(),
                d.getIssueDate(), d.getDataSaidaEntrada(), d.getTipoOperacao(), d.getDestinoOperacao(),
                d.getCodigoMunicipioFatoGerador(), x.tipoImpressao(), x.tipoEmissao(), x.digitoVerificador(),
                d.getAmbiente(), d.getFinalidadeEmissao(), d.getConsumidorFinal(), d.getIndicadorPresenca(),
                x.processoEmissao(), x.versaoProcessoEmissao());

        FiscalXmlEmitenteDTO emitente = new FiscalXmlEmitenteDTO(
                d.getIssuerCnpj(), d.getEmitenteCpf(), d.getEmitenteNome(), d.getEmitenteNomeFantasia(),
                d.getEmitenteInscricaoEstadual(), d.getEmitenteInscricaoEstadualSubstitutoTributario(),
                d.getEmitenteInscricaoMunicipal(), d.getEmitenteCnae(), d.getEmitenteRegimeTributario(),
                new FiscalXmlEnderecoDTO(d.getEmitenteLogradouro(), d.getEmitenteEnderecoNumero(), d.getEmitenteComplemento(),
                        d.getEmitenteBairro(), d.getEmitenteCodigoMunicipio(), d.getEmitenteMunicipio(), d.getEmitenteUf(),
                        d.getEmitenteCep(), x.emitenteCodigoPais(), x.emitentePais(), d.getEmitenteTelefone()));

        FiscalXmlDestinatarioDTO destinatario = new FiscalXmlDestinatarioDTO(
                d.getRecipientCnpj(), d.getDestinatarioCpf(), d.getDestinatarioIdEstrangeiro(), d.getDestinatarioNome(),
                d.getDestinatarioInscricaoEstadual(), d.getDestinatarioIndicadorIe(), d.getDestinatarioEmail(),
                new FiscalXmlEnderecoDTO(d.getDestinatarioLogradouro(), d.getDestinatarioEnderecoNumero(), d.getDestinatarioComplemento(),
                        d.getDestinatarioBairro(), d.getDestinatarioCodigoMunicipio(), d.getDestinatarioMunicipio(), d.getDestinatarioUf(),
                        d.getDestinatarioCep(), x.destinatarioCodigoPais(), x.destinatarioPais(), d.getDestinatarioTelefone()));

        FiscalXmlTotaisDTO totais = new FiscalXmlTotaisDTO(
                d.getBaseCalculoIcms(), d.getValorIcms(), d.getValorIcmsDesonerado(), d.getValorFcp(),
                d.getBaseCalculoIcmsSt(), d.getValorIcmsSt(), d.getValorFcpSt(), d.getValorProdutos(),
                d.getValorFrete(), d.getValorSeguro(), d.getValorDesconto(), d.getValorImportacao(),
                d.getValorIpi(), d.getValorPis(), d.getValorCofins(), d.getValorOutrasDespesas(),
                d.getValorTotalNota(), d.getValorAproximadoTributos(), d.getIbsCbsTotals());

        return new FiscalXmlDetailDTO(d.getId(), d.getCompany().getId(), d.getAccessKey(), d.getDocumentType(),
                d.getIssuerCnpj(), d.getRecipientCnpj(), d.getIssueDate(), d.getRectifiedFilePath() != null,
                itemRepository.countByDocument_Id(d.getId()), identificacao, emitente, destinatario, totais,
                d.getDirection(), d.isOwnIssue(), d.getStatus(), d.getCanceledAt(),
                d.getReviewStatus(), d.getReviewedAt(), d.getReviewedBy(), d.getReviewNote());
    }

    private FiscalXmlSummaryDTO toSummary(FiscalXmlDocument document, long quantidadeItens, List<String> cfops) {
        return new FiscalXmlSummaryDTO(
                document.getId(),
                document.getAccessKey(),
                document.getDocumentType(),
                document.getIssueDate(),
                document.getModelo(),
                document.getSerie(),
                document.getNumero(),
                document.getTipoOperacao(),
                document.getNaturezaOperacao(),
                document.getEmitenteNome(),
                document.getIssuerCnpj(),
                document.getEmitenteCpf(),
                document.getDestinatarioNome(),
                document.getRecipientCnpj(),
                document.getDestinatarioCpf(),
                document.getValorTotalNota(),
                quantidadeItens,
                document.getRectifiedFilePath() != null,
                document.getDirection(),
                document.isOwnIssue(),
                document.getStatus(),
                document.getReviewStatus(),
                cfops
        );
    }

    private FiscalXmlProdutoDTO toProduto(FiscalXmlDocumentItem item) {
        return new FiscalXmlProdutoDTO(
                item.getNumeroItem(),
                item.getCodigo(),
                item.getCodigoBarras(),
                item.getDescricao(),
                item.getNcm(),
                item.getCfop(),
                item.getUnidadeComercial(),
                item.getQuantidadeComercial(),
                item.getValorUnitarioComercial(),
                item.getValorTotal(),
                item.getUnidadeTributavel(),
                item.getQuantidadeTributavel(),
                item.getValorUnitarioTributavel(),
                new FiscalXmlImpostoItemDTO(
                        item.getSituacaoTributariaIcms(),
                        item.getBaseCalculoIcms(),
                        item.getAliquotaIcms(),
                        item.getValorIcms(),
                        item.getSituacaoTributariaIpi(),
                        item.getValorIpi(),
                        item.getSituacaoTributariaPis(),
                        item.getValorPis(),
                        item.getSituacaoTributariaCofins(),
                        item.getValorCofins(),
                        item.getTaxDetail(),
                        item.getIbsCbs()
                )
        );
    }

    private UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(messageService.getMessage("error.generic.bad.request"));
        }
    }
}
