package br.com.cshub.taxify.dto.signup;

import java.time.LocalDate;

/** O teste começou: vale até 23:59:59 (Recife) de {@code trialEndsOn}. */
public record SignupVerifyResponseDTO(String status, LocalDate trialEndsOn) {
}
