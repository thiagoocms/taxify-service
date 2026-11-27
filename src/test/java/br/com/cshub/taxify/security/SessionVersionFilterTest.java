package br.com.cshub.taxify.security;

import br.com.cshub.taxify.repository.UserRepository;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** SN-RN-11: CA-SN-08, CA-SN-18, CA-SN-19, CT-SN-29, CT-SN-30 - the session check after the bearer token. */
class SessionVersionFilterTest {

    private final UserRepository users = mock(UserRepository.class);
    private final SecurityErrorHandler errors = mock(SecurityErrorHandler.class);
    private final RequestMatcher publicRoutes = request -> request.getRequestURI().endsWith("/auth/password/forgot");
    private final SessionVersionFilter filter = new SessionVersionFilter(users, errors, publicRoutes);
    private final FilterChain chain = mock(FilterChain.class);
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() throws IOException {
        request = new MockHttpServletRequest("GET", "/taxify-service/api/v1/companies");
        response = new MockHttpServletResponse();
        doAnswer(invocation -> {
            MockHttpServletResponse target = invocation.getArgument(1);
            target.setStatus(401);
            return null;
        }).when(errors).sessionExpired(any(), any());
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticate(Map<String, Object> extraClaims) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "RS256").subject("ana.ficticia")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).claim("userId", "x");
        extraClaims.forEach(builder::claim);
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(builder.build(), List.of(new SimpleGrantedAuthority("USER"))));
    }

    private void run() throws Exception {
        filter.doFilter(request, response, chain);
    }

    /** CA-SN-18 / CT-SN-29: a token issued before the deploy (no "sv") counts as version 0. */
    @Test
    void aTokenWithoutTheClaimPassesWhileTheVersionIsZero() throws Exception {
        authenticate(Map.of());
        when(users.findSessionVersionByLogin("ana.ficticia")).thenReturn(Optional.of(0));
        run();
        verify(chain).doFilter(request, response);
        verify(errors, never()).sessionExpired(any(), any());
    }

    /** CT-SN-29: ...and falls after the first password change. */
    @Test
    void aTokenWithoutTheClaimFallsAfterAChange() throws Exception {
        authenticate(Map.of());
        when(users.findSessionVersionByLogin("ana.ficticia")).thenReturn(Optional.of(1));
        run();
        verify(chain, never()).doFilter(any(), any());
        verify(errors).sessionExpired(request, response);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void theCurrentVersionPasses() throws Exception {
        authenticate(Map.of("sv", 3));
        when(users.findSessionVersionByLogin("ana.ficticia")).thenReturn(Optional.of(3));
        run();
        verify(chain).doFilter(request, response);
    }

    /** CA-SN-08 / CA-SN-12: an older version (the other browser) gets 401. */
    @Test
    void anOlderVersionGets401() throws Exception {
        authenticate(Map.of("sv", 2));
        when(users.findSessionVersionByLogin("ana.ficticia")).thenReturn(Optional.of(3));
        run();
        verify(errors).sessionExpired(request, response);
        verify(chain, never()).doFilter(any(), any());
    }

    /** CA-SN-19 / CT-SN-30: a deleted user (not found among the not deleted) gets 401 - never "system". */
    @Test
    void aDeletedUserGets401() throws Exception {
        authenticate(Map.of("sv", 0));
        when(users.findSessionVersionByLogin("ana.ficticia")).thenReturn(Optional.empty());
        run();
        verify(errors).sessionExpired(request, response);
        verify(chain, never()).doFilter(any(), any());
    }

    /** Fail closed: the read fails - 401. */
    @Test
    void aFailedReadGets401() throws Exception {
        authenticate(Map.of("sv", 0));
        when(users.findSessionVersionByLogin("ana.ficticia")).thenThrow(new IllegalStateException("db down"));
        run();
        verify(errors).sessionExpired(request, response);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void aMalformedClaimGets401() throws Exception {
        authenticate(Map.of("sv", "abc"));
        run();
        verify(errors).sessionExpired(request, response);
        verify(users, never()).findSessionVersionByLogin(any());

        SecurityContextHolder.clearContext();
        authenticate(Map.of("sv", 1.5));
        run();
        verify(chain, never()).doFilter(any(), any());
    }

    /** JSON numbers may come as Long: version 0 as 0L passes. */
    @Test
    void aLongClaimIsAccepted() throws Exception {
        authenticate(Map.of("sv", 0L));
        when(users.findSessionVersionByLogin("ana.ficticia")).thenReturn(Optional.of(0));
        run();
        verify(chain).doFilter(request, response);
    }

    /** No token: nothing to check, the rest of the chain decides (401 for a protected route, as before). */
    @Test
    void anonymousRequestsAreNotTouched() throws Exception {
        run();
        verify(chain).doFilter(request, response);
        verify(users, never()).findSessionVersionByLogin(any());
    }

    @Test
    void otherAuthenticationsAreNotTouched() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("x", null, List.of()));
        run();
        verify(chain).doFilter(request, response);
        verify(users, never()).findSessionVersionByLogin(any());
    }

    /** An old token sent to "forgot password" or the login never locks the person out: the call goes on as anonymous. */
    @Test
    void anOldTokenOnAPublicRouteIsIgnored() throws Exception {
        request = new MockHttpServletRequest("POST", "/taxify-service/api/v1/auth/password/forgot");
        authenticate(Map.of("sv", 0));
        run();
        verify(chain).doFilter(request, response);
        verify(users, never()).findSessionVersionByLogin(any());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
