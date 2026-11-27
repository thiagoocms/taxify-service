package br.com.cshub.taxify.service.capture;

/**
 * As contas da fila SIEG com prioridade (CP-RN-12, CP-RN-15), puras: quantas vagas do lote vão para os pedidos de
 * "Sincronizar agora", quantas sobram para a fila normal, e a volta teórica mínima. Posição começa em 0.
 */
public final class CaptureQueueMath {

    private CaptureQueueMath() {
    }

    /**
     * Vagas de prioridade de uma rodada com lote {@code batchSize} e {@code open} pedidos abertos: as configuradas,
     * no máximo {@code batchSize − 1} (a fila normal nunca para) e no máximo os pedidos que há. Com lote 1, a
     * prioridade se alterna com a fila: {@code lastWasPriority} diz se a rodada anterior foi de pedido.
     */
    public static int prioritySlots(int batchSize, int configuredSlots, int open, boolean lastWasPriority) {
        int batch = Math.max(1, batchSize);
        if (open <= 0 || configuredSlots <= 0) {
            return 0;
        }
        if (batch == 1) {
            return lastWasPriority ? 0 : 1;
        }
        return Math.min(open, Math.min(configuredSlots, batch - 1));
    }

    /** Vagas de prioridade em uso no estado atual (sem a alternância: com lote 1 e pedidos abertos, metade das rodadas). */
    public static int slotsInUse(int batchSize, int configuredSlots, int open) {
        if (open <= 0 || configuredSlots <= 0) {
            return 0;
        }
        if (batchSize <= 1) {
            return 1;
        }
        return Math.min(open, Math.min(configuredSlots, batchSize - 1));
    }

    /**
     * Rodadas da fila normal por volta: {@code ceil(N / vagas_regulares)}. Com lote 1 e pedidos abertos, a fila normal
     * anda uma rodada sim, outra não (alternância) - conta em dobro.
     */
    public static long roundsForNormalPosition(long position, int batchSize, int slotsInUse) {
        int batch = Math.max(1, batchSize);
        if (batch == 1) {
            return slotsInUse > 0 ? position * 2 + 1 : position;
        }
        int regular = Math.max(1, batch - slotsInUse);
        return position / regular;
    }

    /** Rodadas até um pedido aberto na posição {@code position} da fila de prioridade. */
    public static long roundsForPriorityPosition(long position, int batchSize, int configuredSlots) {
        int batch = Math.max(1, batchSize);
        if (batch == 1) {
            return position * 2;
        }
        int slots = Math.max(1, Math.min(Math.max(1, configuredSlots), batch - 1));
        return position / slots;
    }

    /** Volta teórica mínima em rodadas: {@code ceil(N / vagas_regulares)} (com lote 1 e pedidos, em dobro). */
    public static long theoreticalLapRounds(long companies, int batchSize, int slotsInUse) {
        if (companies <= 0) {
            return 0;
        }
        int batch = Math.max(1, batchSize);
        if (batch == 1) {
            return slotsInUse > 0 ? companies * 2 : companies;
        }
        int regular = Math.max(1, batch - slotsInUse);
        return (companies + regular - 1) / regular;
    }
}
