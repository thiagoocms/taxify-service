package br.com.cshub.taxify.service.reform;

import br.com.cshub.taxify.config.StockSurveyProperties;
import br.com.cshub.taxify.repository.CompanyTaxRegimeRepository;
import br.com.cshub.taxify.repository.StockSurveySourceRepository;
import br.com.cshub.taxify.repository.StockSurveySourceRepository.CalculatedRow;
import br.com.cshub.taxify.repository.StockSurveySourceRepository.CompanyRef;
import br.com.cshub.taxify.service.firmaccess.FirmAccessService;
import br.com.cshub.taxify.service.firmaccess.MutableClock;
import br.com.cshub.taxify.service.firmaccess.RoutineGate;
import br.com.cshub.taxify.service.routine.RoutineRunReservation;
import br.com.cshub.taxify.service.routine.RoutineRunReservation.Reservation;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CE-RN-07, CE-RN-08 a and CE-RN-12 (spec 2026-11-27, F5a) with mocked data access: batches of 50 with a fixed number
 * of queries each (CT-CE-13), the firm gate, the reservation of the day, the switch and the closing date.
 */
class StockSurveySchedulerTest {

    private final StockSurveyProperties properties = new StockSurveyProperties();
    private final StockSurveySourceRepository source = mock(StockSurveySourceRepository.class);
    private final CompanyTaxRegimeRepository regimes = mock(CompanyTaxRegimeRepository.class);
    private final RoutineRunReservation reservation = mock(RoutineRunReservation.class);
    private final FirmAccessService firmAccess = mock(FirmAccessService.class);
    private final MutableClock clock = new MutableClock(LocalDateTime.of(2026, 12, 10, 6, 15));
    private final UUID openFirm = UUID.randomUUID();
    private final UUID blockedFirm = UUID.randomUUID();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger schedulerLog = (Logger) LoggerFactory.getLogger(StockSurveyScheduler.class);
    private StockSurveyScheduler scheduler;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        StockSurveyCalculator calculator = new StockSurveyCalculator(source, regimes, new StockSurveyJson(JsonMapper.builder().build()), clock);
        scheduler = new StockSurveyScheduler(properties, source, calculator, reservation, firmAccess, clock);
        when(firmAccess.routineGate("stock-survey")).thenReturn(new RoutineGate("stock-survey", firm -> !firm.equals(blockedFirm)));
        when(reservation.reserve(eq(StockSurveyScheduler.ROUTINE), anyInt()))
                .thenReturn(Optional.of(new Reservation("r1", StockSurveyScheduler.ROUTINE, LocalDate.of(2026, 12, 10), false)));
        logs.start();
        schedulerLog.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        schedulerLog.detachAppender(logs);
    }

    /** CT-CE-13 and CA-CE-06: 51 companies of an open firm in 2 batches (50 + 1), the blocked firm's left out. */
    @Test
    void fiftyOneCompaniesInTwoBatchesWithFixedQueries() {
        List<CompanyRef> companies = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            companies.add(new CompanyRef(UUID.randomUUID(), openFirm, null));
        }
        companies.add(new CompanyRef(UUID.randomUUID(), blockedFirm, null));
        when(source.eligibleCompanies()).thenReturn(companies);

        StockSurveyScheduler.RunSummary summary = scheduler.runOnce().orElseThrow();

        assertThat(summary.companyCount()).isEqualTo(51);
        assertThat(summary.firmCount()).isEqualTo(1);
        assertThat(summary.skippedFirms()).isEqualTo(1);
        assertThat(summary.failed()).isZero();
        // per batch: regimes + 4 aggregates + 1 upsert, never one per company
        verify(regimes, times(2)).historyOf(anyCollection());
        verify(source, times(2)).monthlyPurchases(anyCollection(), anyCollection(), any(), any());
        verify(source, times(2)).purchasesByCstPis(anyCollection(), anyCollection(), any(), any());
        verify(source, times(2)).purchasesByNcm(anyCollection(), anyCollection(), any(), any());
        verify(source, times(2)).inboundMonths(anyCollection(), any(), any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CalculatedRow>> rows = ArgumentCaptor.forClass(List.class);
        verify(source, times(2)).upsert(rows.capture());
        assertThat(rows.getAllValues()).extracting(List::size).containsExactly(50, 1);
        assertThat(rows.getAllValues().getFirst()).allSatisfy(row -> {
            assertThat(row.calculatedAt()).isEqualTo(LocalDateTime.of(2026, 12, 10, 6, 15));
            assertThat(row.classification()).isEqualTo("NO_REGIME");
            assertThat(row.year()).isEqualTo(2026);
        });
        assertThat(rows.getAllValues().stream().flatMap(List::stream).map(CalculatedRow::companyId))
                .doesNotContain(companies.getLast().companyId());
        verify(reservation).finish(any(), eq(51), eq(0));
        assertThat(messages()).anyMatch(m -> m.startsWith("stock.survey.run.end runDate=2026-12-10 firmCount=1 companyCount=51 failed=0"));
    }

    /** A failing batch is retried company by company: only the bad one fails. */
    @Test
    void aFailingCompanyDoesNotStopTheOthers() {
        CompanyRef good = new CompanyRef(UUID.randomUUID(), openFirm, null);
        CompanyRef bad = new CompanyRef(UUID.randomUUID(), openFirm, null);
        when(source.eligibleCompanies()).thenReturn(List.of(good, bad));
        when(source.monthlyPurchases(anyCollection(), anyCollection(), any(), any())).thenAnswer(invocation -> {
            if (invocation.<java.util.Collection<UUID>>getArgument(0).contains(bad.companyId())) {
                throw new IllegalStateException("fictitious failure");
            }
            return java.util.Map.of();
        });

        StockSurveyScheduler.RunSummary summary = scheduler.runOnce().orElseThrow();

        assertThat(summary.failed()).isEqualTo(1);
        verify(source, times(1)).upsert(anyList());
        verify(reservation).finish(any(), eq(1), eq(1));
    }

    /** CT-CE-14: the other instance has the day - skipped with ALREADY_RESERVED. */
    @Test
    void anotherInstanceHasTheDay() {
        when(reservation.reserve(eq(StockSurveyScheduler.ROUTINE), anyInt())).thenReturn(Optional.empty());

        assertThat(scheduler.runOnce()).isEmpty();

        verify(source, never()).eligibleCompanies();
        assertThat(messages()).contains("stock.survey.run.skipped runDate=2026-12-10 reason=ALREADY_RESERVED");
    }

    /** CT-CE-31: switched off - nothing reserved, nothing read. */
    @Test
    void disabledDoesNothing() {
        properties.setEnabled(false);

        assertThat(scheduler.runOnce()).isEmpty();

        verify(reservation, never()).reserve(any(), anyInt());
        assertThat(messages()).contains("stock.survey.run.skipped runDate=2026-12-10 reason=DISABLED");
    }

    /** CA-CE-11 and CT-CE-27: from 2027-07-01 00:00 of Recife the routine doesn't run. */
    @Test
    void closedFromJuly2027() {
        clock.set(LocalDateTime.of(2027, 7, 1, 0, 0));

        assertThat(scheduler.runOnce()).isEmpty();

        verify(reservation, never()).reserve(any(), anyInt());
        assertThat(messages()).contains("stock.survey.run.skipped runDate=2027-07-01 reason=CLOSED");

        clock.set(LocalDateTime.of(2027, 6, 30, 23, 59));
        when(source.eligibleCompanies()).thenReturn(List.of());
        assertThat(scheduler.runOnce()).isPresent();
    }

    private List<String> messages() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }
}
