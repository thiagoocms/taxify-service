package db.migration;

/**
 * Retenções federais da NF-e (total/retTrib) - preenchidas na importação e, para as notas
 * já guardadas, pela releitura dos XMLs (versão 5 do leitor).
 */
public class V39__FiscalXmlDocumentRetentions extends TableMigration {

    public V39__FiscalXmlDocumentRetentions() {
        super("fiscal_xml_documents");

        column("ret_pis", "decimal(15,2)");
        column("ret_cofins", "decimal(15,2)");
        column("ret_csll", "decimal(15,2)");
        column("ret_irrf", "decimal(15,2)");
        column("ret_inss", "decimal(15,2)");
    }
}
