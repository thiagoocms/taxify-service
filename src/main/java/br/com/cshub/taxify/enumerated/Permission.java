package br.com.cshub.taxify.enumerated;

/**
 * What a user can do, grouped by module. Endpoints and screens check permissions - never
 * profile names - so changing what a profile includes ({@link AccessProfile}) is a one-line
 * change. ADMIN users hold every permission plus the "ADMIN" authority itself, which still
 * guards firm-level administration (users, integrations, firm settings).
 * <p>
 * Every permission is limited by the user's portfolio: non-admins only ever see the
 * companies linked to them (UserCompany), whatever their permissions.
 */
public enum Permission {

    // ---- Cadastro
    COMPANIES_VIEW(Module.REGISTRY),

    // ---- Fiscal
    INVOICES_VIEW(Module.FISCAL),
    INVOICES_SYNC(Module.FISCAL),
    INVOICES_RECTIFY(Module.FISCAL),
    INVOICES_AUDIT(Module.FISCAL),
    FISCAL_RULES_VIEW(Module.FISCAL),
    FISCAL_RULES_MANAGE(Module.FISCAL),
    /** Apuração do período: faturamento, RBT12 e estimativa de impostos. */
    TAX_ASSESSMENT_VIEW(Module.FISCAL),
    /** Fechar/reabrir o mês e registrar a guia. */
    TAX_ASSESSMENT_CLOSE(Module.FISCAL),

    // ---- Financeiro
    PLANS_VIEW(Module.FINANCIAL),
    SUBSCRIPTIONS_VIEW(Module.FINANCIAL),
    SUBSCRIPTIONS_MANAGE(Module.FINANCIAL),
    BILLING_VIEW(Module.FINANCIAL),
    BILLING_SYNC(Module.FINANCIAL),
    /** Lançar/editar cobranças manuais e corrigir o vínculo da empresa com o provedor. */
    BILLING_MANAGE(Module.FINANCIAL);

    public enum Module {
        REGISTRY,
        FISCAL,
        FINANCIAL
    }

    private final Module module;

    Permission(Module module) {
        this.module = module;
    }

    public Module module() {
        return module;
    }
}
