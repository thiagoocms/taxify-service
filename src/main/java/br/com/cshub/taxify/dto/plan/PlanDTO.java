package br.com.cshub.taxify.dto.plan;

import br.com.cshub.taxify.enumerated.PlanServiceEnum;
import jakarta.validation.constraints.NotBlank;

import java.util.Set;

public record PlanDTO(
        String id,
        @NotBlank
        String name,
        String description,
        Set<PlanServiceEnum> services,
        Boolean deleted
) {
}
