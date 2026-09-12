package br.com.cshub.taxify.dto.sieg;

import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlItemDTO;

import java.util.List;

public record XmlDownloadResultDTO(
        int totalItemsFromSieg,
        int totalXmlsCounted,
        int totalPersistedNew,
        int totalDuplicatedSkipped,
        int totalParseErrors,
        List<String> persistedAccessKeys,
        List<FiscalXmlItemDTO> xmls
) {
}
