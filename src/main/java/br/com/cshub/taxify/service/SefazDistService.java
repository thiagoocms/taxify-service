package br.com.cshub.taxify.service;

import br.com.cshub.taxify.domain.certificate.CompanyCertificate;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.xml.SefazDistControl;
import br.com.cshub.taxify.domain.xml.SefazDistControl.Status;
import br.com.cshub.taxify.dto.sieg.XmlDownloadResultDTO;
import br.com.cshub.taxify.integration.sefaz.A1Certificate;
import br.com.cshub.taxify.integration.sefaz.SefazDistClient;
import br.com.cshub.taxify.integration.sefaz.SefazDistResponse;
import br.com.cshub.taxify.integration.sefaz.SefazProperties;
import br.com.cshub.taxify.repository.SefazDistControlRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * Captures one company's documents from the SEFAZ distribution (NFeDistribuicaoDFe), from
 * the last NSU read on. Full notes and events go through the regular persistence
 * ({@link FiscalXmlIntegrationService#importFromSefaz}); summaries (resNFe - entries without
 * "Ciência da Operação") are only counted for now. The NSU is saved after each batch is
 * stored, so a crash only means re-reading that batch (known notes are skipped).
 *
 * <p>SEFAZ rules followed: once caught up ({@code ultNSU == maxNSU} or cStat 137) or told
 * to stop (656), the company isn't asked again for an hour; documents only stay available
 * for 90 days, hence the hourly schedule.
 */
@Service
public class SefazDistService {

    private static final Logger log = LoggerFactory.getLogger(SefazDistService.class);
    /** SEFAZ asks for one hour; a few minutes more so the next query never lands early. */
    static final long WAIT_MINUTES = 65;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final SefazDistControlRepository controlRepository;
    private final CompanyCertificateService certificateService;
    private final FiscalXmlIntegrationService integrationService;
    private final SefazDistClient client;
    private final SefazProperties properties;

    public SefazDistService(SefazDistControlRepository controlRepository, CompanyCertificateService certificateService,
                            FiscalXmlIntegrationService integrationService, SefazDistClient client, SefazProperties properties) {
        this.controlRepository = controlRepository;
        this.certificateService = certificateService;
        this.integrationService = integrationService;
        this.client = client;
        this.properties = properties;
    }

    /** @return the control after the run - unchanged when SEFAZ's waiting hour isn't over yet */
    public SefazDistControl capture(Company company) {
        SefazDistControl control = controlRepository.findByCompany_Id(company.getId()).orElseGet(() -> {
            SefazDistControl created = new SefazDistControl();
            created.setCompany(company);
            created.setDeleted(false);
            return created;
        });
        LocalDateTime now = LocalDateTime.now();
        if (control.getNextQueryAt() != null && control.getNextQueryAt().isAfter(now)) {
            return control;
        }

        Run run = new Run();
        try {
            runBatches(company, control, run);
        } catch (IOException e) {
            log.warn("SEFAZ distribution failed for company {}", company.getId(), e);
            run.error("Falha de conexão com a SEFAZ: " + e.getMessage());
        } catch (RuntimeException e) {
            log.warn("SEFAZ distribution failed for company {}", company.getId(), e);
            run.error("Erro inesperado: " + e.getMessage());
        }

        control.setLastRunAt(LocalDateTime.now());
        control.setLastNewNotes(run.newNotes);
        control.setLastSummaries(run.summaries);
        control.setLastStatus(run.status());
        control.setLastDetail(truncate(run.detail()));
        log.info("SEFAZ distribution for company {}: {} - {} new notes, {} events, {} summaries{}", company.getId(),
                control.getLastStatus(), run.newNotes, run.events, run.summaries,
                run.error == null ? "" : " (" + run.error + ")");
        return controlRepository.save(control);
    }

    private void runBatches(Company company, SefazDistControl control, Run run) throws IOException {
        String cnpj = CompanyCertificateService.cnpjOf(company);
        if (cnpj == null) {
            run.error("A empresa não tem CNPJ.");
            return;
        }
        if (SefazDistClient.ufCode(company.getUf()) == null) {
            run.error("UF da empresa não informada.");
            return;
        }
        Optional<CompanyCertificate> stored = certificateService.resolve(company);
        if (stored.isEmpty()) {
            run.error("Sem certificado A1.");
            return;
        }
        if (stored.get().getValidUntil().isBefore(LocalDateTime.now())) {
            run.error("Certificado vencido em " + stored.get().getValidUntil().format(DATE) + ".");
            return;
        }
        A1Certificate certificate;
        try {
            certificate = certificateService.open(stored.get());
        } catch (java.nio.file.NoSuchFileException e) {
            // e.g. uploaded by a backend with another storage (a local run on disk) - not a SEFAZ problem
            log.warn("Certificate file of company {} not found in the storage: {}", company.getId(), e.getMessage());
            run.error("Arquivo do certificado não encontrado no armazenamento - envie o certificado novamente.");
            return;
        } catch (IOException e) {
            log.warn("Could not read the certificate file of company {}", company.getId(), e);
            run.error("Não foi possível ler o arquivo do certificado: " + e.getMessage());
            return;
        } catch (GeneralSecurityException | IllegalStateException e) {
            run.error("Não foi possível abrir o certificado: " + e.getMessage());
            return;
        }

        for (int batch = 0; batch < properties.getMaxBatchesPerCompany(); batch++) {
            SefazDistResponse response;
            try {
                response = client.fetch(certificate, company.getUf(), cnpj, control.getUltNsu());
            } catch (GeneralSecurityException e) {
                run.error("Certificado recusado na conexão: " + e.getMessage());
                return;
            }
            if ("656".equals(response.cStat())) {
                control.setNextQueryAt(LocalDateTime.now().plusMinutes(WAIT_MINUTES));
                run.note("SEFAZ pediu pausa de 1h (656).");
                return;
            }
            if (!"137".equals(response.cStat()) && !"138".equals(response.cStat())) {
                run.error(response.cStat() + " " + response.xMotivo());
                return;
            }
            store(company, response, run);
            if (response.ultNsu() != null && !response.ultNsu().isBlank()) {
                control.setUltNsu(response.ultNsu());
            }
            control.setMaxNsu(response.maxNsu());
            controlRepository.save(control);
            if (response.caughtUp()) {
                control.setNextQueryAt(LocalDateTime.now().plusMinutes(WAIT_MINUTES));
                return;
            }
            pause();
        }
    }

    private void store(Company company, SefazDistResponse response, Run run) {
        List<byte[]> full = response.documents().stream()
                .filter(document -> document.isFullNote() || document.isFullEvent())
                .map(SefazDistResponse.Document::xml)
                .toList();
        run.summaries += (int) response.documents().stream().filter(SefazDistResponse.Document::isNoteSummary).count();
        if (!full.isEmpty()) {
            XmlDownloadResultDTO result = integrationService.importFromSefaz(company.getId(), full);
            run.newNotes += result.totalPersistedNew();
            run.events += result.totalEventsRecorded();
        }
    }

    private void pause() {
        try {
            Thread.sleep(properties.getPauseBetweenBatchesMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String truncate(String value) {
        return value == null || value.length() <= 500 ? value : value.substring(0, 500);
    }

    /** What one run found, for the control row. */
    private static final class Run {
        int newNotes;
        int events;
        int summaries;
        String error;
        String note;

        void error(String message) {
            error = message;
        }

        void note(String message) {
            note = message;
        }

        Status status() {
            if (error != null) {
                return Status.ERRO;
            }
            return newNotes + events + summaries > 0 ? Status.OK : Status.SEM_MOVIMENTO;
        }

        String detail() {
            StringBuilder detail = new StringBuilder();
            if (error != null) {
                detail.append(error);
            } else if (note != null) {
                detail.append(note);
            }
            if (summaries > 0) {
                if (!detail.isEmpty()) {
                    detail.append(' ');
                }
                detail.append(summaries).append(" entrada(s) só com resumo (falta Ciência da Operação).");
            }
            return detail.isEmpty() ? null : detail.toString();
        }
    }
}
