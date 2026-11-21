package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.CurrentUserService;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.domain.user.User;
import br.com.cshub.taxify.dto.contaazul.ContaAzulConnectionStatusDTO;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.exception.ContaAzulIntegrationException;
import br.com.cshub.taxify.integration.contaazul.ContaAzulOAuthClient;
import br.com.cshub.taxify.integration.contaazul.ContaAzulProperties;
import br.com.cshub.taxify.repository.AccountingFirmRepository;
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
    private AccountingFirmRepository accountingFirmRepository;
    @Mock
    private CurrentUserService currentUserService;
    @Mock
    private MessageService messageService;

    private ContaAzulProperties properties;
    private ContaAzulAuthService service;
    private AccountingFirm firm;

    @BeforeEach
    void setUp() {
        properties = new ContaAzulProperties();
        properties.setClientId("client id");
        properties.setClientSecret("secret");
        properties.setRedirectUri("https://app.example/integracoes/conta-azul/callback");

        firm = new AccountingFirm();
        firm.setId(UUID.randomUUID());
        User user = new User();
        user.setAccountingFirm(firm);
        when(currentUserService.getCurrentUser()).thenReturn(Optional.of(user));
        when(accountingFirmRepository.findById(firm.getId())).thenReturn(Optional.of(firm));
        when(messageService.getMessage(anyString())).thenAnswer(inv -> inv.getArgument(0));

        service = new ContaAzulAuthService(properties, oauthClient, accountingFirmRepository, currentUserService, messageService);
    }

    @Test
    void authorizeUrlCarriesEncodedParamsAndStoresState() {
        String url = service.buildAuthorizeUrl().url();

        assertThat(firm.getContaAzulOauthState()).hasSize(48);
        assertThat(url).startsWith("https://login.contaazul.com/#/oauth/authorize?response_type=code")
                .contains("client_id=client%20id")
                .contains("redirect_uri=https%3A%2F%2Fapp.example%2Fintegracoes%2Fconta-azul%2Fcallback")
                .contains("state=" + firm.getContaAzulOauthState())
                .endsWith("scope=openid+profile+aws.cognito.signin.user.admin");
    }

    @Test
    void callbackWithMatchingStateStoresTokens() {
        service.buildAuthorizeUrl();
        String state = firm.getContaAzulOauthState();
        when(oauthClient.exchangeCode("abc")).thenReturn(new ContaAzulOAuthClient.TokenResponse("at", "rt", 3600L, "Bearer"));

        ContaAzulConnectionStatusDTO status = service.completeAuthorization("abc", state);

        assertThat(status.connected()).isTrue();
        assertThat(firm.getContaAzulAccessToken()).isEqualTo("at");
        assertThat(firm.getContaAzulRefreshToken()).isEqualTo("rt");
        assertThat(firm.getContaAzulOauthState()).isNull();
        assertThat(firm.getContaAzulTokenExpiresAt()).isAfter(LocalDateTime.now().plusMinutes(55));
    }

    @Test
    void callbackWithWrongOrExpiredStateIsRejected() {
        service.buildAuthorizeUrl();
        assertThatThrownBy(() -> service.completeAuthorization("abc", "forged"))
                .isInstanceOf(BadRequestException.class);
        // the state is one-shot: even the right one no longer works after a failed attempt
        assertThat(firm.getContaAzulOauthState()).isNull();

        service.buildAuthorizeUrl();
        String state = firm.getContaAzulOauthState();
        firm.setContaAzulOauthStateCreatedAt(LocalDateTime.now().minusMinutes(11));
        assertThatThrownBy(() -> service.completeAuthorization("abc", state))
                .isInstanceOf(BadRequestException.class);
        verify(oauthClient, never()).exchangeCode(anyString());
    }

    @Test
    void validTokenIsReusedAndExpiredOneIsRefreshedKeepingRefreshToken() {
        firm.setContaAzulAccessToken("old");
        firm.setContaAzulRefreshToken("rt");
        firm.setContaAzulTokenExpiresAt(LocalDateTime.now().plusMinutes(30));
        assertThat(service.getValidAccessToken(firm.getId())).isEqualTo("old");
        verify(oauthClient, never()).refresh(anyString());

        firm.setContaAzulTokenExpiresAt(LocalDateTime.now().plusSeconds(30));
        when(oauthClient.refresh("rt")).thenReturn(new ContaAzulOAuthClient.TokenResponse("new", null, 3600L, "Bearer"));
        assertThat(service.getValidAccessToken(firm.getId())).isEqualTo("new");
        assertThat(firm.getContaAzulRefreshToken()).isEqualTo("rt");
    }

    @Test
    void notConfiguredOrNotConnectedFailsClearly() {
        assertThatThrownBy(() -> service.getValidAccessToken(firm.getId()))
                .isInstanceOf(ContaAzulIntegrationException.class)
                .hasMessage("error.contaAzul.not.connected");

        properties.setClientSecret(null);
        assertThatThrownBy(() -> service.buildAuthorizeUrl())
                .isInstanceOf(ContaAzulIntegrationException.class)
                .hasMessage("error.contaAzul.not.configured");
        assertThat(service.status().configured()).isFalse();
    }
}
