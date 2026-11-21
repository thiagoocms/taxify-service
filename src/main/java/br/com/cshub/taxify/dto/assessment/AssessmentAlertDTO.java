package br.com.cshub.taxify.dto.assessment;

/** Something the fiscal team should look at for the period (limit, missing notes, missing registration). */
public record AssessmentAlertDTO(Level level, String code, String message) {

    public enum Level {
        INFO,
        WARNING,
        DANGER
    }
}
