package br.com.cshub.taxify.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CorsFilterTest {

    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    @Mock
    private FilterChain chain;

    private final CorsFilter filter = new CorsFilter();

    @Test
    void optionsRequestsAreAnsweredDirectlyWithoutReachingTheChain() throws Exception {
        when(request.getMethod()).thenReturn("OPTIONS");

        filter.doFilter(request, response, chain);

        verify(response).setHeader("Access-Control-Allow-Origin", "*");
        verify(response).setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        verify(response).setHeader("Access-Control-Allow-Headers",
                "Authorization, Content-Type, Accept, Origin, X-Requested-With, ngrok-skip-browser-warning");
        verify(response).setStatus(HttpServletResponse.SC_OK);
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void optionsMethodCheckIsCaseInsensitive() throws Exception {
        when(request.getMethod()).thenReturn("options");

        filter.doFilter(request, response, chain);

        verify(response).setStatus(HttpServletResponse.SC_OK);
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void nonOptionsRequestsSetCorsHeadersAndContinueTheChain() throws Exception {
        when(request.getMethod()).thenReturn("GET");

        filter.doFilter(request, response, chain);

        verify(response).setHeader("Access-Control-Allow-Origin", "*");
        verify(chain).doFilter(request, response);
        verify(response, never()).setStatus(HttpServletResponse.SC_OK);
    }
}
