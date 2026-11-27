package db.migration;

/**
 * Taxify SaaS fase 1, teto diário dos reenvios do link de verificação (decisão do Tony): o IP de quem pediu cada
 * reenvio, para contar os reenvios por IP por dia civil de Recife. Nulo no e-mail do próprio cadastro (o IP dele já
 * está em terms_acceptances).
 */
public class V64__EmailVerificationRequestIp extends TableMigration {

    public V64__EmailVerificationRequestIp() {
        super("email_verifications");

        column("requested_ip", "varchar(45)");

        index("idx_email_verifications_ip", "requested_ip", "created_date");
    }
}
