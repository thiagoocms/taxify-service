package legacy.xml;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * Test copy of the old embeddable (legacy model, removed from src/main by the fiscal documents remodel) - input of
 * the reference translator.
 *
 * The few header fields of a note that only the detail screen (and the DANFE) shows - kept
 * as columns so opening a note doesn't have to read its XML file from storage. Null on
 * notes saved by a parser older than version 4, until the backfill re-reads them.
 */
@Embeddable
public record FiscalXmlHeaderExtras(
        @Column(name = "ide_tipo_impressao", length = 5) String tipoImpressao,
        @Column(name = "ide_tipo_emissao", length = 5) String tipoEmissao,
        @Column(name = "ide_digito_verificador", length = 5) String digitoVerificador,
        @Column(name = "ide_processo_emissao", length = 5) String processoEmissao,
        @Column(name = "ide_versao_processo_emissao", length = 30) String versaoProcessoEmissao,
        @Column(name = "emit_codigo_pais", length = 10) String emitenteCodigoPais,
        @Column(name = "emit_pais", length = 60) String emitentePais,
        @Column(name = "dest_codigo_pais", length = 10) String destinatarioCodigoPais,
        @Column(name = "dest_pais", length = 60) String destinatarioPais
) {
}
