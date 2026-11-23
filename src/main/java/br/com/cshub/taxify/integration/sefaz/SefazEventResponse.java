package br.com.cshub.taxify.integration.sefaz;

import java.util.List;
import java.util.Set;

/**
 * NFeRecepcaoEvento4's answer: the batch's cStat (128 = processed) and one result per event.
 */
public record SefazEventResponse(String cStat, String xMotivo, List<Result> results) {

    /** 128: batch received and processed - each event then has its own cStat. */
    public boolean processed() {
        return "128".equals(cStat);
    }

    /**
     * @param cStat 135 registered, 136 registered without linking, 573 duplicated (already had one)
     */
    public record Result(String accessKey, String cStat, String xMotivo, String protocol, String registeredAt) {

        private static final Set<String> REGISTERED = Set.of("135", "136");
        /** "Rejeição: Duplicidade de evento" - the note already has this ciência (from SIEG, an ERP...). */
        public static final String DUPLICATED = "573";

        public boolean registered() {
            return cStat != null && REGISTERED.contains(cStat);
        }

        public boolean alreadyDone() {
            return DUPLICATED.equals(cStat);
        }
    }
}
