package br.com.cshub.taxify.common;

import br.com.cshub.taxify.domain.user.User;
import br.com.cshub.taxify.exception.UnauthorizedException;
import br.com.cshub.taxify.repository.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.Optional;

/**
 * Resolves the User behind the current request's security context, when there is one -
 * empty for anonymous calls and for background/scheduled work that has no HTTP request
 * (e.g. FiscalXmlSyncScheduler), mirroring AuditorAwareImpl's own "no auth -> no user" logic.
 */
@Component
public class CurrentUserService {

    private final UserRepository userRepository;
    private final MessageService messageService;

    public CurrentUserService(UserRepository userRepository, MessageService messageService) {
        this.userRepository = userRepository;
        this.messageService = messageService;
    }

    private static final String REQUEST_KEY = CurrentUserService.class.getName() + ".user";
    private static final String SESSION_EXPIRED = "error.auth.session.expired";

    /**
     * The user is read once per HTTP request (the lists check access company by company - each call
     * used to query the user again); background work without a request still reads it every time.
     */
    @SuppressWarnings("unchecked")
    public Optional<User> getCurrentUser() {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        String login = currentLogin();
        if (request == null || login == null) {
            return loadCurrentUser();
        }
        Object cached = request.getAttribute(REQUEST_KEY, RequestAttributes.SCOPE_REQUEST);
        if (cached instanceof CachedUser(String cachedLogin, Optional<?> user) && cachedLogin.equals(login)) {
            return (Optional<User>) user;
        }
        Optional<User> user = loadCurrentUser();
        request.setAttribute(REQUEST_KEY, new CachedUser(login, user), RequestAttributes.SCOPE_REQUEST);
        return user;
    }

    private record CachedUser(String login, Optional<?> user) {
    }

    private static String currentLogin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null || !authentication.isAuthenticated() ? null : authentication.getName();
    }

    private Optional<User> loadCurrentUser() {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null || !authentication.isAuthenticated() || authentication.getName() == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(userRepository.findByLoginAndDeletedIsFalse(authentication.getName()));
        } catch (Exception e) {
            // SN-RN-11 (R-02): in an authenticated HTTP request a failed read is never "no user" - that would be the
            // unrestricted "system" context of TenantAccessService. Background work keeps the old behavior.
            if (RequestContextHolder.getRequestAttributes() != null) {
                throw new UnauthorizedException(SESSION_EXPIRED, messageService.getMessage(SESSION_EXPIRED));
            }
            return Optional.empty();
        }
    }
}
