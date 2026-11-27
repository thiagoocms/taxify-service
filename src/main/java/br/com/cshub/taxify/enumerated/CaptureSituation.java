package br.com.cshub.taxify.enumerated;

/**
 * Situação de um canal ou da empresa no painel da captura (CP-RN-06 a CP-RN-08). {@link #severity()} é a ordem da
 * "pior situação" da CP-RN-08 (maior = pior) e a ordem padrão da tabela (CP-RN-14).
 */
public enum CaptureSituation {
    EM_DIA(0),
    PENDENTE(1),
    ATRASADA(2),
    NUNCA_TENTADA(3),
    COM_ERRO(4),
    FORA_DO_PLANO(-1),
    BLOQUEADA(5);

    private final int severity;

    CaptureSituation(int severity) {
        this.severity = severity;
    }

    /** COM_ERRO > NUNCA_TENTADA > ATRASADA > PENDENTE > EM_DIA; BLOQUEADA no topo, FORA_DO_PLANO no fim. */
    public int severity() {
        return severity;
    }

    /** Pendente, atrasada ou com erro - o filtro por tipo (CP-RN-13). */
    public boolean needsAttention() {
        return this == PENDENTE || this == ATRASADA || this == COM_ERRO;
    }
}
