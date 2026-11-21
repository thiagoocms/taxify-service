package br.com.cshub.taxify.enumerated;

public enum InvoiceAuditStatus {
    OK,                   // Nota validada e correta
    NEEDS_RECTIFICATION,  // Precisa ser retificada pelo cliente/empresa
    PENDING_DOCUMENTATION,// Pendente de alguma informação ou documento complementar
    REJECTED,             // Nota inválida ou recusada pelo contado
    RECTIFIED,             // Retificada automaticamente pela rotina (novo arquivo gerado a partir de uma regra)
    RECTIFICATION_REVERTED,// Nenhuma regra diverge mais - a nota volta a usar o XML original
    DIVERGENT,             // Conferida com divergência (ver as observações)
    REVIEW_REOPENED,       // Conferência desfeita - a nota voltou para "a conferir"
    CANCELED               // Cancelada na SEFAZ (evento de cancelamento registrado)
}
