package br.com.cshub.taxify.domain.company;

import org.springframework.security.core.GrantedAuthority;

public class CompanyAuthority implements GrantedAuthority {

    private final Company company;
    private final String role;

    public CompanyAuthority(Company company, String role) {
        this.company = company;
        this.role = role;
    }

    public Company getCompany() {
        return this.company;
    }

    public String getRole() {
        return this.role;
    }

    @Override
    public String getAuthority() {
        return "COMPANY_" + company.getId() + "_ROLE_" + role;
    }
}
