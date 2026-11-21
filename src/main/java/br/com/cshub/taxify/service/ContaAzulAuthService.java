package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.CurrentUserService;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.dto.contaazul.ContaAzulAuthorizeUrlDTO;
import br.com.cshub.taxify.dto.contaazul.ContaAzulConnectionStatusDTO;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.exception.ContaAzulIntegrationException;
import br.com.cshub.taxify.exception.ResourceNotFoundException;
import br.com.cshub.taxify.integration.contaazul.ContaAzulOAuthClient;
import br.com.cshub.taxify.integration.contaazul.ContaAzulProperties;
import br.com.cshub.taxify.repository.AccountingFirmRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OAuth2 (authorization code) connection between an accounting firm and Conta Azul:
 * <ol>
 *   <li>{@link #buildAuthorizeUrl()} - an ADMIN clicks "Conectar"; we store a random
 *   {@code state} on the firm and send the browser to Conta Azul's login.</li>
 *   <li>Conta Azul redirects back to the front-end ({@code conta-azul.redirect-uri}) with
 *   {@code code} + {@code state}, which posts them to {@link #completeAuthorization}: the
 *   state must match the one stored for the same firm (anti-CSRF) and be recent.</li>
 *   <li>{@link #getValidAccessToken(UUID)} hands the API calls a live access token,
 *   renewing it with the refresh token when it's about to expire.</li>
 * </ol>
 */
@Service
public class ContaAzulAuthService {

    private static final long STATE_TTL_MINUTES = 10;
    /** Renew a bit before the real expiry so a call in flight doesn't get a 401. */
    private static final long EXPIRY_MARGIN_SECONDS = 120;
    private static final long DEFAULT_EXPIRES_IN_SECONDS = 3600;

    private final ContaAzulProperties properties;
    private final ContaAzulOAuthClient oauthClient;
    private final AccountingFirmRepository accountingFirmRepository;
    private final CurrentUserService currentUserService;
    private final MessageService messageService;
    private final SecureRandom random = new SecureRandom();
    private final Map<UUID, Object> refreshLocks = new ConcurrentHashMap<>();

    public ContaAzulAuthService(ContaAzulProperties properties, ContaAzulOAuthClient oauthClient,
                                AccountingFirmRepository accountingFirmRepository, CurrentUserService currentUserService,
                                MessageService messageService) {
        this.properties = properties;
        this.oauthClient = oauthClient;
        this.accountingFirmRepository = accountingFirmRepository;
        this.currentUserService = currentUserService;
        this.messageService = messageService;
    }

    @Transactional
    public ContaAzulAuthorizeUrlDTO buildAuthorizeUrl() {
        assertConfigured();
        AccountingFirm firm = currentFirm();

        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        String state = HexFormat.of().formatHex(bytes);
        firm.setContaAzulOauthState(state);
        firm.setContaAzulOauthStateCreatedAt(LocalDateTime.now());
        accountingFirmRepository.save(firm);

        String url = properties.getAuthorizeUrl()
                + "?response_type=code"
                + "&client_id=" + encode(properties.getClientId())
                + "&redirect_uri=" + encode(properties.getRedirectUri())
                + "&state=" + state
                + "&scope=" + encode(properties.getScope()).replace("%20", "+");
        return new ContaAzulAuthorizeUrlDTO(url);
    }

    @Transactional
    public ContaAzulConnectionStatusDTO completeAuthorization(String code, String state) {
        assertConfigured();
        AccountingFirm firm = currentFirm();

        String expected = firm.getContaAzulOauthState();
        LocalDateTime createdAt = firm.getContaAzulOauthStateCreatedAt();
        boolean stateOk = expected != null && state != null
                && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), state.getBytes(StandardCharsets.UTF_8))
                && createdAt != null && createdAt.isAfter(LocalDateTime.now().minusMinutes(STATE_TTL_MINUTES));
        // One-shot: whatever happens next, this state can't be replayed.
        firm.setContaAzulOauthState(null);
        firm.setContaAzulOauthStateCreatedAt(null);
        if (!stateOk || code == null || code.isBlank()) {
            accountingFirmRepository.save(firm);
            throw new BadRequestException(messageService.getMessage("error.contaAzul.state.invalid"));
        }

        ContaAzulOAuthClient.TokenResponse tokens = oauthClient.exchangeCode(code);
        applyTokens(firm, tokens);
        firm.setContaAzulConnectedAt(LocalDateTime.now());
        accountingFirmRepository.save(firm);
        return toStatus(firm);
    }

    @Transactional(readOnly = true)
    public ContaAzulConnectionStatusDTO status() {
        return toStatus(currentFirm());
    }

    @Transactional
    public void disconnect() {
        AccountingFirm firm = currentFirm();
        firm.setContaAzulAccessToken(null);
        firm.setContaAzulRefreshToken(null);
        firm.setContaAzulTokenExpiresAt(null);
        firm.setContaAzulConnectedAt(null);
        firm.setContaAzulOauthState(null);
        firm.setContaAzulOauthStateCreatedAt(null);
        accountingFirmRepository.save(firm);
    }

    /**
     * Access token for API calls on behalf of {@code accountingFirmId} - renewed (and
     * persisted) when it's expired or about to. Fails if the firm never connected or the
     * refresh token was revoked, in which case an ADMIN has to connect again.
     */
    @Transactional
    public String getValidAccessToken(UUID accountingFirmId) {
        synchronized (refreshLocks.computeIfAbsent(accountingFirmId, id -> new Object())) {
            AccountingFirm firm = accountingFirmRepository.findById(accountingFirmId)
                    .orElseThrow(() -> new ResourceNotFoundException(messageService.getMessage("error.accountingFirm.not.found")));
            if (firm.getContaAzulRefreshToken() == null && firm.getContaAzulAccessToken() == null) {
                throw new ContaAzulIntegrationException(messageService.getMessage("error.contaAzul.not.connected"));
            }
            LocalDateTime expiresAt = firm.getContaAzulTokenExpiresAt();
            boolean stillValid = firm.getContaAzulAccessToken() != null && expiresAt != null
                    && expiresAt.isAfter(LocalDateTime.now().plusSeconds(EXPIRY_MARGIN_SECONDS));
            if (stillValid) {
                return firm.getContaAzulAccessToken();
            }
            if (firm.getContaAzulRefreshToken() == null) {
                throw new ContaAzulIntegrationException(messageService.getMessage("error.contaAzul.not.connected"));
            }
            applyTokens(firm, oauthClient.refresh(firm.getContaAzulRefreshToken()));
            accountingFirmRepository.save(firm);
            return firm.getContaAzulAccessToken();
        }
    }

    private void applyTokens(AccountingFirm firm, ContaAzulOAuthClient.TokenResponse tokens) {
        firm.setContaAzulAccessToken(tokens.accessToken());
        // Some servers don't rotate the refresh token on renewal - keep the current one then.
        if (tokens.refreshToken() != null && !tokens.refreshToken().isBlank()) {
            firm.setContaAzulRefreshToken(tokens.refreshToken());
        }
        long expiresIn = tokens.expiresIn() != null && tokens.expiresIn() > 0 ? tokens.expiresIn() : DEFAULT_EXPIRES_IN_SECONDS;
        firm.setContaAzulTokenExpiresAt(LocalDateTime.now().plusSeconds(expiresIn));
    }

    private ContaAzulConnectionStatusDTO toStatus(AccountingFirm firm) {
        boolean connected = firm.getContaAzulRefreshToken() != null || firm.getContaAzulAccessToken() != null;
        return new ContaAzulConnectionStatusDTO(properties.isConfigured(), connected, firm.getContaAzulConnectedAt());
    }

    private AccountingFirm currentFirm() {
        return currentUserService.getCurrentUser()
                .map(user -> user.getAccountingFirm().getId())
                .flatMap(accountingFirmRepository::findById)
                .orElseThrow(() -> new ResourceNotFoundException(messageService.getMessage("error.accountingFirm.not.found")));
    }

    private void assertConfigured() {
        if (!properties.isConfigured()) {
            throw new ContaAzulIntegrationException(messageService.getMessage("error.contaAzul.not.configured"));
        }
    }

    private static String encode(String value) {
        return UriUtils.encode(value, StandardCharsets.UTF_8);
    }
}
