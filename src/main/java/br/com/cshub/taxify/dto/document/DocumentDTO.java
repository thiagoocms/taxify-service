package br.com.cshub.taxify.dto.document;

import br.com.cshub.taxify.enumerated.DocumentTypeEnum;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record DocumentDTO(
        String id,
        @NotBlank
        String number,
        @NotNull
        DocumentTypeEnum type,
        boolean deleted
) {
}
