package db.migration;

/** Fronteira PE: o PDF do extrato fica guardado (no storage dos XMLs) para abrir pelo Taxify - aqui onde ele está. */
public class V52__EfiscoExtractPdf extends TableMigration {

    public V52__EfiscoExtractPdf() {
        super("efisco_extracts");

        column("pdf_path", "varchar(500)");
    }
}
