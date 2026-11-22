package db.migration;

/**
 * Itens das notas: indica se o grupo IBS/CBS tem diferimento ou devolução de tributo
 * (gDif/gDevTrib) - nesse caso o valor não é base x alíquota e a conferência do cálculo
 * não se aplica. Nulo nos itens já importados até o backfill reler o XML.
 */
public class V25__FiscalXmlItemIbsCbsAdjustment extends TableMigration {

    public V25__FiscalXmlItemIbsCbsAdjustment() {
        super("fiscal_xml_document_items");

        column("ibscbs_diferimento_devolucao", "bit");
    }
}
