package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.CurrentUserService;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.domain.user.User;
import br.com.cshub.taxify.dto.acessorias.AcessoriasUserSummaryDTO;
import br.com.cshub.taxify.exception.ResourceNotFoundException;
import br.com.cshub.taxify.integration.acessorias.AcessoriasClient;
import br.com.cshub.taxify.integration.acessorias.AcessoriasUserDTO;
import br.com.cshub.taxify.repository.AccountingFirmRepository;
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
    private final AccountingFirmRepository accountingFirmRepository;
    private final UserRepository userRepository;
    private final CurrentUserService currentUserService;
    private final MessageService messageService;

    public AcessoriasUserService(AcessoriasClient acessoriasClient, AccountingFirmRepository accountingFirmRepository,
                                 UserRepository userRepository, CurrentUserService currentUserService, MessageService messageService) {
        this.acessoriasClient = acessoriasClient;
        this.accountingFirmRepository = accountingFirmRepository;
        this.userRepository = userRepository;
        this.currentUserService = currentUserService;
        this.messageService = messageService;
    }

    /** Active users first, then by name. */
    public List<AcessoriasUserSummaryDTO> listForCurrentFirm() {
        AccountingFirm firm = currentUserService.getCurrentUser()
                .map(user -> user.getAccountingFirm().getId())
                .flatMap(accountingFirmRepository::findById)
                .orElseThrow(() -> new ResourceNotFoundException(messageService.getMessage("error.accountingFirm.not.found")));

        Map<String, User> taxifyUsersByEmail = new HashMap<>();
        for (User user : userRepository.findByAccountingFirm_IdAndDeletedIsFalse(firm.getId())) {
            if (user.getEmail() != null) {
                taxifyUsersByEmail.putIfAbsent(normalize(user.getEmail()), user);
            }
        }

        return acessoriasClient.listUsers(firm.getAcessoriasApiToken()).stream()
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
