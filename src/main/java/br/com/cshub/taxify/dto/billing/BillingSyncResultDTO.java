package br.com.cshub.taxify.dto.billing;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @param companies              companies of the firm looked at
 * @param linked                 companies linked to a provider customer (after this run)
 * @param newlyLinked            of those, linked in this run (matched by CNPJ/CPF)
 * @param unmatched              companies with no customer of the same document in the provider
 * @param subscriptionsUpdated   current subscriptions that got contract/fee data
 * @param withoutSubscription    linked companies with no current subscription in Taxify (nothing to attach the contract to)
 * @param chargesSynced          charges created or updated
 * @param warnings               per-company problems worth a look (several active contracts, none...)
 * @param errors                 per-company failures - the rest of the run went on
 */
public record BillingSyncResultDTO(
        LocalDateTime startedAt,
        int companies,
        int linked,
        int newlyLinked,
        List<String> unmatched,
        int subscriptionsUpdated,
        List<String> withoutSubscription,
        int chargesSynced,
        List<String> warnings,
        List<String> errors
) {
}
