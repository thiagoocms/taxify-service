package db.migration;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** F1-RN-13 / CA1-19 / CT1-33: os escritórios existentes ficam ACTIVE sem fim, não revisados; rodar de novo não cria nada. */
class ExistingFirmsReleaseTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 26, 10, 0);

    @Test
    void releasesEveryFirmWithoutARowAndRecordsTheEvent() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(contains("NOT EXISTS"), eq(String.class))).thenReturn(List.of("f1", "f2", "f3"));

        int released = new ExistingFirmsRelease(jdbc).run(NOW);

        assertThat(released).isEqualTo(3);
        verify(jdbc, times(3)).update(contains("INSERT INTO firm_subscriptions"), anyString(), anyString(),
                eq("liberado na migração"), eq("system"), any());
        verify(jdbc, times(3)).update(contains("INSERT INTO firm_subscription_events"), anyString(), anyString(),
                contains("\"status\":\"ACTIVE\""), eq("liberado na migração"), eq("system"), any());
    }

    /** A segunda rodada não acha ninguém sem linha (o NOT EXISTS) e não grava nada. */
    @Test
    void runningAgainCreatesNothing() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(contains("NOT EXISTS"), eq(String.class))).thenReturn(List.of());

        assertThat(new ExistingFirmsRelease(jdbc).run(NOW)).isZero();
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void onlyFirmsNotDeletedAndWithoutARowAreSelected() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of());

        new ExistingFirmsRelease(jdbc).run(NOW);

        verify(jdbc).queryForList(contains("f.deleted = 0"), eq(String.class));
    }
}
