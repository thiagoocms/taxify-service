package br.com.cshub.taxify.dto.contaazul;

import jakarta.validation.constraints.NotBlank;

/** What Conta Azul appended to the redirect URI, relayed by the front-end. */
public record ContaAzulCallbackDTO(@NotBlank String code, @NotBlank String state) {
}
