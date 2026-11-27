package br.com.cshub.taxify.security;

import br.com.cshub.taxify.dto.ErrorDetailsDTO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.LocaleResolver;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;

/**
 * The 401 (no token, invalid or expired token) and the 403 that Spring Security answers in its filters, before any
 * controller - in the same error body as the rest of the API ({@link ErrorDetailsDTO}, see GlobalExceptionHandler).
 * The {@code WWW-Authenticate: Bearer ...} challenge is still written by Spring's own Bearer handlers. The message
 * follows {@code ?language=} like the other errors: the filters run before the LocaleChangeInterceptor, so the
 * parameter is read here.
 */
@Component
public class SecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    static final String UNAUTHORIZED_CODE = "error.generic.unauthorized";
    static final String FORBIDDEN_CODE = "error.access.denied";
    private static final String LANGUAGE_PARAM = "language";

    private final AuthenticationEntryPoint bearerEntryPoint = new BearerTokenAuthenticationEntryPoint();
    private final AccessDeniedHandler bearerAccessDenied = new BearerTokenAccessDeniedHandler();
    private final MessageSource messageSource;
    private final LocaleResolver localeResolver;
    private final ObjectMapper objectMapper;

    public SecurityErrorHandler(MessageSource messageSource, LocaleResolver localeResolver, ObjectMapper objectMapper) {
        this.messageSource = messageSource;
        this.localeResolver = localeResolver;
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        // status 401 and the Bearer challenge (with error="invalid_token" when a token was sent)
        try {
            bearerEntryPoint.commence(request, response, authException);
        } catch (jakarta.servlet.ServletException e) {
            throw new IOException(e);
        }
        write(request, response, HttpStatus.UNAUTHORIZED, UNAUTHORIZED_CODE);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException {
        try {
            bearerAccessDenied.handle(request, response, accessDeniedException);
        } catch (jakarta.servlet.ServletException e) {
            throw new IOException(e);
        }
        write(request, response, HttpStatus.FORBIDDEN, FORBIDDEN_CODE);
    }

    private void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String code)
            throws IOException {
        String message = messageSource.getMessage(code, null, locale(request));
        ErrorDetailsDTO body = new ErrorDetailsDTO(LocalDateTime.now().atZone(ZoneOffset.UTC).toInstant().toEpochMilli(),
                status.value(), code, message, "uri=" + request.getRequestURI());
        response.setStatus(status.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(body));
        response.getWriter().flush();
    }

    /** {@code ?language=} when sent (as the LocaleChangeInterceptor would), else the request's locale (pt-BR by default). */
    Locale locale(HttpServletRequest request) {
        String language = request.getParameter(LANGUAGE_PARAM);
        if (StringUtils.hasText(language)) {
            try {
                Locale parsed = StringUtils.parseLocale(language.strip());
                if (parsed != null) {
                    return parsed;
                }
            } catch (IllegalArgumentException ignored) {
                // an invalid value falls back to the default, like the interceptor ignoring it
            }
        }
        return localeResolver.resolveLocale(request);
    }
}
