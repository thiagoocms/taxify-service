package br.com.cshub.taxify.common;

import br.com.cshub.taxify.domain.user.User;
import br.com.cshub.taxify.enumerated.UserProfileEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Administrador da plataforma (a CS Hub), acima dos escritórios: o único que altera o que vale para TODOS os escritórios -
 * a tabela de ICMS geral do Fronteira e as regras da auditoria tipo malha - e que usa o console da plataforma
 * ({@code /platform}: liberar, estender o teste, suspender escritórios).
 *
 * <p>Quem é (F1-RN-04): perfil ADMIN, login em {@code taxify.platform.admin-logins} (PLATFORM_ADMIN_LOGINS, separados
 * por vírgula) <b>e</b> do escritório {@code taxify.platform.firm-id} (PLATFORM_FIRM_ID). Um login igual criado em
 * outro escritório nunca vira admin. Lista vazia ou escritório vazio = ninguém é admin da plataforma (com aviso no log).
 */
@Component
public class PlatformAdminService {

    private static final Logger log = LoggerFactory.getLogger(PlatformAdminService.class);

    private final Set<String> logins;
    private final UUID platformFirmId;
    private final CurrentUserService currentUserService;

    @Autowired
    public PlatformAdminService(@Value("${taxify.platform.admin-logins:}") List<String> logins,
                                @Value("${taxify.platform.firm-id:}") String platformFirmId,
                                CurrentUserService currentUserService) {
        this.logins = logins.stream()
                .filter(Objects::nonNull)
                .map(PlatformAdminService::normalize)
                .filter(login -> !login.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        this.platformFirmId = parse(platformFirmId);
        this.currentUserService = currentUserService;
        if (this.logins.isEmpty() || this.platformFirmId == null) {
            log.warn("platform.admin.disabled: PLATFORM_ADMIN_LOGINS{} and PLATFORM_FIRM_ID{} - nobody is platform admin"
                            + " and no firm is exempt from the trial",
                    this.logins.isEmpty() ? " is empty" : " is set", this.platformFirmId == null ? " is empty or invalid" : " is set");
        }
    }

    private static UUID parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.strip());
        } catch (IllegalArgumentException e) {
            log.warn("PLATFORM_FIRM_ID is not a UUID - ignored");
            return null;
        }
    }

    private static String normalize(String login) {
        return login.strip().toLowerCase(Locale.ROOT);
    }

    public boolean isPlatformAdmin(String login, UserProfileEnum profile, UUID accountingFirmId) {
        return login != null && profile == UserProfileEnum.ADMIN && platformFirmId != null
                && platformFirmId.equals(accountingFirmId) && logins.contains(normalize(login));
    }

    public boolean isPlatformAdmin(User user) {
        return user != null && isPlatformAdmin(user.getLogin(), user.getProfile(),
                user.getAccountingFirm() == null ? null : user.getAccountingFirm().getId());
    }

    public boolean isCurrentUserPlatformAdmin() {
        return currentUserService.getCurrentUser().map(this::isPlatformAdmin).orElse(false);
    }

    /** Login da lista da CS Hub: o cadastro público recusa com a mesma resposta de login em uso (F1-RN-04). */
    public boolean isReservedLogin(String login) {
        return login != null && logins.contains(normalize(login));
    }

    /** O escritório da CS Hub - isento de teste, bloqueio e limite (F1-RN-12). */
    public Optional<UUID> platformFirmId() {
        return Optional.ofNullable(platformFirmId);
    }

    public boolean isPlatformFirm(UUID accountingFirmId) {
        return platformFirmId != null && platformFirmId.equals(accountingFirmId);
    }

    /** Regras que valem para todos os escritórios: só a CS Hub altera (403 para os outros). */
    public void assertCanManageGlobalRules() {
        if (!isCurrentUserPlatformAdmin()) {
            throw new AccessDeniedException("Regras gerais valem para todos os escritórios: só a CS Hub altera.");
        }
    }
}
