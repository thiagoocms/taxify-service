package br.com.cshub.taxify.dto.billing;

import jakarta.validation.constraints.NotBlank;

/** A CNPJ/CPF to look the customer up by in the provider - e.g. the owner's CPF when the client is registered that way. */
public record ExternalLinkLookupDTO(@NotBlank String document) {
}
