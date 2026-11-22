package db.migration;

/**
 * Itens das notas: detalhe tributário que a conferência precisa (origem, CSOSN, redução de
 * base, ICMS-ST, base/alíquota de IPI/PIS/COFINS, CEST, cBenef) e o grupo IBS/CBS da
 * Reforma Tributária. Tudo nulo nos itens já importados - FiscalXmlTaxDetailBackfill relê
 * os XMLs e preenche.
 */
public class V22__FiscalXmlItemTaxDetail extends TableMigration {

    public V22__FiscalXmlItemTaxDetail() {
        super("fiscal_xml_document_items");

        // FiscalItemTaxDetail
        column("cest", "varchar(10)");
        column("codigo_beneficio", "varchar(10)");
        column("origem_mercadoria", "varchar(1)");
        column("csosn", "varchar(4)");
        column("modalidade_bc_icms", "varchar(1)");
        column("reducao_bc_icms", "decimal(7,4)");
        column("valor_icms_desonerado", "decimal(19,2)");
        column("modalidade_bc_icms_st", "varchar(1)");
        column("mva_icms_st", "decimal(7,4)");
        column("base_calculo_icms_st", "decimal(19,2)");
        column("aliquota_icms_st", "decimal(7,4)");
        column("valor_icms_st", "decimal(19,2)");
        column("base_calculo_ipi", "decimal(19,2)");
        column("aliquota_ipi", "decimal(7,4)");
        column("base_calculo_pis", "decimal(19,2)");
        column("aliquota_pis", "decimal(7,4)");
        column("base_calculo_cofins", "decimal(19,2)");
        column("aliquota_cofins", "decimal(7,4)");

        // FiscalItemIbsCbs
        column("ibscbs_cst", "varchar(3)");
        column("ibscbs_class_trib", "varchar(6)");
        column("ibscbs_base_calculo", "decimal(19,2)");
        column("ibs_uf_aliquota", "decimal(7,4)");
        column("ibs_uf_reducao_aliquota", "decimal(7,4)");
        column("ibs_uf_valor", "decimal(19,2)");
        column("ibs_mun_aliquota", "decimal(7,4)");
        column("ibs_mun_reducao_aliquota", "decimal(7,4)");
        column("ibs_mun_valor", "decimal(19,2)");
        column("ibs_valor", "decimal(19,2)");
        column("cbs_aliquota", "decimal(7,4)");
        column("cbs_reducao_aliquota", "decimal(7,4)");
        column("cbs_valor", "decimal(19,2)");
    }
}
