package br.com.cshub.taxify.enumerated;

/** Layout an NFS-e came in (I-13). */
public enum NfseLayout {
    /** Padrão nacional (ADN): 50-digit access key. */
    NATIONAL,
    /** Municipal ABRASF layout: no access key, a synthetic one {@code ABRASF-<city>-<provider>-<number>} is built. */
    ABRASF;

    public static final String ABRASF_KEY_PREFIX = "ABRASF-";
    public static final int NATIONAL_KEY_LENGTH = 50;
}
