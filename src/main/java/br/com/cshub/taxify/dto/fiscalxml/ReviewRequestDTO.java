package br.com.cshub.taxify.dto.fiscalxml;

import br.com.cshub.taxify.enumerated.ReviewStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** Conferência of notes. {@code documentIds} only on the bulk endpoint; {@code note} required for DIVERGENT. */
public record ReviewRequestDTO(
        @Size(max = 500) List<UUID> documentIds,
        @NotNull ReviewStatus status,
        @Size(max = 500) String note
) {
}
