package br.com.cshub.taxify.service.fronteira;

import br.com.cshub.taxify.domain.certificate.FirmCertificate;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.company.StateRegistration;
import br.com.cshub.taxify.integration.efisco.EfiscoClient;
import br.com.cshub.taxify.integration.efisco.EfiscoException;
import br.com.cshub.taxify.integration.sefaz.A1Certificate;
import br.com.cshub.taxify.repository.CompanyRepository;
import br.com.cshub.taxify.service.FirmCertificateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetches the Fronteira extract from PE's e-Fisco with the firm's accountant's certificate (Integrações) and imports
 * it, as if someone had downloaded the PDF and dropped it on the screen: for each PE company of the firm with an IE
 * and each period, "Calculadas" in PDF → {@link FronteiraService#importFromEfisco}. Only with the certificate; one run
 * per firm at a time. A Cloudflare challenge or a refused login stops the whole run - nothing is worked around.
 */
@Service
public class EfiscoSyncService {

    private static final Logger log = LoggerFactory.getLogger(EfiscoSyncService.class);
    static final String UF = "PE";
    /** A pause between companies: the run is a person's pace, not a burst on e-Fisco. */
    private static final long PAUSE_MS = 1_500;

    private final FirmCertificateService certificateService;
    private final CompanyRepository companyRepository;
    private final FronteiraService fronteiraService;
    private final Set<UUID> running = ConcurrentHashMap.newKeySet();

    public EfiscoSyncService(FirmCertificateService certificateService, CompanyRepository companyRepository,
                             FronteiraService fronteiraService) {
        this.certificateService = certificateService;
        this.companyRepository = companyRepository;
        this.fronteiraService = fronteiraService;
    }

    /** One company's result in a run. */
    public record CompanyResult(UUID companyId, String companyName, YearMonth period, Status status, String detail) {
    }

    public enum Status { IMPORTADO, SEM_MUDANCA, SEM_EXTRATO, SEM_IE, ERRO }

    /** The whole run: OK / PARCIAL / ERRO / BLOQUEADO / SEM_CERTIFICADO / EM_ANDAMENTO, and each company's result. */
    public record SyncResult(String status, String detail, List<CompanyResult> companies) {
    }

    /**
     * Every PE company of the firm, for each period - the scheduled run. A past month whose extract is already closed
     * (past its due date, or paid) isn't asked again: it doesn't change any more.
     */
    public SyncResult syncFirm(UUID accountingFirmId, List<YearMonth> periods) {
        List<Company> companies = companyRepository.findByAccountingFirm_IdAndDeletedIsFalseOrderByNameAsc(accountingFirmId).stream()
                .filter(EfiscoSyncService::isPernambuco)
                .toList();
        return run(accountingFirmId, companies, periods, true);
    }

    /** One company and period - the "Sincronizar com e-Fisco" of the Fronteira screen. */
    public SyncResult syncCompany(Company company, YearMonth period) {
        if (company.getAccountingFirm() == null) {
            return new SyncResult("SEM_CERTIFICADO", "A empresa não pertence a um escritório.", List.of());
        }
        return run(company.getAccountingFirm().getId(), List.of(company), List.of(period), false);
    }

    private SyncResult run(UUID firmId, List<Company> companies, List<YearMonth> periods, boolean skipClosed) {
        Optional<FirmCertificate> found = certificateService.find(firmId);
        if (found.isEmpty()) {
            return new SyncResult("SEM_CERTIFICADO",
                    "Cadastre o certificado do contador (e-CPF) em Integrações para buscar o extrato no e-Fisco.", List.of());
        }
        FirmCertificate certificate = found.get();
        if (certificate.getValidUntil().isBefore(LocalDateTime.now())) {
            return record(certificate, new SyncResult("ERRO", "O certificado do contador está vencido.", List.of()));
        }
        if (!running.add(firmId)) {
            return new SyncResult("EM_ANDAMENTO", "Uma sincronização com o e-Fisco já está em andamento.", List.of());
        }
        List<CompanyResult> results = new ArrayList<>();
        try {
            A1Certificate a1 = certificateService.open(certificate);
            EfiscoClient client = new EfiscoClient(a1);
            client.login();
            for (Company company : companies) {
                String ie = stateRegistration(company);
                for (YearMonth period : periods) {
                    if (ie == null) {
                        results.add(new CompanyResult(company.getId(), company.getName(), period, Status.SEM_IE,
                                "Empresa sem inscrição estadual de PE no cadastro."));
                        continue;
                    }
                    if (skipClosed && period.isBefore(YearMonth.now()) && fronteiraService.extractClosed(company, period)) {
                        continue; // closed: due date passed or paid - nothing left to change
                    }
                    results.add(fetch(client, company, ie, period));
                    Thread.sleep(PAUSE_MS);
                }
            }
            return record(certificate, summary(results));
        } catch (EfiscoException e) {
            String status = e.reason() == EfiscoException.Reason.BLOQUEADO || e.reason() == EfiscoException.Reason.LOGIN_RECUSADO
                    ? "BLOQUEADO" : "ERRO";
            log.warn("e-Fisco sync of firm {} stopped: {}", firmId, e.getMessage());
            return record(certificate, new SyncResult(status, e.getMessage() + done(results), results));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return record(certificate, new SyncResult("ERRO", "Sincronização interrompida." + done(results), results));
        } catch (Exception e) {
            log.warn("e-Fisco sync of firm {} failed", firmId, e);
            return record(certificate, new SyncResult("ERRO", "Falha ao falar com o e-Fisco: " + e.getMessage() + done(results), results));
        } finally {
            running.remove(firmId);
        }
    }

    /** One IE and period: a refusal of this company (not linked, no extract) doesn't stop the others. */
    private CompanyResult fetch(EfiscoClient client, Company company, String ie, YearMonth period) throws Exception {
        EfiscoClient.Extract extract = client.calculatedExtract(ie, company.getName(), period);
        if (!extract.found()) {
            return new CompanyResult(company.getId(), company.getName(), period, Status.SEM_EXTRATO,
                    extract.message() == null ? "O e-Fisco não gerou extrato para o período." : extract.message());
        }
        if (fronteiraService.sameAsImported(company, extract.pdf())) {
            fronteiraService.attachPdfIfMissing(company, extract.pdf());
            return new CompanyResult(company.getId(), company.getName(), period, Status.SEM_MUDANCA,
                    "Conferido no e-Fisco: o extrato é o mesmo já importado.");
        }
        try {
            var report = fronteiraService.importFromEfisco(company, extract.pdf());
            String detail = report.summary() == null ? "Extrato importado."
                    : "Extrato importado: " + report.summary().notas() + " nota(s).";
            return new CompanyResult(company.getId(), company.getName(), period, Status.IMPORTADO, detail);
        } catch (RuntimeException e) {
            log.warn("e-Fisco extract of company {} ({}) could not be imported", company.getId(), period, e);
            return new CompanyResult(company.getId(), company.getName(), period, Status.ERRO,
                    "O PDF veio do e-Fisco, mas não pôde ser importado: " + e.getMessage());
        }
    }

    private static SyncResult summary(List<CompanyResult> results) {
        long imported = results.stream().filter(r -> r.status() == Status.IMPORTADO).count();
        long none = results.stream().filter(r -> r.status() == Status.SEM_EXTRATO).count();
        long unchanged = results.stream().filter(r -> r.status() == Status.SEM_MUDANCA).count();
        long failed = results.stream().filter(r -> r.status() == Status.ERRO || r.status() == Status.SEM_IE).count();
        String detail = imported + " extrato(s) importado(s), " + unchanged + " sem mudança, " + none + " sem extrato no e-Fisco"
                + (failed > 0 ? ", " + failed + " com problema" : "") + ".";
        return new SyncResult(failed > 0 ? "PARCIAL" : "OK", detail, results);
    }

    private SyncResult record(FirmCertificate certificate, SyncResult result) {
        certificateService.recordSync(certificate.getId(), result.status(), result.detail());
        return result;
    }

    private static String done(List<CompanyResult> results) {
        long imported = results.stream().filter(r -> r.status() == Status.IMPORTADO).count();
        return imported == 0 ? "" : " (" + imported + " extrato(s) já importado(s) antes de parar)";
    }

    static boolean isPernambuco(Company company) {
        return company.getUf() != null && UF.equalsIgnoreCase(company.getUf().strip());
    }

    /** The company's PE state registration, digits only. */
    static String stateRegistration(Company company) {
        return company.getStateRegistrations().stream()
                .filter(registration -> registration.getUf() == null || UF.equalsIgnoreCase(registration.getUf().strip()))
                .map(StateRegistration::getNumber)
                .filter(number -> number != null && !number.replaceAll("\\D", "").isEmpty())
                .map(number -> number.replaceAll("\\D", ""))
                .findFirst()
                .orElse(null);
    }
}
