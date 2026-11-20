package br.com.cshub.taxify.integration.acessorias;

import feign.Response;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

import java.net.URI;

/**
 * Declarative Feign client for the Acessórias REST API (https://api.acessorias.com/documentation).
 * {@code Authorization} carries the calling AccountingFirm's own token ("Bearer ..."),
 * passed explicitly by {@link AcessoriasClient} - each firm has its own Acessórias account.
 *
 * <p>Takes the full request {@link URI} instead of {@code @RequestParam}s because the
 * API's flags ({@code departments}, {@code registrationData}...) are bare presence flags
 * ({@code ?departments&registrationData}) - Spring's contract would always send them as
 * {@code departments=}. {@link AcessoriasClient} builds the URI; Feign uses it in place of
 * the configured url.
 *
 * <p>Returns the raw {@link Response} because the API reports most errors as HTTP 200 with
 * an {@code {"Erro": "..."}} object instead of the expected array, so
 * {@link AcessoriasClient} reads and classifies the body itself.
 */
@FeignClient(name = "acessoriasClient", url = "${acessorias.base-url}")
public interface AcessoriasFeignClient {

    @GetMapping
    Response get(URI uri, @RequestHeader("Authorization") String authorization);
}
