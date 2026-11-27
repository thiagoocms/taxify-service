package br.com.cshub.taxify.integration.efisco;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TaxClearanceEfiscoClientTest {

    /** The public page's form as e-Fisco served it in 11/2026 (trimmed): the Turnstile the emission needs. */
    private static final String PAGE = """
            <FORM name="frm_principal" method="post">
            <INPUT type="text" id="nuDocumentoCredor" name="NuDocumentoPessoa" value="" class="campoobrigatorio">
            <div class="cf-turnstile" data-sitekey="0x4AAAAAAFm_2uhioO9w4xhl" data-action="63082"></div><br/>
            <script src="https://challenges.cloudflare.com/turnstile/v0/api.js" async defer></script>
            <INPUT type="button" class="botaofuncaom" id="btt_emitir" name="btt_emitir" value="Emitir Certidão (m)">
            </FORM>
            """;

    @Test
    void theTurnstileIsAHumanCheck() {
        assertThat(EfiscoTaxClearanceClient.requiresHumanCheck(PAGE)).isTrue();
    }

    @Test
    void aFormWithoutCaptchaIsNot() {
        assertThat(EfiscoTaxClearanceClient.requiresHumanCheck(PAGE.replaceAll("(?s)<div class=\"cf-turnstile\".*?</script>", ""))).isFalse();
    }
}
