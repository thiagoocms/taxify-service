package br.com.cshub.taxify.security;

import br.com.cshub.taxify.config.SaasProperties;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * O IP de quem fez o cadastro (F1-RN-06). O {@code X-Forwarded-For} só vale quando a conexão vem de um proxy
 * confiável (o Caddy e o nginx do front, na rede interna do Docker); vindo direto de fora, ele é ignorado e vale o IP
 * da conexão. Do header, a leitura é da direita para a esquerda, pulando os proxies confiáveis: o primeiro endereço que
 * não é proxy é o cliente - o que o próprio cliente escreve à esquerda não é levado em conta.
 *
 * <p>O IP da conexão e o header são lidos da requisição original do Tomcat (por baixo dos wrappers), porque o
 * ForwardedHeaderFilter do Spring ({@code server.forward-headers-strategy=framework}) troca o {@code getRemoteAddr()}
 * pelo valor do header, sem conferir de onde veio, e esconde o header no wrapper.
 */
@Component
public class ClientIpResolver {

    private static final String FORWARDED_FOR = "X-Forwarded-For";
    private static final List<String> DEFAULT_TRUSTED = List.of(
            "127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7");

    private final List<Cidr> trusted;

    @Autowired
    public ClientIpResolver(SaasProperties properties) {
        this(properties.getTrustedProxies());
    }

    ClientIpResolver(List<String> trustedProxies) {
        List<String> source = trustedProxies == null || trustedProxies.stream().allMatch(value -> value == null || value.isBlank())
                ? DEFAULT_TRUSTED : trustedProxies;
        List<Cidr> parsed = new ArrayList<>();
        for (String value : source) {
            if (value != null && !value.isBlank()) {
                parsed.add(Cidr.parse(value.strip()));
            }
        }
        this.trusted = List.copyOf(parsed);
    }

    public String resolve(HttpServletRequest request) {
        ServletRequest original = original(request);
        String header = original instanceof HttpServletRequest http ? http.getHeader(FORWARDED_FOR) : request.getHeader(FORWARDED_FOR);
        return resolve(original.getRemoteAddr(), header);
    }

    /** {@code connection}: IP da conexão TCP; {@code forwardedFor}: o header como chegou (pode ser nulo). */
    String resolve(String connection, String forwardedFor) {
        if (connection == null || !isTrusted(connection) || forwardedFor == null || forwardedFor.isBlank()) {
            return connection;
        }
        String[] hops = forwardedFor.split(",");
        String leftmost = null;
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = clean(hops[i]);
            if (hop == null || !looksLikeIp(hop)) {
                // lixo no header: não dá para confiar no que estiver à esquerda
                return leftmost == null ? connection : leftmost;
            }
            leftmost = hop;
            if (!isTrusted(hop)) {
                return hop;
            }
        }
        return leftmost == null ? connection : leftmost;
    }

    /** A requisição do Tomcat por baixo dos wrappers - o ForwardedHeaderFilter troca o IP e esconde o header no wrapper. */
    private static ServletRequest original(HttpServletRequest request) {
        ServletRequest original = request;
        while (original instanceof ServletRequestWrapper wrapper) {
            original = wrapper.getRequest();
        }
        return original;
    }

    private boolean isTrusted(String ip) {
        byte[] address = toBytes(ip);
        return address != null && trusted.stream().anyMatch(cidr -> cidr.contains(address));
    }

    private static String clean(String hop) {
        String value = hop.strip();
        if (value.isEmpty()) {
            return null;
        }
        if (value.startsWith("[") && value.contains("]")) {
            return value.substring(1, value.indexOf(']'));
        }
        // "1.2.3.4:5678" - a porta não interessa
        if (value.indexOf(':') == value.lastIndexOf(':') && value.contains(".") && value.contains(":")) {
            return value.substring(0, value.indexOf(':'));
        }
        return value;
    }

    private static boolean looksLikeIp(String value) {
        return value.matches("[0-9a-fA-F:.]+") && toBytes(value) != null;
    }

    /** Só endereços literais - nunca consulta DNS. */
    private static byte[] toBytes(String ip) {
        if (ip == null || ip.isBlank() || !ip.matches("[0-9a-fA-F:.%]+")) {
            return null;
        }
        try {
            return InetAddress.getByName(ip.toLowerCase(Locale.ROOT)).getAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private record Cidr(byte[] network, int prefix) {

        static Cidr parse(String value) {
            String[] parts = value.split("/");
            byte[] network = toBytes(parts[0]);
            if (network == null) {
                throw new IllegalArgumentException("TAXIFY_TRUSTED_PROXIES: invalid address " + value);
            }
            int prefix = parts.length > 1 ? Integer.parseInt(parts[1]) : network.length * 8;
            return new Cidr(network, prefix);
        }

        boolean contains(byte[] address) {
            if (address.length != network.length) {
                return false;
            }
            int bits = network.length * 8;
            BigInteger mask = prefix == 0 ? BigInteger.ZERO
                    : BigInteger.ONE.shiftLeft(prefix).subtract(BigInteger.ONE).shiftLeft(bits - prefix);
            return new BigInteger(1, address).and(mask).equals(new BigInteger(1, network).and(mask));
        }
    }
}
