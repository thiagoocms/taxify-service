package br.com.cshub.taxify.integration.sieg;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * Request body for {@code POST /api/v1/baixar-xmls}, per SIEG's "Sistemas Externos"
 * contract at integracoes.sieg.com/sistema-externo/docs/baixarxmls-v-1-0-0.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SiegBaixarXmlsRequest(
        @JsonProperty("TipoXml") Integer tipoXml,
        @JsonProperty("Take") Integer take,
        @JsonProperty("Skip") Integer skip,
        @JsonProperty("DataEmissaoInicio") Instant dataEmissaoInicio,
        @JsonProperty("DataEmissaoFim") Instant dataEmissaoFim,
        @JsonProperty("CNPJemit") String cnpjEmit,
        @JsonProperty("CNPJdest") String cnpjDest
) {
}
