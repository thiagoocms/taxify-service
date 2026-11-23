package br.com.cshub.taxify.service;

import br.com.cshub.taxify.domain.certificate.CompanyCertificate;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.pendency.CaptureRun;
import br.com.cshub.taxify.domain.xml.NfseAdnControl;
import br.com.cshub.taxify.domain.xml.NfseAdnControl.Status;
import br.com.cshub.taxify.integration.nfse.AdnClient;
import br.com.cshub.taxify.integration.nfse.AdnProperties;
import br.com.cshub.taxify.integration.nfse.AdnResponse;
import br.com.cshub.taxify.integration.sefaz.A1Certificate;
import br.com.cshub.taxify.repository.CaptureRunRepository;
import br.com.cshub.taxify.repository.NfseAdnControlRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Captures one company's NFS-e from the Ambiente Nacional (ADN), from the last NSU read on: provided
 * and taken notes go to {@link ServiceInvoiceService#store} (source ADN - one row per note whatever
 * the source), cancellation events mark the note canceled. The NSU is saved after each batch.
 */
@Service
public class NfseAdnService {

    private static final Logger log = LoggerFactory.getLogger(NfseAdnService.class);
    public static final String SOURCE = "ADN";
    /** A full batch has 50 documents - fewer means the company is caught up. */
    static final int FULL_BATCH = 50;
    /** e101101 cancelamento, e105102 cancelamento por substituição. */
    private static final Set<String> CANCELLATIONS = Set.of("101101", "105102");
    private static final Pattern KEY_IN_EVENT = Pattern.compile("<(?:\\w+:)?chNFSe>\\s*(\\d{50})\\s*<");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final NfseAdnControlRepository controlRepository;
    private final CompanyCertificateService certificateService;
    private final ServiceInvoiceService serviceInvoiceService;
    private final AdnClient client;
    private final AdnProperties properties;
    private final CaptureRunRepository captureRunRepository;

    public NfseAdnService(NfseAdnControlRepository controlRepository, CompanyCertificateService certificateService,
                          ServiceInvoiceService serviceInvoiceService, AdnClient client, AdnProperties properties,
                          CaptureRunRepository captureRunRepository) {
        this.controlRepository = controlRepository;
        this.certificateService = certificateService;
        this.serviceInvoiceService = serviceInvoiceService;
        this.client = client;
        this.properties = properties;
        this.captureRunRepository = captureRunRepository;
    }

    /** The company's capture as the screen shows it (null fields = never ran). */
    public br.com.cshub.taxify.dto.company.NfseAdnStatusDTO status(Company company, boolean inPlan) {
        NfseAdnControl control = controlRepository.findByCompany_Id(company.getId()).orElse(null);
        if (control == null) {
            return new br.com.cshub.taxify.dto.company.NfseAdnStatusDTO(inPlan, null, null, null, null, null, null);
        }
        return new br.com.cshub.taxify.dto.company.NfseAdnStatusDTO(inPlan, control.getLastRunAt(),
                control.getLastStatus() == null ? null : control.getLastStatus().name(), control.getLastDetail(),
                control.getLastNewNotes(), control.getNextQueryAt(), control.getUltNsu());
    }

    /** @return the control after the run - unchanged while the waiting time isn't over */
    public NfseAdnControl capture(Company company) {
        NfseAdnControl control = controlRepository.findByCompany_Id(company.getId()).orElseGet(() -> {
            NfseAdnControl created = new NfseAdnControl();
            created.setCompany(company);
            created.setDeleted(false);
            return created;
        });
        LocalDateTime startedAt = LocalDateTime.now();
        if (control.getNextQueryAt() != null && control.getNextQueryAt().isAfter(startedAt)) {
            return control;
        }
        Run run = new Run();
        try {
            runBatches(company, control, run);
        } catch (IOException e) {
            log.warn("ADN capture failed for company {}", company.getId(), e);
            run.error = "Falha de conexão com o Ambiente Nacional: " + e.getMessage();
        } catch (RuntimeException e) {
            log.warn("ADN capture failed for company {}", company.getId(), e);
            run.error = "Erro inesperado: " + e.getMessage();
        }
        control.setLastRunAt(LocalDateTime.now());
        control.setLastNewNotes(run.newNotes);
        control.setLastStatus(run.status());
        control.setLastDetail(truncate(run.detail()));
        NfseAdnControl saved = controlRepository.save(control);
        recordRun(company, startedAt, control);
        log.info("ADN capture for company {}: {} - {} new notes, {} canceled", company.getId(), control.getLastStatus(),
                run.newNotes, run.canceled);
        return saved;
    }

    private void runBatches(Company company, NfseAdnControl control, Run run) throws IOException {
        String cnpj = CompanyCertificateService.cnpjOf(company);
        if (cnpj == null) {
            run.error = "A empresa não tem CNPJ.";
            return;
        }
        Optional<CompanyCertificate> stored = certificateService.resolve(company);
        if (stored.isEmpty()) {
            run.error = "Sem certificado A1.";
            return;
        }
        if (stored.get().getValidUntil().isBefore(LocalDateTime.now())) {
            run.error = "Certificado vencido em " + stored.get().getValidUntil().format(DATE) + ".";
            return;
        }
        A1Certificate certificate;
        try {
            certificate = certificateService.open(stored.get());
        } catch (IOException | GeneralSecurityException | IllegalStateException e) {
            run.error = "Não foi possível abrir o certificado: " + e.getMessage();
            return;
        }

        for (int batch = 0; batch < properties.getMaxBatchesPerCompany(); batch++) {
            AdnResponse response;
            try {
                response = client.fetch(certificate, cnpj, control.getUltNsu());
            } catch (GeneralSecurityException e) {
                run.error = "Certificado recusado na conexão: " + e.getMessage();
                return;
            }
            if (response.throttled()) {
                control.setNextQueryAt(LocalDateTime.now().plusMinutes(properties.getWaitMinutesWhenCaughtUp()));
                run.note = "O Ambiente Nacional pediu para esperar (HTTP 429) - nova consulta em "
                        + properties.getWaitMinutesWhenCaughtUp() + " minutos.";
                return;
            }
            if (response.failed()) {
                run.error = response.errors().isEmpty() ? "HTTP " + response.httpStatus() : String.join("; ", response.errors());
                return;
            }
            if (response.nothingNew()) {
                control.setNextQueryAt(LocalDateTime.now().plusMinutes(properties.getWaitMinutesWhenCaughtUp()));
                return;
            }
            store(company, response, run);
            long previous = control.getUltNsu();
            control.setUltNsu(Math.max(previous, response.lastNsu(previous)));
            controlRepository.save(control);
            if (response.documents().size() < FULL_BATCH || control.getUltNsu() == previous) {
                control.setNextQueryAt(LocalDateTime.now().plusMinutes(properties.getWaitMinutesWhenCaughtUp()));
                return;
            }
            pause();
        }
    }

    private void store(Company company, AdnResponse response, Run run) {
        for (AdnResponse.Document document : response.documents()) {
            if (document.isEvent()) {
                if (isCancellation(document)) {
                    String key = document.accessKey() != null && document.accessKey().matches("\\d{50}")
                            ? document.accessKey() : keyIn(document.xml());
                    if (key != null && serviceInvoiceService.markCanceled(company, key)) {
                        run.canceled++;
                    }
                }
                continue;
            }
            if (serviceInvoiceService.store(company, document.xml(), SOURCE) == ServiceInvoiceService.Stored.NEW) {
                run.newNotes++;
            }
        }
    }

    static boolean isCancellation(AdnResponse.Document document) {
        String type = document.eventType() == null ? "" : document.eventType();
        if (CANCELLATIONS.stream().anyMatch(type::contains)) {
            return true;
        }
        String xml = new String(document.xml(), StandardCharsets.UTF_8);
        return xml.contains("e101101") || xml.contains("e105102");
    }

    static String keyIn(byte[] xml) {
        Matcher matcher = KEY_IN_EVENT.matcher(new String(xml, StandardCharsets.UTF_8));
        return matcher.find() ? matcher.group(1) : null;
    }

    private void recordRun(Company company, LocalDateTime startedAt, NfseAdnControl control) {
        try {
            CaptureRun captureRun = new CaptureRun();
            captureRun.setCompany(company);
            captureRun.setService(CaptureRun.NFSE_ADN);
            captureRun.setStartedAt(startedAt);
            captureRun.setFinishedAt(control.getLastRunAt());
            captureRun.setStatus(control.getLastStatus().name());
            captureRun.setNewDocuments(control.getLastNewNotes());
            captureRun.setDetail(control.getLastDetail());
            captureRun.setDeleted(false);
            captureRunRepository.save(captureRun);
        } catch (RuntimeException e) {
            log.warn("Could not record the ADN capture run of company {}", company.getId(), e);
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

    private static final class Run {
        int newNotes;
        int canceled;
        String error;
        String note;

        Status status() {
            if (error != null) {
                return Status.ERRO;
            }
            return newNotes + canceled > 0 ? Status.OK : Status.SEM_MOVIMENTO;
        }

        String detail() {
            if (error != null) {
                return error;
            }
            StringBuilder detail = new StringBuilder();
            if (note != null) {
                detail.append(note);
            }
            if (canceled > 0) {
                detail.append(detail.isEmpty() ? "" : " ").append(canceled).append(" NFS-e cancelada(s).");
            }
            return detail.isEmpty() ? null : detail.toString();
        }
    }
}
