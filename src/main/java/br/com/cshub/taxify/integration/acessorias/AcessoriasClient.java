package br.com.cshub.taxify.integration.acessorias;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.exception.AcessoriasIntegrationException;
import feign.FeignException;
import feign.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;

/**
 * Pages through the Acessórias list endpoints (companies: 50 per page, users: 20 per page)
 * until a page comes back empty, as the API documentation prescribes.
 *
 * <p>Acessórias answers most problems (bad parameter, sometimes "nothing found") with HTTP
 * 200 and an {@code {"Erro": "..."}} object instead of an array - {@link #fetchPage} treats a
 * "nada encontrado" one as an empty page and any other as a failure carrying the API's own
 * message.
 */
@Component
public class AcessoriasClient {

    private static final Logger log = LoggerFactory.getLogger(AcessoriasClient.class);
    private static final int MAX_ERROR_BODY_CHARS = 500;
    private static final DateTimeFormatter CHANGED_AFTER_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** Safety net against a misbehaving API that never returns an empty page. */
    private static final int MAX_PAGES = 1000;

    private final AcessoriasFeignClient feignClient;
    private final AcessoriasProperties properties;
    private final ObjectMapper objectMapper;
    private final MessageService messageService;

    public AcessoriasClient(AcessoriasFeignClient feignClient, AcessoriasProperties properties, ObjectMapper objectMapper,
                            MessageService messageService) {
        this.feignClient = feignClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.messageService = messageService;
    }

    /**
     * Every company of the firm's Acessórias account (active and inactive), with its
     * departments/responsáveis, tax regime/company group and state registrations.
     *
     * @param apiToken     the calling AccountingFirm's own token (FirmIntegration of type ACESSORIAS)
     * @param changedAfter only companies created/changed after this; null for all of them
     */
    public List<AcessoriasCompanyDTO> listCompanies(String apiToken, LocalDateTime changedAfter) {
        String changedAfterParam = changedAfter != null
                ? "&DtLastDH=" + URLEncoder.encode(changedAfter.format(CHANGED_AFTER_FORMAT), StandardCharsets.UTF_8).replace("+", "%20")
                : "";
        return listAll(apiToken, AcessoriasCompanyDTO.class,
                page -> "/companies/ListAll/?departments&registrationData&stateRegistrations" + changedAfterParam + "&Pagina=" + page);
    }

    /** Every user (active and inactive) of the firm's Acessórias account. */
    public List<AcessoriasUserDTO> listUsers(String apiToken) {
        return listAll(apiToken, AcessoriasUserDTO.class, page -> "/users/ListAll?Pagina=" + page);
    }

    private <T> List<T> listAll(String apiToken, Class<T> type, IntFunction<String> pathForPage) {
        if (apiToken == null || apiToken.isBlank()) {
            throw new AcessoriasIntegrationException(messageService.getMessage("error.acessorias.api.token.missing"));
        }
        String authorization = "Bearer ".concat(apiToken.trim());

        List<T> items = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            List<T> pageItems = fetchPage(authorization, pathForPage.apply(page), type);
            if (pageItems.isEmpty()) {
                break;
            }
            items.addAll(pageItems);
        }
        return items;
    }

    private <T> List<T> fetchPage(String authorization, String path, Class<T> type) {
        URI uri = URI.create(properties.getBaseUrl().replaceAll("/+$", "") + path);
        try (Response response = feignClient.get(uri, authorization)) {
            byte[] body = readBody(response);

            if (response.status() == 401 || response.status() == 403) {
                throw new AcessoriasIntegrationException(messageService.getMessage("error.acessorias.api.token.invalid"));
            }
            if (response.status() == 429) {
                throw new AcessoriasIntegrationException(messageService.getMessage("error.acessorias.rate.limited"));
            }
            if (response.status() == 204 || response.status() == 404 || body.length == 0) {
                return List.of();
            }
            if (response.status() >= 400) {
                log.warn("Acessórias {} returned HTTP {}: {}", path, response.status(), errorSnippet(body));
                throw new AcessoriasIntegrationException(messageService.getMessage("error.acessorias.integration.failed"));
            }

            JsonNode root = objectMapper.readTree(body);
            if (!root.isArray()) {
                return emptyOrFail(path, root);
            }
            List<T> items = new ArrayList<>(root.size());
            for (JsonNode node : root) {
                items.add(objectMapper.treeToValue(node, type));
            }
            return items;
        } catch (FeignException e) {
            throw new AcessoriasIntegrationException(messageService.getMessage("error.acessorias.integration.failed"), e);
        } catch (JacksonException e) {
            log.warn("Could not parse Acessórias response for {}", path, e);
            throw new AcessoriasIntegrationException(messageService.getMessage("error.acessorias.integration.failed"), e);
        }
    }

    /** A non-array body: "nothing found" means an empty page; any other {"Erro": ...} is a real failure. */
    private <T> List<T> emptyOrFail(String path, JsonNode root) {
        JsonNode error = root.get("Erro");
        String message = error != null && error.isString() ? error.asString() : null;
        if (message == null) {
            return List.of();
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        if (normalized.contains("encontrad") || normalized.contains("nenhum")) {
            return List.of();
        }
        if (normalized.contains("token")) {
            throw new AcessoriasIntegrationException(messageService.getMessage("error.acessorias.api.token.invalid"));
        }
        log.warn("Acessórias {} answered with an error: {}", path, message);
        throw new AcessoriasIntegrationException(messageService.getMessage("error.acessorias.api.error", message));
    }

    private byte[] readBody(Response response) {
        if (response.body() == null) {
            return new byte[0];
        }
        try (InputStream in = response.body().asInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new AcessoriasIntegrationException(messageService.getMessage("error.acessorias.integration.failed"), e);
        }
    }

    private String errorSnippet(byte[] body) {
        String text = new String(body, StandardCharsets.UTF_8);
        return text.length() > MAX_ERROR_BODY_CHARS ? text.substring(0, MAX_ERROR_BODY_CHARS) : text;
    }
}
