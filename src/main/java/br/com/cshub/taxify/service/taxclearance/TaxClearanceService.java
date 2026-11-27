package br.com.cshub.taxify.service.taxclearance;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.common.TenantAccessService;
import br.com.cshub.taxify.domain.taxclearance.StateTaxClearance;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.pendency.CaptureRun;
import br.com.cshub.taxify.dto.taxclearance.TaxClearanceDTOs;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.exception.ResourceNotFoundException;
import br.com.cshub.taxify.integration.efisco.EfiscoTaxClearanceClient;
import br.com.cshub.taxify.integration.efisco.EfiscoException;
import br.com.cshub.taxify.integration.sieg.FiscalXmlFileStorage;
import br.com.cshub.taxify.repository.CaptureRunRepository;
import br.com.cshub.taxify.repository.StateTaxClearanceRepository;
import br.com.cshub.taxify.repository.CompanyRepository;
import br.com.cshub.taxify.service.pendency.PendencyService;
import br.com.cshub.taxify.validation.CompanyValidation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.AuditorAware;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Certidão SEFAZ-PE (roadmap M5): the state certidão of every PE company, renewed before it expires. A certidão comes
 * in as PDF - issued on e-Fisco by Taxify when the public page allows it, or by a person and sent on the company's
 * page - and is read ({@link TaxClearancePdfParser}), kept (PDF in the XMLs' storage, as the Fronteira extract) and
 * turned into pendencies ({@link TaxClearancePendencies}). Every issue attempt is a {@link CaptureRun} for the
 * integrations' health. Only companies of PE with a CNPJ.
 */
@Service
public class TaxClearanceService {

    public static final String UF = "PE";
    static final String SYSTEM = "sistema";

    private static final Logger log = LoggerFactory.getLogger(TaxClearanceService.class);

    private final StateTaxClearanceRepository repository;
    private final CompanyRepository companyRepository;
    private final CompanyValidation companyValidation;
    private final TenantAccessService tenantAccessService;
    private final CaptureRunRepository captureRunRepository;
    private final FiscalXmlFileStorage storage;
    private final TaxClearancePdfParser parser;
    private final TaxClearanceIssuer issuer;
    private final PendencyService pendencyService;
    private final MessageService messageService;
    private final AuditorAware<String> auditorAware;

    public TaxClearanceService(StateTaxClearanceRepository repository, CompanyRepository companyRepository,
                           CompanyValidation companyValidation, TenantAccessService tenantAccessService,
                           CaptureRunRepository captureRunRepository, FiscalXmlFileStorage storage, TaxClearancePdfParser parser,
                           TaxClearanceIssuer issuer, PendencyService pendencyService, MessageService messageService,
                           AuditorAware<String> auditorAware) {
        this.repository = repository;
        this.companyRepository = companyRepository;
        this.companyValidation = companyValidation;
        this.tenantAccessService = tenantAccessService;
        this.captureRunRepository = captureRunRepository;
        this.storage = storage;
        this.parser = parser;
        this.issuer = issuer;
        this.pendencyService = pendencyService;
        this.messageService = messageService;
        this.auditorAware = auditorAware;
    }

    public static boolean isPernambuco(Company company) {
        return company != null && company.getUf() != null && UF.equalsIgnoreCase(company.getUf().strip());
    }

    /** The company's CNPJ (14 digits) - the certidão is issued by it; null for a CPF or no document. */
    public static String cnpjOf(Company company) {
        if (company == null || company.getDocument() == null || company.getDocument().getNumber() == null) {
            return null;
        }
        String digits = company.getDocument().getNumber().replaceAll("\\D", "");
        return digits.length() == 14 ? digits : null;
    }

    /** Why the certidão doesn't apply to the company, or null when it does. */
    static String notApplicable(Company company) {
        if (!isPernambuco(company)) {
            return "A certidão SEFAZ-PE é só para empresas de Pernambuco (UF da empresa = PE).";
        }
        if (cnpjOf(company) == null) {
            return "A empresa não tem CNPJ no cadastro - a certidão é emitida pelo CNPJ.";
        }
        return null;
    }

    // ---------------------------------------------------------------- reading

    /** The portfolio's column: the newest certidão of each PE company of the user's firm they can see. */
    @Transactional(readOnly = true)
    public List<TaxClearanceDTOs.Summary> portfolio() {
        UUID firmId = tenantAccessService.getCurrentAccountingFirmId()
                .orElseThrow(() -> new BadRequestException("error.pendency.firm.required", messageService.getMessage("error.pendency.firm.required")));
        List<UUID> companyIds = tenantAccessService.accessibleCompanyIds(
                companyRepository.findByAccountingFirm_IdAndDeletedIsFalseOrderByNameAsc(firmId).stream()
                        .filter(company -> notApplicable(company) == null)
                        .map(Company::getId)
                        .toList());
        if (companyIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, StateTaxClearance> latest = new LinkedHashMap<>();
        for (StateTaxClearance clearance : repository.findAllOf(companyIds, UF)) {
            latest.putIfAbsent(clearance.getCompany().getId(), clearance);
        }
        LocalDate today = LocalDate.now();
        List<TaxClearanceDTOs.Summary> summaries = new ArrayList<>();
        for (UUID companyId : companyIds) {
            StateTaxClearance clearance = latest.get(companyId);
            summaries.add(new TaxClearanceDTOs.Summary(companyId, TaxClearanceRenewalRule.status(clearance, today).name(),
                    clearance == null ? null : clearance.getStatus().name(),
                    clearance == null ? null : clearance.getStatus().label,
                    clearance == null ? null : clearance.getValidUntil(),
                    clearance == null ? null : clearance.getId(),
                    clearance != null && clearance.getPdfPath() != null));
        }
        return summaries;
    }

    /** The company's section (access checked). */
    @Transactional(readOnly = true)
    public TaxClearanceDTOs.CompanyTaxClearance ofCompany(String companyId) {
        return toDto(company(companyId));
    }

    /** The kept PDF of a certidão - the user's access to its company checked. */
    @Transactional(readOnly = true)
    public byte[] pdf(String clearanceId) {
        StateTaxClearance clearance = owned(clearanceId);
        if (clearance.getPdfPath() == null) {
            throw new ResourceNotFoundException("error.entity.not.found", messageService.getMessage("error.entity.not.found"));
        }
        try {
            return storage.read(clearance.getPdfPath());
        } catch (IOException e) {
            throw new ResourceNotFoundException("error.entity.not.found", messageService.getMessage("error.entity.not.found"));
        }
    }

    // ---------------------------------------------------------------- writing

    /** A certidão PDF issued on e-Fisco by a person: read, checked against the company's CNPJ and kept. */
    @Transactional
    public TaxClearanceDTOs.CompanyTaxClearance upload(String companyId, String fileName, byte[] pdf) {
        Company company = applicable(company(companyId));
        if (pdf == null || pdf.length == 0) {
            throw new BadRequestException("error.certidao.file.required", messageService.getMessage("error.certidao.file.required"));
        }
        TaxClearancePdfParser.TaxClearance read;
        try {
            read = parser.parse(pdf);
        } catch (TaxClearancePdfParser.InvalidTaxClearanceException e) {
            throw new BadRequestException("error.certidao.invalid", messageService.getMessage("error.certidao.invalid", e.getMessage()));
        }
        keep(company, read, pdf, fileName, StateTaxClearance.ORIGIN_UPLOAD);
        refreshPendencies(company);
        return toDto(company);
    }

    /**
     * "Emitir agora": tries to issue the certidão on e-Fisco now. While e-Fisco's public page asks for a captcha it
     * doesn't issue (and says so, with the page to issue it by hand) - the attempt is recorded either way.
     */
    public TaxClearanceDTOs.IssueResult issueNow(String companyId) {
        Company company = applicable(company(companyId));
        Attempt attempt = issue(company);
        return new TaxClearanceDTOs.IssueResult(attempt.issued() ? "OK" : "ERRO", attempt.detail(), EfiscoTaxClearanceClient.PUBLIC_PAGE,
                toDto(company));
    }

    /**
     * One attempt: {@code stopsRun} when e-Fisco refused the access itself (captcha, Cloudflare) - the scheduled job
     * then doesn't ask again for the other companies.
     */
    record Attempt(boolean issued, boolean stopsRun, String detail) {
    }

    /** One issue attempt: the certidão kept and the pendencies refreshed when e-Fisco gives it. */
    Attempt issue(Company company) {
        LocalDateTime startedAt = LocalDateTime.now();
        Attempt attempt;
        try {
            byte[] pdf = issuer.issue(cnpjOf(company));
            keep(company, parser.parse(pdf), pdf, null, StateTaxClearance.ORIGIN_EFISCO);
            refreshPendencies(company);
            attempt = new Attempt(true, false, null);
        } catch (EfiscoException e) {
            boolean stops = e.reason() == EfiscoException.Reason.CAPTCHA || e.reason() == EfiscoException.Reason.BLOQUEADO
                    || e.reason() == EfiscoException.Reason.PAGINA_INESPERADA;
            attempt = new Attempt(false, stops, e.getMessage());
        } catch (TaxClearancePdfParser.InvalidTaxClearanceException e) {
            attempt = new Attempt(false, false, "O e-Fisco devolveu um PDF que não pôde ser lido: " + e.getMessage());
        } catch (BadRequestException e) {
            attempt = new Attempt(false, false, e.getMessage());
        } catch (IOException e) {
            attempt = new Attempt(false, false, "Falha de comunicação com o e-Fisco: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            attempt = new Attempt(false, true, "Emissão interrompida.");
        }
        recordRun(company, startedAt, attempt.detail());
        return attempt;
    }

    /** The scheduled job: records an attempt that wasn't made (e-Fisco blocked the run earlier) with the reason. */
    void recordSkipped(Company company, String detail) {
        recordRun(company, LocalDateTime.now(), detail);
    }

    /** Every PE company (all firms) with a CNPJ whose certidão is missing or expires in 15 days or less. */
    @Transactional(readOnly = true)
    public List<Company> dueCompanies() {
        LocalDate today = LocalDate.now();
        List<Company> due = new ArrayList<>();
        for (Company company : companyRepository.findAllOfUf(UF)) {
            if (Boolean.FALSE.equals(company.getActive()) || notApplicable(company) != null) {
                continue;
            }
            StateTaxClearance latest = repository
                    .findFirstByCompany_IdAndUfAndDeletedIsFalseOrderByValidUntilDescImportedAtDesc(company.getId(), UF).orElse(null);
            if (TaxClearanceRenewalRule.due(latest, today)) {
                due.add(company);
            }
        }
        return due;
    }

    private void keep(Company company, TaxClearancePdfParser.TaxClearance read, byte[] pdf, String fileName, String origin) {
        String cnpj = cnpjOf(company);
        if (read.cnpj() != null && !read.cnpj().equals(cnpj)) {
            throw new BadRequestException("error.certidao.other.cnpj", messageService.getMessage("error.certidao.other.cnpj", formatCnpj(read.cnpj())));
        }
        // the same certidão sent again replaces the one kept (its PDF too)
        StateTaxClearance clearance = read.number() == null ? null : repository
                .findByCompany_IdAndUfAndDeletedIsFalseOrderByValidUntilDescImportedAtDesc(company.getId(), UF).stream()
                .filter(existing -> read.number().equals(existing.getNumber()))
                .findFirst()
                .orElse(null);
        if (clearance == null) {
            clearance = new StateTaxClearance();
            clearance.setCompany(company);
            clearance.setUf(UF);
            clearance.setDeleted(false);
        }
        clearance.setType(read.type());
        clearance.setNumber(read.number());
        clearance.setStatus(read.status());
        clearance.setIssuedOn(read.issuedOn());
        clearance.setValidUntil(read.validUntil());
        clearance.setDocument(cnpj);
        clearance.setFileName(fileName);
        clearance.setOrigin(origin);
        clearance.setImportedAt(LocalDateTime.now());
        clearance.setImportedBy(auditorAware.getCurrentAuditor().orElse(SYSTEM));
        String previous = clearance.getPdfPath();
        try {
            clearance.setPdfPath(storage.storeTaxClearancePdf(company.getId(), UF, pdf));
            if (previous != null) {
                storage.delete(previous);
            }
        } catch (IOException | RuntimeException e) {
            // the certidão counts without its PDF; the screen just doesn't offer "Ver PDF"
            log.warn("Could not keep the certidão PDF of company {}", company.getId(), e);
        }
        repository.save(clearance);
    }

    private void refreshPendencies(Company company) {
        try {
            pendencyService.refresh(company);
        } catch (RuntimeException e) {
            log.warn("Could not refresh the pendencies of company {} after its certidão", company.getId(), e);
        }
    }

    /** History of the attempts - the integrations' health on the pendencies screen. Never fails the attempt. */
    private void recordRun(Company company, LocalDateTime startedAt, String detail) {
        try {
            CaptureRun run = new CaptureRun();
            run.setCompany(company);
            run.setService(CaptureRun.TAX_CLEARANCE_PE);
            run.setStartedAt(startedAt);
            run.setFinishedAt(LocalDateTime.now());
            run.setStatus(detail == null ? "OK" : "ERRO");
            run.setNewDocuments(detail == null ? 1 : 0);
            run.setDetail(detail == null ? "Certidão emitida no e-Fisco." : truncate(detail, 500));
            run.setDeleted(false);
            captureRunRepository.save(run);
        } catch (RuntimeException e) {
            log.warn("Could not record the certidão run of company {}", company.getId(), e);
        }
    }

    private TaxClearanceDTOs.CompanyTaxClearance toDto(Company company) {
        String reason = notApplicable(company);
        if (reason != null) {
            return new TaxClearanceDTOs.CompanyTaxClearance(false, reason, null, List.of(), null, EfiscoTaxClearanceClient.PUBLIC_PAGE);
        }
        LocalDate today = LocalDate.now();
        List<TaxClearanceDTOs.TaxClearance> all = repository
                .findByCompany_IdAndUfAndDeletedIsFalseOrderByValidUntilDescImportedAtDesc(company.getId(), UF).stream()
                .map(clearance -> toDto(clearance, today))
                .toList();
        TaxClearanceDTOs.LastRun lastRun = captureRunRepository.findLatestOf(List.of(company.getId()), CaptureRun.TAX_CLEARANCE_PE).stream()
                .max(Comparator.comparing(CaptureRun::getStartedAt))
                .map(run -> new TaxClearanceDTOs.LastRun(run.getStartedAt(), run.getStatus(), run.getDetail()))
                .orElse(null);
        return new TaxClearanceDTOs.CompanyTaxClearance(true, null, all.isEmpty() ? null : all.getFirst(),
                all.size() > 1 ? all.subList(1, all.size()) : List.of(), lastRun, EfiscoTaxClearanceClient.PUBLIC_PAGE);
    }

    static TaxClearanceDTOs.TaxClearance toDto(StateTaxClearance clearance, LocalDate today) {
        return new TaxClearanceDTOs.TaxClearance(clearance.getId(), clearance.getUf(), clearance.getType().name(), clearance.getType().label,
                clearance.getNumber(), clearance.getStatus().name(), clearance.getStatus().label, clearance.getIssuedOn(),
                clearance.getValidUntil(), TaxClearanceRenewalRule.status(clearance, today).name(),
                clearance.getValidUntil() == null ? 0 : TaxClearanceRenewalRule.daysLeft(clearance, today), clearance.getOrigin(),
                clearance.getFileName(), clearance.getImportedAt(), clearance.getImportedBy(), clearance.getPdfPath() != null);
    }

    private Company applicable(Company company) {
        String reason = notApplicable(company);
        if (reason != null) {
            throw new BadRequestException("error.certidao.not.applicable", messageService.getMessage("error.certidao.not.applicable", reason));
        }
        return company;
    }

    private Company company(String companyId) {
        try {
            return companyValidation.checkExist(UUID.fromString(companyId.strip()));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BadRequestException("error.generic.bad.request", messageService.getMessage("error.generic.bad.request"));
        }
    }

    private StateTaxClearance owned(String clearanceId) {
        StateTaxClearance clearance;
        try {
            clearance = repository.findFirstByIdAndDeletedIsFalse(UUID.fromString(clearanceId.strip()))
                    .orElseThrow(() -> new ResourceNotFoundException("error.entity.not.found", messageService.getMessage("error.entity.not.found")));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BadRequestException("error.generic.bad.request", messageService.getMessage("error.generic.bad.request"));
        }
        tenantAccessService.assertCompanyAccess(clearance.getCompany());
        return clearance;
    }

    private static String formatCnpj(String cnpj) {
        return cnpj.length() == 14
                ? cnpj.substring(0, 2) + "." + cnpj.substring(2, 5) + "." + cnpj.substring(5, 8) + "/" + cnpj.substring(8, 12) + "-" + cnpj.substring(12)
                : cnpj;
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
