package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.common.TenantAccessService;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocument;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocumentItem;
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
import java.nio.file.Files;
import java.nio.file.Path;
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

    private final FiscalXmlDocumentRepository documentRepository;
    private final FiscalXmlDocumentItemRepository itemRepository;
    private final FiscalXmlMetadataParser metadataParser;
    private final CompanyValidation companyValidation;
    private final TenantAccessService tenantAccessService;
    private final MessageService messageService;

    public FiscalXmlQueryService(FiscalXmlDocumentRepository documentRepository, FiscalXmlDocumentItemRepository itemRepository,
                                 FiscalXmlMetadataParser metadataParser, CompanyValidation companyValidation,
                                 TenantAccessService tenantAccessService, MessageService messageService) {
        this.documentRepository = documentRepository;
        this.itemRepository = itemRepository;
        this.metadataParser = metadataParser;
        this.companyValidation = companyValidation;
        this.tenantAccessService = tenantAccessService;
        this.messageService = messageService;
    }

    @Transactional(readOnly = true)
    public Page<FiscalXmlSummaryDTO> search(XmlSearchFilterDTO filter) {
        Company company = companyValidation.checkExist(parseUuid(filter.companyId()));
        Pageable pageable = PageRequestDTO.toPageable(filter.pagination(), "issueDate", Sort.Direction.DESC);

        LocalDateTime from = filter.dataEmissaoInicio() != null ? filter.dataEmissaoInicio().atStartOfDay() : LocalDateTime.MIN;
        LocalDateTime to = filter.dataEmissaoFim() != null ? filter.dataEmissaoFim().atTime(LocalTime.MAX) : LocalDateTime.MAX;

        // nNF is stored without leading zeros, so "000123" typed by the user still matches.
        String numero = filter.numero() == null ? "" : filter.numero().replaceAll("\\D", "").replaceFirst("^0+(?=\\d)", "");

        Page<FiscalXmlDocument> documents = documentRepository
                .search(company.getId(), from, to, numero.isEmpty() ? null : numero, pageable);

        Map<UUID, Long> itemCounts = new HashMap<>();
        if (documents.hasContent()) {
            List<UUID> ids = documents.getContent().stream().map(FiscalXmlDocument::getId).toList();
            for (Object[] row : itemRepository.countByDocumentIds(ids)) {
                itemCounts.put((UUID) row[0], (Long) row[1]);
            }
        }

        return documents.map(document -> toSummary(document, itemCounts.getOrDefault(document.getId(), 0L)));
    }

    /**
     * Parses the original file, since a few header fields shown on screen (tipo de
     * emissão, versão do emissor, país...) aren't stored as columns. When the file is
     * gone (purged by FiscalXmlPurgeScheduler), falls back to the stored columns so the
     * note still opens - just without those few fields.
     */
    @Transactional(readOnly = true)
    public FiscalXmlDetailDTO findDetail(UUID documentId) {
        FiscalXmlDocument document = findDocument(documentId);

        byte[] xmlBytes;
        try {
            xmlBytes = Files.readAllBytes(Path.of(document.getXmlFilePath()));
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
                metadata.totais()
        );
    }

    @Transactional(readOnly = true)
    public List<FiscalXmlProdutoDTO> findProdutos(UUID documentId) {
        FiscalXmlDocument document = findDocument(documentId);
        return itemRepository.findByDocument_IdOrderByNumeroItemAsc(document.getId()).stream()
                .map(this::toProduto)
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
            return Files.readAllBytes(Path.of(path));
        } catch (IOException e) {
            log.warn("Could not read stored XML file '{}' for document {}", path, document.getId(), e);
            throw new ResourceNotFoundException(messageService.getMessage("error.fiscalXml.file.not.found"));
        }
    }

    private FiscalXmlDetailDTO detailFromColumns(FiscalXmlDocument d) {
        FiscalXmlIdentificacaoDTO identificacao = new FiscalXmlIdentificacaoDTO(
                d.getCodigoUf(), d.getNaturezaOperacao(), d.getModelo(), d.getSerie(), d.getNumero(),
                d.getIssueDate(), d.getDataSaidaEntrada(), d.getTipoOperacao(), d.getDestinoOperacao(),
                d.getCodigoMunicipioFatoGerador(), null, null, null, d.getAmbiente(), d.getFinalidadeEmissao(),
                d.getConsumidorFinal(), d.getIndicadorPresenca(), null, null);

        FiscalXmlEmitenteDTO emitente = new FiscalXmlEmitenteDTO(
                d.getIssuerCnpj(), d.getEmitenteCpf(), d.getEmitenteNome(), d.getEmitenteNomeFantasia(),
                d.getEmitenteInscricaoEstadual(), d.getEmitenteInscricaoEstadualSubstitutoTributario(),
                d.getEmitenteInscricaoMunicipal(), d.getEmitenteCnae(), d.getEmitenteRegimeTributario(),
                new FiscalXmlEnderecoDTO(d.getEmitenteLogradouro(), d.getEmitenteEnderecoNumero(), d.getEmitenteComplemento(),
                        d.getEmitenteBairro(), d.getEmitenteCodigoMunicipio(), d.getEmitenteMunicipio(), d.getEmitenteUf(),
                        d.getEmitenteCep(), null, null, d.getEmitenteTelefone()));

        FiscalXmlDestinatarioDTO destinatario = new FiscalXmlDestinatarioDTO(
                d.getRecipientCnpj(), d.getDestinatarioCpf(), d.getDestinatarioIdEstrangeiro(), d.getDestinatarioNome(),
                d.getDestinatarioInscricaoEstadual(), d.getDestinatarioIndicadorIe(), d.getDestinatarioEmail(),
                new FiscalXmlEnderecoDTO(d.getDestinatarioLogradouro(), d.getDestinatarioEnderecoNumero(), d.getDestinatarioComplemento(),
                        d.getDestinatarioBairro(), d.getDestinatarioCodigoMunicipio(), d.getDestinatarioMunicipio(), d.getDestinatarioUf(),
                        d.getDestinatarioCep(), null, null, d.getDestinatarioTelefone()));

        FiscalXmlTotaisDTO totais = new FiscalXmlTotaisDTO(
                d.getBaseCalculoIcms(), d.getValorIcms(), d.getValorIcmsDesonerado(), d.getValorFcp(),
                d.getBaseCalculoIcmsSt(), d.getValorIcmsSt(), d.getValorFcpSt(), d.getValorProdutos(),
                d.getValorFrete(), d.getValorSeguro(), d.getValorDesconto(), d.getValorImportacao(),
                d.getValorIpi(), d.getValorPis(), d.getValorCofins(), d.getValorOutrasDespesas(),
                d.getValorTotalNota(), d.getValorAproximadoTributos());

        return new FiscalXmlDetailDTO(d.getId(), d.getCompany().getId(), d.getAccessKey(), d.getDocumentType(),
                d.getIssuerCnpj(), d.getRecipientCnpj(), d.getIssueDate(), d.getRectifiedFilePath() != null,
                itemRepository.countByDocument_Id(d.getId()), identificacao, emitente, destinatario, totais);
    }

    private FiscalXmlSummaryDTO toSummary(FiscalXmlDocument document, long quantidadeItens) {
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
                document.getRectifiedFilePath() != null
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
                        item.getValorCofins()
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
