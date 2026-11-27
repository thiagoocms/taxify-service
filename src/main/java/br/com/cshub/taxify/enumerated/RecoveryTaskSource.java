package br.com.cshub.taxify.enumerated;

/** De onde vem um pedaço da recuperação dos meses bloqueados (RecoveryTask). */
public enum RecoveryTaskSource {
    SIEG_NFE_RECEIVED, SIEG_NFE_ISSUED, SIEG_NFSE, EFISCO;

    public boolean sieg() {
        return this != EFISCO;
    }
}
