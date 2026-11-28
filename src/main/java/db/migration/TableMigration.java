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
 * One table per migration, declared once and applied to whatever the database already has:
 * <ul>
 *   <li>table missing → created with every column and the primary key;</li>
 *   <li>table present → each column is checked: a missing one is added, and an enum column
 *       of the old kind (MySQL ENUM, or an ORDINAL number) is converted to its text form;</li>
 *   <li>then indexes, unique keys and foreign keys - each created only when missing. One that
 *       already exists under another name (Hibernate's generated names) is renamed for
 *       indexes/unique keys and accepted as is for foreign keys.</li>
 * </ul>
 * A unique key or foreign key the data doesn't allow yet (duplicates, orphans) is logged and
 * skipped instead of failing the deploy - the next run tries again.
 *
 * <p>Public so the helpers of the fiscal documents cutover (V86 to V89) declare their tables the same way.
 */
public abstract class TableMigration extends BaseJavaMigration {

    private record Column(String name, String definition) {
    }

    private record Index(String name, boolean unique, List<String> columns) {
    }

    private record ForeignKey(String name, String column, String referencedTable) {
    }

    private record Check(String name, String expression) {
    }

    private static final List<String> NUMERIC_TYPES = List.of("tinyint", "smallint", "mediumint", "int", "integer", "bigint");

    protected final Logger log = LoggerFactory.getLogger(getClass());
    private final String table;
    private final List<Column> columns = new ArrayList<>();
    private final Map<String, List<String>> ordinalEnums = new LinkedHashMap<>();
    private final List<Index> indexes = new ArrayList<>();
    private final List<ForeignKey> foreignKeys = new ArrayList<>();
    private final List<Check> checks = new ArrayList<>();
    private List<String> primaryKey = List.of("id");
    private String tableOptions = "";
    private Integer lockWaitSeconds;
    protected JdbcTemplate jdbc;

    protected TableMigration(String table) {
        this.table = table;
    }

    // ------------------------------------------------------------------ declaration

    protected final void column(String name, String definition) {
        columns.add(new Column(name, definition));
    }

    protected final void primaryKey(String... columnNames) {
        this.primaryKey = List.of(columnNames);
    }

    protected final void index(String name, String... columnNames) {
        indexes.add(new Index(name, false, List.of(columnNames)));
    }

    protected final void unique(String name, String... columnNames) {
        indexes.add(new Index(name, true, List.of(columnNames)));
    }

    protected final void foreignKey(String name, String column, String referencedTable) {
        foreignKeys.add(new ForeignKey(name, column, referencedTable));
    }

    /**
     * A short {@code lock_wait_timeout} for this migration's DDL (given back at the end): a table still in use makes it
     * fail after that many seconds instead of hanging the deploy. Unset = the session's value, as before.
     */
    protected final void lockWaitSeconds(int seconds) {
        this.lockWaitSeconds = seconds;
    }

    /** Extra table options of the CREATE (e.g. {@code ROW_FORMAT=DYNAMIC}); an existing table is left as it is. */
    protected final void tableOptions(String options) {
        this.tableOptions = options;
    }

    /**
     * A CHECK constraint: part of the CREATE, or added to an existing table that lacks it. Servers that don't keep
     * CHECK constraints (MySQL before 8.0.16 parses and ignores them) only get the CREATE clause.
     */
    protected final void check(String name, String expression) {
        checks.add(new Check(name, expression));
    }

    /** A column once stored as @Enumerated(ORDINAL): the constants in declaration order. */
    protected final void ordinalEnum(String column, String... constants) {
        ordinalEnums.put(column, List.of(constants));
    }

    /** Table-specific fixes after the columns and before the keys (e.g. dropping an obsolete index). */
    protected void beforeKeys() {
    }

    // ------------------------------------------------------------------ apply

    @Override
    public final void migrate(Context context) {
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(context.getConnection(), true));
        Integer previousLockWait = lockWaitSeconds == null ? null
                : jdbc.queryForObject("SELECT @@SESSION.lock_wait_timeout", Integer.class);
        try {
            if (lockWaitSeconds != null) {
                jdbc.execute("SET SESSION lock_wait_timeout = " + lockWaitSeconds);
            }
            if (tableExists(table)) {
                completeColumns();
            } else {
                createTable();
            }
            beforeKeys();
            indexes.forEach(this::ensureIndex);
            foreignKeys.forEach(this::ensureForeignKey);
            checks.forEach(this::ensureCheck);
        } finally {
            if (previousLockWait != null) {
                jdbc.execute("SET SESSION lock_wait_timeout = " + previousLockWait);
            }
        }
    }

    private void createTable() {
        StringBuilder sql = new StringBuilder("CREATE TABLE ").append(table).append(" (\n");
        for (Column column : columns) {
            sql.append("    ").append(column.name()).append(' ').append(column.definition()).append(",\n");
        }
        for (Check check : checks) {
            sql.append("    CONSTRAINT ").append(check.name()).append(" CHECK (").append(check.expression()).append("),\n");
        }
        sql.append("    PRIMARY KEY (").append(String.join(", ", primaryKey)).append(")\n) ENGINE=InnoDB");
        if (!tableOptions.isBlank()) {
            sql.append(' ').append(tableOptions);
        }
        jdbc.execute(sql.toString());
        log.info("Created table {}", table);
    }

    private void completeColumns() {
        boolean hasRows = count("SELECT COUNT(*) FROM (SELECT 1 FROM " + table + " LIMIT 1) t") > 0;
        for (Column column : columns) {
            String type = columnType(column.name());
            if (type == null) {
                addColumn(column, hasRows);
            } else {
                fixType(column, type);
            }
        }
    }

    /**
     * A NOT NULL column added to a table with rows needs a value for them: numbers/bits get 0,
     * anything else is added nullable (logged) - Hibernate never relied on those being NOT NULL.
     */
    private void addColumn(Column column, boolean hasRows) {
        String definition = column.definition();
        String lower = definition.toLowerCase(Locale.ROOT);
        if (hasRows && lower.contains("not null") && !lower.contains("default")) {
            if (lower.startsWith("bit") || lower.startsWith("int") || lower.startsWith("bigint") || lower.startsWith("decimal")) {
                definition = definition + " DEFAULT 0";
            } else {
                definition = definition.replaceAll("(?i)\\s+not null", "");
                log.warn("Column {}.{} added as nullable: the table already has rows", table, column.name());
            }
        }
        jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column.name() + " " + definition);
        log.info("Added column {}.{}", table, column.name());
    }

    /** Old enum storage → the declared VARCHAR: MySQL ENUM is converted as is, an ORDINAL number mapped to its name. */
    private void fixType(Column column, String actualType) {
        if (!column.definition().toLowerCase(Locale.ROOT).startsWith("varchar")) {
            return;
        }
        if ("enum".equals(actualType)) {
            jdbc.execute("ALTER TABLE " + table + " MODIFY " + column.name() + " " + column.definition());
            log.info("Converted {}.{} from ENUM to VARCHAR", table, column.name());
            return;
        }
        List<String> constants = ordinalEnums.get(column.name());
        if (constants != null && NUMERIC_TYPES.contains(actualType)) {
            jdbc.execute("ALTER TABLE " + table + " MODIFY " + column.name() + " " + column.definition());
            StringBuilder mapping = new StringBuilder("CASE " + column.name());
            for (int i = 0; i < constants.size(); i++) {
                mapping.append(" WHEN '").append(i).append("' THEN '").append(constants.get(i)).append("'");
            }
            mapping.append(" ELSE ").append(column.name()).append(" END");
            int rows = jdbc.update("UPDATE " + table + " SET " + column.name() + " = " + mapping);
            log.info("Converted {}.{} from ordinal to text ({} rows)", table, column.name(), rows);
        }
    }

    private void ensureIndex(Index index) {
        Map<String, List<String>> existing = indexesOf(index.unique());
        if (existing.containsKey(index.name())) {
            return;
        }
        for (Map.Entry<String, List<String>> entry : existing.entrySet()) {
            if (entry.getValue().equals(index.columns()) && !"PRIMARY".equals(entry.getKey())) {
                jdbc.execute("ALTER TABLE " + table + " RENAME INDEX `" + entry.getKey() + "` TO " + index.name());
                log.info("Renamed index {}.{} to {}", table, entry.getKey(), index.name());
                return;
            }
        }
        for (String column : index.columns()) {
            if (columnType(column) == null) {
                log.warn("Index {} skipped: column {}.{} missing", index.name(), table, column);
                return;
            }
        }
        String columnList = String.join(", ", index.columns());
        try {
            jdbc.execute(index.unique()
                    ? "ALTER TABLE " + table + " ADD CONSTRAINT " + index.name() + " UNIQUE (" + columnList + ")"
                    : "CREATE INDEX " + index.name() + " ON " + table + " (" + columnList + ")");
            log.info("Created {} {}", index.unique() ? "unique key" : "index", index.name());
        } catch (DataAccessException e) {
            log.warn("{} not created on {} ({}): {}", index.name(), table, columnList, e.getMostSpecificCause().getMessage());
        }
    }

    private void ensureForeignKey(ForeignKey fk) {
        int existing = count("SELECT COUNT(*) FROM information_schema.key_column_usage WHERE table_schema = DATABASE()"
                + " AND table_name = ? AND column_name = ? AND referenced_table_name = ?", table, fk.column(), fk.referencedTable());
        if (existing > 0) {
            return;
        }
        if (!tableExists(fk.referencedTable()) || columnType(fk.column()) == null) {
            log.warn("Foreign key {} skipped: {} or {}.{} missing", fk.name(), fk.referencedTable(), table, fk.column());
            return;
        }
        try {
            jdbc.execute("ALTER TABLE " + table + " ADD CONSTRAINT " + fk.name() + " FOREIGN KEY (" + fk.column() + ")"
                    + " REFERENCES " + fk.referencedTable() + " (id)");
            log.info("Created foreign key {}", fk.name());
        } catch (DataAccessException e) {
            log.warn("Foreign key {} not created: {}", fk.name(), e.getMostSpecificCause().getMessage());
        }
    }

    private void ensureCheck(Check check) {
        if (count("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'information_schema'"
                + " AND table_name = 'CHECK_CONSTRAINTS'") == 0) {
            return;
        }
        int existing = count("SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_schema = DATABASE()"
                + " AND table_name = ? AND constraint_name = ? AND constraint_type = 'CHECK'", table, check.name());
        if (existing > 0) {
            return;
        }
        jdbc.execute("ALTER TABLE " + table + " ADD CONSTRAINT " + check.name() + " CHECK (" + check.expression() + ")");
        log.info("Created check constraint {}", check.name());
    }

    // ------------------------------------------------------------------ information_schema

    protected boolean tableExists(String name) {
        return count("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?", name) > 0;
    }

    /** DATA_TYPE (int, varchar, enum...) of a column of this table, or null when it doesn't exist. */
    protected String columnType(String column) {
        List<String> types = jdbc.queryForList("SELECT DATA_TYPE FROM information_schema.columns"
                + " WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?", String.class, table, column);
        return types.isEmpty() ? null : types.getFirst().toLowerCase(Locale.ROOT);
    }

    /** Index name → its columns in order, unique or non-unique ones. */
    protected Map<String, List<String>> indexesOf(boolean unique) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        jdbc.query("SELECT index_name, column_name FROM information_schema.statistics WHERE table_schema = DATABASE()"
                        + " AND table_name = ? AND non_unique = ? ORDER BY index_name, seq_in_index",
                row -> {
                    result.computeIfAbsent(row.getString(1), key -> new ArrayList<>()).add(row.getString(2));
                }, table, unique ? 0 : 1);
        return result;
    }

    protected int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    protected String table() {
        return table;
    }
}
