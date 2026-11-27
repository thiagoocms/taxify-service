package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.enumerated.StockSurveyClassification;
import br.com.cshub.taxify.enumerated.StockSurveyContactStatus;
import br.com.cshub.taxify.dto.reform.StockSurveyFilter;
import br.com.cshub.taxify.dto.reform.StockSurveyFilter.Sort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The stock credit survey's list, summary and CSV (spec 2026-11-27, F5a, section 8): read only from
 * {@code stock_credit_surveys} + {@code companies} (+ the CNPJ), filtered, ordered and paged in the database. The scope
 * is always the user's firm, and their portfolio when they are not ADMIN ({@code users_companies}). A fixed number of
 * queries per request; all parameterized (the ORDER BY comes from a closed list).
 */
@Repository
public class StockSurveyListRepository {

    /** One row of the list (the calculation columns, the contact status and the company). */
    public record ListRow(UUID companyId, String companyName, String cnpj, String referenceRegime, String nextYearRegime,
                          String classification, BigDecimal purchasesTotal, int purchaseItems, int inboundNotes,
                          int monthsWithInbound, int monthsEvaluated, String coverage, LocalDate firstInbound,
                          LocalDate lastInbound, String contactStatus, String statusChangedBy, LocalDateTime statusChangedAt,
                          String note, int version, LocalDateTime calculatedAt) {
    }

    /** The summary of every row that passes the filters (not only the page). */
    public record Summary(long companies, Map<StockSurveyClassification, Long> byClassification,
                          Map<StockSurveyContactStatus, Long> byContactStatus, BigDecimal purchasesTotal,
                          LocalDateTime oldestCalculatedAt) {
    }

    /** Who is asking: the firm, and the user when the portfolio limits them (null for ADMIN). */
    public record Scope(UUID firmId, UUID restrictedUserId) {
    }

    private static final String CLASSIFICATION_ORDER = "CASE s.classification WHEN 'HYPOTHESIS_I' THEN 0"
            + " WHEN 'HYPOTHESIS_II_III' THEN 1 WHEN 'NO_REGIME' THEN 2 WHEN 'OUT_OF_SCOPE' THEN 3 ELSE 4 END";

    private static final String COLUMNS = "SELECT s.company_id, c.name, d.number AS cnpj, s.reference_regime, s.next_year_regime,"
            + " s.classification, s.purchases_total, s.purchase_items, s.inbound_notes, s.months_with_inbound, s.months_evaluated,"
            + " s.coverage, s.first_inbound, s.last_inbound, s.contact_status, s.status_changed_by, s.status_changed_at, s.note, s.version,"
            + " s.calculated_at";

    private static final String SURVEY_FROM = " FROM stock_credit_surveys s JOIN companies c ON c.id = s.company_id"
            + " LEFT JOIN documents d ON d.id = c.document_id";

    private final NamedParameterJdbcTemplate jdbc;

    public StockSurveyListRepository(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    public List<ListRow> page(Scope scope, StockSurveyFilter filter) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String sql = COLUMNS + SURVEY_FROM + surveyWhere(scope, filter, params) + orderBy(filter.sort())
                + " LIMIT :limit OFFSET :offset";
        params.addValue("limit", filter.size()).addValue("offset", (long) filter.page() * filter.size());
        return jdbc.query(sql, params, (rs, i) -> row(rs));
    }

    /** Every row that passes the filters, in the list's order - the CSV. */
    public List<ListRow> all(Scope scope, StockSurveyFilter filter) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        return jdbc.query(COLUMNS + SURVEY_FROM + surveyWhere(scope, filter, params) + orderBy(filter.sort()), params,
                (rs, i) -> row(rs));
    }

    public Summary summary(Scope scope, StockSurveyFilter filter) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) AS total, SUM(s.purchases_total) AS purchases,"
                + " MIN(s.calculated_at) AS oldest");
        for (StockSurveyClassification classification : StockSurveyClassification.values()) {
            sql.append(", SUM(CASE WHEN s.classification = '").append(classification.name()).append("' THEN 1 ELSE 0 END) AS c_")
                    .append(classification.ordinal());
        }
        for (StockSurveyContactStatus status : StockSurveyContactStatus.values()) {
            sql.append(", SUM(CASE WHEN s.contact_status = '").append(status.name()).append("' THEN 1 ELSE 0 END) AS s_")
                    .append(status.ordinal());
        }
        sql.append(SURVEY_FROM).append(surveyWhere(scope, filter, params));
        return jdbc.queryForObject(sql.toString(), params, (rs, i) -> {
            Map<StockSurveyClassification, Long> byClassification = new EnumMap<>(StockSurveyClassification.class);
            for (StockSurveyClassification classification : StockSurveyClassification.values()) {
                byClassification.put(classification, rs.getLong("c_" + classification.ordinal()));
            }
            Map<StockSurveyContactStatus, Long> byStatus = new EnumMap<>(StockSurveyContactStatus.class);
            for (StockSurveyContactStatus status : StockSurveyContactStatus.values()) {
                byStatus.put(status, rs.getLong("s_" + status.ordinal()));
            }
            BigDecimal purchases = rs.getBigDecimal("purchases");
            return new Summary(rs.getLong("total"), byClassification, byStatus,
                    purchases == null ? BigDecimal.ZERO : purchases, dateTime(rs, "oldest"));
        });
    }

    /** CE-RN-01 companies in scope (and in the search) with no calculated survey of the year - outside the page. */
    public long withoutCalculation(Scope scope, StockSurveyFilter filter) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("year", filter.year());
        String sql = "SELECT COUNT(*) FROM companies c LEFT JOIN documents d ON d.id = c.document_id" + companyWhere(scope, filter, params)
                + " AND NOT EXISTS (SELECT 1 FROM stock_credit_surveys s WHERE s.company_id = c.id AND s.survey_year = :year"
                + " AND s.deleted = 0 AND s.calculated_at IS NOT NULL)";
        Long count = jdbc.queryForObject(sql, params, Long.class);
        return count == null ? 0 : count;
    }

    // ------------------------------------------------------------------ SQL pieces

    private static String companyWhere(Scope scope, StockSurveyFilter filter, MapSqlParameterSource params) {
        StringBuilder where = new StringBuilder(" WHERE c.accounting_firm_id = :firmId AND c.deleted = 0"
                + " AND (c.active IS NULL OR c.active = 1)");
        params.addValue("firmId", scope.firmId().toString());
        if (scope.restrictedUserId() != null) {
            where.append(" AND EXISTS (SELECT 1 FROM users_companies uc WHERE uc.company_id = c.id AND uc.user_id = :userId"
                    + " AND uc.deleted = 0)");
            params.addValue("userId", scope.restrictedUserId().toString());
        }
        if (filter.search() != null) {
            params.addValue("nameLike", "%" + escapeLike(filter.search()) + "%");
            if (filter.searchDigits() != null) {
                where.append(" AND (c.name LIKE :nameLike OR d.number LIKE :digitsLike)");
                params.addValue("digitsLike", "%" + filter.searchDigits() + "%");
            } else {
                where.append(" AND c.name LIKE :nameLike");
            }
        }
        return where.toString();
    }

    private static String surveyWhere(Scope scope, StockSurveyFilter filter, MapSqlParameterSource params) {
        StringBuilder where = new StringBuilder(companyWhere(scope, filter, params))
                .append(" AND s.survey_year = :year AND s.deleted = 0 AND s.calculated_at IS NOT NULL");
        params.addValue("year", filter.year());
        if (!filter.classifications().isEmpty()) {
            where.append(" AND s.classification IN (:classifications)");
            params.addValue("classifications", names(filter.classifications()));
        }
        if (!filter.statuses().isEmpty()) {
            where.append(" AND s.contact_status IN (:statuses)");
            params.addValue("statuses", names(filter.statuses()));
        }
        if (!filter.coverages().isEmpty()) {
            where.append(" AND s.coverage IN (:coverages)");
            params.addValue("coverages", names(filter.coverages()));
        }
        return where.toString();
    }

    /** CE-RN-11 by default; any other order ends with the company name (collation: no case, no accent) and the id. */
    static String orderBy(Sort sort) {
        String tail = " c.name ASC, s.company_id ASC";
        if (sort.field() == null) {
            return " ORDER BY " + CLASSIFICATION_ORDER + " ASC, s.purchases_total DESC," + tail;
        }
        String direction = sort.ascending() ? " ASC" : " DESC";
        return switch (sort.field()) {
            case CLASSIFICATION -> " ORDER BY " + CLASSIFICATION_ORDER + direction + "," + tail;
            case YEAR_PURCHASES -> " ORDER BY s.purchases_total" + direction + "," + tail;
            case COMPANY_NAME -> " ORDER BY c.name" + direction + ", s.company_id ASC";
            case STATUS_CHANGED_AT -> " ORDER BY s.status_changed_at" + direction + "," + tail;
        };
    }

    private static String escapeLike(String term) {
        return term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static List<String> names(java.util.Collection<? extends Enum<?>> values) {
        return values.stream().map(Enum::name).toList();
    }

    private static ListRow row(ResultSet rs) throws SQLException {
        return new ListRow(UUID.fromString(rs.getString("company_id")), rs.getString("name"), rs.getString("cnpj"),
                rs.getString("reference_regime"), rs.getString("next_year_regime"), rs.getString("classification"),
                rs.getBigDecimal("purchases_total"), rs.getInt("purchase_items"), rs.getInt("inbound_notes"),
                rs.getInt("months_with_inbound"), rs.getInt("months_evaluated"), rs.getString("coverage"),
                date(rs, "first_inbound"), date(rs, "last_inbound"), rs.getString("contact_status"),
                rs.getString("status_changed_by"), dateTime(rs, "status_changed_at"), rs.getString("note"), rs.getInt("version"),
                dateTime(rs, "calculated_at"));
    }

    private static LocalDate date(ResultSet rs, String column) throws SQLException {
        java.sql.Date value = rs.getDate(column);
        return value == null ? null : value.toLocalDate();
    }

    private static LocalDateTime dateTime(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }
}
