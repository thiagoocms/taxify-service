package br.com.cshub.taxify.common;

import br.com.cshub.taxify.domain.user.User;
import br.com.cshub.taxify.enumerated.UserProfileEnum;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Administrador da plataforma (a CS Hub), acima dos escritórios: o único que altera o que vale para TODOS os escritórios -
 * a tabela de ICMS geral do Fronteira e as regras da auditoria tipo malha. O ADMIN de um escritório administra só o
 * escritório dele; sem esta checagem, qualquer escritório com FISCAL_RULES_MANAGE mudava as regras dos outros.
 *
 * <p>Quem é: os logins de {@code taxify.platform.admin-logins} (PLATFORM_ADMIN_LOGINS, separados por vírgula) que
 * tenham o perfil ADMIN. Login é único na plataforma. Lista vazia = ninguém altera as regras gerais.
 */
@Component
public class PlatformAdminService {

    private final Set<String> logins;
    private final CurrentUserService currentUserService;

    public PlatformAdminService(@Value("${taxify.platform.admin-logins:}") List<String> logins, CurrentUserService currentUserService) {
        this.logins = logins.stream()
                .filter(Objects::nonNull)
                .map(login -> login.strip().toLowerCase(Locale.ROOT))
                .filter(login -> !login.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        this.currentUserService = currentUserService;
    }

    public boolean isPlatformAdmin(String login, UserProfileEnum profile) {
        return login != null && profile == UserProfileEnum.ADMIN && logins.contains(login.strip().toLowerCase(Locale.ROOT));
    }

    public boolean isCurrentUserPlatformAdmin() {
        return currentUserService.getCurrentUser()
                .map((User user) -> isPlatformAdmin(user.getLogin(), user.getProfile()))
                .orElse(false);
    }

    /** Regras que valem para todos os escritórios: só a CS Hub altera (403 para os outros). */
    public void assertCanManageGlobalRules() {
        if (!isCurrentUserPlatformAdmin()) {
            throw new AccessDeniedException("Regras gerais valem para todos os escritórios: só a CS Hub altera.");
        }
    }
}
