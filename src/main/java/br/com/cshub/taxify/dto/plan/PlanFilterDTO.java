package br.com.cshub.taxify.dto.plan;

public record PlanFilterDTO(
        String id,
        String name,
        Boolean deleted
) {
}
