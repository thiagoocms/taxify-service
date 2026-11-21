package br.com.cshub.taxify.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Notes used to be unique by access key across ALL companies, so a note between two clients
 * of the firm (one's exit, the other's entry) could only be stored once. The entity now
 * declares (company_id, access_key) - but Hibernate's ddl-auto=update never drops the old
 * constraint, so this does it once on startup (MySQL): drops any unique index made of
 * access_key alone and creates the composite one if missing. Idempotent; logs and moves on
 * if the database doesn't cooperate, the app keeps working with the old rule.
 */
@Component
@Order(0)
public class FiscalXmlDocumentKeyMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FiscalXmlDocumentKeyMigration.class);
    private static final String TABLE = "fiscal_xml_documents";
    private static final String COMPOSITE = "uk_fiscal_xml_document_company_key";

    private final JdbcTemplate jdbcTemplate;

    public FiscalXmlDocumentKeyMigration(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            List<String> accessKeyOnly = jdbcTemplate.queryForList("""
                    SELECT index_name FROM information_schema.statistics
                     WHERE table_schema = DATABASE() AND table_name = ? AND non_unique = 0
                     GROUP BY index_name
                    HAVING COUNT(*) = 1 AND MAX(column_name) = 'access_key'
                    """, String.class, TABLE);
            for (String index : accessKeyOnly) {
                jdbcTemplate.execute("ALTER TABLE " + TABLE + " DROP INDEX `" + index.replace("`", "") + "`");
                log.info("Dropped unique index {} (access_key only) from {}", index, TABLE);
            }
            Integer composite = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.statistics
                     WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?
                    """, Integer.class, TABLE, COMPOSITE);
            if (composite == null || composite == 0) {
                jdbcTemplate.execute("ALTER TABLE " + TABLE + " ADD CONSTRAINT " + COMPOSITE + " UNIQUE (company_id, access_key)");
                log.info("Created unique constraint {} (company_id, access_key) on {}", COMPOSITE, TABLE);
            }
        } catch (DataAccessException e) {
            log.warn("Could not migrate the unique key of {} - notes stay unique by access key only for now: {}", TABLE, e.getMessage());
        }
    }
}
