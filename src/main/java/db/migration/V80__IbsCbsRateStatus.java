package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Alíquotas de IBS/CBS por ano com status (spec 2026-11-27, F1a-0, AL-RN-01 e AL-RN-07). A tabela ganha o status, o
 * fundamento, o link do ato, a versão (concorrência otimista, AL-RN-04), quem mudou por último e quando (Recife) e as
 * colunas de auditoria do projeto. As três alíquotas passam a aceitar nulo (só {@code PENDING} grava nulo).
 *
 * <p>Tudo num {@code ALTER TABLE} só (só o que falta), com {@code lock_wait_timeout} da sessão baixado para
 * {@value #LOCK_WAIT_SECONDS} s durante o ALTER e devolvido no {@code finally}: uma transação longa aberta na tabela
 * (a conferência lendo as alíquotas) nunca deixa o deploy esperando sem fim nem enfileira as leituras atrás dele. Como
 * as colunas são obrigatórias para o esquema novo, um ALTER que não sai falha a migration com a mensagem
 * {@code ibs.cbs.rates.alter.failed} - o startup para com causa clara, sem nada aplicado (o ALTER é atômico).
 *
 * <p>O MySQL não tem DDL transacional: o Flyway grava essa falha no histórico com {@code success = 0}, e a subida
 * seguinte pararia no validate pedindo {@code flyway repair}. O callback {@code db/migration/beforeValidate.sql} apaga
 * esse registro de falha só desta versão (idempotente e atômica) antes da validação, então a próxima subida, com o
 * lock livre, roda a V80 de novo e completa sozinha. Falha de qualquer outra migration continua exigindo análise.
 *
 * <p>A linha de 2026 semeada pela V24 recebe {@code LAW}, o fundamento da AL-RN-07 e {@code version = 0}: a conferência
 * de 2026 continua idêntica. Idempotente ({@code WHERE ano = 2026 AND status IS NULL}). Tabela pequena (1 linha por ano).
 * Segura com a versão antiga rodando junto: as colunas novas são nulas ou têm padrão, e a antiga só lê as três alíquotas.
 */
public class V80__IbsCbsRateStatus extends BaseJavaMigration {

    private static final Logger log = LoggerFactory.getLogger(V80__IbsCbsRateStatus.class);
    static final int LOCK_WAIT_SECONDS = 30;
    static final String SEED_LEGAL_BASIS = "LC 214/2025: alíquotas do ano-teste de 2026 (CBS 0,9%; IBS estadual 0,1%;"
            + " IBS municipal 0%). Artigo a completar pelo Fiscal.";
    static final String SEED_SOURCE_URL = "https://www.planalto.gov.br/ccivil_03/leis/lcp/lcp214.htm";

    private static final List<String> RATE_COLUMNS = List.of("aliquota_cbs", "aliquota_ibs_uf", "aliquota_ibs_mun");
    private static final Map<String, String> NEW_COLUMNS = new LinkedHashMap<>();

    static {
        NEW_COLUMNS.put("status", "varchar(12)");
        NEW_COLUMNS.put("legal_basis", "varchar(500)");
        NEW_COLUMNS.put("source_url", "varchar(300)");
        NEW_COLUMNS.put("version", "int not null default 0");
        NEW_COLUMNS.put("changed_by", "varchar(50)");
        NEW_COLUMNS.put("changed_at", "datetime(6)");
        NEW_COLUMNS.put("deleted", "bit not null default 0");
        NEW_COLUMNS.put("created_by", "varchar(50)");
        NEW_COLUMNS.put("created_date", "datetime(6)");
        NEW_COLUMNS.put("last_modified_by", "varchar(50)");
        NEW_COLUMNS.put("last_modified_date", "datetime(6)");
    }

    @Override
    public void migrate(Context context) {
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(context.getConnection(), true));
        alter(jdbc);
        seed(jdbc);
    }

    /** The missing columns and the nullable rates in one ALTER, under a short metadata lock wait. */
    private void alter(JdbcTemplate jdbc) {
        Map<String, String> columns = new LinkedHashMap<>();
        jdbc.query("SELECT column_name, is_nullable FROM information_schema.columns WHERE table_schema = DATABASE()"
                + " AND table_name = 'ibs_cbs_rates'", row -> {
                    columns.put(row.getString(1).toLowerCase(), row.getString(2));
                });
        List<String> changes = new ArrayList<>();
        NEW_COLUMNS.forEach((name, definition) -> {
            if (!columns.containsKey(name)) {
                changes.add("ADD COLUMN " + name + " " + definition);
            }
        });
        for (String rate : RATE_COLUMNS) {
            if ("NO".equals(columns.get(rate))) {
                changes.add("MODIFY " + rate + " decimal(7,4) NULL");
            }
        }
        if (changes.isEmpty()) {
            log.info("ibs_cbs_rates already has the F1a-0 columns");
            return;
        }
        Integer previousLockWait = jdbc.queryForObject("SELECT @@SESSION.lock_wait_timeout", Integer.class);
        long started = System.currentTimeMillis();
        try {
            jdbc.execute("SET SESSION lock_wait_timeout = " + LOCK_WAIT_SECONDS);
            jdbc.execute("ALTER TABLE ibs_cbs_rates " + String.join(", ", changes));
            log.info("ibs_cbs_rates altered ({} changes) in {} ms", changes.size(), System.currentTimeMillis() - started);
        } catch (DataAccessException e) {
            throw new IllegalStateException("ibs.cbs.rates.alter.failed: metadata lock on ibs_cbs_rates not obtained in "
                    + LOCK_WAIT_SECONDS + " s (a long transaction open on the table?) or ALTER refused - the deploy stops here"
                    + " and the next start tries again (" + e.getMostSpecificCause().getMessage() + ")", e);
        } finally {
            if (previousLockWait != null) {
                jdbc.execute("SET SESSION lock_wait_timeout = " + previousLockWait);
            }
        }
    }

    private void seed(JdbcTemplate jdbc) {
        int seeded = jdbc.update("UPDATE ibs_cbs_rates SET status = 'LAW', legal_basis = ?, source_url = ?, version = 0,"
                + " changed_by = 'system', created_by = COALESCE(created_by, 'system'), created_date = COALESCE(created_date, NOW(6))"
                + " WHERE ano = 2026 AND status IS NULL", SEED_LEGAL_BASIS, SEED_SOURCE_URL);
        if (seeded > 0) {
            log.info("ibs_cbs_rates: 2026 marked as LAW");
        }
        // any other row inserted by hand before this version: kept, but out of the engine until reviewed (AL-RN-02)
        int others = jdbc.update("UPDATE ibs_cbs_rates SET status = 'PENDING', created_by = COALESCE(created_by, 'system'),"
                + " created_date = COALESCE(created_date, NOW(6)) WHERE status IS NULL");
        if (others > 0) {
            log.warn("ibs_cbs_rates: {} rows without status marked as PENDING", others);
        }
    }
}
