package br.com.cshub.taxify.security;

import br.com.cshub.taxify.repository.UserRepository;
import br.com.cshub.taxify.service.TokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Revogação das sessões (SN-RN-11), logo depois da autenticação pelo bearer token: o JWT continua conferido pela
 * assinatura, e aqui o claim {@code sv} é comparado com {@code users.session_version}. Responde 401
 * {@code error.auth.session.expired} quando o usuário não existe ou foi excluído, quando a versão é outra e quando a
 * leitura falha (falha fechada: nunca segue como "sistema", o que fecha o R-02 da arquitetura).
 * <ul>
 *   <li>Token sem {@code sv} (emitido antes do deploy) vale como versão 0 - o deploy não derruba ninguém.</li>
 *   <li>Nas rotas anônimas ({@code publicRoutes}: login, cadastro, "Esqueci a senha") um token velho que o navegador
 *       ainda mande não bloqueia nada: a requisição segue como anônima.</li>
 *   <li>Lê só o número da versão (uma consulta pela chave única do login), sem carregar a entidade fora do contexto de
 *       persistência da requisição.</li>
 * </ul>
 * Não é um bean: é criado na SecurityConfig, para o Spring Boot não registrá-lo também como filtro do servlet.
 */
public class SessionVersionFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SessionVersionFilter.class);

    private final UserRepository userRepository;
    private final SecurityErrorHandler errorHandler;
    private final RequestMatcher publicRoutes;

    public SessionVersionFilter(UserRepository userRepository, SecurityErrorHandler errorHandler, RequestMatcher publicRoutes) {
        this.userRepository = userRepository;
        this.errorHandler = errorHandler;
        this.publicRoutes = publicRoutes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication) || !authentication.isAuthenticated()) {
            chain.doFilter(request, response);
            return;
        }
        if (publicRoutes != null && publicRoutes.matches(request)) {
            // an old token sent to the login or to "forgot password" must not lock the person out: anonymous call
            SecurityContextHolder.clearContext();
            chain.doFilter(request, response);
            return;
        }
        if (!isCurrent(jwtAuthentication)) {
            SecurityContextHolder.clearContext();
            errorHandler.sessionExpired(request, response);
            return;
        }
        chain.doFilter(request, response);
    }

    /** The user exists, is not deleted and the token carries his current session version. */
    boolean isCurrent(JwtAuthenticationToken authentication) {
        Jwt jwt = authentication.getToken();
        String login = jwt.getSubject();
        if (login == null || login.isBlank()) {
            return false;
        }
        Integer tokenVersion = sessionVersionOf(jwt);
        if (tokenVersion == null) {
            return false;
        }
        Optional<Integer> current;
        try {
            current = userRepository.findSessionVersionByLogin(login);
        } catch (RuntimeException e) {
            // fail closed: without the user the request is never treated as "system"
            log.warn("auth.session.check.failed error={}", e.getClass().getSimpleName());
            return false;
        }
        return current.isPresent() && current.get().intValue() == tokenVersion;
    }

    /** {@code sv} as a number; absent = 0 (token issued before the deploy); anything else = invalid (null). */
    static Integer sessionVersionOf(Jwt jwt) {
        Object claim = jwt.getClaims().get(TokenService.SESSION_VERSION_CLAIM);
        if (claim == null) {
            return 0;
        }
        if (claim instanceof Number number) {
            long value = number.longValue();
            return value == number.doubleValue() && value >= 0 && value <= Integer.MAX_VALUE ? (int) value : null;
        }
        return null;
    }
}
