package br.com.cshub.taxify.integration.sefaz;

import java.util.List;

/**
 * One batch from NFeDistribuicaoDFe. cStat 137 = nothing new, 138 = documents found,
 * 656 = "consumo indevido" (asked again too soon - wait an hour).
 */
public record SefazDistResponse(String cStat, String xMotivo, String ultNsu, String maxNsu, List<Document> documents) {

    public record Document(String nsu, String schema, byte[] xml) {

        /** procNFe: the full note. */
        public boolean isFullNote() {
            return schema != null && schema.startsWith("procNFe");
        }

        /** resNFe: only the summary - an entry still without "Ciência da Operação". */
        public boolean isNoteSummary() {
            return schema != null && schema.startsWith("resNFe");
        }

        /** procEventoNFe: a full event (cancelamento, CC-e...); resEvento is only a summary of one. */
        public boolean isFullEvent() {
            return schema != null && schema.startsWith("procEvento");
        }
    }

    public boolean caughtUp() {
        return "137".equals(cStat) || (ultNsu != null && ultNsu.equals(maxNsu));
    }
}
