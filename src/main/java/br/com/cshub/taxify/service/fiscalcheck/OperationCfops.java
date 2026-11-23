package br.com.cshub.taxify.service.fiscalcheck;

/**
 * The CFOP a rule expects on one note. A rule keeps the operation (the last three digits:
 * 102 = compra/venda de mercadoria) and whether it is an entry or an exit (1/2/3 or 5/6/7);
 * the first digit follows the note's destination (ide/idDest): 1 internal, 2 interstate,
 * 3 abroad. So a rule "1102" expects 1102 on a purchase inside the state and 2102 on one from
 * another state - one rule per product instead of one per origin.
 */
public final class OperationCfops {

    private OperationCfops() {
    }

    /**
     * @param ruleCfop the rule's CFOP
     * @param idDest   the note's ide/idDest; unknown → the rule's CFOP as registered
     */
    public static String forOperation(String ruleCfop, String idDest) {
        if (ruleCfop == null) {
            return null;
        }
        String cfop = ruleCfop.strip();
        String destination = idDest == null ? "" : idDest.strip();
        if (cfop.length() != 4 || !cfop.chars().allMatch(Character::isDigit) || destination.length() != 1
                || destination.charAt(0) < '1' || destination.charAt(0) > '3') {
            return cfop;
        }
        char place = cfop.charAt(0);
        int offset = destination.charAt(0) - '1';
        if (place >= '1' && place <= '3') {
            return (char) ('1' + offset) + cfop.substring(1);
        }
        if (place >= '5' && place <= '7') {
            return (char) ('5' + offset) + cfop.substring(1);
        }
        return cfop;
    }
}
