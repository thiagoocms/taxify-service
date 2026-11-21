package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.CurrentFirmService;
import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.domain.user.User;
import br.com.cshub.taxify.dto.acessorias.AcessoriasUserSummaryDTO;
import br.com.cshub.taxify.integration.acessorias.AcessoriasClient;
import br.com.cshub.taxify.integration.acessorias.AcessoriasUserDTO;
import br.com.cshub.taxify.enumerated.IntegrationType;
import br.com.cshub.taxify.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lists the firm's Acessórias users next to the Taxify user with the same e-mail, so the
 * ADMIN can see who still needs an account. Read-only: a Taxify user needs a CPF and a
 * password Acessórias doesn't have, so creating one is left to the regular user form
 * (pre-filled by the front-end from this list).
 */
@Service
public class AcessoriasUserService {

    private static final String ACTIVE_STATUS = "Ativo";

    private final AcessoriasClient acessoriasClient;
    private final UserRepository userRepository;
    private final CurrentFirmService currentFirmService;
    private final FirmIntegrationService firmIntegrationService;

    public AcessoriasUserService(AcessoriasClient acessoriasClient, UserRepository userRepository,
                                 CurrentFirmService currentFirmService, FirmIntegrationService firmIntegrationService) {
        this.acessoriasClient = acessoriasClient;
        this.userRepository = userRepository;
        this.currentFirmService = currentFirmService;
        this.firmIntegrationService = firmIntegrationService;
    }

    /** Active users first, then by name. */
    public List<AcessoriasUserSummaryDTO> listForCurrentFirm() {
        AccountingFirm firm = currentFirmService.require();

        Map<String, User> taxifyUsersByEmail = new HashMap<>();
        for (User user : userRepository.findByAccountingFirm_IdAndDeletedIsFalse(firm.getId())) {
            if (user.getEmail() != null) {
                taxifyUsersByEmail.putIfAbsent(normalize(user.getEmail()), user);
            }
        }

        return acessoriasClient.listUsers(firmIntegrationService.getApiKey(firm.getId(), IntegrationType.ACESSORIAS)).stream()
                .map(user -> toSummary(user, taxifyUsersByEmail))
                .sorted(Comparator.comparing(AcessoriasUserSummaryDTO::active).reversed()
                        .thenComparing(summary -> summary.name() == null ? "" : summary.name(), String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private AcessoriasUserSummaryDTO toSummary(AcessoriasUserDTO user, Map<String, User> taxifyUsersByEmail) {
        String email = user.email() == null || user.email().isBlank() ? null : user.email().trim();
        User taxifyUser = email == null ? null : taxifyUsersByEmail.get(normalize(email));
        return new AcessoriasUserSummaryDTO(
                user.id(),
                user.nome() == null ? null : user.nome().trim(),
                email,
                user.status() != null && ACTIVE_STATUS.equalsIgnoreCase(user.status().trim()),
                taxifyUser != null ? taxifyUser.getId().toString() : null
        );
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
