package br.com.cshub.taxify.integration.efisco;

import br.com.cshub.taxify.integration.sefaz.A1Certificate;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PE's e-Fisco as the browser uses it, mapped step by step on the accountant's own session:
 * <ol>
 *   <li>login: the public menu's form posted to efiscoi's {@code PRGerenciarLoginUsuario} with
 *       {@code evento=tratarLoginCertificado} - efiscoi asks for the ICP-Brasil certificate in the TLS handshake;</li>
 *   <li>the service "Extrato Contribuinte - CONTESTAÇÃO" ({@code cd_menu=190696});</li>
 *   <li>"Calculadas" in PDF: {@code evento=emitirRelatorioExtratoCalculadas} with {@code DtPeriodoFiscal=mm/aaaa}
 *       and {@code NuDocumentoIdentificacao} (the IE, masked);</li>
 *   <li>"Visualizar": {@code evento=exibirDocumento} with the {@code id_contexto_sessao} the success page carries -
 *       the PDF stays in the session between the two.</li>
 * </ol>
 * Every page's {@code frm_principal} goes back as the browser would send it. Nothing is evaded: a Cloudflare
 * challenge, a refused login or a page that isn't the expected one stops the run with the reason
 * ({@link EfiscoException}). One client = one session; not thread-safe.
 */
public final class EfiscoClient {

    static final String PUBLIC_MENU = "https://efisco.sefaz.pe.gov.br/sfi_com_sca/PRMontarMenuAcesso";
    static final String HOST = "https://efiscoi.sefaz.pe.gov.br";
    static final String LOGIN = HOST + "/sfi_com_sca/PRGerenciarLoginUsuario";
    static final String EXTRACT = HOST + "/sfi_trb_cmt/PREmitirExtratosContribuinte";
    static final String EXTRACT_MENU = "190696";

    private static final Charset PAGE_CHARSET = Charset.forName("ISO-8859-1");
    private static final Pattern FORM = Pattern.compile("<form[^>]*name=\"?frm_principal\"?[^>]*>(.*?)</form>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern INPUT = Pattern.compile("<input\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTR = Pattern.compile("(\\w[\\w-]*)\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");
    /** "Operação concluída com sucesso! TL.UTL.30-MP.1" - the message e-Fisco shows, before its code. */
    private static final Pattern MESSAGE = Pattern.compile("([^.!?<>]{4,300}[.!?])\\s*([A-Z]{2}\\.[A-Z]{3}\\.\\d+[-\\w.]*)");

    private final HttpClient http;
    private Map<String, String> form = Map.of();

    public EfiscoClient(A1Certificate certificate) throws GeneralSecurityException {
        this.http = HttpClient.newBuilder()
                .sslContext(certificate.sslContext())
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /** Logs the certificate's holder in; afterwards the session reaches every company linked to them. */
    public void login() throws IOException, InterruptedException {
        Page menu = send(HttpRequest.newBuilder(URI.create(PUBLIC_MENU)).GET());
        form = menu.form();
        if (form.isEmpty()) {
            throw new EfiscoException(EfiscoException.Reason.PAGINA_INESPERADA, "O e-Fisco não mostrou a página de acesso.");
        }
        Map<String, String> fields = new LinkedHashMap<>(form);
        fields.put("evento", "tratarLoginCertificado");
        Page logged = post(LOGIN, fields);
        if (!logged.html().contains("btt_encerrarsessao")) {
            throw new EfiscoException(EfiscoException.Reason.LOGIN_RECUSADO,
                    "O e-Fisco não aceitou o login com o certificado" + logged.messageSuffix());
        }
        form = logged.form();
    }

    /**
     * The "Calculadas" extract of one IE and period, as PDF - or {@link Extract#none} with e-Fisco's message when
     * there is none (no notes calculated in the month, no debit, IE not linked to the accountant...).
     */
    public Extract calculatedExtract(String stateRegistration, String companyName, YearMonth period)
            throws IOException, InterruptedException {
        // the service, as the menu opens it
        Map<String, String> open = new LinkedHashMap<>(form);
        open.put("cd_menu", EXTRACT_MENU);
        open.put("evento", "");
        Page screen = post(EXTRACT, open);
        Map<String, String> fields = screen.form();
        if (!fields.containsKey("DtPeriodoFiscal")) {
            throw new EfiscoException(EfiscoException.Reason.PAGINA_INESPERADA,
                    "O e-Fisco não abriu a emissão de extrato" + screen.messageSuffix());
        }
        fields.put("DtPeriodoFiscal", String.format(Locale.ROOT, "%02d/%d", period.getMonthValue(), period.getYear()));
        fields.put("SqExtrato", "");
        fields.put("NuDocumentoIdentificacao", maskStateRegistration(stateRegistration));
        fields.put("NmRazaoSocial", companyName == null ? "" : companyName);
        fields.put("radio", "on");
        fields.put("evento", "emitirRelatorioExtratoCalculadas");
        Page result = post(EXTRACT, fields);
        form = result.form().isEmpty() ? form : result.form();
        if (!result.html().contains("btt_pmu_acao_1")) {
            // no document to show: e-Fisco explains why (no extract in the month, IE not linked...)
            return Extract.none(result.message());
        }

        Map<String, String> show = new LinkedHashMap<>(result.form());
        show.put("evento", "exibirDocumento");
        show.put("parametro_metodo_botao", "");
        HttpResponse<byte[]> pdf = postRaw(EXTRACT, show);
        String type = pdf.headers().firstValue("Content-Type").orElse("");
        byte[] body = pdf.body();
        if (!type.toLowerCase(Locale.ROOT).contains("pdf") && !(body.length > 4 && body[0] == '%' && body[1] == 'P')) {
            Page page = new Page(new String(body, PAGE_CHARSET));
            checkChallenge(pdf.statusCode(), page.html());
            throw new EfiscoException(EfiscoException.Reason.PAGINA_INESPERADA,
                    "O e-Fisco não devolveu o PDF do extrato" + page.messageSuffix());
        }
        return Extract.of(body);
    }

    /** "039469751" → "0394697-51" (the 9-digit PE IE as the screen takes it); other layouts go as they are. */
    static String maskStateRegistration(String value) {
        String digits = value == null ? "" : value.replaceAll("\\D", "");
        return digits.length() == 9 ? digits.substring(0, 7) + "-" + digits.substring(7) : digits;
    }

    // ---------------------------------------------------------------- http

    private Page post(String url, Map<String, String> fields) throws IOException, InterruptedException {
        HttpResponse<byte[]> response = postRaw(url, fields);
        Page page = new Page(new String(response.body(), PAGE_CHARSET));
        checkChallenge(response.statusCode(), page.html());
        if (response.statusCode() >= 400) {
            throw new EfiscoException(EfiscoException.Reason.ERRO_HTTP, "O e-Fisco respondeu HTTP " + response.statusCode() + ".");
        }
        return page;
    }

    private HttpResponse<byte[]> postRaw(String url, Map<String, String> fields) throws IOException, InterruptedException {
        StringJoiner body = new StringJoiner("&");
        fields.forEach((name, value) -> body.add(URLEncoder.encode(name, PAGE_CHARSET) + "=" + URLEncoder.encode(value, PAGE_CHARSET)));
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(90))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.US_ASCII));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private Page send(HttpRequest.Builder request) throws IOException, InterruptedException {
        HttpResponse<byte[]> response = http.send(request.timeout(Duration.ofSeconds(60)).build(), HttpResponse.BodyHandlers.ofByteArray());
        Page page = new Page(new String(response.body(), PAGE_CHARSET));
        checkChallenge(response.statusCode(), page.html());
        if (response.statusCode() >= 400) {
            throw new EfiscoException(EfiscoException.Reason.ERRO_HTTP, "O e-Fisco respondeu HTTP " + response.statusCode() + ".");
        }
        return page;
    }

    /**
     * A Cloudflare challenge (the interstitial, not the passive script every page carries): the run stops here -
     * it is never solved or worked around.
     */
    static void checkChallenge(int status, String html) {
        boolean interstitial = html.contains("cf-chl") || html.contains("Just a moment") || html.contains("cf_chl_opt")
                || html.contains("Attention Required! | Cloudflare");
        if (interstitial || status == 403 || status == 429 || status == 503) {
            throw new EfiscoException(EfiscoException.Reason.BLOQUEADO,
                    "O e-Fisco (Cloudflare) pediu uma verificação de navegador ou recusou o acesso (HTTP " + status
                            + "). A rotina parou; tente mais tarde ou importe o PDF à mão.");
        }
    }

    // ---------------------------------------------------------------- pages

    /** A page of e-Fisco: its {@code frm_principal} as the browser would post it, and its message. */
    record Page(String html) {

        /** Hidden and text inputs, the checked radios and checkboxes - buttons are never part of a form post. */
        Map<String, String> form() {
            Matcher formMatcher = FORM.matcher(html);
            String scope = formMatcher.find() ? formMatcher.group(1) : html;
            Map<String, String> fields = new LinkedHashMap<>();
            Matcher input = INPUT.matcher(scope);
            while (input.find()) {
                Map<String, String> attrs = attributes(input.group());
                String name = attrs.get("name");
                String type = attrs.getOrDefault("type", "text").toLowerCase(Locale.ROOT);
                if (name == null || type.equals("button") || type.equals("submit") || type.equals("file") || type.equals("image")
                        || type.equals("reset")) {
                    continue;
                }
                if ((type.equals("radio") || type.equals("checkbox")) && !attrs.containsKey("checked")) {
                    continue;
                }
                fields.putIfAbsent(name, unescape(attrs.getOrDefault("value", type.equals("checkbox") || type.equals("radio") ? "on" : "")));
            }
            return fields;
        }

        String message() {
            String text = html.replaceAll("(?is)<script.*?</script>|<style.*?</style>", " ").replaceAll("<[^>]+>", " ")
                    .replaceAll("&nbsp;", " ").replaceAll("\\s+", " ");
            Matcher matcher = MESSAGE.matcher(text);
            return matcher.find() ? unescape(matcher.group(1).strip()) : null;
        }

        String messageSuffix() {
            String message = message();
            return message == null ? "." : ": " + message;
        }

        private static Map<String, String> attributes(String tag) {
            Map<String, String> attrs = new LinkedHashMap<>();
            Matcher matcher = ATTR.matcher(tag);
            while (matcher.find()) {
                String value = matcher.group(3) != null ? matcher.group(3) : matcher.group(4) != null ? matcher.group(4) : matcher.group(5);
                attrs.put(matcher.group(1).toLowerCase(Locale.ROOT), value);
            }
            // a bare attribute ("checked", "disabled") has no "=": still present
            if (tag.matches("(?is).*\\schecked(\\s|>|/).*")) {
                attrs.putIfAbsent("checked", "checked");
            }
            return attrs;
        }

        private static String unescape(String value) {
            return value.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">");
        }
    }

    /** The PDF of the extract, or why there is none. */
    public record Extract(byte[] pdf, String message) {

        static Extract of(byte[] pdf) {
            return new Extract(pdf, null);
        }

        static Extract none(String message) {
            return new Extract(null, message);
        }

        public boolean found() {
            return pdf != null;
        }
    }
}
