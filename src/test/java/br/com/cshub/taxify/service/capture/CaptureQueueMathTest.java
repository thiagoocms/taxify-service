package br.com.cshub.taxify.service.capture;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/** CP-RN-12 e CP-RN-15: as contas da fila (CT-CP-32 a 35, 41, 54). Posição da API começa em 0. */
class CaptureQueueMathTest {

    private static final ZoneId RECIFE = ZoneId.of("America/Recife");

    /** CT-CP-32: ceil(300 / 2) × 5 = 750 min; CT-CP-33: one priority slot in use, ceil(300 / 1) × 5 = 1.500 min. */
    @Test
    void theoreticalLap() {
        assertThat(CaptureQueueMath.theoreticalLapRounds(300, 2, 0) * 5).isEqualTo(750);
        assertThat(CaptureQueueMath.theoreticalLapRounds(300, 2, 1) * 5).isEqualTo(1500);
        assertThat(CaptureQueueMath.theoreticalLapRounds(301, 2, 0)).isEqualTo(151);
    }

    /** CT-CP-35: position 41, 2 regular slots, next run 10:05 = 10:05 + floor(41 / 2) × 5 = 11:45. */
    @Test
    void nextAttemptEstimate() {
        CaptureQueueClock clock = CaptureQueueClock.of("0 0/5 * * * *", LocalDateTime.of(2026, 11, 27, 10, 1), RECIFE);
        assertThat(clock.nextTick()).isEqualTo(LocalDateTime.of(2026, 11, 27, 10, 5));
        assertThat(clock.interval()).isEqualTo(Duration.ofMinutes(5));
        assertThat(clock.afterRounds(CaptureQueueMath.roundsForNormalPosition(41, 2, 0)))
                .isEqualTo(LocalDateTime.of(2026, 11, 27, 11, 45));
        // CT-CP-41 / CA-CP-13: a request in position 0 goes on the next run, 10:05
        assertThat(clock.afterRounds(CaptureQueueMath.roundsForPriorityPosition(0, 2, 1)))
                .isEqualTo(LocalDateTime.of(2026, 11, 27, 10, 5));
    }

    /** The cron runs in the JVM's zone (UTC in the container): the next run comes back in Recife's time. */
    @Test
    void theCronInUtcComesBackInRecife() {
        CaptureQueueClock clock = CaptureQueueClock.of("0 0 9 * * *", LocalDateTime.of(2026, 11, 27, 5, 0), ZoneId.of("UTC"));
        assertThat(clock.nextTick()).isEqualTo(LocalDateTime.of(2026, 11, 27, 6, 0));
        assertThat(CaptureQueueClock.of("not a cron", LocalDateTime.now(), RECIFE)).isNull();
    }

    /** P-CP-04: 1 of the 2 slots; never batch-size − 1 or more; CT-CP-54 with batch 1: request, queue, request... */
    @Test
    void prioritySlots() {
        assertThat(CaptureQueueMath.prioritySlots(2, 1, 3, false)).isEqualTo(1);
        assertThat(CaptureQueueMath.prioritySlots(2, 5, 3, false)).as("at most batch-size - 1").isEqualTo(1);
        assertThat(CaptureQueueMath.prioritySlots(4, 2, 1, false)).as("at most the open requests").isEqualTo(1);
        assertThat(CaptureQueueMath.prioritySlots(2, 1, 0, false)).isZero();
        assertThat(CaptureQueueMath.prioritySlots(1, 1, 3, false)).isEqualTo(1);
        assertThat(CaptureQueueMath.prioritySlots(1, 1, 3, true)).isZero();
        assertThat(CaptureQueueMath.slotsInUse(2, 1, 2)).isEqualTo(1);
        assertThat(CaptureQueueMath.slotsInUse(2, 1, 0)).isZero();
    }
}
