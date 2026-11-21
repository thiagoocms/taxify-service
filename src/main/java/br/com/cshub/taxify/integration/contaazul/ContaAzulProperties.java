package br.com.cshub.taxify.integration.contaazul;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The Taxify app registered in the Conta Azul developer portal - one client id/secret for
 * the whole system. Each accounting firm then authorizes it by logging into Conta Azul
 * (OAuth2 authorization code), and gets its own tokens (see ContaAzulAuthService).
 */
@Component
@ConfigurationProperties(prefix = "conta-azul")
public class ContaAzulProperties {

    private String clientId;
    private String clientSecret;
    /**
     * Must match, character by character, the redirect URI registered for the app. It points
     * to the front-end route that receives ?code=&state= and hands them to the API.
     */
    private String redirectUri;
    private String authorizeUrl = "https://login.contaazul.com/#/oauth/authorize";
    /** developers.contaazul.com/changecode - also used to renew (renewingaccesstoken). */
    private String tokenUrl = "https://api-v2.contaazul.com/oauth/token";
    private String scope = "openid profile aws.cognito.signin.user.admin";
    private String apiBaseUrl = "https://api-v2.contaazul.com";

    public boolean isConfigured() {
        return notBlank(clientId) && notBlank(clientSecret) && notBlank(redirectUri);
    }

    /** Spaces or a stray  from a .env edited on Windows would break the Basic auth / redirect match. */
    private static String trim(String value) {
        return value == null ? null : value.strip();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = trim(clientId);
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = trim(clientSecret);
    }

    public String getRedirectUri() {
        return redirectUri;
    }

    public void setRedirectUri(String redirectUri) {
        this.redirectUri = trim(redirectUri);
    }

    public String getAuthorizeUrl() {
        return authorizeUrl;
    }

    /** Blank (e.g. an empty env var from docker-compose) keeps the default instead of wiping it. */
    public void setAuthorizeUrl(String authorizeUrl) {
        if (notBlank(authorizeUrl)) {
            this.authorizeUrl = authorizeUrl;
        }
    }

    public String getTokenUrl() {
        return tokenUrl;
    }

    /** Blank (e.g. an empty env var from docker-compose) keeps the default instead of wiping it. */
    public void setTokenUrl(String tokenUrl) {
        if (notBlank(tokenUrl)) {
            this.tokenUrl = tokenUrl;
        }
    }

    public String getScope() {
        return scope;
    }

    /** Blank (e.g. an empty env var from docker-compose) keeps the default instead of wiping it. */
    public void setScope(String scope) {
        if (notBlank(scope)) {
            this.scope = scope;
        }
    }

    public String getApiBaseUrl() {
        return apiBaseUrl;
    }

    /** Blank (e.g. an empty env var from docker-compose) keeps the default instead of wiping it. */
    public void setApiBaseUrl(String apiBaseUrl) {
        if (notBlank(apiBaseUrl)) {
            this.apiBaseUrl = apiBaseUrl;
        }
    }
}
