package br.com.cshub.taxify.dto.reform;

/** AL-RN-05: the current or the next year (Recife) has no LAW rate. {@code type} is always {@code NO_LAW}. */
public record IbsCbsRateWarningDTO(int year, String type, String text) {

    public static final String NO_LAW = "NO_LAW";
}
