package br.com.cshub.taxify.integration.sieg;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * Declarative Feign client for SIEG's JWT creation endpoint (POST /api/v1/create-jwt).
 * Matches SIEG's documented call exactly: credentials travel as the {@code X-Secret-Key}
 * / {@code X-Client-Id} headers, not as meaningful request-body fields, and the response
 * is the raw JWT. A body is still sent ("{}") because SIEG's IIS-hosted endpoint replies
 * "411 Length Required" to a bodyless POST that has no Content-Length header - curl avoids
 * this by sending Content-Length: 0 automatically, which Feign doesn't do for a null body.
 * The URL is only resolved (and called) when {@link SiegTokenService#isConfigured()}
 * is true - see {@link SiegProperties#getTokenUrl()}.
 */
@FeignClient(name = "siegTokenClient", url = "${sieg.token-url}")
public interface SiegTokenFeignClient {

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    String createJwt(@RequestHeader("X-Secret-Key") String secretKey, @RequestHeader("X-Client-Id") String clientId,
                      @RequestBody String emptyJsonBody);
}
