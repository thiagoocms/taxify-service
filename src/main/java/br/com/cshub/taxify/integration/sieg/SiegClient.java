package br.com.cshub.taxify.integration.sieg;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.exception.SiegIntegrationException;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlPackageExtractor;
import feign.FeignException;
import feign.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
public class SiegClient {

    private static final Logger log = LoggerFactory.getLogger(SiegClient.class);
    private static final int MAX_ERROR_BODY_CHARS = 500;

    private final SiegFeignClient feignClient;
    private final FiscalXmlPackageExtractor packageExtractor;
    private final SiegProperties properties;
    private final MessageService messageService;

    public SiegClient(SiegFeignClient feignClient, FiscalXmlPackageExtractor packageExtractor,
                       SiegProperties properties, MessageService messageService) {
        this.feignClient = feignClient;
        this.packageExtractor = packageExtractor;
        this.properties = properties;
        this.messageService = messageService;
    }

    /**
     * How many NFe match this filter, per SIEG's own count (not the size of a
     * downloaded batch) - meant to be checked before calling {@link #baixarXmls} so a
     * company with nothing pending doesn't spend a rate-limited download call for
     * nothing. NFCe/CTe/CFe/NFSe counts also come back but aren't used here since this
     * integration only handles NFe (TIPO_XML_NFE) today.
     */
    public int contarXmlsNfe(SiegBaixarXmlsRequest request) {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new SiegIntegrationException(messageService.getMessage("error.sieg.api.key.missing"));
        }

        try {
            SiegContarXmlsResponse response = feignClient.contarXmls(request);
            if (response == null || !response.isSuccess() || response.data() == null) {
                throw new SiegIntegrationException(messageService.getMessage("error.sieg.integration.failed"));
            }
            Integer nfe = response.data().nfe();
            return nfe != null ? nfe : 0;
        } catch (FeignException e) {
            throw new SiegIntegrationException(messageService.getMessage("error.sieg.integration.failed"), e);
        }
    }

    public List<byte[]> baixarXmls(SiegBaixarXmlsRequest request) {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new SiegIntegrationException(messageService.getMessage("error.sieg.api.key.missing"));
        }

        try (Response response = feignClient.baixarXmls(request)) {
            byte[] body = readBody(response);
            if (response.status() >= 400) {
                log.warn("SIEG baixar-xmls returned HTTP {}: {}", response.status(), errorSnippet(body));
                throw new SiegIntegrationException(messageService.getMessage("error.sieg.integration.failed"));
            }
            return packageExtractor.extract(body);
        } catch (FeignException e) {
            throw new SiegIntegrationException(messageService.getMessage("error.sieg.integration.failed"), e);
        }
    }

    private byte[] readBody(Response response) {
        if (response.body() == null) {
            return new byte[0];
        }
        try (InputStream in = response.body().asInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new SiegIntegrationException(messageService.getMessage("error.sieg.integration.failed"), e);
        }
    }

    private String errorSnippet(byte[] body) {
        String text = new String(body, StandardCharsets.UTF_8);
        return text.length() > MAX_ERROR_BODY_CHARS ? text.substring(0, MAX_ERROR_BODY_CHARS) : text;
    }
}
