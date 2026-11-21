package db.migration;

/** Itens das notas. */
public class V17__FiscalXmlDocumentItems extends TableMigration {

    public V17__FiscalXmlDocumentItems() {
        super("fiscal_xml_document_items");

        column("id", "varchar(36) not null");
        column("aliquota_icms", "decimal(7,4)");
        column("base_calculo_icms", "decimal(19,2)");
        column("numero_item", "integer");
        column("quantidade_comercial", "decimal(19,4)");
        column("quantidade_tributavel", "decimal(19,4)");
        column("valor_cofins", "decimal(19,2)");
        column("valor_icms", "decimal(19,2)");
        column("valor_ipi", "decimal(19,2)");
        column("valor_pis", "decimal(19,2)");
        column("valor_total", "decimal(19,2)");
        column("valor_unitario_comercial", "decimal(19,4)");
        column("valor_unitario_tributavel", "decimal(19,4)");
        column("cst_cofins", "varchar(5)");
        column("cst_icms", "varchar(5)");
        column("cst_ipi", "varchar(5)");
        column("cst_pis", "varchar(5)");
        column("cfop", "varchar(10)");
        column("ncm", "varchar(10)");
        column("unidade_comercial", "varchar(10)");
        column("unidade_tributavel", "varchar(10)");
        column("codigo_barras", "varchar(20)");
        column("document_id", "varchar(36) not null");
        column("codigo", "varchar(60)");
        column("descricao", "varchar(500)");

        foreignKey("fk_fiscal_xml_document_items_document", "document_id", "fiscal_xml_documents");
    }
}
