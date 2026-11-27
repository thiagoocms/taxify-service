package br.com.cshub.taxify.service.firmaccess;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** Relógio de Recife que o teste move. */
public class MutableClock extends Clock {

    private static final ZoneId RECIFE = ZoneId.of("America/Recife");
    private Instant now;

    public MutableClock(LocalDateTime recifeTime) {
        set(recifeTime);
    }

    public void set(LocalDateTime recifeTime) {
        this.now = recifeTime.atZone(RECIFE).toInstant();
    }

    @Override
    public ZoneId getZone() {
        return RECIFE;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.fixed(now, zone);
    }

    @Override
    public Instant instant() {
        return now;
    }
}
