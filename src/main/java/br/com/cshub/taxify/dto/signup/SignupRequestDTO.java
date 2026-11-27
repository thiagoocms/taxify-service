package br.com.cshub.taxify.dto.signup;

/**
 * Corpo de {@code POST /signup} (F1-RN-01 a F1-RN-03, F1-RN-11). O pedido não tem campo de perfil: quem se cadastra é
 * sempre o ADMIN do escritório novo, e campos a mais (profile, accessProfiles...) são ignorados.
 */
public record SignupRequestDTO(SignupFirmDTO firm, SignupOwnerDTO user, String termsVersion) {
}
