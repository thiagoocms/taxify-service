package br.com.cshub.taxify.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.util.Optional;

/**
 * The committed version of a year for the 409 of AL-RN-04, read after a failed INSERT (two admins creating the same
 * year). Plain JDBC on the save's own transaction connection - the Hibernate session is unusable after the failed
 * flush, and a new transaction would ask the pool for a second connection while holding the first. A plain read: the
 * save runs in READ COMMITTED, so it sees the other admin's commit; a locking read here would upgrade the shared lock
 * of the failed INSERT and deadlock with the other losers.
 */
@Repository
public class IbsCbsRateVersionRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public IbsCbsRateVersionRepository(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    public Optional<Integer> committedVersion(int year) {
        return jdbc.query("SELECT version FROM ibs_cbs_rates WHERE ano = :year", new MapSqlParameterSource("year", year),
                (rs, i) -> rs.getInt(1)).stream().findFirst();
    }
}
