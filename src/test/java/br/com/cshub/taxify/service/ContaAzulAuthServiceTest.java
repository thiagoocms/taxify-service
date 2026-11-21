package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.domain.billing.FirmIntegration;
import br.com.cshub.taxify.dto.contaazul.ContaAzulConnectionStatusDTO;
import br.com.cshub.taxify.enumerated.IntegrationType;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.exception.ContaAzulIntegrationException;
import br.com.cshub.taxify.integration.contaazul.ContaAzulOAuthClient;
import br.com.cshub.taxify.integration.contaazul.ContaAzulProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContaAzulAuthServiceTest {

    @Mock
    private ContaAzulOAuthClient oauthClient;
    @Mock
    private FirmIntegrationService firmIntegrationService;
    @Mock
    private MessageService messageService;

    private ContaAzulProperties properties;
    private ContaAzulAuthService service;
    private UUID firmId;
    private FirmIntegration integration;

    @BeforeEach
    void setUp() {
        properties = new ContaAzulProperties();
        properties.setClientId("client id");
        properties.setClientSecret("secret");
        properties.setRedirectUri("https://app.example/integracoes/conta-azul/callback");

        firmId = UUID.randomUUID();
        integration = new FirmIntegration(new AccountingFirm(firmId), IntegrationType.CONTA_AZUL);
        when(firmIntegrationService.currentFirmId()).thenReturn(firmId);
        when(firmIntegrationService.getOrCreate(firmId, IntegrationType.CONTA_AZUL)).thenReturn(integration);
        when(firmIntegrationService.find(firmId, IntegrationType.CONTA_AZUL)).thenReturn(Optional.of(integration));
        when(firmIntegrationService.save(any(FirmIntegration.class))).thenAnswer(inv -> inv.getArgument(0));
        when(messageService.getMessage(anyString())).thenAnswer(inv -> inv.getArgument(0));

        service = new ContaAzulAuthService(properties, oauthClient, firmIntegrationService, messageService);
    }

    @Test
    void authorizeUrlCarriesEncodedParamsAndStoresState() {
        String url = service.buildAuthorizeUrl().url();

        assertThat(integration.getOauthState()).hasSize(48);
        assertThat(url).startsWith("https://login.contaazul.com/#/oauth/authorize?response_type=code")
                .contains("client_id=client%20id")
                .contains("redirect_uri=https%3A%2F%2Fapp.example%2Fintegracoes%2Fconta-azul%2Fcallback")
                .contains("state=" + integration.getOauthState())
                .endsWith("scope=openid+profile+aws.cognito.signin.user.admin");
    }

    @Test
    void blankUrlsFromEmptyEnvVarsKeepTheDefaults() {
        properties.setAuthorizeUrl("");
        properties.setTokenUrl("  ");

        assertThat(properties.getAuthorizeUrl()).isEqualTo("https://login.contaazul.com/#/oauth/authorize");
        assertThat(properties.getTokenUrl()).isEqualTo("https://api-v2.contaazul.com/oauth/token");
    }

    @Test
    void credentialsAreTrimmed() {
        properties.setClientSecret(" secret\r");
        properties.setRedirectUri("https://app.example/cb \r");

        assertThat(properties.getClientSecret()).isEqualTo("secret");
        assertThat(properties.getRedirectUri()).isEqualTo("https://app.example/cb");
    }

    @Test
    void callbackWithMatchingStateStoresTokens() {
        service.buildAuthorizeUrl();
        String state = integration.getOauthState();
        when(oauthClient.exchangeCode("abc")).thenReturn(new ContaAzulOAuthClient.TokenResponse("at", "rt", 3600L, "Bearer"));

        ContaAzulConnectionStatusDTO status = service.completeAuthorization("abc", state);

        assertThat(status.connected()).isTrue();
        assertThat(integration.getAccessToken()).isEqualTo("at");
        assertThat(integration.getRefreshToken()).isEqualTo("rt");
        assertThat(integration.getOauthState()).isNull();
        assertThat(integration.getConnectedAt()).isNotNull();
        assertThat(integration.getTokenExpiresAt()).isAfter(LocalDateTime.now().plusMinutes(55));
    }

    @Test
    void callbackWithWrongOrExpiredStateIsRejected() {
        service.buildAuthorizeUrl();
        assertThatThrownBy(() -> service.completeAuthorization("abc", "forged"))
                .isInstanceOf(BadRequestException.class);
        // the state is one-shot: even the right one no longer works after a failed attempt
        assertThat(integration.getOauthState()).isNull();

        service.buildAuthorizeUrl();
        String state = integration.getOauthState();
        integration.setOauthStateCreatedAt(LocalDateTime.now().minusMinutes(11));
        assertThatThrownBy(() -> service.completeAuthorization("abc", state))
                .isInstanceOf(BadRequestException.class);
        verify(oauthClient, never()).exchangeCode(anyString());
    }

    @Test
    void validTokenIsReusedAndExpiredOneIsRefreshedStoringTheRotatedRefreshToken() {
        integration.setAccessToken("old");
        integration.setRefreshToken("rt1");
        integration.setTokenExpiresAt(LocalDateTime.now().plusMinutes(30));
        assertThat(service.getValidAccessToken(firmId)).isEqualTo("old");
        verify(oauthClient, never()).refresh(anyString());

        integration.setTokenExpiresAt(LocalDateTime.now().plusSeconds(30));
        when(oauthClient.refresh("rt1")).thenReturn(new ContaAzulOAuthClient.TokenResponse("new", "rt2", 3600L, "Bearer"));
        assertThat(service.getValidAccessToken(firmId)).isEqualTo("new");
        assertThat(integration.getRefreshToken()).isEqualTo("rt2");
    }

    @Test
    void notConfiguredOrNotConnectedFailsClearly() {
        assertThatThrownBy(() -> service.getValidAccessToken(firmId))
                .isInstanceOf(ContaAzulIntegrationException.class)
                .hasMessage("error.contaAzul.not.connected");

        properties.setClientSecret(null);
        assertThatThrownBy(() -> service.buildAuthorizeUrl())
                .isInstanceOf(ContaAzulIntegrationException.class)
                .hasMessage("error.contaAzul.not.configured");
        assertThat(service.status().configured()).isFalse();
    }

    @Test
    void disconnectClearsTokens() {
        integration.setAccessToken("at");
        integration.setRefreshToken("rt");

        service.disconnect();

        assertThat(integration.isConfigured()).isFalse();
        assertThat(service.isConnected(firmId)).isFalse();
    }
}
