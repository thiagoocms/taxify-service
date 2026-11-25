package br.com.cshub.taxify.integration.efisco;

/** Why an e-Fisco run stopped - the message is shown as is on the screen and in the last-sync status. */
public class EfiscoException extends RuntimeException {

    public enum Reason {
        /** A Cloudflare challenge or access refused: the whole run stops, nothing is retried or worked around. */
        BLOQUEADO,
        /** The certificate didn't log in (not linked, revoked, e-Fisco refused it). */
        LOGIN_RECUSADO,
        /** e-Fisco changed or answered something else than the mapped flow. */
        PAGINA_INESPERADA,
        /** The page asks a person to prove they are human (captcha / Cloudflare Turnstile): never solved by Taxify. */
        CAPTCHA,
        ERRO_HTTP
    }

    private final Reason reason;

    public EfiscoException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
