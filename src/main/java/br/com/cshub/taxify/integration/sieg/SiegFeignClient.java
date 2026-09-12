package br.com.cshub.taxify.integration.sieg;

import feign.Response;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * Declarative Feign client for SIEG's "download XMLs in batch" web service.
 * The {@code X-API-Key} header and (when configured) the OAuth bearer token are
 * added by {@link SiegFeignClientConfig}'s request interceptor, which also enforces
 * the rate limit - so this interface only declares the call shape.
 *
 * <p>Verified against the live API: despite the {@code Accept: application/json}
 * header, a successful call returns the raw zip archive of XMLs as the response
 * body, not a JSON envelope - so this returns the untouched {@link Response} and
 * {@link SiegClient} reads/classifies the body itself.
 */
@FeignClient(name = "siegClient", url = "${sieg.base-url}", configuration = SiegFeignClientConfig.class)
public interface SiegFeignClient {

    @PostMapping(path = "/api/v1/baixar-xmls", produces = MediaType.APPLICATION_JSON_VALUE)
    Response baixarXmls(@RequestBody SiegBaixarXmlsRequest request);

    /**
     * Counts how many fiscal documents match the filter, broken down by document type
     * (NFe/NFCe/CTe/CFe/NFSe) - verified live: same request body shape as baixar-xmls,
     * but this one is a real JSON response, not a raw zip.
     */
    @PostMapping(path = "/api/v1/contar-xmls", produces = MediaType.APPLICATION_JSON_VALUE)
    SiegContarXmlsResponse contarXmls(@RequestBody SiegBaixarXmlsRequest request);
}
