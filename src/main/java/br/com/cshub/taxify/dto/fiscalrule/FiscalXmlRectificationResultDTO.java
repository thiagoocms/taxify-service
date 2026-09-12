package br.com.cshub.taxify.dto.fiscalrule;

import java.util.List;

public record FiscalXmlRectificationResultDTO(
        String documentId,
        String accessKey,
        String originalFilePath,
        String rectifiedFilePath,
        List<String> correcoesAplicadas
) {
}
