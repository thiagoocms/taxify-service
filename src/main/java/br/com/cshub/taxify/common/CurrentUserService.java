package br.com.cshub.taxify.common;

import br.com.cshub.taxify.domain.user.User;
import br.com.cshub.taxify.repository.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Resolves the User behind the current request's security context, when there is one -
 * empty for anonymous calls and for background/scheduled work that has no HTTP request
 * (e.g. FiscalXmlSyncScheduler), mirroring AuditorAwareImpl's own "no auth -> no user" logic.
 */
@Component
public class CurrentUserService {

    private final UserRepository userRepository;

    public CurrentUserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public Optional<User> getCurrentUser() {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null || !authentication.isAuthenticated() || authentication.getName() == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(userRepository.findByLoginAndDeletedIsFalse(authentication.getName()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
