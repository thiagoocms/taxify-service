package br.com.cshub.taxify.service;

import java.util.Map;

/**
 * The entry CFOP the recipient books for a supplier's exit CFOP: the place goes from 5/6/7
 * (exit) to 1/2/3 (entry) and the operation keeps its meaning (5102 venda de mercadoria →
 * 1102 compra para comercialização). Only the default reading - a purchase for use and
 * consumption (1556) or for the fixed assets (1551) depends on what the company does with
 * the product, so the suggestion is a starting point the accountant confirms.
 */
public final class EntryCfops {

    /** Exit operations whose entry counterpart has another number. */
    private static final Map<String, String> OPERATION = Map.of(
            "405", "403",   // venda com ST retida anteriormente → compra p/ comercialização com ST
            "655", "652",   // venda de combustível a comerciante → compra de combustível p/ comercialização
            "656", "653");  // venda de combustível a consumidor final → compra de combustível p/ consumo

    private EntryCfops() {
    }

    /** {@code cfop} itself when it is not a 4-digit exit CFOP (already an entry one, blank...). */
    public static String fromSupplier(String cfop) {
        if (cfop == null) {
            return null;
        }
        String code = cfop.strip();
        if (code.length() != 4 || !code.chars().allMatch(Character::isDigit)) {
            return code;
        }
        char place = switch (code.charAt(0)) {
            case '5' -> '1';
            case '6' -> '2';
            case '7' -> '3';
            default -> 0;
        };
        if (place == 0) {
            return code;
        }
        String operation = code.substring(1);
        return place + OPERATION.getOrDefault(operation, operation);
    }
}
