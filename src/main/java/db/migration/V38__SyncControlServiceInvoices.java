package db.migration;

/** Dia em que as NFS-e da empresa foram buscadas no SIEG pela última vez (uma vez por dia). */
public class V38__SyncControlServiceInvoices extends TableMigration {

    public V38__SyncControlServiceInvoices() {
        super("fiscal_xml_sync_control");

        column("service_invoices_checked_on", "date");
    }
}
