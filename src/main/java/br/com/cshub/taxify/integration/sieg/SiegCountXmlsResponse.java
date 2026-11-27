package br.com.cshub.taxify.integration.sieg;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response body for {@code POST /api/v1/contar-xmls} - verified live against the API.
 */
public record SiegCountXmlsResponse(
        @JsonProperty("IsSuccess") boolean isSuccess,
        @JsonProperty("ErrorMessage") String errorMessage,
        @JsonProperty("StatusCode") int statusCode,
        @JsonProperty("Data") SiegCountXmlsDataDTO data,
        @JsonProperty("IsFailure") boolean isFailure
) {
}
