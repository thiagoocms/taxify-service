package db.migration;

/**
 * Notas: os campos de cabeçalho que só a tela de detalhe e o DANFE mostram (tipo de
 * impressão/emissão, DV, processo e versão do emissor, país do emitente e do destinatário).
 * Com eles no banco, abrir a nota não precisa ler o XML do storage (S3). Nulos nas notas já
 * importadas até o FiscalXmlTaxDetailBackfill reler o XML (parser versão 4).
 */
public class V27__FiscalXmlHeaderExtras extends TableMigration {

    public V27__FiscalXmlHeaderExtras() {
        super("fiscal_xml_documents");

        column("ide_tipo_impressao", "varchar(5)");
        column("ide_tipo_emissao", "varchar(5)");
        column("ide_digito_verificador", "varchar(5)");
        column("ide_processo_emissao", "varchar(5)");
        column("ide_versao_processo_emissao", "varchar(30)");
        column("emit_codigo_pais", "varchar(10)");
        column("emit_pais", "varchar(60)");
        column("dest_codigo_pais", "varchar(10)");
        column("dest_pais", "varchar(60)");
    }
}
