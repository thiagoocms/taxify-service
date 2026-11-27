package br.com.cshub.taxify.integration.contaazul;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.common.RestClients;
import br.com.cshub.taxify.exception.ContaAzulIntegrationException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Token endpoint of Conta Azul's OAuth2 server (developers.contaazul.com/changecode and
 * /renewingaccesstoken): exchanges the authorization code and
 * renews the access token with the refresh token - which Conta Azul rotates on every renewal. The client id/secret go as HTTP Basic,
 * the grant as form fields - standard OAuth2 (RFC 6749 §4.1.3 / §6).
 */
@Component
public class ContaAzulOAuthClient {

    private static final Logger log = LoggerFactory.getLogger(ContaAzulOAuthClient.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    private final ContaAzulProperties properties;
    private final MessageService messageService;
    private final RestClient restClient;

    public ContaAzulOAuthClient(ContaAzulProperties properties, MessageService messageService) {
        this.properties = properties;
        this.messageService = messageService;
        this.restClient = RestClients.withTimeouts(CONNECT_TIMEOUT, READ_TIMEOUT);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("refresh_token") String refreshToken,
            @JsonProperty("expires_in") Long expiresIn,
            @JsonProperty("token_type") String tokenType
    ) {
    }

    public TokenResponse exchangeCode(String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", properties.getRedirectUri());
        return post(form, "error.contaAzul.code.rejected");
    }

    public TokenResponse refresh(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", refreshToken);
        return post(form, "error.contaAzul.refresh.rejected");
    }

    private TokenResponse post(MultiValueMap<String, String> form, String rejectedMessageKey) {
        String basic = Base64.getEncoder().encodeToString(
                (properties.getClientId() + ":" + properties.getClientSecret()).getBytes(StandardCharsets.UTF_8));
        try {
            TokenResponse response = restClient.post()
                    .uri(properties.getTokenUrl())
                    .header("Authorization", "Basic " + basic)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .retrieve()
                    .body(TokenResponse.class);
            if (response == null || response.accessToken() == null || response.accessToken().isBlank()) {
                throw new ContaAzulIntegrationException("error.contaAzul.integration.failed", messageService.getMessage("error.contaAzul.integration.failed"));
            }
            return response;
        } catch (RestClientResponseException e) {
            // 400/401 here = code expired/reused or refresh token revoked: the firm must log in again.
            log.warn("Conta Azul token endpoint answered {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            if (e.getStatusCode().is4xxClientError()) {
                throw new ContaAzulIntegrationException(rejectedMessageKey, messageService.getMessage(rejectedMessageKey), e);
            }
            throw new ContaAzulIntegrationException("error.contaAzul.integration.failed", messageService.getMessage("error.contaAzul.integration.failed"), e);
        } catch (RestClientException e) {
            log.warn("Conta Azul token endpoint unreachable", e);
            throw new ContaAzulIntegrationException("error.contaAzul.integration.failed", messageService.getMessage("error.contaAzul.integration.failed"), e);
        }
    }
}
