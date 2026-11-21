package br.com.cshub.taxify.service.assessment;

import br.com.cshub.taxify.enumerated.FiscalDirection;

/**
 * Which item CFOPs are revenue, returns or purchases for the period assessment. The first
 * digit is the place (5/6/7 outbound, 1/2/3 inbound); the last three are the operation.
 * Everything else - remessas, transferências, bonificações, retornos, simples faturamento
 * (x922: the revenue counts on the delivery note, x116/x117) - is not revenue.
 */
public final class RevenueCfops {

    public enum Kind {
        /** Venda de produção do estabelecimento - Simples anexo II. */
        OWN_PRODUCTION_SALE,
        /** Revenda de mercadoria - Simples anexo I. */
        RESALE,
        /** Prestação de serviço, industrialização por encomenda, transporte, comunicação. */
        SERVICE,
        /** Devolução de venda - deducted from the revenue. */
        SALES_RETURN,
        /** Compra para comercialização/industrialização - PIS/COFINS credit estimate (Lucro Real). */
        PURCHASE,
        NONE
    }

    private RevenueCfops() {
    }

    public static Kind classify(FiscalDirection direction, String cfop) {
        if (direction == null || cfop == null || cfop.length() != 4 || !cfop.chars().allMatch(Character::isDigit)) {
            return Kind.NONE;
        }
        char place = cfop.charAt(0);
        int operation = Integer.parseInt(cfop.substring(1));
        if (direction == FiscalDirection.OUTBOUND) {
            return place >= '5' && place <= '7' ? outbound(operation) : Kind.NONE;
        }
        return inbound(place, operation);
    }

    private static Kind outbound(int op) {
        if (op == 101 || op == 103 || op == 105 || op == 109 || op == 111 || op == 113 || op == 116 || op == 118
                || op == 122 || op == 401 || op == 402 || op == 651 || op == 653) {
            return Kind.OWN_PRODUCTION_SALE;
        }
        if (op == 102 || op == 104 || op == 106 || op == 110 || op == 112 || op == 114 || op == 115 || op == 117
                || op == 119 || op == 120 || op == 123 || op == 403 || op == 405 || op == 652 || op == 654 || op == 655
                || op == 656) {
            return Kind.RESALE;
        }
        if (op == 124 || op == 125 || op == 933 || (op >= 301 && op <= 307) || (op >= 351 && op <= 360)) {
            return Kind.SERVICE;
        }
        return Kind.NONE;
    }

    /*
     * A return reaches the company two ways: its own entry note (1201/2202/1411...) or the
     * customer's note, received, with the customer's own return CFOP (5201/6202/5411...).
     */
    private static Kind inbound(char place, int op) {
        boolean ownEntry = place >= '1' && place <= '3';
        boolean customerNote = place >= '5' && place <= '7';
        if ((ownEntry && (op == 201 || op == 202 || op == 203 || op == 204 || op == 410 || op == 411))
                || (customerNote && (op == 201 || op == 202 || op == 410 || op == 411))) {
            return Kind.SALES_RETURN;
        }
        // a supplier's note carries the supplier's sale CFOP (5102), an own entry note the purchase one (1102)
        if (ownEntry && (op == 101 || op == 102 || op == 401 || op == 403 || op == 126)) {
            return Kind.PURCHASE;
        }
        if (customerNote) {
            Kind supplierSide = outbound(op);
            if (supplierSide == Kind.OWN_PRODUCTION_SALE || supplierSide == Kind.RESALE) {
                return Kind.PURCHASE;
            }
        }
        return Kind.NONE;
    }
}
