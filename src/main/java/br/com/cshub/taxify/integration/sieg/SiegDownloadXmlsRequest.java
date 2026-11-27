package br.com.cshub.taxify.integration.sieg;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * Request body for {@code POST /api/v1/baixar-xmls}, per SIEG's "Sistemas Externos"
 * contract at integracoes.sieg.com/sistema-externo/docs/baixar-xmls-2.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SiegDownloadXmlsRequest(
        @JsonProperty("TipoXml") Integer xmlType,
        @JsonProperty("Take") Integer take,
        @JsonProperty("Skip") Integer skip,
        @JsonProperty("DataEmissaoInicio") Instant issueDateFrom,
        @JsonProperty("DataEmissaoFim") Instant issueDateTo,
        @JsonProperty("CNPJemit") String cnpjEmit,
        @JsonProperty("CNPJdest") String cnpjDest,
        /** true = each page also brings the notes' events (cancelamento, CC-e, manifestação), beyond the 50 notes. */
        @JsonProperty("BaixarEventos") Boolean includeEvents
) {
}
