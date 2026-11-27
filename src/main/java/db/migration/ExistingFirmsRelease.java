package db.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * F1-RN-13: cria a {@code firm_subscription} de cada escritório não excluído que ainda não tem uma - ACTIVE, sem
 * {@code access_until}, não revisada, marcada como migrada, com a nota "liberado na migração" - e o evento
 * RELEASED com {@code actor = system}. Idempotente: um escritório que já tem a linha é deixado como está, então rodar
 * de novo não cria nada.
 */
public class ExistingFirmsRelease {

    static final String NOTE = "liberado na migração";
    static final String ACTOR = "system";
    private static final Logger log = LoggerFactory.getLogger(ExistingFirmsRelease.class);

    private final JdbcTemplate jdbc;

    public ExistingFirmsRelease(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return how many firms got a row now */
    public int run(LocalDateTime now) {
        List<String> firms = jdbc.queryForList("""
                SELECT f.id FROM accounting_firms f
                 WHERE f.deleted = 0
                   AND NOT EXISTS (SELECT 1 FROM firm_subscriptions s WHERE s.accounting_firm_id = f.id)
                """, String.class);
        Timestamp at = Timestamp.valueOf(now);
        for (String firmId : firms) {
            jdbc.update("""
                    INSERT INTO firm_subscriptions (id, accounting_firm_id, status, migrated, source, notes, deleted,
                                                    created_by, created_date)
                    VALUES (?, ?, 'ACTIVE', 1, 'MANUAL', ?, 0, ?, ?)
                    """, UUID.randomUUID().toString(), firmId, NOTE, ACTOR, at);
            jdbc.update("""
                    INSERT INTO firm_subscription_events (id, accounting_firm_id, type, before_json, after_json, reason,
                                                          actor, created_date)
                    VALUES (?, ?, 'RELEASED', NULL, ?, ?, ?, ?)
                    """, UUID.randomUUID().toString(), firmId,
                    "{\"status\":\"ACTIVE\",\"accessUntil\":null,\"migrated\":true}", NOTE, ACTOR, at);
        }
        if (!firms.isEmpty()) {
            log.info("Firm access: {} existing firms released without an end date, waiting for review", firms.size());
        }
        return firms.size();
    }
}
