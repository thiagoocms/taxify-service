package br.com.cshub.taxify.service.taxclearance;

import br.com.cshub.taxify.integration.efisco.EfiscoTaxClearanceClient;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Issues the certidão SEFAZ-PE of a CNPJ on e-Fisco's public page - see {@link EfiscoTaxClearanceClient}: while the page
 * asks for a captcha it always answers with {@link br.com.cshub.taxify.integration.efisco.EfiscoException}.
 */
@Component
public class TaxClearanceIssuer {

    /** @return the certidão PDF */
    public byte[] issue(String cnpj) throws IOException, InterruptedException {
        return new EfiscoTaxClearanceClient().issue(cnpj);
    }
}
