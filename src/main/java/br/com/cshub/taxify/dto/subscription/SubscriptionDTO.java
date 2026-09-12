package br.com.cshub.taxify.dto.subscription;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record SubscriptionDTO(
        String id,
        @NotBlank
        String companyId,
        @NotBlank
        String planId,
        @NotNull
        LocalDate startDate,
        @NotNull
        LocalDate endDate,
        Boolean deleted
) {
}
