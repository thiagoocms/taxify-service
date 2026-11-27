package br.com.cshub.taxify.enumerated;

/**
 * Uma etapa da captura que grava o próprio resultado em {@code capture_stage_status} (painel da captura, CP-RN-10).
 * As etapas SIEG são as do FiscalXmlSyncScheduler; SEFAZ_DIST e NFSE_ADN, as dos serviços de captura direta.
 */
public enum CaptureStage {
    SIEG_RECEIVED(CaptureChannel.SIEG),
    SIEG_ISSUED(CaptureChannel.SIEG),
    SIEG_EVENTS_RECEIVED(CaptureChannel.SIEG),
    SIEG_EVENTS_ISSUED(CaptureChannel.SIEG),
    SIEG_NFSE(CaptureChannel.SIEG),
    SIEG_RECOVERY(CaptureChannel.SIEG),
    SEFAZ_DIST(CaptureChannel.SEFAZ),
    NFSE_ADN(CaptureChannel.ADN);

    private final CaptureChannel channel;

    CaptureStage(CaptureChannel channel) {
        this.channel = channel;
    }

    public CaptureChannel channel() {
        return channel;
    }

    /** Etapa SIEG de um lado (RECEIVED ou ISSUED). */
    public static CaptureStage siegSide(boolean issued) {
        return issued ? SIEG_ISSUED : SIEG_RECEIVED;
    }

    public static CaptureStage siegEvents(boolean issued) {
        return issued ? SIEG_EVENTS_ISSUED : SIEG_EVENTS_RECEIVED;
    }
}
