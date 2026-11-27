package br.com.cshub.taxify.dto.firmaccess;

/**
 * Corpo de {@code PUT /platform/firms/{id}/contact} (F1-RN-13): os dados do escritório que os migrados não têm.
 * Campo nulo = mantém o atual; cada campo enviado passa pelas mesmas validações do cadastro.
 */
public record FirmContactUpdateDTO(String documentNumber, String billingEmail, String contactPhone) {
}
