package br.com.cshub.taxify.integration.sieg;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.exception.SiegIntegrationException;
import feign.FeignException;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Obtains and caches a JWT from SIEG's {@code POST /api/v1/create-jwt} endpoint.
 *
 * NOTE: SIEG's partner integration model requires a Client ID + Client Secret (issued
 * after an integration request approved through integracoes.sieg.com/clientes-sieg) in
 * addition to the account-level API key. Credentials are sent as the {@code X-Secret-Key}
 * / {@code X-Client-Id} headers (see {@link SiegTokenFeignClient}), matching SIEG's
 * documented curl example exactly - no OAuth2 form body, no Authorization header.
 * The endpoint doesn't return an expiry, so the token is cached for a fixed TTL.
 */
@Component
public class SiegTokenService {

    private static final long DEFAULT_TOKEN_TTL_SECONDS = 55 * 60L;

    private final SiegTokenFeignClient tokenClient;
    private final SiegProperties properties;
    private final MessageService messageService;

    private volatile CachedToken cachedToken;

    public SiegTokenService(@Lazy SiegTokenFeignClient tokenClient, SiegProperties properties, MessageService messageService) {
        this.tokenClient = tokenClient;
        this.properties = properties;
        this.messageService = messageService;
    }

    public boolean isConfigured() {
        return notBlank(properties.getClientId()) && notBlank(properties.getClientSecret()) && notBlank(properties.getBaseUrl());
    }

    public synchronized String getAccessToken() {
        if (cachedToken != null && Instant.now().isBefore(cachedToken.expiresAt())) {
            return cachedToken.accessToken();
        }
        return requestNewToken();
    }

    private String requestNewToken() {
        if (!isConfigured()) {
            throw new SiegIntegrationException(messageService.getMessage("error.sieg.oauth.not.configured"));
        }

        try {
            String accessToken = stripJsonQuotes(tokenClient.createJwt(properties.getClientSecret(), properties.getClientId(), "{}"));

            if (accessToken == null || accessToken.isBlank()) {
                throw new SiegIntegrationException(messageService.getMessage("error.sieg.oauth.invalid.response"));
            }

            Instant expiresAt = Instant.now().plusSeconds(DEFAULT_TOKEN_TTL_SECONDS);

            cachedToken = new CachedToken(accessToken, expiresAt);
            return accessToken;
        } catch (FeignException e) {
            throw new SiegIntegrationException(messageService.getMessage("error.sieg.oauth.failed"), e);
        }
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    // Depending on which Spring HttpMessageConverter wins for a String return type,
    // create-jwt's `"<jwt>"` JSON-string body sometimes arrives already unwrapped by
    // Jackson and sometimes arrives as raw text with the JSON quotes still attached
    // (StringHttpMessageConverter doesn't parse JSON) - strip them either way so a
    // malformed Bearer token never reaches SIEG.
    private String stripJsonQuotes(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private record CachedToken(String accessToken, Instant expiresAt) {
    }
}
