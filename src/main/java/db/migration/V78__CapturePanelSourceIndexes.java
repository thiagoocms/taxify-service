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
import java.util.Locale;
import java.util.Map;

/**
 * Painel da captura (spec 2026-11-27, CP-RN-14, passo 4): "última nota gravada por origem" das empresas da página -
 * {@code max(created_date)} por {@code (company_id, source_system)} - lida direto do índice, sem varrer as notas da
 * empresa.
 *
 * <p>{@code fiscal_xml_documents} é a maior tabela do banco: o índice é criado com DDL online
 * ({@code ALGORITHM=INPLACE, LOCK=NONE}), que o InnoDB aceita para índice secundário no MySQL 5.7 e no 8.x - leituras e
 * gravações seguem durante a criação; só há um lock de metadados curto no início e no fim. Se o servidor recusar o
 * modo online (motor diferente, versão sem suporte), o índice <b>não</b> é criado com cópia da tabela (isso travaria
 * as gravações da captura): fica o aviso no log, e o painel continua funcionando, só mais lento nesse passo. O mesmo
 * vale se o lock de metadados do início não sair em {@value #LOCK_WAIT_SECONDS} s (uma transação longa aberta na
 * tabela): {@code lock_wait_timeout} da sessão é baixado só durante o ALTER, para o deploy nunca ficar preso esperando
 * (e segurando as gravações que entrariam na fila atrás dele). Um índice que já existe com o mesmo nome ou as mesmas
 * colunas é aceito como está.
 */
public class V78__CapturePanelSourceIndexes extends BaseJavaMigration {

    private static final Logger log = LoggerFactory.getLogger(V78__CapturePanelSourceIndexes.class);
    static final int LOCK_WAIT_SECONDS = 30;

    private record Index(String table, String name, List<String> columns) {
    }

    static final List<Index> INDEXES = List.of(
            new Index("fiscal_xml_documents", "idx_fxd_company_source_created", List.of("company_id", "source_system", "created_date")),
            new Index("service_invoices", "idx_service_invoices_company_source_created",
                    List.of("company_id", "source_system", "created_date")));

    @Override
    public void migrate(Context context) {
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(context.getConnection(), true));
        for (Index index : INDEXES) {
            ensure(jdbc, index);
        }
    }

    private void ensure(JdbcTemplate jdbc, Index index) {
        Integer tables = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()"
                + " AND table_name = ?", Integer.class, index.table());
        if (tables == null || tables == 0) {
            log.warn("Index {} skipped: table {} missing", index.name(), index.table());
            return;
        }
        Map<String, List<String>> existing = new LinkedHashMap<>();
        jdbc.query("SELECT index_name, column_name FROM information_schema.statistics WHERE table_schema = DATABASE()"
                        + " AND table_name = ? ORDER BY index_name, seq_in_index",
                row -> {
                    // column_name is NULL for a functional index part (MySQL 8.0.13+): never one of ours
                    String column = row.getString(2);
                    existing.computeIfAbsent(row.getString(1), key -> new ArrayList<>())
                            .add(column == null ? "(expression)" : column.toLowerCase(Locale.ROOT));
                }, index.table());
        if (existing.containsKey(index.name()) || existing.containsValue(index.columns())) {
            log.info("Index {} already on {}", index.name(), index.table());
            return;
        }
        long started = System.currentTimeMillis();
        Integer previousLockWait = jdbc.queryForObject("SELECT @@SESSION.lock_wait_timeout", Integer.class);
        try {
            jdbc.execute("SET SESSION lock_wait_timeout = " + LOCK_WAIT_SECONDS);
            jdbc.execute("ALTER TABLE " + index.table() + " ADD INDEX " + index.name() + " ("
                    + String.join(", ", index.columns()) + "), ALGORITHM=INPLACE, LOCK=NONE");
            log.info("Created index {} on {} online in {} ms", index.name(), index.table(), System.currentTimeMillis() - started);
        } catch (DataAccessException e) {
            log.warn("capture.panel.index.not.created index={} table={}: online DDL refused or metadata lock not obtained in {} s"
                            + " - create it by hand off hours: ALTER TABLE {} ADD INDEX {} ({}), ALGORITHM=INPLACE, LOCK=NONE ({})",
                    index.name(), index.table(), LOCK_WAIT_SECONDS, index.table(), index.name(),
                    String.join(", ", index.columns()), e.getMostSpecificCause().getMessage());
        } finally {
            if (previousLockWait != null) {
                jdbc.execute("SET SESSION lock_wait_timeout = " + previousLockWait);
            }
        }
    }
}
