package br.com.cshub.taxify.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

class AuditorAwareImplTest {

    private final AuditorAwareImpl auditorAware = new AuditorAwareImpl();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void returnsAnonymousWhenThereIsNoAuthentication() {
        SecurityContextHolder.clearContext();

        assertThat(auditorAware.getCurrentAuditor()).contains("anonymous");
    }

    @Test
    void returnsAnonymousWhenAuthenticationIsNotAuthenticated() {
        var token = new UsernamePasswordAuthenticationToken("jane", "pwd");
        token.setAuthenticated(false);
        SecurityContextHolder.getContext().setAuthentication(token);

        assertThat(auditorAware.getCurrentAuditor()).contains("anonymous");
    }

    @Test
    void returnsTheAuthenticatedPrincipalName() {
        var token = new UsernamePasswordAuthenticationToken("jane", "pwd", java.util.List.of());
        SecurityContextHolder.getContext().setAuthentication(token);

        assertThat(auditorAware.getCurrentAuditor()).contains("jane");
    }

    @Test
    void returnsUnknownWhenReadingTheAuthenticationFails() {
        Authentication broken = Mockito.mock(Authentication.class);
        Mockito.when(broken.isAuthenticated()).thenReturn(true);
        Mockito.when(broken.getName()).thenThrow(new RuntimeException("boom"));
        SecurityContextHolder.getContext().setAuthentication(broken);

        assertThat(auditorAware.getCurrentAuditor()).contains("unknown");
    }
}
