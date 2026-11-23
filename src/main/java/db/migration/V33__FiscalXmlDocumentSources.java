package db.migration;

/**
 * Em que fonte cada nota já foi vista: o confronto SIEG × SEFAZ aponta a chave que está numa
 * fonte e não na outra. Notas anteriores ficam sem data (fora do confronto) até uma fonte trazê-las de novo.
 */
public class V33__FiscalXmlDocumentSources extends TableMigration {

    public V33__FiscalXmlDocumentSources() {
        super("fiscal_xml_documents");

        column("found_in_sieg_at", "datetime(6)");
        column("found_in_sefaz_at", "datetime(6)");
    }
}
