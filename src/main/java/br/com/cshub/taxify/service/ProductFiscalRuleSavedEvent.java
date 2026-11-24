package br.com.cshub.taxify.service;

import java.util.UUID;

/**
 * A product fiscal rule was created or changed: the company's notes with that product get rectified
 * right after the save (see ProductFiscalRuleRectificationListener) instead of waiting for the job.
 *
 * @param documentoFornecedor the supplier the rule is limited to; null = any supplier
 */
public record ProductFiscalRuleSavedEvent(UUID companyId, String codigoProduto, String documentoFornecedor) {
}
