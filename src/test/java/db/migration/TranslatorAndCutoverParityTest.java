package db.migration;

import br.com.cshub.taxify.domain.fiscaldocument.CapturePeriodCount;
import br.com.cshub.taxify.domain.fiscaldocument.FiscalDocument;
import br.com.cshub.taxify.domain.fiscaldocument.FiscalDocumentEvent;
import br.com.cshub.taxify.domain.fiscaldocument.FiscalDocumentItem;
import br.com.cshub.taxify.domain.fiscaldocument.FiscalDocumentSource;
import br.com.cshub.taxify.domain.fiscaldocument.GoodsInvoice;
import br.com.cshub.taxify.domain.fiscaldocument.LegacyFiscalDocumentTranslator;
import legacy.xml.FiscalXmlDocument;
import legacy.xml.FiscalXmlDocumentItem;
import legacy.xml.FiscalXmlEvent;
import legacy.xml.FiscalXmlSyncControl;
import legacy.xml.ServiceInvoice;
import org.hibernate.Session;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.metamodel.mapping.ModelPart;
import org.hibernate.persister.entity.EntityPersister;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypesScanner;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;

import static db.migration.FiscalDocumentsMigrationsTest.BEFORE;
import static db.migration.FiscalDocumentsMigrationsTest.SCRATCH;
import static db.migration.FiscalDocumentsMigrationsTest.flyway;
import static db.migration.FiscalDocumentsMigrationsTest.jdbc;
import static db.migration.FiscalDocumentsMigrationsTest.url;
import static db.migration.LegacyFixtures.E1;
import static db.migration.LegacyFixtures.E2;
import static db.migration.LegacyFixtures.doc;
import static db.migration.LegacyFixtures.insert;
import static db.migration.LegacyFixtures.item;
import static db.migration.LegacyFixtures.key;
import static db.migration.LegacyFixtures.nfe;
import static db.migration.LegacyFixtures.nfse;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * FD-RN-55, the proof that the SQL of V87 and the Java translator apply the same rules: the same legacy rows (the
 * scenario of the migration tests plus the edge cases below) go through {@link LegacyFiscalDocumentTranslator} -
 * loaded by Hibernate with the old entities - and through the real V87 on the local MySQL, and every column of
 * {@code fiscal_documents}, {@code fiscal_document_items}, {@code fiscal_document_events},
 * {@code fiscal_document_sources} (with {@code retrieval_mode}) and {@code capture_period_counts} is compared.
 *
 * <p>The Java side is turned into columns by Hibernate's own mapping of the new entities (identifier, discriminator and
 * every attribute broken down into its JDBC values, converters applied), so a column the mapping writes differently
 * from the SQL shows up here. Only what is random or "now" by nature is not compared by value: the id of a source and
 * of a count row (UUID() in SQL, a new UUID in Java - matched by their natural key instead) and {@code closed_at} of a
 * count row (the copy's clock; compared as closed / open).
 *
 * <p>The ids of the fixtures are made UUIDs first (the old entities map them as {@code UUID}), with the same
 * {@code MD5} rule for every table, so the links between the rows stay.
 */
@EnabledIfEnvironmentVariable(named = "DB_HOST", matches = "localhost|127\\.0\\.0\\.1",
        disabledReason = "integration test: only against a local MySQL (DB_HOST=localhost)")
class TranslatorAndCutoverParityTest {

    private static final LocalDateTime COPIED_AT = LocalDateTime.of(2026, 11, 27, 3, 0);

    private record Table(String name, List<String> key, List<String> ignored, List<String> openOrClosed) {
    }

    private static final Table DOCUMENTS = new Table("fiscal_documents", List.of("id"), List.of(), List.of());
    private static final Table ITEMS = new Table("fiscal_document_items", List.of("id"), List.of(), List.of());
    private static final Table EVENTS = new Table("fiscal_document_events", List.of("id"), List.of(), List.of());
    private static final Table SOURCES = new Table("fiscal_document_sources", List.of("document_id", "source", "retrieval_mode"),
            List.of("id"), List.of());
    private static final Table COUNTS = new Table("capture_period_counts",
            List.of("company_id", "channel", "document_type", "side", "period_month"), List.of("id"), List.of("closed_at"));

    private StandardServiceRegistry registry;
    private SessionFactoryImplementor factory;

    @BeforeEach
    void createScratchDatabase() {
        JdbcTemplate admin = jdbc("");
        admin.execute("DROP DATABASE IF EXISTS " + SCRATCH);
        admin.execute("CREATE DATABASE " + SCRATCH + " CHARACTER SET utf8mb4");
    }

    @AfterEach
    void dropScratchDatabase() {
        if (factory != null) {
            factory.close();
        }
        if (registry != null) {
            StandardServiceRegistryBuilder.destroy(registry);
        }
        jdbc("").execute("DROP DATABASE IF EXISTS " + SCRATCH);
    }

    @Test
    void theTranslatorAndV87WriteTheSameRows() {
        flyway(BEFORE).migrate();
        JdbcTemplate jdbc = jdbc(SCRATCH);
        LegacyFixtures.scenario(jdbc);
        LegacyFixtures.syncControls(jdbc);
        edgeCases(jdbc);
        Map<String, String> names = idsAsUuids(jdbc);
        flyway("86.4").migrate();

        Map<Table, Map<List<Object>, Map<String, Object>>> expected = translateWithJava();

        flyway("87").migrate();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_fiscal_xml_documents", Integer.class)).isEqualTo(18);

        List<String> divergences = new ArrayList<>();
        Map<Table, Integer> compared = new LinkedHashMap<>();
        for (Table table : List.of(DOCUMENTS, ITEMS, EVENTS, SOURCES, COUNTS)) {
            Map<List<Object>, Map<String, Object>> actual = rowsOf(jdbc, table);
            compared.put(table, actual.size());
            divergences.addAll(compare(table, expected.get(table), actual, names));
        }
        System.out.println("fiscal.documents.translator.parity compared=" + compared.entrySet().stream()
                .map(e -> e.getKey().name() + ":" + e.getValue()).toList() + " divergences=" + divergences.size());
        divergences.forEach(d -> System.out.println("  " + d));

        assertThat(compared).containsEntry(DOCUMENTS, 18 + 7).containsEntry(ITEMS, 6).containsEntry(EVENTS, 2);
        assertThat(compared.get(SOURCES)).isGreaterThanOrEqualTo(10);
        assertThat(compared.get(COUNTS)).isEqualTo(7);
        assertThat(divergences).as("SQL of V87 x LegacyFiscalDocumentTranslator, column by column").isEmpty();
        long cells = expected.values().stream().flatMap(rows -> rows.values().stream()).mapToLong(Map::size).sum();
        assertThat(cells).as("columns the Java side produced").isGreaterThan(2_000);

        // and the comparison does see a difference: one column of a CT-e, one of an item, one source, one count
        jdbc.update("UPDATE fiscal_documents SET products_amount = 290.00 WHERE id = (SELECT id FROM legacy_fiscal_xml_documents"
                + " WHERE access_key = ?)", key(202));
        jdbc.update("UPDATE fiscal_document_items SET cfop = '5102 ' WHERE cfop = '5102' AND total_amount = 45.00");
        jdbc.update("UPDATE fiscal_document_sources SET first_seen_at = first_seen_at + INTERVAL 1 SECOND WHERE source = 'UPLOAD'"
                + " AND document_id = (SELECT id FROM legacy_service_invoices WHERE access_key = 'ABRASF-2611606-99-1')");
        jdbc.update("UPDATE capture_period_counts SET closed_at = NULL WHERE company_id = ? AND period_month = '2026-09'", E1);
        List<String> planted = new ArrayList<>();
        for (Table table : List.of(DOCUMENTS, ITEMS, EVENTS, SOURCES, COUNTS)) {
            planted.addAll(compare(table, expected.get(table), rowsOf(jdbc, table), names));
        }
        assertThat(planted).hasSize(4).anyMatch(d -> d.contains("d-x2") && d.contains(".products_amount: java=null sql=290"))
                .anyMatch(d -> d.contains(".cfop: java=5102 sql=5102 "))
                .anyMatch(d -> d.contains("d-y1") && d.contains(".first_seen_at"))
                .anyMatch(d -> d.contains("2026-09") && d.contains(".closed_at: java=closed sql=open"));
    }

    // ------------------------------------------------------------------ the mass

    /** What the scenario of the migration tests does not have yet: the corner cases of FD-RN-55 and 3.9. */
    private static void edgeCases(JdbcTemplate jdbc) {
        String fxd = "fiscal_xml_documents";
        // FD-RN-55 (1): blank issuer documents ignored, only a name; recipient: blank CPF, foreign id. FD-RN-56: own issue,
        // tpNF " 0 " = INBOUND. Canceled without date; review without status; no metadata version
        insert(jdbc, fxd, nfe(doc("x1"), E1, "access_key", key(201), "direction", null, "own_issue", 1, "ide_tipo_operacao", " 0 ",
                "issue_date", "2026-10-21 10:00:00", "issuer_cnpj", "", "emit_cpf", " ", "emit_nome", "Só o nome",
                "dest_cpf", "  ", "dest_id_estrangeiro", "EXT-1", "status", "CANCELED", "canceled_at", null,
                "review_status", null, "reviewed_by", "fiscal2@exemplo.test", "metadata_version", null,
                "total_valor_total_nota", "12.34", "source_system", "UPLOAD", "found_in_sieg_at", "2026-11-10 08:00:00"));
        // FD-RN-55 (2) and (3): own-issue CT-e, AUTHORIZED with a stray canceled_at; of block G only the ICMS base/value
        insert(jdbc, fxd, nfe(doc("x2"), E1, "document_type", "CTE", "ide_modelo", "57", "access_key", key(202),
                "direction", "OUTBOUND", "own_issue", 1, "issue_date", "2026-10-22 10:00:00", "status", "AUTHORIZED",
                "canceled_at", "2026-10-23 10:00:00", "total_valor_total_nota", "300.00", "total_valor_produtos", "290.00",
                "total_valor_icms", "36.00", "total_base_calculo_icms", "300.00", "total_valor_frete", "10.00",
                "ret_irrf", "4.50", "emit_end_logradouro", "Rua C", "source_system", "SEFAZ_DIST",
                "found_in_sefaz_at", "2026-11-11 08:00:00", "review_status", "REVIEWED", "reviewed_at", "2026-11-12 08:00:00"));
        // a tab is text (TRIM strips only the space, like FiscalText); NFC-e with model " 65"; IBS/CBS only partly filled
        insert(jdbc, fxd, nfe(doc("x3"), E2, "access_key", key(203), "direction", "INBOUND", "own_issue", 0, "ide_modelo", " 65",
                "issue_date", "2026-10-23 10:00:00", "issuer_cnpj", "\t", "recipient_cnpj", LegacyFixtures.E1_CNPJ,
                "total_valor_ibs", "0.50", "total_valor_total_nota", "45.00"));
        // own issue, tpNF 1 with spaces, a deleted row
        insert(jdbc, fxd, nfe(doc("x4"), E2, "access_key", key(204), "direction", null, "own_issue", 1, "ide_tipo_operacao", "1 ",
                "issue_date", "2026-10-24 10:00:00", "total_valor_total_nota", "8.00", "deleted", 1));
        insert(jdbc, "fiscal_xml_document_items", item("i1000000-0000-4000-8000-000000000101", doc("x3"), "numero_item", 1,
                "cfop", " 5102 ", "valor_total", "45.00", "quantidade_comercial", "1.0000", "valor_unitario_comercial", "45.0000"));
        insert(jdbc, "fiscal_xml_document_items", item("i1000000-0000-4000-8000-000000000102", doc("x3"), "numero_item", 2,
                "cfop", null, "valor_total", null, "ibscbs_cst", "410"));
        // blank model (TRIM gives ''), no own_issue nor direction (received = INBOUND), CFOP of spaces only
        insert(jdbc, fxd, nfe(doc("x5"), E2, "access_key", key(205), "direction", null, "own_issue", null, "ide_modelo", "  ",
                "issue_date", "2026-10-26 10:00:00", "total_valor_total_nota", "5.00"));
        insert(jdbc, "fiscal_xml_document_items", item("i1000000-0000-4000-8000-000000000103", doc("x5"), "numero_item", 1,
                "cfop", "   ", "valor_total", "5.00"));
        // NFS-e issued by the company with a blank provider document and nothing else of the provider
        insert(jdbc, "service_invoices", nfse(doc("y2"), E1, "access_key", "2611606" + "7".repeat(43), "direction", "PRESTADA",
                "issue_date", "2026-10-27 11:00:00", "prestador_documento", "  ", "tomador_documento", LegacyFixtures.E1_CNPJ,
                "valor_servico", "15.00"));
        // NFS-e: third party, ABRASF, canceled; 12 digits (no type) and a CPF; upload also seen by SIEG
        insert(jdbc, "service_invoices", nfse(doc("y1"), E2, "access_key", "ABRASF-2611606-99-1", "layout", "ABRASF",
                "direction", "TERCEIRO", "canceled", 1, "issue_date", "2026-10-25 11:00:00", "prestador_documento", "123456789012",
                "tomador_documento", LegacyFixtures.SUPPLIER_CPF, "valor_servico", "70.00", "source_system", "UPLOAD",
                "found_in_sieg_at", "2026-11-12 09:00:00", "created_date", "2026-11-05 10:00:00"));
    }

    /** {@code MD5}-based UUID of the old id (version 4 shape), the same for the row and every link to it. */
    private static final String UUID_OF = "LOWER(CONCAT(SUBSTR(MD5(%1$s), 1, 8), '-', SUBSTR(MD5(%1$s), 9, 4), '-4',"
            + " SUBSTR(MD5(%1$s), 14, 3), '-8', SUBSTR(MD5(%1$s), 18, 3), '-', SUBSTR(MD5(%1$s), 21, 12)))";

    /** Old id → fixture name, for readable divergences; then every fixture id becomes a UUID. */
    private static Map<String, String> idsAsUuids(JdbcTemplate jdbc) {
        Map<String, String> names = new LinkedHashMap<>();
        for (String table : List.of("fiscal_xml_documents", "service_invoices", "fiscal_xml_document_items", "fiscal_xml_events",
                "fiscal_xml_sync_control")) {
            jdbc.query("SELECT id, " + UUID_OF.formatted("id") + " FROM " + table,
                    (RowCallbackHandler) row -> names.put(row.getString(2), row.getString(1)));
        }
        String[][] columns = {{"accounting_firms", "id"}, {"companies", "accounting_firm_id"}, {"documents", "id"},
                {"companies", "document_id"}, {"fiscal_xml_documents", "id"}, {"fiscal_xml_document_items", "id"},
                {"fiscal_xml_document_items", "document_id"}, {"service_invoices", "id"}, {"fiscal_xml_events", "id"},
                {"fiscal_xml_events", "document_id"}, {"fiscal_xml_sync_control", "id"}};
        // the FK checks are off for this session only: one connection for the whole remap
        SingleConnectionDataSource single = new SingleConnectionDataSource(url(SCRATCH),
                System.getenv().getOrDefault("DB_USER", "root"), System.getenv().getOrDefault("DB_PASSWORD", "root"), true);
        try {
            JdbcTemplate session = new JdbcTemplate(single);
            session.execute("SET FOREIGN_KEY_CHECKS = 0");
            for (String[] column : columns) {
                session.update("UPDATE " + column[0] + " SET " + column[1] + " = " + UUID_OF.formatted(column[1])
                        + " WHERE " + column[1] + " IS NOT NULL");
            }
            session.execute("SET FOREIGN_KEY_CHECKS = 1");
        } finally {
            single.destroy();
        }
        return names;
    }

    // ------------------------------------------------------------------ Java side

    /** Hibernate over the whole model (old and new entities, as between V86 and V87), only to read and to map. */
    private SessionFactoryImplementor factory() {
        registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.dialect", "org.hibernate.dialect.MySQLDialect")
                .applySetting("jakarta.persistence.jdbc.url", url(SCRATCH))
                .applySetting("jakarta.persistence.jdbc.user", System.getenv().getOrDefault("DB_USER", "root"))
                .applySetting("jakarta.persistence.jdbc.password", System.getenv().getOrDefault("DB_PASSWORD", "root"))
                .applySetting("hibernate.hbm2ddl.auto", "none")
                .applySetting("hibernate.physical_naming_strategy", CamelCaseToUnderscoresNamingStrategy.class.getName())
                .build();
        MetadataSources sources = new MetadataSources(registry);
        List<String> managed = new ArrayList<>(new PersistenceManagedTypesScanner(new DefaultResourceLoader())
                .scan("br.com.cshub.taxify").getManagedClassNames());
        // the old entities, kept only in src/test (legacy.xml) since the switch of the consumers (B-FD-05): never in the
        // application's persistence unit, mapped here to read the legacy rows as the translator expects them
        for (Class<?> legacy : List.of(FiscalXmlDocument.class, FiscalXmlDocumentItem.class, ServiceInvoice.class,
                FiscalXmlEvent.class, FiscalXmlSyncControl.class)) {
            if (!managed.contains(legacy.getName())) {
                managed.add(legacy.getName());
            }
        }
        managed.forEach(sources::addAnnotatedClassName);
        factory = (SessionFactoryImplementor) sources.buildMetadata().buildSessionFactory();
        return factory;
    }

    private Map<Table, Map<List<Object>, Map<String, Object>>> translateWithJava() {
        Map<Table, Map<List<Object>, Map<String, Object>>> rows = new LinkedHashMap<>();
        for (Table table : List.of(DOCUMENTS, ITEMS, EVENTS, SOURCES, COUNTS)) {
            rows.put(table, new LinkedHashMap<>());
        }
        SessionFactoryImplementor sessionFactory = factory();
        try (Session session = sessionFactory.openSession()) {
            SharedSessionContractImplementor implementor = (SharedSessionContractImplementor) session;
            Function<Object, Map<String, Object>> columns = entity -> columnsOf(sessionFactory, implementor, entity);
            Map<UUID, FiscalDocument> byId = new LinkedHashMap<>();
            for (FiscalXmlDocument legacy : session.createSelectionQuery("from LegacyFiscalXmlDocument", FiscalXmlDocument.class).list()) {
                byId.put(legacy.getId(), LegacyFiscalDocumentTranslator.fromGoodsRow(legacy));
            }
            for (ServiceInvoice legacy : session.createSelectionQuery("from LegacyServiceInvoice", ServiceInvoice.class).list()) {
                byId.put(legacy.getId(), LegacyFiscalDocumentTranslator.fromServiceInvoice(legacy));
            }
            for (FiscalDocument document : byId.values()) {
                put(rows, DOCUMENTS, columns.apply(document));
                if (document instanceof GoodsInvoice goods) {
                    for (FiscalDocumentItem item : goods.getItems()) {
                        put(rows, ITEMS, columns.apply(item));
                    }
                }
                for (FiscalDocumentSource source : document.getSources()) {
                    put(rows, SOURCES, columns.apply(source));
                }
            }
            for (FiscalXmlEvent legacy : session.createSelectionQuery("from LegacyFiscalXmlEvent", FiscalXmlEvent.class).list()) {
                FiscalDocument linked = legacy.getDocument() == null ? null : byId.get(legacy.getDocument().getId());
                FiscalDocumentEvent event = LegacyFiscalDocumentTranslator.fromEvent(legacy, linked);
                put(rows, EVENTS, columns.apply(event));
            }
            for (FiscalXmlSyncControl legacy : session.createSelectionQuery("from LegacyFiscalXmlSyncControl", FiscalXmlSyncControl.class).list()) {
                for (CapturePeriodCount count : LegacyFiscalDocumentTranslator.fromSyncControl(legacy, COPIED_AT)) {
                    put(rows, COUNTS, columns.apply(count));
                }
            }
        }
        return rows;
    }

    private static void put(Map<Table, Map<List<Object>, Map<String, Object>>> rows, Table table, Map<String, Object> row) {
        Map<String, Object> previous = rows.get(table).put(keyOf(table, row), row);
        assertThat(previous).as("two Java rows with the key " + keyOf(table, row) + " in " + table.name()).isNull();
    }

    /** Every column Hibernate would write for this entity, by the mapping of the new model (converters applied). */
    private static Map<String, Object> columnsOf(SessionFactoryImplementor factory, SharedSessionContractImplementor session,
                                                 Object entity) {
        EntityPersister persister = factory.getMappingMetamodel().getEntityDescriptor(entity.getClass());
        Map<String, Object> columns = new TreeMap<>();
        ModelPart.JdbcValueConsumer put = (index, value, selectable) -> {
            if (!selectable.isFormula() && selectable.isInsertable()) {
                columns.put(selectable.getSelectionExpression(), normalize(value));
            }
        };
        persister.getIdentifierMapping().breakDownJdbcValues(persister.getIdentifier(entity, session), put, session);
        persister.forEachAttributeMapping(attribute -> {
            if (!attribute.isPluralAttributeMapping()) {
                attribute.breakDownJdbcValues(attribute.getValue(entity), put, session);
            }
        });
        if (persister.getDiscriminatorMapping() != null) {
            String discriminator = persister.getDiscriminatorSQLValue();
            columns.put(persister.getDiscriminatorMapping().getSelectionExpression(),
                    discriminator == null ? null : discriminator.replace("'", ""));
        }
        return columns;
    }

    // ------------------------------------------------------------------ SQL side and comparison

    private static Map<List<Object>, Map<String, Object>> rowsOf(JdbcTemplate jdbc, Table table) {
        Map<List<Object>, Map<String, Object>> rows = new LinkedHashMap<>();
        for (Map<String, Object> raw : jdbc.queryForList("SELECT * FROM " + table.name())) {
            Map<String, Object> row = new TreeMap<>();
            raw.forEach((column, value) -> row.put(column.toLowerCase(), normalize(value)));
            assertThat(rows.put(keyOf(table, row), row)).as("two SQL rows with the same key in " + table.name()).isNull();
        }
        return rows;
    }

    private static List<Object> keyOf(Table table, Map<String, Object> row) {
        return table.key().stream().map(row::get).toList();
    }

    private static List<String> compare(Table table, Map<List<Object>, Map<String, Object>> java,
                                        Map<List<Object>, Map<String, Object>> sql, Map<String, String> names) {
        List<String> divergences = new ArrayList<>();
        TreeSet<String> keys = new TreeSet<>();
        java.keySet().forEach(k -> keys.add(k.toString()));
        sql.keySet().forEach(k -> keys.add(k.toString()));
        Map<String, Map<String, Object>> javaByKey = new LinkedHashMap<>();
        java.forEach((k, v) -> javaByKey.put(k.toString(), v));
        Map<String, Map<String, Object>> sqlByKey = new LinkedHashMap<>();
        sql.forEach((k, v) -> sqlByKey.put(k.toString(), v));
        for (String key : keys) {
            String label = table.name() + " " + readable(key, names);
            Map<String, Object> j = javaByKey.get(key);
            Map<String, Object> s = sqlByKey.get(key);
            if (j == null || s == null) {
                divergences.add(label + ": only in " + (j == null ? "SQL" : "Java"));
                continue;
            }
            TreeSet<String> columns = new TreeSet<>(j.keySet());
            columns.addAll(s.keySet());
            for (String column : columns) {
                if (table.ignored().contains(column)) {
                    continue;
                }
                Object javaValue = j.get(column);
                Object sqlValue = s.get(column);
                if (table.openOrClosed().contains(column)) {
                    javaValue = javaValue == null ? "open" : "closed";
                    sqlValue = sqlValue == null ? "open" : "closed";
                }
                if (!Objects.equals(javaValue, sqlValue)) {
                    divergences.add(label + " ." + column + ": java=" + javaValue + " sql=" + sqlValue
                            + (j.containsKey(column) ? "" : " (column not mapped for this subtype)"));
                }
            }
        }
        return divergences;
    }

    private static String readable(String key, Map<String, String> names) {
        String result = key;
        for (Map.Entry<String, String> name : names.entrySet()) {
            result = result.replace(name.getKey(), name.getValue().replaceAll("x+$", "").replaceAll("-$", ""));
        }
        return result;
    }

    /** One representation per value on both sides: text, whole numbers as long, decimals without trailing zeros. */
    private static Object normalize(Object value) {
        return switch (value) {
            case null -> null;
            case Enum<?> e -> e.name();
            case Boolean b -> b ? 1L : 0L;
            case BigDecimal d -> d.signum() == 0 ? "0" : d.stripTrailingZeros().toPlainString();
            case Byte n -> n.longValue();
            case Short n -> n.longValue();
            case Integer n -> n.longValue();
            case Long n -> n;
            case BigInteger n -> n.longValue();
            case Timestamp t -> t.toLocalDateTime().toString();
            case Date d -> d.toLocalDate().toString();
            case LocalDateTime t -> t.toString();
            case LocalDate d -> d.toString();
            case Temporal t -> t.toString();
            case UUID u -> u.toString();
            default -> value.toString();
        };
    }
}
