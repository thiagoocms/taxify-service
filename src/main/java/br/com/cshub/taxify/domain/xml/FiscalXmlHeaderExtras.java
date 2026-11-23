package br.com.cshub.taxify.domain.xml;

import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlEnderecoDTO;
import br.com.cshub.taxify.integration.sieg.FiscalXmlMetadata;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
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

    public static FiscalXmlHeaderExtras of(FiscalXmlMetadata metadata) {
        var ide = metadata.identificacao();
        FiscalXmlEnderecoDTO emitente = metadata.emitente() == null ? null : metadata.emitente().endereco();
        FiscalXmlEnderecoDTO destinatario = metadata.destinatario() == null ? null : metadata.destinatario().endereco();
        return new FiscalXmlHeaderExtras(
                ide == null ? null : ide.tipoImpressao(),
                ide == null ? null : ide.tipoEmissao(),
                ide == null ? null : ide.digitoVerificador(),
                ide == null ? null : ide.processoEmissao(),
                ide == null ? null : ide.versaoProcessoEmissao(),
                emitente == null ? null : emitente.codigoPais(),
                emitente == null ? null : emitente.pais(),
                destinatario == null ? null : destinatario.codigoPais(),
                destinatario == null ? null : destinatario.pais());
    }
}
