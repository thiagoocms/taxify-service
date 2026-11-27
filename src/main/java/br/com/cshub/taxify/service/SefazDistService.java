package br.com.cshub.taxify.service;

import org.springframework.beans.factory.annotation.Autowired;
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
import br.com.cshub.taxify.repository.CaptureRunRepository;
import br.com.cshub.taxify.domain.pendency.CaptureRun;
import br.com.cshub.taxify.enumerated.CaptureFailureCode;
import br.com.cshub.taxify.enumerated.CaptureStage;
import br.com.cshub.taxify.service.capture.CaptureFailureClassifier;
import br.com.cshub.taxify.service.capture.CaptureStageRecorder;
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
 * "Ciência da Operação") are kept as notes waiting for their XML, and {@link SefazManifestService}
 * sends the ciência and fetches them by key afterwards. The NSU is saved after each batch is
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
    private final SefazManifestService manifestService;
    private final CaptureRunRepository captureRunRepository;
    private CaptureStageRecorder stageRecorder = CaptureStageRecorder.noop();

    @Autowired(required = false)
    public void setStageRecorder(CaptureStageRecorder stageRecorder) {
        if (stageRecorder != null) {
            this.stageRecorder = stageRecorder;
        }
    }

    public SefazDistService(SefazDistControlRepository controlRepository, CompanyCertificateService certificateService,
                            FiscalXmlIntegrationService integrationService, SefazDistClient client, SefazProperties properties,
                            SefazManifestService manifestService, CaptureRunRepository captureRunRepository) {
        this.manifestService = manifestService;
        this.captureRunRepository = captureRunRepository;
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
        LocalDateTime startedAt = LocalDateTime.now();
        try {
            A1Certificate certificate = runBatches(company, control, run);
            if (certificate != null) {
                SefazManifestService.Outcome outcome = manifestService.manifestAndFetch(company, certificate,
                        CompanyCertificateService.cnpjOf(company), !run.blocked);
                run.ciencias += outcome.cienciasSent();
                run.cienciaErrors += outcome.cienciaErrors();
                run.newNotes += outcome.fetched();
                if (outcome.blocked()) {
                    control.setNextQueryAt(LocalDateTime.now().plusMinutes(WAIT_MINUTES));
                }
                if (outcome.error() != null && run.error == null) {
                    run.error(outcome.error(), outcome.errorCode() == null ? CaptureFailureCode.SEFAZ_CONNECTION
                            : outcome.errorCode(), null);
                }
            }
        } catch (IOException e) {
            log.warn("SEFAZ distribution failed for company {}", company.getId(), e);
            run.error("Falha de conexão com a SEFAZ: " + e.getMessage(), CaptureFailureClassifier.sefazIo(e), null);
        } catch (RuntimeException e) {
            log.warn("SEFAZ distribution failed for company {}", company.getId(), e);
            run.error("Erro inesperado: " + e.getMessage(), CaptureFailureCode.INTERNAL_ERROR, null);
        }

        control.setLastRunAt(LocalDateTime.now());
        control.setLastNewNotes(run.newNotes);
        control.setLastSummaries(run.summaries);
        control.setLastStatus(run.status());
        control.setLastDetail(truncate(run.detail()));
        log.info("SEFAZ distribution for company {}: {} - {} new notes, {} events, {} summaries, {} ciencias{}", company.getId(),
                control.getLastStatus(), run.newNotes, run.events, run.summaries, run.ciencias,
                run.error == null ? "" : " (" + run.error + ")");
        SefazDistControl saved = controlRepository.save(control);
        recordRun(company, startedAt, control);
        recordStage(company, run);
        return saved;
    }

    /** CP-RN-10/11: the run's safe code in capture_stage_status (own transaction, never fails the capture). */
    private void recordStage(Company company, Run run) {
        if (run.error != null) {
            stageRecorder.failure(company.getId(), CaptureStage.SEFAZ_DIST, run.code, run.providerStatus);
        } else {
            stageRecorder.success(company.getId(), CaptureStage.SEFAZ_DIST, run.warning, null);
        }
    }

    /** History of the runs - the integrations' health on the pendencies screen. Never fails the capture. */
    private void recordRun(Company company, LocalDateTime startedAt, SefazDistControl control) {
        try {
            CaptureRun captureRun = new CaptureRun();
            captureRun.setCompany(company);
            captureRun.setService(CaptureRun.SEFAZ_DIST);
            captureRun.setStartedAt(startedAt);
            captureRun.setFinishedAt(control.getLastRunAt());
            captureRun.setStatus(control.getLastStatus().name());
            captureRun.setNewDocuments(control.getLastNewNotes());
            captureRun.setDetail(control.getLastDetail());
            captureRun.setDeleted(false);
            captureRunRepository.save(captureRun);
        } catch (RuntimeException e) {
            log.warn("Could not record the SEFAZ capture run of company {}", company.getId(), e);
        }
    }

    /** @return the opened certificate when the distribution could be asked (the ciência step uses it too) */
    private A1Certificate runBatches(Company company, SefazDistControl control, Run run) throws IOException {
        String cnpj = CompanyCertificateService.cnpjOf(company);
        if (cnpj == null) {
            run.error("A empresa não tem CNPJ.", CaptureFailureCode.COMPANY_WITHOUT_CNPJ, null);
            return null;
        }
        if (SefazDistClient.ufCode(company.getUf()) == null) {
            run.error("UF da empresa não informada.", CaptureFailureCode.COMPANY_WITHOUT_UF, null);
            return null;
        }
        Optional<CompanyCertificate> stored = certificateService.resolve(company);
        if (stored.isEmpty()) {
            run.error("Sem certificado A1.", CaptureFailureCode.CERT_MISSING, null);
            return null;
        }
        if (stored.get().getValidUntil().isBefore(LocalDateTime.now())) {
            run.error("Certificado vencido em " + stored.get().getValidUntil().format(DATE) + ".", CaptureFailureCode.CERT_EXPIRED, null);
            return null;
        }
        A1Certificate certificate;
        try {
            certificate = certificateService.open(stored.get());
        } catch (java.nio.file.NoSuchFileException e) {
            // e.g. uploaded by a backend with another storage (a local run on disk) - not a SEFAZ problem
            log.warn("Certificate file of company {} not found in the storage: {}", company.getId(), e.getMessage());
            run.error("Arquivo do certificado não encontrado no armazenamento - envie o certificado novamente.",
                    CaptureFailureCode.CERT_FILE_MISSING, null);
            return null;
        } catch (IOException e) {
            log.warn("Could not read the certificate file of company {}", company.getId(), e);
            run.error("Não foi possível ler o arquivo do certificado: " + e.getMessage(), CaptureFailureCode.INTERNAL_ERROR, null);
            return null;
        } catch (GeneralSecurityException | IllegalStateException e) {
            run.error("Não foi possível abrir o certificado: " + e.getMessage(), CaptureFailureCode.CERT_INVALID, null);
            return null;
        }

        for (int batch = 0; batch < properties.getMaxBatchesPerCompany(); batch++) {
            SefazDistResponse response;
            try {
                response = client.fetch(certificate, company.getUf(), cnpj, control.getUltNsu());
            } catch (GeneralSecurityException e) {
                run.error("Certificado recusado na conexão: " + e.getMessage(), CaptureFailureCode.CERT_INVALID, null);
                return null;
            }
            if ("656".equals(response.cStat())) {
                run.blocked = true;
                run.warning = CaptureFailureCode.SEFAZ_CONSUMO_INDEVIDO;
                control.setNextQueryAt(LocalDateTime.now().plusMinutes(WAIT_MINUTES));
                String motivo = response.xMotivo() == null ? "" : " " + response.xMotivo();
                // "Deve ser utilizado o ultNSU nas solicitações subsequentes": SEFAZ already delivered up
                // to that NSU for this CNPJ (another system read it) - continue from there after the pause.
                if (isAhead(response.ultNsu(), control.getUltNsu())) {
                    log.info("SEFAZ 656 for company {}: NSU moved from {} to {} as SEFAZ informed", company.getId(),
                            control.getUltNsu(), response.ultNsu());
                    run.note("SEFAZ pediu pausa de 1h (656" + motivo + "). NSU ajustado de " + control.getUltNsu()
                            + " para " + response.ultNsu() + " conforme a SEFAZ - documentos anteriores a ele não vêm"
                            + " por aqui (use o SIEG ou a importação manual).");
                    control.setUltNsu(response.ultNsu());
                    if (response.maxNsu() != null && !response.maxNsu().isBlank()) {
                        control.setMaxNsu(response.maxNsu());
                    }
                } else {
                    run.note("SEFAZ pediu pausa de 1h (656" + motivo + ").");
                }
                return certificate;
            }
            if (!"137".equals(response.cStat()) && !"138".equals(response.cStat())) {
                run.error(response.cStat() + " " + response.xMotivo(), CaptureFailureCode.SEFAZ_REJECTED, response.cStat());
                return null;
            }
            store(company, response, run);
            if (response.ultNsu() != null && !response.ultNsu().isBlank()) {
                control.setUltNsu(response.ultNsu());
            }
            control.setMaxNsu(response.maxNsu());
            controlRepository.save(control);
            if (response.caughtUp()) {
                control.setNextQueryAt(LocalDateTime.now().plusMinutes(WAIT_MINUTES));
                return certificate;
            }
            pause();
        }
        return certificate;
    }

    private void store(Company company, SefazDistResponse response, Run run) {
        List<byte[]> full = response.documents().stream()
                .filter(document -> document.isFullNote() || document.isFullEvent())
                .map(SefazDistResponse.Document::xml)
                .toList();
        run.summaries += manifestService.recordSummaries(company, response.documents());
        if (!full.isEmpty()) {
            XmlDownloadResultDTO result = integrationService.importFromSefaz(company.getId(), full);
            run.newNotes += result.totalPersistedNew();
            run.events += result.totalEventsRecorded();
        }
    }

    /** Whether NSU {@code candidate} (15 digits) is after {@code current}. */
    static boolean isAhead(String candidate, String current) {
        if (candidate == null || !candidate.strip().matches("\\d{1,15}")) {
            return false;
        }
        long now = current == null || !current.strip().matches("\\d{1,15}") ? 0 : Long.parseLong(current.strip());
        return Long.parseLong(candidate.strip()) > now;
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
        int ciencias;
        int cienciaErrors;
        boolean blocked;
        String error;
        String note;
        /** The safe code of the error (CP-RN-11) - the text above stays only in last_detail, as before. */
        CaptureFailureCode code;
        String providerStatus;
        CaptureFailureCode warning;

        void error(String message, CaptureFailureCode failureCode, String status) {
            error = message;
            code = failureCode == null ? CaptureFailureCode.INTERNAL_ERROR : failureCode;
            providerStatus = status;
        }

        void note(String message) {
            note = message;
        }

        Status status() {
            if (error != null) {
                return Status.ERRO;
            }
            return newNotes + events + summaries + ciencias > 0 ? Status.OK : Status.SEM_MOVIMENTO;
        }

        String detail() {
            StringBuilder detail = new StringBuilder();
            if (error != null) {
                detail.append(error);
            } else if (note != null) {
                detail.append(note);
            }
            if (summaries > 0) {
                append(detail, summaries + " entrada(s) nova(s) só com resumo.");
            }
            if (ciencias > 0) {
                append(detail, "Ciência da Operação enviada para " + ciencias + " nota(s) - o XML completo vem na próxima consulta.");
            }
            if (cienciaErrors > 0) {
                append(detail, cienciaErrors + " ciência(s) recusada(s) pela SEFAZ.");
            }
            return detail.isEmpty() ? null : detail.toString();
        }

        private static void append(StringBuilder detail, String text) {
            if (!detail.isEmpty()) {
                detail.append(' ');
            }
            detail.append(text);
        }
    }
}
