package br.com.cshub.taxify.enumerated;

/** Eventos da trilha da assinatura do escritório (F1-RN-18). */
public enum FirmEventType {
    SIGNUP,
    EMAIL_VERIFIED,
    TRIAL_STARTED,
    TRIAL_EXTENDED,
    CONVERTED_TO_TRIAL,
    RELEASED,
    REVIEWED,
    SUSPENDED,
    REACTIVATED,
    BLOCKED,
    CANCELED,
    /** CNPJ, e-mail financeiro ou telefone do escritório preenchidos pelo admin da plataforma (F1-RN-13). */
    CONTACT_UPDATED,
    /** Recuperação dos meses bloqueados agendada na reativação. */
    RECOVERY_SCHEDULED,
    /** Recuperação dos meses bloqueados concluída (os meses e as fontes no "depois"). */
    RECOVERY_DONE,
    /** Cadastro não verificado apagado pelo expurgo (F1-RN-03) - sem dado pessoal. */
    UNVERIFIED_PURGED
}
