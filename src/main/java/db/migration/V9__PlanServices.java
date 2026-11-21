package db.migration;

/** Serviços incluídos em cada plano. */
public class V9__PlanServices extends TableMigration {

    public V9__PlanServices() {
        super("plan_services");

        column("plan_id", "varchar(36) not null");
        column("service", "varchar(40) not null");

        primaryKey("plan_id", "service");

        // stored as @Enumerated(ORDINAL) before - converted to the constant names
        ordinalEnum("service", "FISCAL_XML_IMPORT", "INVOICE_AUDIT", "SIEG_INTEGRATION", "MULTI_USER_ACCESS", "REPORTS");

        foreignKey("fk_plan_services_plan", "plan_id", "plans");
    }
}
