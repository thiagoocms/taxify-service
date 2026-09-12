package br.com.cshub.taxify.dto.subscription;

public record SubscriptionFilterDTO(
        String id,
        String companyId,
        String planId,
        Boolean deleted
) {
}
