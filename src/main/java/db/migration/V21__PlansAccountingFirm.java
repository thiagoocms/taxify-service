package db.migration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Plans belong to a firm (they were one catalog shared by every firm). Existing plans go to
 * the firm whose companies subscribe to them; a plan used by several firms is copied, one
 * copy per firm, and each firm's subscriptions point to its copy. Unused plans go to the only
 * firm when there is just one - otherwise they stay without a firm (hidden) and are logged.
 */
public class V21__PlansAccountingFirm extends TableMigration {

    public V21__PlansAccountingFirm() {
        super("plans");

        column("accounting_firm_id", "varchar(36)");

        index("idx_plans_accounting_firm", "accounting_firm_id");
        foreignKey("fk_plans_accounting_firm", "accounting_firm_id", "accounting_firms");
    }

    @Override
    protected void beforeKeys() {
        Map<String, List<String>> firmsByPlan = new LinkedHashMap<>();
        jdbc.query("SELECT DISTINCT s.plan_id, c.accounting_firm_id FROM subscriptions s"
                        + " JOIN companies c ON c.id = s.company_id"
                        + " JOIN plans p ON p.id = s.plan_id WHERE p.accounting_firm_id IS NULL ORDER BY s.plan_id",
                row -> {
                    firmsByPlan.computeIfAbsent(row.getString(1), key -> new ArrayList<>()).add(row.getString(2));
                });

        for (Map.Entry<String, List<String>> entry : firmsByPlan.entrySet()) {
            String planId = entry.getKey();
            List<String> firms = entry.getValue();
            jdbc.update("UPDATE plans SET accounting_firm_id = ? WHERE id = ?", firms.getFirst(), planId);
            for (String firm : firms.subList(1, firms.size())) {
                String copy = copyPlan(planId, firm);
                int moved = jdbc.update("UPDATE subscriptions s JOIN companies c ON c.id = s.company_id"
                        + " SET s.plan_id = ? WHERE s.plan_id = ? AND c.accounting_firm_id = ?", copy, planId, firm);
                log.info("Plan {} was shared - copied as {} for firm {} ({} subscriptions)", planId, copy, firm, moved);
            }
        }

        List<String> firms = jdbc.queryForList("SELECT id FROM accounting_firms", String.class);
        int unused = count("SELECT COUNT(*) FROM plans WHERE accounting_firm_id IS NULL");
        if (unused > 0 && firms.size() == 1) {
            jdbc.update("UPDATE plans SET accounting_firm_id = ? WHERE accounting_firm_id IS NULL", firms.getFirst());
            log.info("{} unused plans assigned to the only firm", unused);
        } else if (unused > 0) {
            log.warn("{} plans without subscriptions could not be assigned to a firm - they stay hidden;"
                    + " set plans.accounting_firm_id by hand to show them again", unused);
        }
    }

    /** Copies the plan row (every column but the id and the firm) and its services. */
    private String copyPlan(String planId, String firmId) {
        String copyId = UUID.randomUUID().toString();
        List<String> columns = jdbc.queryForList("SELECT column_name FROM information_schema.columns"
                + " WHERE table_schema = DATABASE() AND table_name = 'plans' AND column_name NOT IN ('id', 'accounting_firm_id')"
                + " ORDER BY ordinal_position", String.class);
        String list = String.join(", ", columns);
        jdbc.update("INSERT INTO plans (id, accounting_firm_id, " + list + ") SELECT ?, ?, " + list + " FROM plans WHERE id = ?",
                copyId, firmId, planId);
        jdbc.update("INSERT INTO plan_services (plan_id, service) SELECT ?, service FROM plan_services WHERE plan_id = ?",
                copyId, planId);
        return copyId;
    }
}
