package db.migration;

/**
 * Pendências: every read of the screen (list, counters) filters by the companies of the portfolio and the status -
 * the old index (status, severity) left MySQL scanning every pendency of the status for each request.
 */
public class V54__PendenciesCompanyStatusIndex extends TableMigration {

    public V54__PendenciesCompanyStatusIndex() {
        super("pendencies");

        index("idx_pendencies_company_status", "company_id", "status", "deleted");
    }
}
