package br.com.cshub.taxify.integration.efisco;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The pages as e-Fisco serves them (mapped on the accountant's session) - what goes back in the next post. */
class EfiscoClientTest {

    /** The emission screen: hidden fields, the text inputs, the PDF / Excel radios and the buttons. */
    private static final String EMISSION = """
            <html><body>
            <form name="frm_principal" method="post" autocomplete="off">
              <input type="hidden" id="id_contexto_sessao" name="id_contexto_sessao" value=""/>
              <input type="hidden" id="evento" name="evento" value=""/>
              <input type="hidden" name="cd_menu" value="190696">
              <input type="hidden" name="IsContribuinteSemDebitos" value="N">
              <input type="hidden" name="historico" value="Emiss&atilde;o de Extrato">
              <input type="text" name="DtPeriodoFiscal" maxlength="7" value="">
              <input type="text" name="NuDocumentoIdentificacao" maxlength="18" value="">
              <input type="radio" name="radio" checked onchange="verifcaRadio(this)"> PDF
              <input type="radio" name="radio" onchange="verifcaRadio(this)"> Excel
              <input type="button" name="bttemitirRelatorioCalculadas" value="Calculadas (w)" onclick="processarCalculadas('emitirRelatorioExtratoCalculadas')">
            </form>
            <input type="text" name="fora_do_form" value="x">
            </body></html>
            """;

    @Test
    void theFormGoesBackAsTheBrowserPostsIt() {
        Map<String, String> form = new EfiscoClient.Page(EMISSION).form();

        assertThat(form).containsEntry("cd_menu", "190696")
                .containsEntry("IsContribuinteSemDebitos", "N")
                .containsEntry("DtPeriodoFiscal", "")
                .containsEntry("radio", "on")           // only the checked radio
                .doesNotContainKey("bttemitirRelatorioCalculadas") // buttons are never posted
                .doesNotContainKey("fora_do_form");     // only frm_principal
    }

    @Test
    void theSuccessPageMessageAndItsContext() {
        String success = """
                <form name="frm_principal"><input type="hidden" name="id_contexto_sessao" value="3">
                <input type="button" name="btt_pmu_acao_1" value="Visualizar / imprimir documento (v)"></form>
                <div class="msg">Operação concluída com sucesso!</div> <span>TL.UTL.30-MP.1</span>
                """;
        EfiscoClient.Page page = new EfiscoClient.Page(success);

        assertThat(page.form()).containsEntry("id_contexto_sessao", "3");
        assertThat(page.message()).isEqualTo("Operação concluída com sucesso!");
    }

    @Test
    void theNineDigitIeGoesMaskedAsTheScreenTakesIt() {
        assertThat(EfiscoClient.maskStateRegistration("039469751")).isEqualTo("0394697-51");
        assertThat(EfiscoClient.maskStateRegistration("0394697-51")).isEqualTo("0394697-51");
        assertThat(EfiscoClient.maskStateRegistration("18.1.001.0000001-1")).isEqualTo("18100100000011");
    }

    /** The passive Cloudflare script every page carries is fine; the interstitial challenge stops the run. */
    @Test
    void aCloudflareChallengeStopsTheRun() {
        String passive = "<html><script>var u='/cdn-cgi/challenge-platform/scripts/jsd/main.js'</script>menu</html>";
        String challenge = "<html><title>Just a moment...</title><script>window._cf_chl_opt={}</script></html>";

        assertThatCode(() -> EfiscoClient.checkChallenge(200, passive)).doesNotThrowAnyException();
        assertThatThrownBy(() -> EfiscoClient.checkChallenge(200, challenge))
                .isInstanceOfSatisfying(EfiscoException.class, e -> assertThat(e.reason()).isEqualTo(EfiscoException.Reason.BLOQUEADO));
        assertThatThrownBy(() -> EfiscoClient.checkChallenge(403, "<html></html>")).isInstanceOf(EfiscoException.class);
    }

    /** The memória list after the filter: one radio per memória - "Detalhar" goes with the newest one. */
    @Test
    void picksTheNewestMemoriaOfTheList() {
        String list = """
                <TR> <TD class="tabeladados"> <INPUT type="radio" id="rdb_consulta" name="chave_primaria" value="12[[*]]6[[*]]585302508[[*]]2026-11-18 23:17:34.729[[*]]1161054000000[[*]]0" > </TD>
                <TD class="tabeladados">012.6.585302508-0</TD> </TR>
                <TR> <TD class="tabeladados"> <INPUT type="radio" id="rdb_consulta" name="chave_primaria" value="12[[*]]6[[*]]585302508[[*]]2026-11-20 08:01:02.000[[*]]1161054000000[[*]]0" > </TD> </TR>
                """;
        assertThat(EfiscoClient.newestMemoryKey(list))
                .isEqualTo("12[[*]]6[[*]]585302508[[*]]2026-11-20 08:01:02.000[[*]]1161054000000[[*]]0");
        assertThat(EfiscoClient.newestMemoryKey("<TABLE><TR><TH>Registro de Nota</TH></TR></TABLE>")).isNull();
    }
}
