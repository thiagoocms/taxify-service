package br.com.cshub.taxify.dto.signup;

/** Dados do escritório: CNPJ (com ou sem máscara), e-mail financeiro e telefone/WhatsApp. */
public record SignupFirmDTO(String name, String documentNumber, String billingEmail, String contactPhone) {
}
