package br.com.cshub.taxify.common;

import br.com.cshub.taxify.domain.user.User;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

/**
 * Who and when of a change made by the current user: the login (cut to the 50 characters of the audit columns,
 * "system" without a user), the user id (for the logs) and the time of Recife (the injected clock, microseconds).
 */
@Component
public class ChangeStamps {

    static final int LOGIN_MAX = 50;

    public record Stamp(String login, UUID userId, LocalDateTime at) {
    }

    private final CurrentUserService currentUserService;
    private final Clock clock;

    public ChangeStamps(CurrentUserService currentUserService, Clock clock) {
        this.currentUserService = currentUserService;
        this.clock = clock;
    }

    public Stamp now() {
        Optional<User> user = currentUserService.getCurrentUser();
        String login = user.map(User::getLogin).orElse("system");
        return new Stamp(login.length() > LOGIN_MAX ? login.substring(0, LOGIN_MAX) : login, user.map(User::getId).orElse(null),
                LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS));
    }
}
