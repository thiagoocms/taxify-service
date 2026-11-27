package br.com.cshub.taxify.security;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.service.firmaccess.FirmAccess;
import br.com.cshub.taxify.exception.FirmAccessBlockedException;
import br.com.cshub.taxify.service.firmaccess.FirmAccessService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * O filtro único de somente leitura (F1-RN-08), depois da autenticação: para o escritório BLOCKED, SUSPENDED ou
 * CANCELED decide pelo <b>tipo da operação</b>, não só pelo método HTTP.
 * <ul>
 *   <li>liberado: GET, HEAD e OPTIONS; os POST anotados com {@link ReadOnlyOperation}; as rotas
 *       {@link AllowedWhenBlocked} (F1-RN-09);</li>
 *   <li>bloqueado: todo o resto, com 403 {@code error.firm.access.blocked}.</li>
 * </ul>
 * A sessão aberta não cai: só a próxima escrita recebe o 403. O log {@code firm.access.blocked} leva o id do
 * escritório, o motivo, o método e o padrão da rota (sem os valores do caminho) - nunca CNPJ, nome, e-mail ou IP.
 */
@Component
public class FirmAccessInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(FirmAccessInterceptor.class);
    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final FirmAccessService firmAccessService;
    private final MessageService messageService;

    public FirmAccessInterceptor(FirmAccessService firmAccessService, MessageService messageService) {
        this.firmAccessService = firmAccessService;
        this.messageService = messageService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method) || isAlwaysAllowed(request.getMethod(), method)) {
            return true;
        }
        Optional<FirmAccess> access = firmAccessService.currentAccess();
        if (access.isEmpty() || !access.get().readOnly()) {
            return true;
        }
        FirmAccess blocked = access.get();
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        log.info("firm.access.blocked firm={} reason={} method={} path={}", blocked.firmId(),
                blocked.reason() == null ? blocked.status() : blocked.reason(), request.getMethod(),
                pattern == null ? method.getMethod().getName() : pattern);
        throw new FirmAccessBlockedException(messageService.getMessage("error.firm.access.blocked"), blocked);
    }

    /** Leitura pelo método HTTP, POST de leitura anotado, ou rota sempre liberada. */
    public static boolean isAlwaysAllowed(String httpMethod, HandlerMethod method) {
        return isAlwaysAllowed(httpMethod, method.getMethod(), method.getBeanType());
    }

    public static boolean isAlwaysAllowed(String httpMethod, java.lang.reflect.Method method, Class<?> controller) {
        if (httpMethod != null && READ_METHODS.contains(httpMethod.toUpperCase(Locale.ROOT))) {
            return true;
        }
        return AnnotatedElementUtils.hasAnnotation(method, ReadOnlyOperation.class)
                || AnnotatedElementUtils.hasAnnotation(method, AllowedWhenBlocked.class)
                || AnnotatedElementUtils.hasAnnotation(controller, AllowedWhenBlocked.class);
    }
}
