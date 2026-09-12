package br.com.cshub.taxify.enumerated;

public enum InvoiceAuditStatus {
    OK,                   // Nota validada e correta
    NEEDS_RECTIFICATION,  // Precisa ser retificada pelo cliente/empresa
    PENDING_DOCUMENTATION,// Pendente de alguma informação ou documento complementar
    REJECTED,             // Nota inválida ou recusada pelo contado
    RECTIFIED              // Retificada automaticamente pela rotina (novo arquivo gerado a partir de uma regra)
    // New constants must be appended here, never inserted above - @Enumerated(ORDINAL) persists by position.
}
