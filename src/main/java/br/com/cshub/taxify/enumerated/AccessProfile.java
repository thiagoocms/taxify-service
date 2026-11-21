package br.com.cshub.taxify.enumerated;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import static br.com.cshub.taxify.enumerated.Permission.BILLING_MANAGE;
import static br.com.cshub.taxify.enumerated.Permission.BILLING_SYNC;
import static br.com.cshub.taxify.enumerated.Permission.BILLING_VIEW;
import static br.com.cshub.taxify.enumerated.Permission.COMPANIES_VIEW;
import static br.com.cshub.taxify.enumerated.Permission.FISCAL_RULES_MANAGE;
import static br.com.cshub.taxify.enumerated.Permission.FISCAL_RULES_VIEW;
import static br.com.cshub.taxify.enumerated.Permission.INVOICES_AUDIT;
import static br.com.cshub.taxify.enumerated.Permission.INVOICES_RECTIFY;
import static br.com.cshub.taxify.enumerated.Permission.INVOICES_SYNC;
import static br.com.cshub.taxify.enumerated.Permission.INVOICES_VIEW;
import static br.com.cshub.taxify.enumerated.Permission.PLANS_VIEW;
import static br.com.cshub.taxify.enumerated.Permission.SUBSCRIPTIONS_MANAGE;
import static br.com.cshub.taxify.enumerated.Permission.SUBSCRIPTIONS_VIEW;
import static br.com.cshub.taxify.enumerated.Permission.TAX_ASSESSMENT_CLOSE;
import static br.com.cshub.taxify.enumerated.Permission.TAX_ASSESSMENT_VIEW;

/**
 * Fixed access profiles of the firm's team (an employee can have several - permissions add
 * up). They only matter for non-ADMIN users: an ADMIN is a special type that can do
 * everything (see {@link #permissionsOf}). New modules add their permissions here.
 */
public enum AccessProfile {

    /** Notas, SIEG, auditoria, retificação e regras fiscais. */
    FISCAL(EnumSet.of(COMPANIES_VIEW, INVOICES_VIEW, INVOICES_SYNC, INVOICES_RECTIFY, INVOICES_AUDIT,
            FISCAL_RULES_VIEW, FISCAL_RULES_MANAGE, TAX_ASSESSMENT_VIEW, TAX_ASSESSMENT_CLOSE)),

    /** Contábil: consulta notas e regras fiscais das empresas. */
    ACCOUNTING(EnumSet.of(COMPANIES_VIEW, INVOICES_VIEW, FISCAL_RULES_VIEW, TAX_ASSESSMENT_VIEW)),

    /** Planos, assinaturas, contratos, honorários e cobranças. */
    FINANCIAL(EnumSet.of(COMPANIES_VIEW, PLANS_VIEW, SUBSCRIPTIONS_VIEW, SUBSCRIPTIONS_MANAGE, BILLING_VIEW, BILLING_SYNC, BILLING_MANAGE)),

    /** Departamento pessoal - por enquanto só a carteira de empresas; ganha permissões com os módulos de DP. */
    PAYROLL(EnumSet.of(COMPANIES_VIEW)),

    /** Gestão: enxerga tudo, sem alterar nada. */
    MANAGEMENT(EnumSet.of(COMPANIES_VIEW, INVOICES_VIEW, FISCAL_RULES_VIEW, TAX_ASSESSMENT_VIEW, PLANS_VIEW, SUBSCRIPTIONS_VIEW, BILLING_VIEW));

    private final Set<Permission> permissions;

    AccessProfile(Set<Permission> permissions) {
        this.permissions = Collections.unmodifiableSet(permissions);
    }

    public Set<Permission> permissions() {
        return permissions;
    }

    /** Everything a user can do: all permissions for ADMIN, otherwise the union of their profiles'. */
    public static Set<Permission> permissionsOf(UserProfileEnum type, Collection<AccessProfile> profiles) {
        if (type == UserProfileEnum.ADMIN) {
            return EnumSet.allOf(Permission.class);
        }
        Set<Permission> union = EnumSet.noneOf(Permission.class);
        if (profiles != null) {
            profiles.forEach(profile -> union.addAll(profile.permissions));
        }
        return union;
    }
}
