package db.migration;

/**
 * Em que fonte cada NFS-e já foi vista (SIEG e Ambiente Nacional) - o confronto entre as duas.
 * Notas anteriores ficam sem data até uma fonte trazê-las de novo.
 */
public class V42__ServiceInvoiceSources extends TableMigration {

    public V42__ServiceInvoiceSources() {
        super("service_invoices");

        column("found_in_sieg_at", "datetime(6)");
        column("found_in_adn_at", "datetime(6)");
    }
}
