package br.com.cshub.taxify.service.capture;

import br.com.cshub.taxify.enumerated.CaptureFailureCode;
import br.com.cshub.taxify.exception.SiegIntegrationException;
import feign.FeignException;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.file.NoSuchFileException;
import java.security.GeneralSecurityException;
import java.util.regex.Pattern;

/**
 * Escolhe o código seguro da falha de uma etapa da captura (CP-RN-11): pelo <b>tipo</b> do erro - o código da
 * {@link SiegIntegrationException}, o status HTTP (dela ou da {@link FeignException}), a classe da causa - e nunca pelo
 * texto da mensagem, que pode trazer o corpo da resposta, um CNPJ ou um token. Puro, sem I/O.
 */
public final class CaptureFailureClassifier {

    private static final Pattern STATUS = Pattern.compile("[0-9]{1,4}");
    private static final int MAX_CAUSES = 10;

    private CaptureFailureClassifier() {
    }

    /** O resultado da classificação: o código e o status do provedor (só dígitos, ou nulo). */
    public record Classified(CaptureFailureCode code, String providerStatus) {

        static Classified of(CaptureFailureCode code) {
            return new Classified(code, null);
        }

        static Classified of(CaptureFailureCode code, Integer status) {
            return new Classified(code, status == null ? null : CaptureFailureClassifier.providerStatus(String.valueOf(status)));
        }
    }

    /**
     * {@code provider_status}: só dígitos, no máximo 4 (status HTTP ou cStat). Qualquer outra coisa ("4O0", "12345",
     * texto) é descartada - nulo.
     */
    public static String providerStatus(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return STATUS.matcher(trimmed).matches() ? trimmed : null;
    }

    /** Falha de uma etapa SIEG (lados, eventos, NFS-e, recuperação). */
    public static Classified sieg(Throwable error) {
        SiegIntegrationException sieg = find(error, SiegIntegrationException.class);
        if (sieg != null) {
            String code = sieg.getCode() == null ? "" : sieg.getCode();
            switch (code) {
                case "error.sieg.api.key.missing":
                    return Classified.of(CaptureFailureCode.SIEG_API_KEY_MISSING);
                case "error.sieg.xml.storage.failed":
                    return Classified.of(CaptureFailureCode.XML_STORAGE_FAILED);
                case "error.sieg.company.cnpj.required":
                    return Classified.of(CaptureFailureCode.COMPANY_WITHOUT_CNPJ);
                case "error.sieg.oauth.not.configured", "error.sieg.oauth.invalid.response":
                    return Classified.of(CaptureFailureCode.SIEG_AUTH);
                default:
                    break;
            }
            if (sieg.getHttpStatus() != null) {
                return byHttpStatus(sieg.getHttpStatus());
            }
        }
        FeignException feign = find(error, FeignException.class);
        if (feign != null && feign.status() >= 400) {
            return byHttpStatus(feign.status());
        }
        CaptureFailureCode network = network(error, CaptureFailureCode.SIEG_TIMEOUT, CaptureFailureCode.SIEG_CONNECTION);
        if (network != null) {
            return Classified.of(network);
        }
        if (sieg != null && "error.sieg.oauth.failed".equals(sieg.getCode())) {
            return Classified.of(CaptureFailureCode.SIEG_AUTH);
        }
        if (sieg != null || feign != null) {
            // success = false, empty body, a package that does not open, a status the client did not expect
            return Classified.of(CaptureFailureCode.SIEG_INVALID_RESPONSE);
        }
        return Classified.of(CaptureFailureCode.INTERNAL_ERROR);
    }

    /** 401/403 chave recusada; 429 excesso; 5xx indisponível; outro 4xx recusado (o 404 de página vazia não chega aqui). */
    static Classified byHttpStatus(int status) {
        CaptureFailureCode code;
        if (status == 401 || status == 403) {
            code = CaptureFailureCode.SIEG_AUTH;
        } else if (status == 429) {
            code = CaptureFailureCode.SIEG_RATE_LIMITED;
        } else if (status >= 500) {
            code = CaptureFailureCode.SIEG_UNAVAILABLE;
        } else if (status >= 400) {
            code = CaptureFailureCode.SIEG_REJECTED;
        } else {
            code = CaptureFailureCode.SIEG_INVALID_RESPONSE;
        }
        return Classified.of(code, status);
    }

    /** Falha de rede ou de tempo da SEFAZ (IOException da conexão). */
    public static CaptureFailureCode sefazIo(Throwable error) {
        CaptureFailureCode network = network(error, CaptureFailureCode.SEFAZ_TIMEOUT, CaptureFailureCode.SEFAZ_CONNECTION);
        return network == null ? CaptureFailureCode.SEFAZ_CONNECTION : network;
    }

    /** Falha de rede ou de tempo do Ambiente Nacional. */
    public static CaptureFailureCode adnIo(Throwable error) {
        CaptureFailureCode network = network(error, CaptureFailureCode.ADN_TIMEOUT, CaptureFailureCode.ADN_CONNECTION);
        return network == null ? CaptureFailureCode.ADN_CONNECTION : network;
    }

    /** O certificado não abriu: arquivo ausente, ou senha/arquivo inválido. */
    public static CaptureFailureCode certificate(Throwable error) {
        if (find(error, NoSuchFileException.class) != null) {
            return CaptureFailureCode.CERT_FILE_MISSING;
        }
        if (find(error, GeneralSecurityException.class) != null || find(error, IllegalStateException.class) != null) {
            return CaptureFailureCode.CERT_INVALID;
        }
        return CaptureFailureCode.CERT_INVALID;
    }

    private static CaptureFailureCode network(Throwable error, CaptureFailureCode timeout, CaptureFailureCode connection) {
        if (find(error, SocketTimeoutException.class) != null || find(error, HttpTimeoutException.class) != null
                || find(error, HttpConnectTimeoutException.class) != null) {
            return timeout;
        }
        if (find(error, ConnectException.class) != null || find(error, UnknownHostException.class) != null
                || find(error, NoRouteToHostException.class) != null || find(error, javax.net.ssl.SSLException.class) != null
                || find(error, java.net.SocketException.class) != null) {
            return connection;
        }
        return null;
    }

    /** The first throwable of the type in the cause chain (bounded - a cycle never loops). */
    private static <T extends Throwable> T find(Throwable error, Class<T> type) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < MAX_CAUSES; depth++) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return null;
    }
}
