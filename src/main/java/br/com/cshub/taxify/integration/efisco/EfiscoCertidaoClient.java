package br.com.cshub.taxify.integration.efisco;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * e-Fisco's public issue of the state certidão ("Certidão de Regularidade Web", {@code cd_menu=363082}, no login),
 * as mapped in 11/2026: one GET opens the form ({@code frm_principal}, CNPJ in {@code NuDocumentoPessoa}, "Localizar"
 * with {@code evento=processarFiltroConsulta}, "Emitir Certidão" with {@code evento=emitirCertidaoPositiva}) - and the
 * form carries a Cloudflare Turnstile ("Por favor, confirme que você é humano") the emission needs. A captcha is never
 * solved or worked around: when the page has one, {@link #issue} stops with {@link EfiscoException.Reason#CAPTCHA}
 * and the certidão is issued by a person on that page (then sent to Taxify as PDF). The old address
 * ({@code sfi_trb_gcc/PREmitirCertidaoRegularidadeFiscal}) now sends to the gov.br login.
 */
public final class EfiscoCertidaoClient {

    /** The public page where a person issues the certidão - the screen links to it. */
    public static final String PUBLIC_PAGE = "https://efisco.sefaz.pe.gov.br/sfi_fin_gpc/PREmitirCertidaoRegularidadeWeb";

    private static final Pattern CAPTCHA = Pattern.compile(
            "cf-turnstile|challenges\\.cloudflare\\.com/turnstile|g-recaptcha|h-captcha|hcaptcha|captcha", Pattern.CASE_INSENSITIVE);

    private final HttpClient http;

    public EfiscoCertidaoClient() {
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * Issues the certidão of a CNPJ - as far as e-Fisco lets a program go. Today it always ends in
     * {@link EfiscoException}: {@code CAPTCHA} while the page asks for the human check; {@code PAGINA_INESPERADA} if
     * the page changes (the steps after the check were never mapped, since they can't be reached without solving it).
     *
     * @return the certidão PDF
     */
    public byte[] issue(String cnpj) throws IOException, InterruptedException {
        HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(URI.create(PUBLIC_PAGE))
                .timeout(Duration.ofSeconds(60)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        String html = EfiscoClient.decode(response);
        EfiscoClient.checkChallenge(response.statusCode(), html);
        if (response.statusCode() >= 400) {
            throw new EfiscoException(EfiscoException.Reason.ERRO_HTTP, "O e-Fisco respondeu HTTP " + response.statusCode() + ".");
        }
        if (requiresHumanCheck(html)) {
            throw new EfiscoException(EfiscoException.Reason.CAPTCHA,
                    "A emissão pública da certidão no e-Fisco pede a verificação \"confirme que você é humano\" (captcha)."
                            + " O Taxify não resolve captcha: emita no e-Fisco e envie o PDF na ficha da empresa.");
        }
        if (!html.contains("NuDocumentoPessoa")) {
            throw new EfiscoException(EfiscoException.Reason.PAGINA_INESPERADA,
                    "O e-Fisco não mostrou o formulário de emissão da certidão.");
        }
        throw new EfiscoException(EfiscoException.Reason.PAGINA_INESPERADA,
                "O e-Fisco abriu a emissão sem verificação humana, mas esse fluxo ainda não foi mapeado no Taxify."
                        + " Emita no e-Fisco e envie o PDF na ficha da empresa.");
    }

    /** The page asks for a captcha (Cloudflare Turnstile today). */
    static boolean requiresHumanCheck(String html) {
        return html != null && CAPTCHA.matcher(html).find();
    }
}
