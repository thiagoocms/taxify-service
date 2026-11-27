package br.com.cshub.taxify.enumerated;

/** Situação do resumo diário de um usuário (AV-RN-09, AV-RN-10). */
public enum PendencyAlertDigestStatus {
    /** Reservado por uma rodada (a chave única impede outra instância); se a aplicação cair, vira FAILED na seguinte. */
    SENDING,
    SENT,
    /** O envio falhou: nada fica marcado como avisado, e as pendências vão no próximo dia útil. */
    FAILED,
    /** Acima do teto global do dia: nada marcado, e o usuário vem primeiro na próxima rodada. */
    CAPPED
}
