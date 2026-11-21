package br.com.cshub.taxify.service;

import br.com.cshub.taxify.domain.company.StateRegistration;
import br.com.cshub.taxify.common.DocumentNumbers;
import br.com.cshub.taxify.common.CurrentFirmService;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.document.Document;
import br.com.cshub.taxify.domain.user.User;
import br.com.cshub.taxify.domain.user_company.UserCompany;
import br.com.cshub.taxify.domain.user_company.UserCompanyId;
import br.com.cshub.taxify.dto.acessorias.AcessoriasImportResultDTO;
import br.com.cshub.taxify.dto.integration.SyncJobStatusDTO;
import br.com.cshub.taxify.enumerated.DocumentTypeEnum;
import br.com.cshub.taxify.integration.acessorias.AcessoriasClient;
import br.com.cshub.taxify.integration.acessorias.AcessoriasCompanyDTO;
import br.com.cshub.taxify.enumerated.IntegrationType;
import br.com.cshub.taxify.repository.CompanyRepository;
import br.com.cshub.taxify.repository.DocumentRepository;
import br.com.cshub.taxify.repository.UserCompanyRepository;
import br.com.cshub.taxify.repository.UserRepository;
import br.com.cshub.taxify.service.job.IntegrationJobRunner;
import br.com.cshub.taxify.service.job.SyncProgress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Brings a firm's client companies over from Acessórias so they don't have to be
 * registered by hand: creates the ones Taxify doesn't have yet (matched by CNPJ/CPF within
 * the firm), refreshes the registration data of the ones it does, and links each company
 * to the firm's existing users named as responsáveis of its Acessórias departments.
 *
 * <p>Responsáveis are only ever linked, never created: a login needs a password the
 * import can't choose, so an e-mail with no matching user just shows up in
 * {@link AcessoriasImportResultDTO#responsiblesNotFound()} for the ADMIN to register.
 *
 * <p>Never deletes or deactivates anything in Taxify: a company inactive in Acessórias is
 * not created, and an existing one only gets {@code active = false}. A company the firm
 * soft-deleted in Taxify stays deleted - the import doesn't bring it back. Existing user
 * links (even soft-deleted ones) are left untouched, so removing a link by hand sticks.
 */
@Service
public class AcessoriasImportService {

    private static final Logger log = LoggerFactory.getLogger(AcessoriasImportService.class);
    private static final String ACTIVE_STATUS = "Ativa";
    private static final int NAME_MAX = 150;
    private static final int ROLE_MAX = 50;

    private final AcessoriasClient acessoriasClient;
    private final CompanyTaxRegimeService taxRegimeService;
    private final CompanyRepository companyRepository;
    private final DocumentRepository documentRepository;
    private final UserRepository userRepository;
    private final UserCompanyRepository userCompanyRepository;
    private final CurrentFirmService currentFirmService;
    private final FirmIntegrationService firmIntegrationService;
    private final IntegrationJobRunner jobRunner;
    private final MessageService messageService;

    public AcessoriasImportService(AcessoriasClient acessoriasClient, CompanyRepository companyRepository,
                                   DocumentRepository documentRepository, UserRepository userRepository,
                                   UserCompanyRepository userCompanyRepository, CurrentFirmService currentFirmService,
                                   FirmIntegrationService firmIntegrationService, IntegrationJobRunner jobRunner,
                                   MessageService messageService, CompanyTaxRegimeService taxRegimeService) {
        this.taxRegimeService = taxRegimeService;
        this.acessoriasClient = acessoriasClient;
        this.companyRepository = companyRepository;
        this.documentRepository = documentRepository;
        this.userRepository = userRepository;
        this.userCompanyRepository = userCompanyRepository;
        this.currentFirmService = currentFirmService;
        this.firmIntegrationService = firmIntegrationService;
        this.jobRunner = jobRunner;
        this.messageService = messageService;
    }

    // ------------------------------------------------------------------ background job

    /** "Importar do Acessórias": full import in the background - returns RUNNING right away. */
    public SyncJobStatusDTO<AcessoriasImportResultDTO> startForCurrentFirm() {
        return start(currentFirmService.require(), false);
    }

    /** Starts the import as a background job; the job runner prevents overlapping runs per firm. */
    public SyncJobStatusDTO<AcessoriasImportResultDTO> start(AccountingFirm firm, boolean incremental) {
        return jobRunner.start(firm.getId(), IntegrationType.ACESSORIAS, AcessoriasImportResultDTO.class,
                progress -> importFirm(firm, incremental, progress));
    }

    public SyncJobStatusDTO<AcessoriasImportResultDTO> statusForCurrentFirm() {
        return jobRunner.status(currentFirmService.requireId(), IntegrationType.ACESSORIAS, AcessoriasImportResultDTO.class);
    }

    // ------------------------------------------------------------------ the import itself

    /**
     * Synchronous run - what the job executes.
     *
     * @param incremental only companies changed in Acessórias since the firm's last import
     *                    (a firm never imported before still gets a full run)
     */
    public AcessoriasImportResultDTO importFirm(AccountingFirm firm, boolean incremental, SyncProgress progress) {
        LocalDateTime startedAt = LocalDateTime.now();
        LocalDateTime changedAfter = incremental ? firmIntegrationService.getLastSyncAt(firm.getId(), IntegrationType.ACESSORIAS) : null;
        String apiToken = firmIntegrationService.getApiKey(firm.getId(), IntegrationType.ACESSORIAS);
        List<AcessoriasCompanyDTO> source = acessoriasClient.listCompanies(apiToken, changedAfter);
        progress.total(source.size());

        ImportRun run = new ImportRun(firm, source.size());
        for (AcessoriasCompanyDTO item : source) {
            try {
                importCompany(run, item);
            } catch (Exception e) {
                log.warn("Acessórias import failed for company {} of firm {}", item.identificador(), firm.getId(), e);
                run.errors.add(item.identificador() + ": " + e.getMessage());
            }
            progress.advance();
        }

        firmIntegrationService.markSynced(firm.getId(), IntegrationType.ACESSORIAS, startedAt);

        log.info("Acessórias import for firm {}: {} received, {} created, {} updated, {} unchanged, {} links, {} skipped, {} errors",
                firm.getId(), run.received, run.created, run.updated, run.unchanged, run.linksCreated,
                run.skipped.size(), run.errors.size());
        return run.toResult();
    }

    private void importCompany(ImportRun run, AcessoriasCompanyDTO item) {
        String digits = DocumentNumbers.onlyDigits(item.identificador());
        DocumentTypeEnum type = documentType(digits);
        if (type == null) {
            run.skipped.add(label(item) + ": " + messageService.getMessage("acessorias.import.skipped.identifier"));
            return;
        }

        boolean active = ACTIVE_STATUS.equalsIgnoreCase(trim(item.status()));
        Company company = run.companiesByDocument.get(digits);

        if (company == null) {
            if (!active) {
                run.skipped.add(label(item) + ": " + messageService.getMessage("acessorias.import.skipped.inactive"));
                return;
            }
            // Document numbers are unique across the whole platform (see DocumentValidation), so a
            // CNPJ another firm already registered can't be created again here.
            if (documentRepository.existsByNumberAndTypeNative(digits, type)
                    || documentRepository.existsByNumberAndTypeNative(item.identificador().trim(), type)) {
                run.skipped.add(label(item) + ": " + messageService.getMessage("acessorias.import.skipped.document.exists"));
                return;
            }
            company = createCompany(run.firm, item, digits, type, active);
            run.companiesByDocument.put(digits, company);
            run.created++;
        } else if (company.isDeleted()) {
            run.skipped.add(label(item) + ": " + messageService.getMessage("acessorias.import.skipped.deleted"));
            return;
        } else if (applyRegistrationData(company, item, active)) {
            company = companyRepository.save(company);
            run.updated++;
        } else {
            run.unchanged++;
        }

        // a regime change seen in Acessórias starts a period this month
        taxRegimeService.recordChange(company, null);
        linkResponsibles(run, company, item);
    }

    private Company createCompany(AccountingFirm firm, AcessoriasCompanyDTO item, String digits, DocumentTypeEnum type, boolean active) {
        Document document = new Document(digits, type);
        document.setDeleted(false);

        Company company = new Company();
        company.setDeleted(false);
        company.setAccountingFirm(firm);
        company.setDocument(documentRepository.save(document));
        applyRegistrationData(company, item, active);
        return companyRepository.save(company);
    }

    /** Copies Acessórias' registration data onto the company; true when anything actually changed. */
    private boolean applyRegistrationData(Company company, AcessoriasCompanyDTO item, boolean active) {
        boolean changed = false;
        String name = truncate(firstNonBlank(item.razao(), item.fantasia(), item.identificador()), NAME_MAX);
        changed |= set(company.getName(), name, company::setName);
        changed |= set(company.getTradeName(), truncate(trim(item.fantasia()), NAME_MAX), company::setTradeName);
        changed |= set(company.getPhone(), truncate(trim(item.telefone()), 30), company::setPhone);
        changed |= set(company.getUf(), truncate(upper(item.uf()), 2), company::setUf);
        changed |= set(company.getTaxRegime(), truncate(trim(item.regime()), 100), company::applyTaxRegimeLabel);
        changed |= setStateRegistrations(company, item);
        changed |= set(company.getCompanyGroup(), truncate(trim(item.grupoDeEmpresas()), 100), company::setCompanyGroup);
        changed |= set(company.getActive(), active, company::setActive);
        changed |= set(company.getClientSince(), parseDate(item.clienteDesde()), company::setClientSince);
        changed |= set(company.getClientUntil(), parseDate(item.clienteAte()), company::setClientUntil);
        changed |= set(company.getAcessoriasId(), trim(item.id()), company::setAcessoriasId);
        return changed;
    }

    /**
     * One link per responsável e-mail, with the names of every department they answer for
     * as the role (e.g. "Fiscal, Contábil") - UserCompany holds a single role per user+company.
     */
    private void linkResponsibles(ImportRun run, Company company, AcessoriasCompanyDTO item) {
        if (item.departamentos() == null) {
            return;
        }

        Map<String, List<AcessoriasCompanyDTO.Department>> byEmail = item.departamentos().stream()
                .filter(department -> trim(department.respEmail()) != null)
                .collect(Collectors.groupingBy(department -> department.respEmail().trim().toLowerCase(Locale.ROOT),
                        LinkedHashMap::new, Collectors.toList()));

        for (Map.Entry<String, List<AcessoriasCompanyDTO.Department>> entry : byEmail.entrySet()) {
            User user = run.usersByEmail.get(entry.getKey());
            if (user == null) {
                String respName = trim(entry.getValue().getFirst().respNome());
                run.responsiblesNotFound.add((respName == null ? "" : respName + " ") + "<" + entry.getKey() + ">");
                continue;
            }
            if (userCompanyRepository.existsById(new UserCompanyId(user.getId(), company.getId()))) {
                continue;
            }
            String role = entry.getValue().stream()
                    .map(department -> trim(department.nome()))
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.joining(", "));
            UserCompany link = new UserCompany(user, company, truncate(role.isEmpty() ? "Responsável" : role, ROLE_MAX));
            link.setDeleted(false);
            userCompanyRepository.save(link);
            run.linksCreated++;
        }
    }

    private static <T> boolean set(T current, T value, Consumer<T> setter) {
        if (Objects.equals(current, value)) {
            return false;
        }
        setter.accept(value);
        return true;
    }

    private static DocumentTypeEnum documentType(String digits) {
        if (digits == null) {
            return null;
        }
        return switch (digits.length()) {
            case 14 -> DocumentTypeEnum.CNPJ;
            case 11 -> DocumentTypeEnum.CPF;
            default -> null; // CAEPF and anything else Taxify has no document type for
        };
    }

    private static boolean setStateRegistrations(Company company, AcessoriasCompanyDTO item) {
        java.util.List<StateRegistration> registrations = item.inscricoesEstaduais() == null ? java.util.List.of()
                : item.inscricoesEstaduais().stream()
                .map(ie -> StateRegistration.of(ie.uf(), ie.ie()))
                .filter(java.util.Objects::nonNull)
                .toList();
        String text = StateRegistration.format(registrations);
        if (java.util.Objects.equals(text, company.getStateRegistration())) {
            return false;
        }
        company.setStateRegistration(text);
        return true;
    }

    /** {@code yyyy-MM-dd}; Acessórias' "0000-00-00" (and anything unparseable) means not set. */
    private static LocalDate parseDate(String value) {
        String trimmed = trim(value);
        if (trimmed == null || trimmed.startsWith("0000")) {
            return null;
        }
        try {
            return LocalDate.parse(trimmed);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String label(AcessoriasCompanyDTO item) {
        String name = firstNonBlank(item.razao(), item.fantasia());
        return name == null ? item.identificador() : item.identificador() + " - " + name;
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String upper(String value) {
        String trimmed = trim(value);
        return trimmed == null ? null : trimmed.toUpperCase(Locale.ROOT);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (trim(value) != null) {
                return value.trim();
            }
        }
        return null;
    }

    /** Per-run state: the firm's companies and users indexed once up front, plus the counters for the summary. */
    private final class ImportRun {
        private final AccountingFirm firm;
        private final int received;
        private final Map<String, Company> companiesByDocument = new HashMap<>();
        private final Map<String, User> usersByEmail = new HashMap<>();
        private int created;
        private int updated;
        private int unchanged;
        private int linksCreated;
        private final List<String> skipped = new ArrayList<>();
        private final Set<String> responsiblesNotFound = new LinkedHashSet<>();
        private final List<String> errors = new ArrayList<>();

        private ImportRun(AccountingFirm firm, int received) {
            this.firm = firm;
            this.received = received;
            for (Company company : companyRepository.findByAccountingFirm_Id(firm.getId())) {
                if (company.getDocument() != null) {
                    companiesByDocument.putIfAbsent(DocumentNumbers.onlyDigits(company.getDocument().getNumber()), company);
                }
            }
            for (User user : userRepository.findByAccountingFirm_IdAndDeletedIsFalse(firm.getId())) {
                if (user.getEmail() != null) {
                    usersByEmail.putIfAbsent(user.getEmail().trim().toLowerCase(Locale.ROOT), user);
                }
            }
        }

        private AcessoriasImportResultDTO toResult() {
            return new AcessoriasImportResultDTO(received, created, updated, unchanged, linksCreated,
                    List.copyOf(skipped), List.copyOf(responsiblesNotFound), List.copyOf(errors));
        }
    }
}
