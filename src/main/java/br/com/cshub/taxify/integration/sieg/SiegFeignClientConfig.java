package br.com.cshub.taxify.integration.sieg;

import feign.RequestInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;

/**
 * Per-client Feign configuration for {@link SiegFeignClient}.
 * Deliberately NOT annotated with {@code @Configuration}/{@code @Component}: it's
 * wired in via {@code @FeignClient(configuration = ...)} and instantiated in that
 * client's own child context, so it must not be picked up by the app's main
 * component scan (which would make it a default for every Feign client).
 */
public class SiegFeignClientConfig {

    private static final Logger log = LoggerFactory.getLogger(SiegFeignClientConfig.class);

    @Bean
    public RequestInterceptor siegRequestInterceptor(SiegTokenService tokenService, SiegRateLimiter rateLimiter) {
        return template -> {
            rateLimiter.acquire();
            if (tokenService.isConfigured()) {
                template.header(HttpHeaders.AUTHORIZATION, "Bearer ".concat(tokenService.getAccessToken()));
                log.info("SIEG OAuth configured");
            } else {
                log.warn("SIEG OAuth not configured (clientId/clientSecret/tokenUrl blank) - "
                        + "calling baixar-xmls with X-API-Key only, no Authorization header");
            }
        };
    }
}
