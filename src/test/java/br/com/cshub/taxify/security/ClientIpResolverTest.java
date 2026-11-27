package br.com.cshub.taxify.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** F1-RN-06 / CT1-14 (IPs de documentação, RFC 5737). */
class ClientIpResolverTest {

    private final ClientIpResolver resolver = new ClientIpResolver(List.of());

    /** CT1-14: X-Forwarded-For mandado direto ao backend, sem o Caddy, é ignorado - vale o IP da conexão. */
    @Test
    void forwardedForFromOutsideIsIgnored() {
        assertThat(resolver.resolve("203.0.113.10", "198.51.100.7")).isEqualTo("203.0.113.10");
    }

    /** Pelo Caddy e pelo nginx do front (rede do Docker): o cliente é o primeiro endereço não confiável da direita. */
    @Test
    void behindTheProxiesTheClientIsTheRightmostUntrustedHop() {
        assertThat(resolver.resolve("172.18.0.4", "198.51.100.7, 172.18.0.3")).isEqualTo("198.51.100.7");
        // what the client itself wrote on the left is not trusted
        assertThat(resolver.resolve("172.18.0.4", "1.2.3.4, 198.51.100.7, 172.18.0.3")).isEqualTo("198.51.100.7");
    }

    @Test
    void withoutTheHeaderOrWithGarbageTheConnectionOrLastGoodHopWins() {
        assertThat(resolver.resolve("172.18.0.4", null)).isEqualTo("172.18.0.4");
        assertThat(resolver.resolve("172.18.0.4", "nao-e-ip")).isEqualTo("172.18.0.4");
        assertThat(resolver.resolve("127.0.0.1", "10.0.0.5")).isEqualTo("10.0.0.5");
    }

    @Test
    void readsTheConnectionAddressUnderTheWrappers() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.10");
        request.addHeader("X-Forwarded-For", "198.51.100.7");
        jakarta.servlet.http.HttpServletRequestWrapper forwarded = new jakarta.servlet.http.HttpServletRequestWrapper(request) {
            @Override
            public String getRemoteAddr() {
                return "198.51.100.7"; // what ForwardedHeaderFilter would answer
            }
        };
        assertThat(resolver.resolve(forwarded)).isEqualTo("203.0.113.10");
    }

    @Test
    void customTrustedProxies() {
        ClientIpResolver custom = new ClientIpResolver(List.of("203.0.113.0/24"));
        assertThat(custom.resolve("203.0.113.10", "198.51.100.7")).isEqualTo("198.51.100.7");
        assertThat(custom.resolve("172.18.0.4", "198.51.100.7")).isEqualTo("172.18.0.4");
    }
}
