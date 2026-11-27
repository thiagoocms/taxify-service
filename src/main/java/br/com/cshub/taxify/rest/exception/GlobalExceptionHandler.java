package br.com.cshub.taxify.rest.exception;


import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.dto.ErrorDetailsDTO;
import br.com.cshub.taxify.exception.AcessoriasIntegrationException;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.exception.BusinessException;
import br.com.cshub.taxify.exception.ConflictException;
import br.com.cshub.taxify.exception.ContaAzulIntegrationException;
import br.com.cshub.taxify.exception.ForbiddenException;
import br.com.cshub.taxify.exception.ResourceNotFoundException;
import br.com.cshub.taxify.exception.ServiceUnavailableException;
import br.com.cshub.taxify.exception.SiegIntegrationException;
import br.com.cshub.taxify.exception.TooManyRequestsException;
import br.com.cshub.taxify.exception.UnauthorizedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * Every error of the API leaves in one format, {@link ErrorDetailsDTO}: {@code {timestamp, status, code, message,
 * details}} - {@code status} is the HTTP status and {@code code} the i18n key of the message - plus the extra fields
 * of the {@link BusinessException}, when it has them, at the top level of the body. The errors Spring MVC raises itself
 * (malformed JSON, wrong type in the path, missing parameter, 404 of a route, 405, 415...) leave in the same format, with
 * the generic code of their status ({@link #handleExceptionInternal}).
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    static final String INTERNAL_CODE = "error.generic.internal";
    static final String BAD_CREDENTIALS_CODE = "error.auth.bad.credentials";
    static final String ACCESS_DENIED_CODE = "error.access.denied";
    static final String VALIDATION_CODE = "error.validation.field";

    private final MessageService messageService;

    public GlobalExceptionHandler(MessageService messageService) {
        this.messageService = messageService;
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorDetailsDTO> handleResourceNotFoundException(ResourceNotFoundException exception,
                                                                           WebRequest webRequest) {
        return error(HttpStatus.NOT_FOUND, exception, webRequest);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ErrorDetailsDTO> handleConflictException(ConflictException exception,
                                                                   WebRequest webRequest) {
        return error(HttpStatus.CONFLICT, exception, webRequest);
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ErrorDetailsDTO> handleBadRequestException(BadRequestException exception,
                                                                     WebRequest webRequest) {
        return error(HttpStatus.BAD_REQUEST, exception, webRequest);
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ErrorDetailsDTO> handleUnauthorizedException(UnauthorizedException exception,
                                                                       WebRequest webRequest) {
        return error(HttpStatus.UNAUTHORIZED, exception, webRequest);
    }

    /** Business 403: e-mail not verified (F1-RN-03), firm in read-only (F1-RN-08, with firmStatus/reason/blockedSince). */
    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ErrorDetailsDTO> handleForbiddenBusinessException(ForbiddenException exception,
                                                                            WebRequest webRequest) {
        return error(HttpStatus.FORBIDDEN, exception, webRequest);
    }

    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<ErrorDetailsDTO> handleTooManyRequestsException(TooManyRequestsException exception,
                                                                          WebRequest webRequest) {
        return error(HttpStatus.TOO_MANY_REQUESTS, exception, webRequest);
    }

    @ExceptionHandler(ServiceUnavailableException.class)
    public ResponseEntity<ErrorDetailsDTO> handleServiceUnavailableException(ServiceUnavailableException exception,
                                                                             WebRequest webRequest) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, exception, webRequest);
    }

    @ExceptionHandler(AcessoriasIntegrationException.class)
    public ResponseEntity<ErrorDetailsDTO> handleAcessoriasIntegrationException(AcessoriasIntegrationException exception,
                                                                                 WebRequest webRequest) {
        return error(HttpStatus.BAD_GATEWAY, exception, webRequest);
    }

    @ExceptionHandler(ContaAzulIntegrationException.class)
    public ResponseEntity<ErrorDetailsDTO> handleContaAzulIntegrationException(ContaAzulIntegrationException exception,
                                                                                WebRequest webRequest) {
        return error(HttpStatus.BAD_GATEWAY, exception, webRequest);
    }

    @ExceptionHandler(SiegIntegrationException.class)
    public ResponseEntity<ErrorDetailsDTO> handleSiegIntegrationException(SiegIntegrationException exception,
                                                                           WebRequest webRequest) {
        return error(HttpStatus.BAD_GATEWAY, exception, webRequest);
    }

    @ExceptionHandler({InternalAuthenticationServiceException.class, BadCredentialsException.class})
    public ResponseEntity<ErrorDetailsDTO> handleForbiddenException(Exception exception,
                                                                    WebRequest webRequest) {
        return error(HttpStatus.FORBIDDEN, BAD_CREDENTIALS_CODE, messageService.getMessage(BAD_CREDENTIALS_CODE), Map.of(), webRequest);
    }

    /** @RequiresPermission / @AdminOnly denied the call - 403, never the generic 500 below. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorDetailsDTO> handleAccessDeniedException(AccessDeniedException exception, WebRequest webRequest) {
        return error(HttpStatus.FORBIDDEN, ACCESS_DENIED_CODE, messageService.getMessage(ACCESS_DENIED_CODE), Map.of(),
                webRequest);
    }

    /** Anything unexpected: logged with the stack trace, and the body never carries the exception's text (R-11). */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorDetailsDTO> handleExceptionException(Exception exception, WebRequest webRequest) {
        log.error("Unexpected error on {}", webRequest.getDescription(false), exception);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_CODE, messageService.getMessage(INTERNAL_CODE), Map.of(),
                webRequest);
    }

    private Long getTime() {
        return LocalDateTime.now().atZone(ZoneOffset.UTC).toInstant().toEpochMilli();
    }

    /** Bean Validation on a @RequestBody: the first field error, "O campo 'x' ...", or the object's own error. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        FieldError field = ex.getFieldError();
        String msg;
        if (field != null) {
            msg = messageService.getMessage(VALIDATION_CODE, field.getField(), field.getDefaultMessage());
        } else {
            ObjectError global = ex.getGlobalError();
            msg = global != null && global.getDefaultMessage() != null ? global.getDefaultMessage()
                    : messageService.getMessage(BadRequestException.DEFAULT_CODE);
        }
        ErrorDetailsDTO errorDetails = new ErrorDetailsDTO(getTime(), status.value(), VALIDATION_CODE, msg,
                request.getDescription(false));
        return new ResponseEntity<>(errorDetails, new HttpHeaders(), status);
    }

    /**
     * Every other error Spring MVC answers itself (HttpMessageNotReadable, TypeMismatch, MissingServletRequestParameter,
     * NoResourceFound, HttpRequestMethodNotSupported, HttpMediaTypeNotSupported...): same body, the generic code of the
     * status and its message - never the exception's text. The headers Spring sets (e.g. Allow on a 405) are kept.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        String code = genericCode(statusCode.value());
        ErrorDetailsDTO errorDetails = new ErrorDetailsDTO(getTime(), statusCode.value(), code, messageService.getMessage(code),
                request.getDescription(false));
        return new ResponseEntity<>(errorDetails, headers, statusCode);
    }

    static String genericCode(int status) {
        return switch (status) {
            case 400 -> BadRequestException.DEFAULT_CODE;
            case 401 -> "error.generic.unauthorized";
            case 403 -> ACCESS_DENIED_CODE;
            case 404 -> "error.generic.not.found";
            case 405 -> "error.generic.method.not.allowed";
            case 406 -> "error.generic.not.acceptable";
            case 409 -> ConflictException.DEFAULT_CODE;
            case 413 -> "error.generic.payload.too.large";
            case 415 -> "error.generic.unsupported.media.type";
            case 429 -> "error.generic.too.many.requests";
            case 503 -> "error.generic.unavailable";
            default -> status >= 500 ? INTERNAL_CODE : BadRequestException.DEFAULT_CODE;
        };
    }

    private ResponseEntity<ErrorDetailsDTO> error(HttpStatus status, BusinessException exception, WebRequest webRequest) {
        return error(status, exception.getCode(), exception.getMessage(), exception.getExtras(), webRequest);
    }

    private ResponseEntity<ErrorDetailsDTO> error(HttpStatus status, String code, String message, Map<String, Object> extras,
                                                  WebRequest webRequest) {
        ErrorDetailsDTO errorDetails = new ErrorDetailsDTO(getTime(), status.value(), code, message,
                webRequest.getDescription(false), extras);
        return new ResponseEntity<>(errorDetails, new HttpHeaders(), status);
    }
}
