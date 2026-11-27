package db.migration;

/**
 * Taxify SaaS fase 1 (F1-RN-02): CNPJ, e-mail financeiro e telefone/WhatsApp do escritório, pedidos no cadastro
 * público. O CNPJ do escritório tem índice único próprio, fora da tabela {@code documents}: o mesmo CNPJ pode ser
 * empresa cliente de outro escritório. Os escritórios que já existiam ficam com os três nulos (F1-RN-13).
 */
public class V57__AccountingFirmContact extends TableMigration {

    public V57__AccountingFirmContact() {
        super("accounting_firms");

        column("document_number", "varchar(14)");
        column("billing_email", "varchar(300)");
        column("contact_phone", "varchar(11)");

        unique("uk_accounting_firms_document_number", "document_number");
    }
}
