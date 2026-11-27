package br.com.cshub.taxify.dto.acessorias;

import java.util.List;

/**
 * Summary of one Acessórias import run.
 *
 * @param received             companies Acessórias returned for this run
 * @param skipped              companies left out, each with the reason (inactive and not yet in Taxify,
 *                             unsupported identifier, document already registered by another firm, deleted in Taxify...)
 * @param responsiblesNotFound responsáveis ("Name &lt;email&gt;") with no Taxify user of that email in the firm -
 *                             register them as users and run the import again to link them
 * @param skippedByLimit       free trial (F1-RN-10a): new active companies not created, and inactive ones that came
 *                             back "Ativa" but stayed inactive, because the trial's company limit was reached (each
 *                             one is also in {@code skipped})
 */
public record AcessoriasImportResultDTO(
        int received,
        int created,
        int updated,
        int unchanged,
        int linksCreated,
        List<String> skipped,
        List<String> responsiblesNotFound,
        List<String> errors,
        int skippedByLimit
) {
}
