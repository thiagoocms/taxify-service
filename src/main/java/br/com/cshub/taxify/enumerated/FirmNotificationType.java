package br.com.cshub.taxify.enumerated;

/** Avisos de fim do teste por e-mail (F1-RN-16): 3 dias antes, 1 dia antes e no último dia. */
public enum FirmNotificationType {
    TRIAL_D3(3),
    TRIAL_D1(1),
    TRIAL_D0(0);

    private final int daysBefore;

    FirmNotificationType(int daysBefore) {
        this.daysBefore = daysBefore;
    }

    public int daysBefore() {
        return daysBefore;
    }
}
