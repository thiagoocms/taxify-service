package br.com.cshub.taxify.integration.sieg;

import java.util.UUID;
import java.time.LocalDate;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSide;
import br.com.cshub.taxify.dto.sieg.XmlDownloadFilterDTO;
import br.com.cshub.taxify.dto.sieg.XmlDownloadResultDTO;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlProvider;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSyncRequest;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSyncResult;
import br.com.cshub.taxify.service.FiscalXmlIntegrationService;
import org.springframework.stereotype.Component;

/**
 * Adapts SIEG to the {@link FiscalXmlProvider} strategy contract. Registered under the
 * bean name "sieg" - {@code fiscal-xml.sync.provider} selects it by that name.
 */
@Component("sieg")
public class SiegFiscalXmlProvider implements FiscalXmlProvider {

    private static final int TIPO_XML_NFE = 1;

    private final FiscalXmlIntegrationService integrationService;
    private final SiegProperties properties;

    public SiegFiscalXmlProvider(FiscalXmlIntegrationService integrationService, SiegProperties properties) {
        this.integrationService = integrationService;
        this.properties = properties;
    }

    @Override
    public FiscalXmlSyncResult sync(FiscalXmlSyncRequest request) {
        XmlDownloadFilterDTO filter = new XmlDownloadFilterDTO(
                request.companyId().toString(),
                TIPO_XML_NFE,
                request.dataEmissaoInicio(),
                request.dataEmissaoFim(),
                50,
                request.alreadyImportedCount(),
                null,
                request.side()
        );

        XmlDownloadResultDTO result = integrationService.syncFromSieg(filter, request.knownPending());
        return new FiscalXmlSyncResult(result.totalPersistedNew(), result.totalDuplicatedSkipped(), result.totalParseErrors());
    }

    @Override
    public int refreshEvents(UUID companyId, FiscalXmlSide side, LocalDate from, LocalDate to) {
        return integrationService.refreshRecentEvents(companyId, side, from, to);
    }
}
