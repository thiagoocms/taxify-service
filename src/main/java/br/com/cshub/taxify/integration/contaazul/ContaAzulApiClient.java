package br.com.cshub.taxify.integration.contaazul;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.common.RestClients;
import br.com.cshub.taxify.exception.ContaAzulIntegrationException;
import br.com.cshub.taxify.service.ContaAzulAuthService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Read-only calls to the Conta Azul API v2 on behalf of one firm - field names exactly as
 * the OpenAPI specs at developers.contaazul.com (Pessoas, Contratos, Financeiro). Turning
 * them into Taxify's neutral billing records is ContaAzulBillingProvider's job.
 */
@Component
public class ContaAzulApiClient {

    private static final Logger log = LoggerFactory.getLogger(ContaAzulApiClient.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);
    /** Safety net against an API that keeps returning full pages. */
    private static final int MAX_PAGES = 200;
    private static final int FIRST_PAGE = 1;
    static final int MAX_ATTEMPTS = 4;
    private static final Duration BASE_BACKOFF = Duration.ofSeconds(2);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    private final ContaAzulAuthService authService;
    private final ContaAzulProperties properties;
    private final MessageService messageService;
    private final RestClient restClient = RestClients.withTimeouts(CONNECT_TIMEOUT, READ_TIMEOUT);
    Sleeper sleeper = ContaAzulApiClient::sleepQuietly;

    public ContaAzulApiClient(ContaAzulAuthService authService, ContaAzulProperties properties, MessageService messageService) {
        this.authService = authService;
        this.properties = properties;
        this.messageService = messageService;
    }

    // ------------------------------------------------------------------ response shapes

    /** A page of any list endpoint - Conta Azul names the fields differently per API. */
    interface Page<T> {
        List<T> content();

        Integer total();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Ref(String id, String nome) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Pessoa(String id, String nome, String documento, Boolean ativo) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PessoasPage(List<Pessoa> items, Integer totalItems) implements Page<Pessoa> {
        public List<Pessoa> content() {
            return items;
        }

        public Integer total() {
            return totalItems;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Contrato(
            String id,
            Ref cliente,
            String status,
            @JsonProperty("proximo_vencimento") String proximoVencimento,
            @JsonProperty("data_inicio") String dataInicio,
            Integer numero
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ContratosPage(@JsonProperty("itens_totais") Integer total, List<Contrato> items) implements Page<Contrato> {
        public List<Contrato> content() {
            return items;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ContaAReceber(
            String id,
            String descricao,
            @JsonProperty("data_vencimento") String dataVencimento,
            @JsonProperty("data_competencia") String dataCompetencia,
            String status,
            @JsonProperty("status_traduzido") String statusTraduzido,
            BigDecimal total,
            @JsonProperty("nao_pago") BigDecimal naoPago,
            BigDecimal pago,
            List<Ref> categorias,
            Ref cliente
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ContasAReceberPage(@JsonProperty("itens_totais") Integer total, List<ContaAReceber> itens) implements Page<ContaAReceber> {
        public List<ContaAReceber> content() {
            return itens;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Categoria(String id, String nome, String tipo) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CategoriasPage(@JsonProperty("itens_totais") Integer total, List<Categoria> itens) implements Page<Categoria> {
        public List<Categoria> content() {
            return itens;
        }
    }

    // ------------------------------------------------------------------ calls

    /** GET /v1/pessoas?documentos= - the customer(s) with this CNPJ/CPF (one page is plenty). */
    public List<Pessoa> findPessoasByDocument(UUID firmId, String document) {
        PessoasPage page = get(firmId, uri -> uri.path("/v1/pessoas")
                .queryParam("documentos", document)
                .queryParam("pagina", FIRST_PAGE)
                .queryParam("tamanho_pagina", 10)
                .build(), PessoasPage.class);
        return contentOf(page);
    }

    /** GET /v1/contratos - every contract of one customer (the start-date filter is mandatory). */
    public List<Contrato> listContratos(UUID firmId, String clienteId, String dataInicio, String dataFim) {
        return fetchAll(firmId, 50, ContratosPage.class, (uri, page) -> uri.path("/v1/contratos")
                .queryParam("cliente_id", clienteId)
                .queryParam("data_inicio", dataInicio)
                .queryParam("data_fim", dataFim)
                .queryParam("pagina", page.number())
                .queryParam("tamanho_pagina", page.size())
                .build());
    }

    /** GET /v1/financeiro/eventos-financeiros/contas-a-receber/buscar - receivables (fee charges). */
    public List<ContaAReceber> searchContasAReceber(UUID firmId, List<String> idsClientes, List<String> idsCategorias,
                                                    String dataVencimentoDe, String dataVencimentoAte, String dataAlteracaoDe) {
        return fetchAll(firmId, 100, ContasAReceberPage.class, (uri, page) -> {
            UriBuilder builder = uri.path("/v1/financeiro/eventos-financeiros/contas-a-receber/buscar")
                    .queryParam("pagina", page.number())
                    .queryParam("tamanho_pagina", page.size())
                    .queryParam("data_vencimento_de", dataVencimentoDe)
                    .queryParam("data_vencimento_ate", dataVencimentoAte);
            addArray(builder, "ids_clientes", idsClientes);
            addArray(builder, "ids_categorias", idsCategorias);
            if (dataAlteracaoDe != null) {
                builder.queryParam("data_alteracao_de", dataAlteracaoDe);
            }
            return builder.build();
        });
    }

    /** GET /v1/categorias?tipo=RECEITA */
    public List<Categoria> listCategoriasReceita(UUID firmId) {
        return fetchAll(firmId, 200, CategoriasPage.class, (uri, page) -> uri.path("/v1/categorias")
                .queryParam("tipo", "RECEITA")
                .queryParam("pagina", page.number())
                .queryParam("tamanho_pagina", page.size())
                .build());
    }

    // ------------------------------------------------------------------ plumbing

    private record PageRequest(int number, int size) {
    }

    /** Walks pages from 1 until a short page, the reported total, or {@link #MAX_PAGES}. */
    private <T, P extends Page<T>> List<T> fetchAll(UUID firmId, int pageSize, Class<P> pageType,
                                                    BiFunction<UriBuilder, PageRequest, URI> uriForPage) {
        List<T> all = new ArrayList<>();
        for (int number = FIRST_PAGE; number < FIRST_PAGE + MAX_PAGES; number++) {
            PageRequest request = new PageRequest(number, pageSize);
            P page = get(firmId, uri -> uriForPage.apply(uri, request), pageType);
            List<T> items = contentOf(page);
            all.addAll(items);
            boolean lastPage = items.size() < pageSize || (page.total() != null && all.size() >= page.total());
            if (lastPage) {
                break;
            }
        }
        return all;
    }

    private static <T> List<T> contentOf(Page<T> page) {
        return page == null || page.content() == null ? List.of() : page.content();
    }

    /** Array query params go repeated (ids_clientes=a&ids_clientes=b), OpenAPI's default "form, explode". */
    private static void addArray(UriBuilder builder, String name, Collection<String> values) {
        if (values != null && !values.isEmpty()) {
            builder.queryParam(name, values.toArray());
        }
    }

    /**
     * One GET with the firm's token. A 429 (rate limit - more likely now that a sync runs a few
     * companies in parallel) is retried up to {@link #MAX_ATTEMPTS} times, waiting what the
     * {@code Retry-After} header says or an exponential backoff; anything else fails at once.
     */
    private <T> T get(UUID firmId, Function<UriBuilder, URI> uri, Class<T> type) {
        for (int attempt = 1; ; attempt++) {
            String token = authService.getValidAccessToken(firmId);
            try {
                return restClient.get()
                        .uri(properties.getApiBaseUrl(), uri)
                        .header("Authorization", "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .body(type);
            } catch (RestClientResponseException e) {
                boolean rateLimited = e.getStatusCode().value() == HttpStatus.TOO_MANY_REQUESTS.value();
                if (rateLimited && attempt < MAX_ATTEMPTS) {
                    Duration wait = retryDelay(e.getResponseHeaders() == null ? null : e.getResponseHeaders().getFirst("Retry-After"), attempt);
                    log.info("Conta Azul rate limit for firm {} - retry {}/{} in {}s", firmId, attempt, MAX_ATTEMPTS - 1, wait.toSeconds());
                    sleeper.sleep(wait);
                    continue;
                }
                log.warn("Conta Azul API answered {} for firm {}: {}", e.getStatusCode(), firmId, e.getResponseBodyAsString());
                throw new ContaAzulIntegrationException(messageFor(e.getStatusCode().value()), e);
            } catch (RestClientException e) {
                log.warn("Conta Azul API unreachable for firm {}", firmId, e);
                throw new ContaAzulIntegrationException(messageService.getMessage("error.contaAzul.integration.failed"), e);
            }
        }
    }

    /** {@code Retry-After} in seconds when the API sends it, else 2s, 4s, 8s... capped at {@link #MAX_BACKOFF}. */
    static Duration retryDelay(String retryAfter, int attempt) {
        if (retryAfter != null) {
            try {
                long seconds = Long.parseLong(retryAfter.trim());
                if (seconds >= 0) {
                    return Duration.ofSeconds(Math.min(seconds, MAX_BACKOFF.toSeconds()));
                }
            } catch (NumberFormatException ignored) {
                // HTTP-date form - fall back to the backoff below
            }
        }
        Duration backoff = BASE_BACKOFF.multipliedBy(1L << (attempt - 1));
        return backoff.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff;
    }

    /** Waiting is pluggable so tests don't actually sleep. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration);
    }

    private static void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry Conta Azul", e);
        }
    }

    private String messageFor(int status) {
        if (status == HttpStatus.TOO_MANY_REQUESTS.value()) {
            return messageService.getMessage("error.contaAzul.rate.limited");
        }
        if (status == HttpStatus.UNAUTHORIZED.value() || status == HttpStatus.FORBIDDEN.value()) {
            return messageService.getMessage("error.contaAzul.refresh.rejected");
        }
        return messageService.getMessage("error.contaAzul.integration.failed");
    }
}
