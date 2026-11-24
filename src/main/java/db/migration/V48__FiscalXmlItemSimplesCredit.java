package db.migration;

/**
 * Itens das notas: o crédito de ICMS que o fornecedor do Simples Nacional transfere (pCredSN e
 * vCredICMSSN - LC 123/2006, art. 23, § 1º), usado no cálculo da Fronteira PE. Nulo nos itens já
 * importados até o backfill reler o XML (versão 6 do parser).
 */
public class V48__FiscalXmlItemSimplesCredit extends TableMigration {

    public V48__FiscalXmlItemSimplesCredit() {
        super("fiscal_xml_document_items");

        column("aliquota_credito_sn", "decimal(7,4)");
        column("valor_credito_sn", "decimal(19,2)");
    }
}
