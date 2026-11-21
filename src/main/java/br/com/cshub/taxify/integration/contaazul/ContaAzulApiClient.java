package br.com.cshub.taxify.integration.contaazul;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.exception.ContaAzulIntegrationException;
import br.com.cshub.taxify.service.ContaAzulAuthService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Read-only calls to the Conta Azul API v2 (https://api-v2.contaazul.com) on behalf of one
 * firm - field names exactly as the OpenAPI specs published at developers.contaazul.com
 * (Pessoas, Contratos, Financeiro). Translating them into Taxify's neutral billing records
 * is ContaAzulBillingProvider's job, not this class's.
 */
@Component
public class ContaAzulApiClient {

    private static final Logger log = LoggerFactory.getLogger(ContaAzulApiClient.class);
    /** Safety net against a provider that keeps returning full pages. */
    private static final int MAX_PAGES = 200;

    private final ContaAzulAuthService authService;
    private final ContaAzulProperties properties;
    private final MessageService messageService;
    private final RestClient restClient;

    public ContaAzulApiClient(ContaAzulAuthService authService, ContaAzulProperties properties, MessageService messageService) {
        this.authService = authService;
        this.properties = properties;
        this.messageService = messageService;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(15));
        requestFactory.setReadTimeout(Duration.ofSeconds(60));
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    // ------------------------------------------------------------------ response shapes

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Ref(String id, String nome) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Pessoa(String id, String nome, String documento, Boolean ativo) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PessoasPage(List<Pessoa> items, Integer totalItems) {
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
    record ContratosPage(@JsonProperty("itens_totais") Integer total, List<Contrato> items) {
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
    record ContasAReceberPage(@JsonProperty("itens_totais") Integer total, List<ContaAReceber> itens) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Categoria(String id, String nome, String tipo) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CategoriasPage(@JsonProperty("itens_totais") Integer total, List<Categoria> itens) {
    }

    // ------------------------------------------------------------------ calls

    /** GET /v1/pessoas?documentos= - the customer(s) with this CNPJ/CPF. */
    public List<Pessoa> findPessoasByDocument(UUID firmId, String document) {
        PessoasPage page = get(firmId, uri -> uri.path("/v1/pessoas")
                .queryParam("documentos", document)
                .queryParam("pagina", 1)
                .queryParam("tamanho_pagina", 10)
                .build(), PessoasPage.class);
        return page == null || page.items() == null ? List.of() : page.items();
    }

    /** GET /v1/contratos - every contract of one customer (start date filter is mandatory, so a wide window). */
    public List<Contrato> listContratos(UUID firmId, String clienteId, String dataInicio, String dataFim) {
        List<Contrato> all = new ArrayList<>();
        for (int pagina = 1; pagina <= MAX_PAGES; pagina++) {
            int currentPage = pagina;
            ContratosPage page = get(firmId, uri -> uri.path("/v1/contratos")
                    .queryParam("cliente_id", clienteId)
                    .queryParam("data_inicio", dataInicio)
                    .queryParam("data_fim", dataFim)
                    .queryParam("pagina", currentPage)
                    .queryParam("tamanho_pagina", 50)
                    .build(), ContratosPage.class);
            List<Contrato> items = page == null || page.items() == null ? List.of() : page.items();
            all.addAll(items);
            if (items.size() < 50 || (page.total() != null && all.size() >= page.total())) {
                break;
            }
        }
        return all;
    }

    /** GET /v1/financeiro/eventos-financeiros/contas-a-receber/buscar - receivables (fee charges). */
    public List<ContaAReceber> searchContasAReceber(UUID firmId, List<String> idsClientes, List<String> idsCategorias,
                                                    String dataVencimentoDe, String dataVencimentoAte, String dataAlteracaoDe) {
        List<ContaAReceber> all = new ArrayList<>();
        int pageSize = 100;
        for (int pagina = 1; pagina <= MAX_PAGES; pagina++) {
            int currentPage = pagina;
            ContasAReceberPage page = get(firmId, uri -> {
                UriBuilder builder = uri.path("/v1/financeiro/eventos-financeiros/contas-a-receber/buscar")
                        .queryParam("pagina", currentPage)
                        .queryParam("tamanho_pagina", pageSize)
                        .queryParam("data_vencimento_de", dataVencimentoDe)
                        .queryParam("data_vencimento_ate", dataVencimentoAte);
                if (idsClientes != null && !idsClientes.isEmpty()) {
                    builder.queryParam("ids_clientes", idsClientes.toArray());
                }
                if (idsCategorias != null && !idsCategorias.isEmpty()) {
                    builder.queryParam("ids_categorias", idsCategorias.toArray());
                }
                if (dataAlteracaoDe != null) {
                    builder.queryParam("data_alteracao_de", dataAlteracaoDe);
                }
                return builder.build();
            }, ContasAReceberPage.class);
            List<ContaAReceber> items = page == null || page.itens() == null ? List.of() : page.itens();
            all.addAll(items);
            if (items.size() < pageSize || (page.total() != null && all.size() >= page.total())) {
                break;
            }
        }
        return all;
    }

    /** GET /v1/categorias?tipo=RECEITA */
    public List<Categoria> listCategoriasReceita(UUID firmId) {
        List<Categoria> all = new ArrayList<>();
        int pageSize = 200;
        for (int pagina = 1; pagina <= MAX_PAGES; pagina++) {
            int currentPage = pagina;
            CategoriasPage page = get(firmId, uri -> uri.path("/v1/categorias")
                    .queryParam("tipo", "RECEITA")
                    .queryParam("pagina", currentPage)
                    .queryParam("tamanho_pagina", pageSize)
                    .build(), CategoriasPage.class);
            List<Categoria> items = page == null || page.itens() == null ? List.of() : page.itens();
            all.addAll(items);
            if (items.size() < pageSize || (page.total() != null && all.size() >= page.total())) {
                break;
            }
        }
        return all;
    }

    private <T> T get(UUID firmId, Function<UriBuilder, URI> uri, Class<T> type) {
        String token = authService.getValidAccessToken(firmId);
        try {
            return restClient.get()
                    .uri(properties.getApiBaseUrl(), uri)
                    .header("Authorization", "Bearer " + token)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(type);
        } catch (RestClientResponseException e) {
            log.warn("Conta Azul API answered {} for firm {}: {}", e.getStatusCode(), firmId, e.getResponseBodyAsString());
            if (e.getStatusCode().value() == HttpStatus.TOO_MANY_REQUESTS.value()) {
                throw new ContaAzulIntegrationException(messageService.getMessage("error.contaAzul.rate.limited"), e);
            }
            if (e.getStatusCode().value() == HttpStatus.UNAUTHORIZED.value()
                    || e.getStatusCode().value() == HttpStatus.FORBIDDEN.value()) {
                throw new ContaAzulIntegrationException(messageService.getMessage("error.contaAzul.refresh.rejected"), e);
            }
            throw new ContaAzulIntegrationException(messageService.getMessage("error.contaAzul.integration.failed"), e);
        } catch (RestClientException e) {
            log.warn("Conta Azul API unreachable for firm {}", firmId, e);
            throw new ContaAzulIntegrationException(messageService.getMessage("error.contaAzul.integration.failed"), e);
        }
    }
}
