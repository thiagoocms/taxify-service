package br.com.cshub.taxify.integration.sieg;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Per-document-type counts from {@code POST /api/v1/contar-xmls} - verified live:
 * the count isn't filtered by the request's TipoXml, it always breaks down every type.
 */
public record SiegContarXmlsDataDTO(
        @JsonProperty("NFe") Integer nfe,
        @JsonProperty("NFCe") Integer nfce,
        @JsonProperty("CTe") Integer cte,
        @JsonProperty("CFe") Integer cfe,
        @JsonProperty("NFSe") Integer nfse
) {
}
