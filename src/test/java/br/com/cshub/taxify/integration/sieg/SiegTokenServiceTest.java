package br.com.cshub.taxify.integration.sieg;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.exception.SiegIntegrationException;
import feign.FeignException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SiegTokenServiceTest {

    @Mock
    private SiegTokenFeignClient tokenClient;
    @Mock
    private MessageService messageService;

    private SiegProperties properties;
    private SiegTokenService tokenService;

    @BeforeEach
    void setUp() {
        properties = new SiegProperties();
        tokenService = new SiegTokenService(tokenClient, properties, messageService);
    }

    private void configureOAuth() {
        properties.setClientId("client-id");
        properties.setClientSecret("client-secret");
        properties.setTokenUrl("https://sieg.example/api/v1/create-jwt");
    }

    @Test
    void isConfiguredIsFalseWhenAnyCredentialIsMissing() {
        assertThat(tokenService.isConfigured()).isFalse();

        properties.setClientId("client-id");
        assertThat(tokenService.isConfigured()).isFalse();

        properties.setClientSecret("client-secret");
        assertThat(tokenService.isConfigured()).isFalse();
    }

    @Test
    void isConfiguredIsTrueOnlyWhenAllThreeCredentialsArePresent() {
        configureOAuth();

        assertThat(tokenService.isConfigured()).isTrue();
    }

    @Test
    void getAccessTokenThrowsWhenOAuthIsNotConfigured() {
        when(messageService.getMessage("error.sieg.oauth.not.configured")).thenReturn("not configured");

        assertThatThrownBy(() -> tokenService.getAccessToken())
                .isInstanceOf(SiegIntegrationException.class)
                .hasMessage("not configured");
    }

    @Test
    void getAccessTokenReturnsAndCachesTheTokenFromASuccessfulResponse() {
        configureOAuth();
        when(tokenClient.createJwt("client-secret", "client-id", "{}")).thenReturn("abc123");

        String first = tokenService.getAccessToken();
        String second = tokenService.getAccessToken();

        assertThat(first).isEqualTo("abc123");
        assertThat(second).isEqualTo("abc123");
        verify(tokenClient, times(1)).createJwt("client-secret", "client-id", "{}");
    }

    @Test
    void getAccessTokenStripsJsonQuotesWhenTheRawResponseStillHasThem() {
        configureOAuth();
        when(tokenClient.createJwt("client-secret", "client-id", "{}")).thenReturn("\"abc123\"");

        assertThat(tokenService.getAccessToken()).isEqualTo("abc123");
    }

    @Test
    void getAccessTokenSendsClientSecretAndClientIdAsHeaders() {
        configureOAuth();
        when(tokenClient.createJwt("client-secret", "client-id", "{}")).thenReturn("abc123");

        tokenService.getAccessToken();

        verify(tokenClient).createJwt("client-secret", "client-id", "{}");
    }

    @Test
    void getAccessTokenThrowsWhenResponseIsBlank() {
        configureOAuth();
        when(tokenClient.createJwt("client-secret", "client-id", "{}")).thenReturn(" ");
        when(messageService.getMessage("error.sieg.oauth.invalid.response")).thenReturn("invalid response");

        assertThatThrownBy(() -> tokenService.getAccessToken())
                .isInstanceOf(SiegIntegrationException.class)
                .hasMessage("invalid response");
    }

    @Test
    void getAccessTokenThrowsWhenResponseIsNull() {
        configureOAuth();
        when(tokenClient.createJwt("client-secret", "client-id", "{}")).thenReturn(null);
        when(messageService.getMessage("error.sieg.oauth.invalid.response")).thenReturn("invalid response");

        assertThatThrownBy(() -> tokenService.getAccessToken())
                .isInstanceOf(SiegIntegrationException.class);
    }

    @Test
    void getAccessTokenPropagatesFeignFailuresAsSiegIntegrationException() {
        configureOAuth();
        when(tokenClient.createJwt("client-secret", "client-id", "{}")).thenThrow(mock(FeignException.class));
        when(messageService.getMessage("error.sieg.oauth.failed")).thenReturn("failed");

        assertThatThrownBy(() -> tokenService.getAccessToken())
                .isInstanceOf(SiegIntegrationException.class)
                .hasMessage("failed");
    }
}
