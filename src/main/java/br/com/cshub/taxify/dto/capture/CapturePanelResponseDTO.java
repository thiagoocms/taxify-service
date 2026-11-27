package br.com.cshub.taxify.dto.capture;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /capture-panel} e {@code GET /platform/capture-panel} (seção 8 da spec 2026-11-27): o resumo do escopo, a
 * saúde da fila SIEG e a página de empresas. Datas e horas em America/Recife, ISO 8601 com o fuso.
 */
public record CapturePanelResponseDTO(Summary summary, Queue queue, Page page) {

    /** Contagem por situação geral (sem os filtros de situação) e a última empresa atendida do escopo (CP-RN-12). */
    public record Summary(Map<String, Long> byStatus, OffsetDateTime lastAttemptAt) {
    }

    /**
     * @param health                RUNNING, STALLED ou DISABLED (FISCAL_XML_SYNC_ENABLED=false)
     * @param stalledSince          a última tentativa da plataforma, quando parada (só na visão da plataforma)
     * @param observedLapMinutes    agora − a tentativa mais antiga entre as empresas com direito (a idade da primeira da
     *                              fila); null se nenhuma foi tentada; null na visão do escritório
     * @param neverAttempted        empresas com direito nunca tentadas, no escopo da consulta (> 0 = "volta incompleta")
     * @param theoreticalLapMinutes ceil(N / vagas regulares) × intervalo do cron - "pelo menos" (null no escritório)
     * @param nextTickAt            próxima rodada do cron
     * @param intervalMinutes       intervalo entre duas rodadas
     * @param batchSize             empresas por rodada
     * @param prioritySlotsInUse    vagas da rodada tomadas por pedidos de "Sincronizar agora"
     * @param openPriorityRequests  pedidos abertos na plataforma (só na visão da plataforma; null no escritório)
     */
    public record Queue(String health, OffsetDateTime stalledSince, Long observedLapMinutes, int neverAttempted,
                        Long theoreticalLapMinutes, OffsetDateTime nextTickAt, Long intervalMinutes, int batchSize,
                        int prioritySlotsInUse, Integer openPriorityRequests) {
    }

    public record Page(List<CaptureCompanyDTO> content, long totalElements, int totalPages, int number, int size) {
    }
}
