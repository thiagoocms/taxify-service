package br.com.cshub.taxify.dto.user_company;

public record UserCompanyFilterDTO(
        String userId,
        String companyId,
        String role,
        Boolean deleted
) {
}
