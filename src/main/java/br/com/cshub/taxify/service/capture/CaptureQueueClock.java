package br.com.cshub.taxify.service.capture;

import br.com.cshub.taxify.config.ClockConfig;
import org.springframework.scheduling.support.CronExpression;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * As próximas execuções do {@code FISCAL_XML_SYNC_CRON} (CP-RN-12): a próxima rodada e o intervalo entre as duas
 * próximas, calculados com o {@link CronExpression} do Spring no fuso em que o agendador roda (o da JVM - o cron da
 * SIEG não tem {@code zone}), e devolvidos em horário de Recife. Puro.
 *
 * @param nextTick a próxima rodada (Recife)
 * @param interval o intervalo entre as duas próximas rodadas
 */
public record CaptureQueueClock(LocalDateTime nextTick, Duration interval) {

    /** Cron inválido ou sem próxima execução: sem estimativa. */
    public static CaptureQueueClock of(String cron, LocalDateTime nowRecife, ZoneId schedulerZone) {
        if (cron == null || cron.isBlank() || nowRecife == null) {
            return null;
        }
        try {
            CronExpression expression = CronExpression.parse(cron.strip());
            ZonedDateTime now = nowRecife.atZone(ClockConfig.RECIFE).withZoneSameInstant(schedulerZone);
            ZonedDateTime next = expression.next(now);
            if (next == null) {
                return null;
            }
            ZonedDateTime after = expression.next(next);
            Duration interval = after == null ? Duration.ZERO : Duration.between(next, after);
            return new CaptureQueueClock(next.withZoneSameInstant(ClockConfig.RECIFE).toLocalDateTime(), interval);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** {@code nextTick + rounds × interval}. */
    public LocalDateTime afterRounds(long rounds) {
        return nextTick.plus(interval.multipliedBy(Math.max(0, rounds)));
    }

    public long intervalMinutes() {
        return interval.toMinutes();
    }
}
