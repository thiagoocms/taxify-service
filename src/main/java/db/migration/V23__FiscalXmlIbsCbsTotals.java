package db.migration;

/**
 * Notas: totais de IBS/CBS (IBSCBSTot) e a versão do parser que preencheu os campos -
 * nula nas notas já importadas, que é o que o FiscalXmlTaxDetailBackfill procura.
 */
public class V23__FiscalXmlIbsCbsTotals extends TableMigration {

    public V23__FiscalXmlIbsCbsTotals() {
        super("fiscal_xml_documents");

        column("total_ibscbs_base_calculo", "decimal(19,2)");
        column("total_valor_ibs_uf", "decimal(19,2)");
        column("total_valor_ibs_mun", "decimal(19,2)");
        column("total_valor_ibs", "decimal(19,2)");
        column("total_valor_cbs", "decimal(19,2)");
        column("metadata_version", "integer");

        index("idx_fiscal_xml_documents_metadata_version", "metadata_version");
    }
}
