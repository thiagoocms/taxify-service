package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.CurrentFirmService;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.domain.billing.FirmIntegration;
import br.com.cshub.taxify.dto.contaazul.ContaAzulAuthorizeUrlDTO;
import br.com.cshub.taxify.dto.contaazul.ContaAzulConnectionStatusDTO;
import br.com.cshub.taxify.enumerated.IntegrationType;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.exception.ContaAzulIntegrationException;
import br.com.cshub.taxify.integration.contaazul.ContaAzulOAuthClient;
import br.com.cshub.taxify.integration.contaazul.ContaAzulProperties;
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
 * OAuth2 (authorization code) connection between an accounting firm and Conta Azul, kept
 * in the firm's {@link FirmIntegration} of type CONTA_AZUL:
 * <ol>
 *   <li>{@link #buildAuthorizeUrl()} - an ADMIN clicks "Conectar"; we store a random
 *   {@code state} and send the browser to Conta Azul's login.</li>
 *   <li>Conta Azul redirects back to the front-end ({@code conta-azul.redirect-uri}) with
 *   {@code code} + {@code state}, which posts them to {@link #completeAuthorization}: the
 *   state must match the one stored for the same firm (anti-CSRF) and be recent.</li>
 *   <li>{@link #getValidAccessToken(UUID)} hands the API calls a live access token,
 *   renewing it with the refresh token (which Conta Azul rotates every time).</li>
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
    private final FirmIntegrationService firmIntegrationService;
    private final CurrentFirmService currentFirmService;
    private final MessageService messageService;
    private final SecureRandom random = new SecureRandom();
    private final Map<UUID, Object> refreshLocks = new ConcurrentHashMap<>();

    public ContaAzulAuthService(ContaAzulProperties properties, ContaAzulOAuthClient oauthClient,
                                FirmIntegrationService firmIntegrationService, CurrentFirmService currentFirmService,
                                MessageService messageService) {
        this.properties = properties;
        this.oauthClient = oauthClient;
        this.firmIntegrationService = firmIntegrationService;
        this.currentFirmService = currentFirmService;
        this.messageService = messageService;
    }

    @Transactional
    public ContaAzulAuthorizeUrlDTO buildAuthorizeUrl() {
        assertConfigured();
        FirmIntegration integration = currentIntegration();

        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        String state = HexFormat.of().formatHex(bytes);
        integration.setOauthState(state);
        integration.setOauthStateCreatedAt(LocalDateTime.now());
        firmIntegrationService.save(integration);

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
        FirmIntegration integration = currentIntegration();

        String expected = integration.getOauthState();
        LocalDateTime createdAt = integration.getOauthStateCreatedAt();
        boolean stateOk = expected != null && state != null
                && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), state.getBytes(StandardCharsets.UTF_8))
                && createdAt != null && createdAt.isAfter(LocalDateTime.now().minusMinutes(STATE_TTL_MINUTES));
        // One-shot: whatever happens next, this state can't be replayed.
        integration.setOauthState(null);
        integration.setOauthStateCreatedAt(null);
        if (!stateOk || code == null || code.isBlank()) {
            firmIntegrationService.save(integration);
            throw new BadRequestException("error.contaAzul.state.invalid", messageService.getMessage("error.contaAzul.state.invalid"));
        }

        applyTokens(integration, oauthClient.exchangeCode(code));
        integration.setConnectedAt(LocalDateTime.now());
        firmIntegrationService.save(integration);
        return toStatus(integration);
    }

    @Transactional(readOnly = true)
    public ContaAzulConnectionStatusDTO status() {
        return firmIntegrationService.find(currentFirmService.requireId(), IntegrationType.CONTA_AZUL)
                .map(this::toStatus)
                .orElseGet(() -> new ContaAzulConnectionStatusDTO(properties.isConfigured(), false, null));
    }

    @Transactional
    public void disconnect() {
        firmIntegrationService.find(currentFirmService.requireId(), IntegrationType.CONTA_AZUL).ifPresent(integration -> {
            integration.setAccessToken(null);
            integration.setRefreshToken(null);
            integration.setTokenExpiresAt(null);
            integration.setConnectedAt(null);
            integration.setOauthState(null);
            integration.setOauthStateCreatedAt(null);
            firmIntegrationService.save(integration);
        });
    }

    public boolean isConnected(UUID accountingFirmId) {
        return firmIntegrationService.find(accountingFirmId, IntegrationType.CONTA_AZUL)
                .map(FirmIntegration::isConfigured)
                .orElse(false);
    }

    /**
     * Access token for API calls on behalf of {@code accountingFirmId} - renewed (and
     * persisted, with the new refresh token) when it's expired or about to. Fails if the
     * firm never connected or the access was revoked, in which case an ADMIN must reconnect.
     */
    @Transactional
    public String getValidAccessToken(UUID accountingFirmId) {
        synchronized (refreshLocks.computeIfAbsent(accountingFirmId, id -> new Object())) {
            FirmIntegration integration = firmIntegrationService.find(accountingFirmId, IntegrationType.CONTA_AZUL)
                    .filter(FirmIntegration::isConfigured)
                    .orElseThrow(() -> new ContaAzulIntegrationException("error.contaAzul.not.connected", messageService.getMessage("error.contaAzul.not.connected")));
            LocalDateTime expiresAt = integration.getTokenExpiresAt();
            boolean stillValid = integration.getAccessToken() != null && expiresAt != null
                    && expiresAt.isAfter(LocalDateTime.now().plusSeconds(EXPIRY_MARGIN_SECONDS));
            if (stillValid) {
                return integration.getAccessToken();
            }
            if (integration.getRefreshToken() == null) {
                throw new ContaAzulIntegrationException("error.contaAzul.not.connected", messageService.getMessage("error.contaAzul.not.connected"));
            }
            applyTokens(integration, oauthClient.refresh(integration.getRefreshToken()));
            firmIntegrationService.save(integration);
            return integration.getAccessToken();
        }
    }

    private void applyTokens(FirmIntegration integration, ContaAzulOAuthClient.TokenResponse tokens) {
        integration.setAccessToken(tokens.accessToken());
        // Conta Azul rotates the refresh token on every renewal - keep the current one only if none came back.
        if (tokens.refreshToken() != null && !tokens.refreshToken().isBlank()) {
            integration.setRefreshToken(tokens.refreshToken());
        }
        long expiresIn = tokens.expiresIn() != null && tokens.expiresIn() > 0 ? tokens.expiresIn() : DEFAULT_EXPIRES_IN_SECONDS;
        integration.setTokenExpiresAt(LocalDateTime.now().plusSeconds(expiresIn));
    }

    private ContaAzulConnectionStatusDTO toStatus(FirmIntegration integration) {
        return new ContaAzulConnectionStatusDTO(properties.isConfigured(), integration.isConfigured(), integration.getConnectedAt());
    }

    private FirmIntegration currentIntegration() {
        return firmIntegrationService.getOrCreate(currentFirmService.requireId(), IntegrationType.CONTA_AZUL);
    }

    private void assertConfigured() {
        if (!properties.isConfigured()) {
            throw new ContaAzulIntegrationException("error.contaAzul.not.configured", messageService.getMessage("error.contaAzul.not.configured"));
        }
    }

    private static String encode(String value) {
        return UriUtils.encode(value, StandardCharsets.UTF_8);
    }
}
