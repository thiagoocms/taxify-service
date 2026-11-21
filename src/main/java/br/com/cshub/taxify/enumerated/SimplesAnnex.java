package br.com.cshub.taxify.enumerated;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Anexos do Simples Nacional (LC 123/2006, tables as of LC 155/2016): six revenue brackets
 * by RBT12, each with a nominal rate and a deduction. The effective rate is
 * {@code (RBT12 × nominal − deduction) / RBT12}.
 */
public enum SimplesAnnex {

    /** Comércio. */
    I(new double[][]{{4.00, 0}, {7.30, 5_940}, {9.50, 13_860}, {10.70, 22_500}, {14.30, 87_300}, {19.00, 378_000}}),
    /** Indústria. */
    II(new double[][]{{4.50, 0}, {7.80, 5_940}, {10.00, 13_860}, {11.20, 22_500}, {14.70, 85_500}, {30.00, 720_000}}),
    /** Serviços em geral (e anexo V com fator R ≥ 28%). */
    III(new double[][]{{6.00, 0}, {11.20, 9_360}, {13.50, 17_640}, {16.00, 35_640}, {21.00, 125_640}, {33.00, 648_000}}),
    /** Construção, vigilância, limpeza, advocacia - CPP fora do DAS. */
    IV(new double[][]{{4.50, 0}, {9.00, 8_100}, {10.20, 12_420}, {14.00, 39_780}, {22.00, 183_780}, {33.00, 828_000}}),
    /** Serviços intelectuais com fator R < 28%. */
    V(new double[][]{{15.50, 0}, {18.00, 4_500}, {19.50, 9_900}, {20.50, 17_100}, {23.00, 62_100}, {30.50, 540_000}});

    /** Upper RBT12 of each bracket. */
    private static final double[] BRACKET_LIMITS = {180_000, 360_000, 720_000, 1_800_000, 3_600_000, 4_800_000};

    private final double[][] brackets;

    SimplesAnnex(double[][] brackets) {
        this.brackets = brackets;
    }

    /** 1..6; above the 4.8M ceiling stays in the last bracket (the company is out of the Simples anyway). */
    public static int bracketOf(BigDecimal rbt12) {
        double value = rbt12.doubleValue();
        for (int i = 0; i < BRACKET_LIMITS.length; i++) {
            if (value <= BRACKET_LIMITS[i]) {
                return i + 1;
            }
        }
        return BRACKET_LIMITS.length;
    }

    /** Effective rate in percent (4 decimals), e.g. 5.1234 for 5,1234%. */
    public BigDecimal effectiveRate(BigDecimal rbt12) {
        double[] bracket = brackets[bracketOf(rbt12) - 1];
        double value = rbt12.doubleValue();
        if (value <= 0 || bracket[1] == 0) {
            return BigDecimal.valueOf(bracket[0]).setScale(4, RoundingMode.HALF_UP);
        }
        double effective = (value * bracket[0] / 100 - bracket[1]) / value * 100;
        return BigDecimal.valueOf(effective).setScale(4, RoundingMode.HALF_UP);
    }
}
