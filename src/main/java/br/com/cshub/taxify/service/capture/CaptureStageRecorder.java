package br.com.cshub.taxify.service.capture;

import br.com.cshub.taxify.enumerated.CaptureFailureCode;
import br.com.cshub.taxify.enumerated.CaptureStage;
import br.com.cshub.taxify.repository.CaptureStageStatusRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Grava o resultado de cada etapa da captura ao terminar (CP-RN-10), numa transação própria (o upsert do
 * {@link CaptureStageStatusRepository} é REQUIRES_NEW). <b>Nunca lança</b>: uma falha ao gravar só gera o log
 * {@code capture.stage.record.failed company={id}} e a captura segue como se nada tivesse acontecido - as notas já
 * gravadas ficam, e a próxima empresa é processada. Os logs desta classe levam só ids, etapa e código.
 */
@Component
public class CaptureStageRecorder {

    private static final Logger log = LoggerFactory.getLogger(CaptureStageRecorder.class);

    private final CaptureStageStatusRepository repository;
    private final Clock clock;

    public CaptureStageRecorder(CaptureStageStatusRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** Um recorder que não grava nada - para os testes e os caminhos montados sem o painel. */
    public static CaptureStageRecorder noop() {
        return new CaptureStageRecorder(null, Clock.systemDefaultZone());
    }

    public void success(UUID companyId, CaptureStage stage) {
        success(companyId, stage, null, null);
    }

    /** Sucesso com ressalva: {@code warning} (SIEG_COUNT_FAILED, XML_PARSE_ERRORS, SEFAZ_CONSUMO_INDEVIDO) e a contagem. */
    public void success(UUID companyId, CaptureStage stage, CaptureFailureCode warning, Integer warningCount) {
        if (repository == null || companyId == null) {
            return;
        }
        try {
            repository.upsertSuccess(UUID.randomUUID().toString(), companyId.toString(), stage.name(), now(),
                    warning == null ? null : warning.name(), warning == null ? null : warningCount);
        } catch (RuntimeException e) {
            log.warn("capture.stage.record.failed company={} stage={} outcome=SUCCESS: {}", companyId, stage,
                    e.getClass().getSimpleName());
        }
    }

    public void failure(UUID companyId, CaptureStage stage, CaptureFailureCode code, String providerStatus) {
        if (repository == null || companyId == null) {
            return;
        }
        CaptureFailureCode safe = code == null ? CaptureFailureCode.INTERNAL_ERROR : code;
        try {
            repository.upsertFailure(UUID.randomUUID().toString(), companyId.toString(), stage.name(), now(), safe.name(),
                    CaptureFailureClassifier.providerStatus(providerStatus));
            log.info("capture.stage.failed company={} stage={} code={}", companyId, stage, safe);
        } catch (RuntimeException e) {
            log.warn("capture.stage.record.failed company={} stage={} outcome=FAILURE code={}: {}", companyId, stage, safe,
                    e.getClass().getSimpleName());
        }
    }

    /**
     * A etapa não tem mais o que tentar (ex.: recuperação sem passo pendente): a falha aberta, se houver, vira
     * SUCCESS com o aviso {@code warning}, para não ficar COM_ERRO para sempre. Sem falha aberta, nada muda.
     */
    public void closeFailure(UUID companyId, CaptureStage stage, CaptureFailureCode warning) {
        if (repository == null || companyId == null) {
            return;
        }
        try {
            repository.closeFailure(companyId.toString(), stage.name(), now(), warning == null ? null : warning.name());
        } catch (RuntimeException e) {
            log.warn("capture.stage.record.failed company={} stage={} outcome=CLOSE: {}", companyId, stage,
                    e.getClass().getSimpleName());
        }
    }

    public void failure(UUID companyId, CaptureStage stage, CaptureFailureClassifier.Classified classified) {
        failure(companyId, stage, classified.code(), classified.providerStatus());
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
