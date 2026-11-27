package br.com.cshub.taxify.dto.signup;

/**
 * Para a tela de login: se o cadastro está aberto ({@code available}; quando false, o front esconde "Criar conta")
 * e o que a tela de cadastro mostra (versão e links dos termos, dias do teste, validade do link).
 */
public record SignupConfigDTO(boolean available, String termsVersion, String termsUrl, String privacyUrl, String dpaUrl,
                           int trialDays, int verificationHours) {
}
