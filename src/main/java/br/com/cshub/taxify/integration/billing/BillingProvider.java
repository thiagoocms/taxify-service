package br.com.cshub.taxify.integration.billing;

import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.enumerated.BillingProviderType;

import java.util.List;
import java.util.Optional;

/**
 * Port to an external billing system (ERP / financial tool) where the firm keeps its
 * clients' contracts and fee charges. Taxify only speaks the neutral records below; each
 * system gets one implementation that translates its API (ContaAzulBillingProvider today).
 * Adding Omie, Asaas, Bling... = a new implementation, nothing else changes.
 */
public interface BillingProvider {

    BillingProviderType type();

    /** The firm authorized/configured this provider and calls can be made on its behalf. */
    boolean isConnected(AccountingFirm firm);

    /** The provider's customer with this CNPJ/CPF (digits only), if any. */
    Optional<ExternalCustomer> findCustomerByDocument(AccountingFirm firm, String document);

    /** Every contract (active or not) of one customer. */
    List<ExternalContract> listContracts(AccountingFirm firm, String customerId);

    /** Receivables matching the query - the fee charges. */
    List<ExternalCharge> listCharges(AccountingFirm firm, ChargeQuery query);

    /** Revenue categories, so the firm can pick which ones are fees. */
    List<ExternalCategory> listRevenueCategories(AccountingFirm firm);
}
