package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.DocumentNumbers;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.xml.ServiceInvoice;
import br.com.cshub.taxify.enumerated.IntegrationType;
import br.com.cshub.taxify.integration.nfse.NfseXml;
import br.com.cshub.taxify.integration.sieg.FiscalXmlFileStorage;
import br.com.cshub.taxify.integration.sieg.SiegBaixarXmlsRequest;
import br.com.cshub.taxify.integration.sieg.SiegClient;
import br.com.cshub.taxify.repository.ServiceInvoiceRepository;
import br.com.cshub.taxify.validation.CompanyValidation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Brings in and stores the companies' service invoices (NFS-e): from SIEG (TipoXml 3), provided
 * and taken, and from manual uploads. One row per note and company - a note found again is
 * skipped, unless it now comes canceled. The direction comes from the company's CNPJ: provider
 * = PRESTADA, taker = TOMADA.
 */
@Service
public class ServiceInvoiceService {

    private static final Logger log = LoggerFactory.getLogger(ServiceInvoiceService.class);
    /** SIEG's document type for NFS-e ("1: NF-e / 2: CT-e / 3: NFS-e / 4: NFC-e / 5: CF-e"). */
    static final int TIPO_XML_NFSE = 3;
    private static final int PAGE_SIZE = 50;
    /** Bounds one side of one window - 1000 notes; a bigger month is completed by the next days' runs. */
    private static final int MAX_PAGES = 20;

    public enum Stored { NEW, DUPLICATE, INVALID }

    private final ServiceInvoiceRepository repository;
    private final FiscalXmlFileStorage fileStorage;
    private final SiegClient siegClient;
    private final FirmIntegrationService firmIntegrationService;
    private final CompanyValidation companyValidation;

    public ServiceInvoiceService(ServiceInvoiceRepository repository, FiscalXmlFileStorage fileStorage, SiegClient siegClient,
                                 FirmIntegrationService firmIntegrationService, CompanyValidation companyValidation) {
        this.repository = repository;
        this.fileStorage = fileStorage;
        this.siegClient = siegClient;
        this.firmIntegrationService = firmIntegrationService;
        this.companyValidation = companyValidation;
    }

    /** Result of one window: notes new, already known, unreadable. */
    public record SyncResult(int persistedNew, int duplicated, int invalid) {
        SyncResult plus(Stored stored) {
            return new SyncResult(persistedNew + (stored == Stored.NEW ? 1 : 0), duplicated + (stored == Stored.DUPLICATE ? 1 : 0),
                    invalid + (stored == Stored.INVALID ? 1 : 0));
        }

        SyncResult plus(SyncResult other) {
            return new SyncResult(persistedNew + other.persistedNew, duplicated + other.duplicated, invalid + other.invalid);
        }
    }

    /**
     * The company's NFS-e issued in [from, to] from SIEG: as provider (CnpjEmit) and as taker (CnpjDest).
     * SIEG doesn't document which filter is which for NFS-e - both are asked and the direction is
     * decided by the note itself, so a swapped mapping still lands right.
     */
    @Transactional
    public SyncResult syncFromSieg(UUID companyId, LocalDate from, LocalDate to) {
        Company company = companyValidation.checkExist(companyId);
        String cnpj = DocumentNumbers.onlyDigits(company.getDocument().getNumber());
        String apiKey = firmIntegrationService.getApiKey(company.getAccountingFirm().getId(), IntegrationType.SIEG);
        SyncResult result = new SyncResult(0, 0, 0);
        for (boolean asProvider : new boolean[]{true, false}) {
            for (int page = 0; page < MAX_PAGES; page++) {
                SiegBaixarXmlsRequest request = new SiegBaixarXmlsRequest(TIPO_XML_NFSE, PAGE_SIZE, page * PAGE_SIZE,
                        startOfDay(from), endOfDay(to), asProvider ? cnpj : null, asProvider ? null : cnpj, null);
                List<byte[]> xmls = siegClient.baixarXmls(request, apiKey);
                for (byte[] xml : xmls) {
                    result = result.plus(store(company, xml, "SIEG"));
                }
                if (xmls.size() < PAGE_SIZE) {
                    break;
                }
            }
        }
        log.info("NFS-e from SIEG for company {} ({} to {}): {} new, {} known, {} unreadable", companyId, from, to,
                result.persistedNew(), result.duplicated(), result.invalid());
        return result;
    }

    /** Stores one NFS-e XML of the company (any layout); a known note only takes a cancellation. */
    @Transactional
    public Stored store(Company company, byte[] xml, String source) {
        Optional<NfseXml> parsed = NfseXml.parse(xml);
        if (parsed.isEmpty()) {
            return Stored.INVALID;
        }
        NfseXml nfse = parsed.get();
        Optional<ServiceInvoice> known = repository.findByCompany_IdAndAccessKey(company.getId(), nfse.accessKey());
        if (known.isPresent()) {
            if (nfse.canceled() && !known.get().isCanceled()) {
                known.get().setCanceled(true);
                repository.save(known.get());
            }
            return Stored.DUPLICATE;
        }
        String path = fileStorage.store(company.getId(), "NFSE-" + nfse.accessKey(), xml);
        repository.save(toEntity(company, nfse, path, source));
        return Stored.NEW;
    }

    static String directionOf(Company company, NfseXml nfse) {
        String own = company.getDocument() == null ? null : DocumentNumbers.onlyDigits(company.getDocument().getNumber());
        if (own != null && own.equals(nfse.prestadorDocumento())) {
            return ServiceInvoice.PRESTADA;
        }
        if (own != null && own.equals(nfse.tomadorDocumento())) {
            return ServiceInvoice.TOMADA;
        }
        return ServiceInvoice.TERCEIRO;
    }

    public static ServiceInvoice toEntity(Company company, NfseXml nfse, String path, String source) {
        ServiceInvoice invoice = new ServiceInvoice();
        invoice.setCompany(company);
        invoice.setAccessKey(nfse.accessKey());
        invoice.setLayout(nfse.layout());
        invoice.setNumero(nfse.numero());
        invoice.setCodigoVerificacao(nfse.codigoVerificacao());
        invoice.setIssueDate(nfse.issueDate() != null ? nfse.issueDate()
                : nfse.competence() != null ? nfse.competence().atStartOfDay() : null);
        invoice.setCompetence(nfse.competence() != null ? nfse.competence()
                : nfse.issueDate() != null ? nfse.issueDate().toLocalDate() : null);
        invoice.setDirection(directionOf(company, nfse));
        invoice.setPrestadorDocumento(nfse.prestadorDocumento());
        invoice.setPrestadorNome(nfse.prestadorNome());
        invoice.setPrestadorMunicipio(nfse.prestadorMunicipio());
        invoice.setTomadorDocumento(nfse.tomadorDocumento());
        invoice.setTomadorNome(nfse.tomadorNome());
        invoice.setMunicipioIncidencia(nfse.municipioIncidencia());
        invoice.setCodigoServico(nfse.codigoServico());
        invoice.setCodigoTributacaoMunicipal(nfse.codigoTributacaoMunicipal());
        invoice.setDescricaoServico(nfse.descricaoServico());
        invoice.setValorServico(nfse.valorServico());
        invoice.setValorDeducoes(nfse.valorDeducoes());
        invoice.setBaseCalculo(nfse.baseCalculo());
        invoice.setAliquotaIss(nfse.aliquotaIss());
        invoice.setValorIss(nfse.valorIss());
        invoice.setIssRetido(nfse.issRetido());
        invoice.setValorIssRetido(nfse.valorIssRetido());
        invoice.setValorIrrf(nfse.valorIrrf());
        invoice.setValorPis(nfse.valorPis());
        invoice.setValorCofins(nfse.valorCofins());
        invoice.setPisCofinsRetidos(nfse.pisCofinsRetidos());
        invoice.setValorCsll(nfse.valorCsll());
        invoice.setValorInss(nfse.valorInss());
        invoice.setValorLiquido(nfse.valorLiquido());
        invoice.setCanceled(nfse.canceled());
        invoice.setXmlFilePath(path);
        invoice.setSourceSystem(source);
        invoice.setDeleted(false);
        return invoice;
    }

    private static Instant startOfDay(LocalDate date) {
        return date == null ? null : date.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static Instant endOfDay(LocalDate date) {
        return date == null ? null : date.atTime(LocalTime.MAX).atZone(ZoneOffset.UTC).toInstant();
    }
}
