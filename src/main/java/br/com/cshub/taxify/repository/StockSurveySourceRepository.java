package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.domain.reform.stock.StockSurveyFigures.InboundMonth;
import br.com.cshub.taxify.domain.reform.stock.StockSurveyFigures.MonthlyPurchases;
import br.com.cshub.taxify.domain.reform.stock.StockSurveyFigures.PurchaseGroup;
import br.com.cshub.taxify.enumerated.TaxRegime;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The stock credit survey's calculation (spec 2026-11-27, F5a, section 8): projections without entity and the
 * idempotent upsert, a fixed number of statements per batch of companies (never one per company). All parameterized.
 * The reads start from {@code idx_fxd_company_direction_issue} and join the items by their {@code document_id} index.
 * Called inside a transaction (the calculator's, or the refresh request's): the JdbcTemplate then uses the
 * transaction's connection - the same one JPA uses - and never asks the pool for a second one.
 */
@Repository
public class StockSurveySourceRepository {

    /** A company the survey covers (CE-RN-01): active, not deleted. */
    public record CompanyRef(UUID companyId, UUID firmId, TaxRegime registryRegime) {
    }

    /** The calculation columns of one row (CE-RN-08 b) - never the contact status, the note or the version. */
    public record CalculatedRow(UUID companyId, int year, TaxRegime referenceRegime, LocalDate referenceRegimeSince,
                                String referenceRegimeSource, TaxRegime nextYearRegime, String classification,
                                BigDecimal purchasesTotal, int purchaseItems, int inboundNotes, int monthsWithInbound,
                                int monthsEvaluated, String coverage, LocalDate firstInbound, LocalDate lastInbound,
                                String purchasesByMonthJson, String purchasesByCstJson, String topNcmJson,
                                LocalDateTime calculatedAt) {
    }

    private static final String COMPANIES = "SELECT c.id, c.accounting_firm_id, c.tax_regime_type FROM companies c"
            + " WHERE c.deleted = 0 AND (c.active IS NULL OR c.active = 1)";

    private static final String PURCHASE_BASE = " FROM fiscal_xml_documents d JOIN fiscal_xml_document_items i ON i.document_id = d.id"
            + " WHERE d.company_id IN (:ids) AND d.direction = 'INBOUND' AND d.deleted = 0"
            + " AND (d.status IS NULL OR d.status <> 'CANCELED') AND d.issue_date >= :start AND d.issue_date < :end"
            + " AND i.cfop IN (:cfops)";

    private final NamedParameterJdbcTemplate jdbc;

    public StockSurveySourceRepository(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    // ------------------------------------------------------------------ companies

    /** Every company the routine covers, ordered by id (stable batches). */
    public List<CompanyRef> eligibleCompanies() {
        return jdbc.query(COMPANIES + " ORDER BY c.id", new MapSqlParameterSource(), (rs, i) -> companyRef(rs));
    }

    public Optional<CompanyRef> eligibleCompany(UUID companyId) {
        return jdbc.query(COMPANIES + " AND c.id = :id", new MapSqlParameterSource("id", companyId.toString()),
                (rs, i) -> companyRef(rs)).stream().findFirst();
    }

    // ------------------------------------------------------------------ aggregates (CE-RN-04 to CE-RN-06)

    /** Purchases per company and month: sum, items and distinct notes. */
    public Map<UUID, List<MonthlyPurchases>> monthlyPurchases(Collection<UUID> companyIds, Collection<String> cfops,
                                                             LocalDateTime start, LocalDateTime end) {
        Map<UUID, List<MonthlyPurchases>> result = new HashMap<>();
        jdbc.query("SELECT d.company_id, MONTH(d.issue_date) AS m, SUM(i.valor_total) AS v, COUNT(i.id) AS n,"
                + " COUNT(DISTINCT d.id) AS docs" + PURCHASE_BASE + " GROUP BY d.company_id, MONTH(d.issue_date)",
                purchaseParams(companyIds, cfops, start, end), rs -> {
                    result.computeIfAbsent(uuid(rs, "company_id"), key -> new ArrayList<>()).add(new MonthlyPurchases(
                            rs.getInt("m"), rs.getBigDecimal("v"), rs.getInt("n"), rs.getInt("docs")));
                });
        return result;
    }

    /** Purchases per company and trimmed CST of PIS. */
    public Map<UUID, List<PurchaseGroup>> purchasesByCstPis(Collection<UUID> companyIds, Collection<String> cfops,
                                                            LocalDateTime start, LocalDateTime end) {
        return groups("TRIM(i.cst_pis)", companyIds, cfops, start, end);
    }

    /** Purchases per company and trimmed NCM (the top 10 is cut in Java). */
    public Map<UUID, List<PurchaseGroup>> purchasesByNcm(Collection<UUID> companyIds, Collection<String> cfops,
                                                         LocalDateTime start, LocalDateTime end) {
        return groups("TRIM(i.ncm)", companyIds, cfops, start, end);
    }

    /** Inbound notes of the year with any CFOP, per company and month: first and last issue date (no item join). */
    public Map<UUID, List<InboundMonth>> inboundMonths(Collection<UUID> companyIds, LocalDateTime start, LocalDateTime end) {
        Map<UUID, List<InboundMonth>> result = new HashMap<>();
        jdbc.query("SELECT d.company_id, MONTH(d.issue_date) AS m, MIN(d.issue_date) AS first_at, MAX(d.issue_date) AS last_at"
                        + " FROM fiscal_xml_documents d WHERE d.company_id IN (:ids) AND d.direction = 'INBOUND' AND d.deleted = 0"
                        + " AND (d.status IS NULL OR d.status <> 'CANCELED') AND d.issue_date >= :start AND d.issue_date < :end"
                        + " GROUP BY d.company_id, MONTH(d.issue_date)",
                new MapSqlParameterSource().addValue("ids", ids(companyIds)).addValue("start", Timestamp.valueOf(start))
                        .addValue("end", Timestamp.valueOf(end)), rs -> {
                    result.computeIfAbsent(uuid(rs, "company_id"), key -> new ArrayList<>()).add(new InboundMonth(
                            rs.getInt("m"), rs.getTimestamp("first_at").toLocalDateTime().toLocalDate(),
                            rs.getTimestamp("last_at").toLocalDateTime().toLocalDate()));
                });
        return result;
    }

    // ------------------------------------------------------------------ writes

    /**
     * CE-RN-08 b: one statement per row in a JDBC batch, {@code INSERT ... ON DUPLICATE KEY UPDATE} on
     * (company_id, survey_year), updating only the calculation columns. A new row starts NOT_EVALUATED, version 0.
     * {@code VALUES(col)} and not the row alias ({@code VALUES (...) AS new ... new.col}): the alias is MySQL 8.0.19+
     * and fails on the 5.7 of the local environment (checked); {@code VALUES()} is deprecated but still works on 8.4.
     * Switch to the alias when no environment runs 5.7 any more.
     */
    public void upsert(List<CalculatedRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        SqlParameterSource[] batch = rows.stream().map(StockSurveySourceRepository::rowParams).toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate("INSERT INTO stock_credit_surveys (id, company_id, survey_year, reference_regime, reference_regime_since,"
                + " reference_regime_source, next_year_regime, classification, purchases_total, purchase_items, inbound_notes,"
                + " months_with_inbound, months_evaluated, coverage, first_inbound, last_inbound, purchases_by_month_json,"
                + " purchases_by_cst_json, top_ncm_json, calculated_at, contact_status, version, deleted, created_by, created_date)"
                + " VALUES (:id, :companyId, :year, :referenceRegime, :referenceRegimeSince, :referenceRegimeSource, :nextYearRegime,"
                + " :classification, :purchasesTotal, :purchaseItems, :inboundNotes, :monthsWithInbound, :monthsEvaluated,"
                + " :coverage, :firstInbound, :lastInbound, :byMonth, :byCst, :topNcm, :calculatedAt, 'NOT_EVALUATED', 0, 0,"
                + " 'system', :calculatedAt)"
                + " ON DUPLICATE KEY UPDATE reference_regime = VALUES(reference_regime),"
                + " reference_regime_since = VALUES(reference_regime_since), reference_regime_source = VALUES(reference_regime_source),"
                + " next_year_regime = VALUES(next_year_regime), classification = VALUES(classification),"
                + " purchases_total = VALUES(purchases_total), purchase_items = VALUES(purchase_items),"
                + " inbound_notes = VALUES(inbound_notes), months_with_inbound = VALUES(months_with_inbound),"
                + " months_evaluated = VALUES(months_evaluated), coverage = VALUES(coverage), first_inbound = VALUES(first_inbound),"
                + " last_inbound = VALUES(last_inbound), purchases_by_month_json = VALUES(purchases_by_month_json),"
                + " purchases_by_cst_json = VALUES(purchases_by_cst_json), top_ncm_json = VALUES(top_ncm_json),"
                + " calculated_at = VALUES(calculated_at), last_modified_by = 'system', last_modified_date = VALUES(calculated_at)",
                batch);
    }

    /** {@code GET_LOCK} on the transaction's connection: 1 = taken, 0 = timeout, null = error (NamedLocks). */
    public Long getLock(String name, int seconds) {
        return jdbc.queryForObject("SELECT GET_LOCK(:name, :seconds)", new MapSqlParameterSource().addValue("name", name)
                .addValue("seconds", seconds), Long.class);
    }

    public void releaseLock(String name) {
        jdbc.queryForObject("SELECT RELEASE_LOCK(:name)", new MapSqlParameterSource("name", name), Long.class);
    }

    /** CE-RN-09: makes sure the row exists (calculation columns empty) so the cooldown has a row to reserve. */
    public void ensureRow(UUID companyId, int year, LocalDateTime now) {
        jdbc.update("INSERT INTO stock_credit_surveys (id, company_id, survey_year, contact_status, version, deleted, created_by,"
                        + " created_date) VALUES (:id, :companyId, :year, 'NOT_EVALUATED', 0, 0, 'system', :now)"
                        + " ON DUPLICATE KEY UPDATE id = id",
                new MapSqlParameterSource().addValue("id", UUID.randomUUID().toString()).addValue("companyId", companyId.toString())
                        .addValue("year", year).addValue("now", Timestamp.valueOf(now)));
    }

    /** CE-RN-09: the atomic cooldown - 1 = reserved; 0 = another refresh happened less than the cooldown ago. */
    public int reserveRefresh(UUID companyId, int year, LocalDateTime now, LocalDateTime cooldownStart) {
        return jdbc.update("UPDATE stock_credit_surveys SET refresh_requested_at = :now WHERE company_id = :companyId"
                        + " AND survey_year = :year AND (refresh_requested_at IS NULL OR refresh_requested_at <= :cooldownStart)",
                new MapSqlParameterSource().addValue("now", Timestamp.valueOf(now)).addValue("companyId", companyId.toString())
                        .addValue("year", year).addValue("cooldownStart", Timestamp.valueOf(cooldownStart)));
    }

    /** The committed reservation (locking read: inside the refresh transaction it sees the other click's commit). */
    public Optional<LocalDateTime> refreshRequestedAtLocked(UUID companyId, int year) {
        return jdbc.query("SELECT refresh_requested_at FROM stock_credit_surveys WHERE company_id = :companyId AND survey_year = :year"
                        + " FOR UPDATE",
                new MapSqlParameterSource().addValue("companyId", companyId.toString()).addValue("year", year),
                (rs, i) -> rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toLocalDateTime()).stream()
                .filter(java.util.Objects::nonNull).findFirst();
    }

    // ------------------------------------------------------------------ helpers

    private Map<UUID, List<PurchaseGroup>> groups(String key, Collection<UUID> companyIds, Collection<String> cfops,
                                                  LocalDateTime start, LocalDateTime end) {
        Map<UUID, List<PurchaseGroup>> result = new HashMap<>();
        jdbc.query("SELECT d.company_id, " + key + " AS k, SUM(i.valor_total) AS v, COUNT(i.id) AS n" + PURCHASE_BASE
                + " GROUP BY d.company_id, " + key, purchaseParams(companyIds, cfops, start, end), rs -> {
                    result.computeIfAbsent(uuid(rs, "company_id"), id -> new ArrayList<>()).add(new PurchaseGroup(
                            rs.getString("k"), rs.getBigDecimal("v"), rs.getInt("n")));
                });
        return result;
    }

    private static MapSqlParameterSource purchaseParams(Collection<UUID> companyIds, Collection<String> cfops,
                                                        LocalDateTime start, LocalDateTime end) {
        return new MapSqlParameterSource().addValue("ids", ids(companyIds)).addValue("cfops", List.copyOf(cfops))
                .addValue("start", Timestamp.valueOf(start)).addValue("end", Timestamp.valueOf(end));
    }

    private static MapSqlParameterSource rowParams(CalculatedRow row) {
        return new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID().toString()).addValue("companyId", row.companyId().toString())
                .addValue("year", row.year()).addValue("referenceRegime", name(row.referenceRegime()))
                .addValue("referenceRegimeSince", date(row.referenceRegimeSince()))
                .addValue("referenceRegimeSource", row.referenceRegimeSource())
                .addValue("nextYearRegime", name(row.nextYearRegime())).addValue("classification", row.classification())
                .addValue("purchasesTotal", row.purchasesTotal()).addValue("purchaseItems", row.purchaseItems())
                .addValue("inboundNotes", row.inboundNotes()).addValue("monthsWithInbound", row.monthsWithInbound())
                .addValue("monthsEvaluated", row.monthsEvaluated()).addValue("coverage", row.coverage())
                .addValue("firstInbound", date(row.firstInbound())).addValue("lastInbound", date(row.lastInbound()))
                .addValue("byMonth", row.purchasesByMonthJson()).addValue("byCst", row.purchasesByCstJson())
                .addValue("topNcm", row.topNcmJson()).addValue("calculatedAt", Timestamp.valueOf(row.calculatedAt()));
    }

    private static CompanyRef companyRef(ResultSet rs) throws SQLException {
        String regime = rs.getString("tax_regime_type");
        return new CompanyRef(uuid(rs, "id"), uuid(rs, "accounting_firm_id"), parseRegime(regime));
    }

    private static TaxRegime parseRegime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return TaxRegime.valueOf(value.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static List<String> ids(Collection<UUID> companyIds) {
        return companyIds.stream().map(UUID::toString).toList();
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        return value == null ? null : UUID.fromString(value);
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static Date date(LocalDate value) {
        return value == null ? null : Date.valueOf(value);
    }
}
