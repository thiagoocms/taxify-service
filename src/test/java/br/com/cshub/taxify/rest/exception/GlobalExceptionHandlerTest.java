package br.com.cshub.taxify.rest.exception;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.dto.ErrorDetailsDTO;
import br.com.cshub.taxify.enumerated.BlockedReason;
import br.com.cshub.taxify.enumerated.FirmSubscriptionStatus;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.exception.ConflictException;
import br.com.cshub.taxify.exception.FirmAccessBlockedException;
import br.com.cshub.taxify.exception.ForbiddenException;
import br.com.cshub.taxify.exception.ResourceNotFoundException;
import br.com.cshub.taxify.exception.ServiceUnavailableException;
import br.com.cshub.taxify.exception.SiegIntegrationException;
import br.com.cshub.taxify.exception.TooManyRequestsException;
import br.com.cshub.taxify.service.firmaccess.FirmAccess;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.context.request.WebRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** One error format for the whole API: {timestamp, status, code, message, details} plus the error's extras at the top. */
class GlobalExceptionHandlerTest {

    private final MessageService messages = mock(MessageService.class);
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(messages);
    private final WebRequest request = mock(WebRequest.class);
    private final ObjectMapper json = JsonMapper.builder().build();

    GlobalExceptionHandlerTest() {
        when(request.getDescription(false)).thenReturn("uri=/api/v1/test");
    }

    private JsonNode body(ResponseEntity<ErrorDetailsDTO> response) {
        return json.readTree(json.writeValueAsString(response.getBody()));
    }

    private static List<String> fields(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.propertyNames().forEach(names::add);
        return names;
    }

    @Test
    void aCommonErrorCarriesTheHttpStatusAndTheMessageKey() {
        JsonNode notFound = body(handler.handleResourceNotFoundException(
                new ResourceNotFoundException("error.plan.not.found", "Plano não encontrado."), request));
        assertThat(fields(notFound)).containsExactly("timestamp", "status", "code", "message", "details");
        assertThat(notFound.get("status").asInt()).isEqualTo(404);
        assertThat(notFound.get("code").asText()).isEqualTo("error.plan.not.found");
        assertThat(notFound.get("message").asText()).isEqualTo("Plano não encontrado.");
        assertThat(notFound.get("details").asText()).isEqualTo("uri=/api/v1/test");

        // an old call with only the message still gets a code - the generic one of its status
        JsonNode badRequest = body(handler.handleBadRequestException(new BadRequestException("Competência inválida."), request));
        assertThat(badRequest.get("status").asInt()).isEqualTo(400);
        assertThat(badRequest.get("code").asText()).isEqualTo("error.generic.bad.request");

        assertThat(body(handler.handleTooManyRequestsException(new TooManyRequestsException("error.signup.rate.limited", "x"), request))
                .get("status").asInt()).isEqualTo(429);
        assertThat(body(handler.handleServiceUnavailableException(new ServiceUnavailableException("error.signup.unavailable", "x"), request))
                .get("status").asInt()).isEqualTo(503);
        assertThat(body(handler.handleForbiddenBusinessException(new ForbiddenException("error.signup.email.not.verified", "x"), request))
                .get("code").asText()).isEqualTo("error.signup.email.not.verified");
        JsonNode sieg = body(handler.handleSiegIntegrationException(new SiegIntegrationException("falhou"), request));
        assertThat(sieg.get("status").asInt()).isEqualTo(502);
        assertThat(sieg.get("code").asText()).isEqualTo("error.sieg.integration.failed");
    }

    @Test
    void accessDeniedAndUnexpectedErrorsHaveACodeToo() {
        when(messages.getMessage("error.access.denied")).thenReturn("sem permissão");
        JsonNode denied = body(handler.handleAccessDeniedException(new AccessDeniedException("x"), request));
        assertThat(denied.get("status").asInt()).isEqualTo(403);
        assertThat(denied.get("code").asText()).isEqualTo("error.access.denied");
        assertThat(denied.get("message").asText()).isEqualTo("sem permissão");

        JsonNode internal = body(handler.handleExceptionException(new IllegalStateException("boom"), request));
        assertThat(internal.get("status").asInt()).isEqualTo(500);
        assertThat(internal.get("code").asText()).isEqualTo("error.generic.internal");
    }

    @Test
    void credentialsAndUnexpectedErrorsNeverEchoTheExceptionText() {
        when(messages.getMessage("error.auth.bad.credentials")).thenReturn("Login ou senha inválidos.");
        when(messages.getMessage("error.generic.internal")).thenReturn("Erro interno.");
        JsonNode credentials = body(handler.handleForbiddenException(
                new org.springframework.security.authentication.BadCredentialsException("Bad credentials"), request));
        assertThat(credentials.get("code").asText()).isEqualTo("error.auth.bad.credentials");
        assertThat(credentials.get("message").asText()).isEqualTo("Login ou senha inválidos.");

        JsonNode internal = body(handler.handleExceptionException(new IllegalStateException("SQL com dado do cliente"), request));
        assertThat(internal.get("message").asText()).isEqualTo("Erro interno.");
        assertThat(internal.toString()).doesNotContain("SQL com dado do cliente");
    }

    @Test
    void springMvcErrorsLeaveInTheSameFormat() {
        when(messages.getMessage("error.generic.bad.request")).thenReturn("Requisição inválida");
        when(messages.getMessage("error.generic.method.not.allowed")).thenReturn("Método não permitido");
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setAllow(java.util.Set.of(org.springframework.http.HttpMethod.GET));

        ResponseEntity<Object> response = handler.handleExceptionInternal(new IllegalArgumentException("jackson detail"), null,
                headers, org.springframework.http.HttpStatus.METHOD_NOT_ALLOWED, request);
        JsonNode body = json.readTree(json.writeValueAsString(response.getBody()));
        assertThat(fields(body)).containsExactly("timestamp", "status", "code", "message", "details");
        assertThat(body.get("status").asInt()).isEqualTo(405);
        assertThat(body.get("code").asText()).isEqualTo("error.generic.method.not.allowed");
        assertThat(response.getHeaders().getAllow()).contains(org.springframework.http.HttpMethod.GET);

        assertThat(GlobalExceptionHandler.genericCode(400)).isEqualTo("error.generic.bad.request");
        assertThat(GlobalExceptionHandler.genericCode(404)).isEqualTo("error.generic.not.found");
        assertThat(GlobalExceptionHandler.genericCode(415)).isEqualTo("error.generic.unsupported.media.type");
        assertThat(GlobalExceptionHandler.genericCode(422)).isEqualTo("error.generic.bad.request");
        assertThat(GlobalExceptionHandler.genericCode(502)).isEqualTo("error.generic.internal");
    }

    @Test
    void beanValidationUsesTheI18nMessageAndSurvivesAnObjectError() throws Exception {
        when(messages.getMessage("error.validation.field", "name", "must not be blank")).thenReturn("O campo 'name' must not be blank");
        org.springframework.validation.BeanPropertyBindingResult fieldErrors =
                new org.springframework.validation.BeanPropertyBindingResult(new Object(), "plan");
        fieldErrors.addError(new org.springframework.validation.FieldError("plan", "name", "must not be blank"));
        org.springframework.core.MethodParameter parameter = new org.springframework.core.MethodParameter(
                Object.class.getMethod("equals", Object.class), 0);
        ResponseEntity<Object> field = handler.handleMethodArgumentNotValid(
                new org.springframework.web.bind.MethodArgumentNotValidException(parameter, fieldErrors),
                new org.springframework.http.HttpHeaders(), org.springframework.http.HttpStatus.BAD_REQUEST, request);
        JsonNode fieldBody = json.readTree(json.writeValueAsString(field.getBody()));
        assertThat(fieldBody.get("code").asText()).isEqualTo("error.validation.field");
        assertThat(fieldBody.get("message").asText()).isEqualTo("O campo 'name' must not be blank");

        org.springframework.validation.BeanPropertyBindingResult objectErrors =
                new org.springframework.validation.BeanPropertyBindingResult(new Object(), "plan");
        objectErrors.addError(new org.springframework.validation.ObjectError("plan", "período inválido"));
        ResponseEntity<Object> global = handler.handleMethodArgumentNotValid(
                new org.springframework.web.bind.MethodArgumentNotValidException(parameter, objectErrors),
                new org.springframework.http.HttpHeaders(), org.springframework.http.HttpStatus.BAD_REQUEST, request);
        assertThat(json.readTree(json.writeValueAsString(global.getBody())).get("message").asText()).isEqualTo("período inválido");
    }

    @Test
    void theTrialLimitAddsKindLimitInUseAndPlanName() {
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("kind", "COMPANY");
        extras.put("limit", 5);
        extras.put("inUse", 5L);
        extras.put("planName", "Teste grátis");
        JsonNode limit = body(handler.handleConflictException(
                new ConflictException("error.plan.limit.companies", "O teste grátis permite 5 empresas", extras), request));

        assertThat(fields(limit)).containsExactly("timestamp", "status", "code", "message", "details",
                "kind", "limit", "inUse", "planName");
        assertThat(limit.get("status").asInt()).isEqualTo(409);
        assertThat(limit.get("kind").asText()).isEqualTo("COMPANY");
        assertThat(limit.get("limit").asInt()).isEqualTo(5);
        assertThat(limit.get("inUse").asLong()).isEqualTo(5);
        assertThat(limit.get("planName").asText()).isEqualTo("Teste grátis");
    }

    @Test
    void theReadOnlyFirmPutsItsSituationInFirmStatus() {
        FirmAccess blocked = new FirmAccess(UUID.randomUUID(), FirmSubscriptionStatus.BLOCKED, false, true,
                BlockedReason.TRIAL_EXPIRED, LocalDate.of(2026, 12, 10), LocalDate.of(2026, 12, 9), null, null, 0, false);
        JsonNode body = body(handler.handleForbiddenBusinessException(new FirmAccessBlockedException("somente leitura", blocked), request));

        assertThat(fields(body)).containsExactly("timestamp", "status", "code", "message", "details",
                "firmStatus", "reason", "blockedSince");
        assertThat(body.get("status").asInt()).isEqualTo(403);
        assertThat(body.get("code").asText()).isEqualTo("error.firm.access.blocked");
        assertThat(body.get("firmStatus").asText()).isEqualTo("BLOCKED");
        assertThat(body.get("reason").asText()).isEqualTo("TRIAL_EXPIRED");
        assertThat(body.get("blockedSince").asText()).isEqualTo("2026-12-10");

        // a suspension without a known start: the keys stay, with null
        FirmAccess suspended = new FirmAccess(UUID.randomUUID(), FirmSubscriptionStatus.SUSPENDED, false, true,
                null, null, null, null, null, null, true);
        JsonNode noDate = body(handler.handleForbiddenBusinessException(new FirmAccessBlockedException("x", suspended), request));
        assertThat(noDate.get("firmStatus").asText()).isEqualTo("SUSPENDED");
        assertThat(noDate.has("reason")).isTrue();
        assertThat(noDate.get("reason").isNull()).isTrue();
        assertThat(noDate.get("blockedSince").isNull()).isTrue();
    }
}
