package br.com.cshub.taxify.domain.fiscalrule;

import br.com.cshub.taxify.domain.fiscalrule.PurchaseUnitPrices.OtherUnit;
import br.com.cshub.taxify.domain.fiscalrule.PurchaseUnitPrices.Summary;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** PU-RN-03 to 06 with the numbers of the spec's test cases (spec PU, section 11). Each item is a group of one. */
class PurchaseUnitPricesTest {

    @Test
    void ct01TheAverageIsWeightedByTheQuantity() {
        PurchaseUnitPrices prices = new PurchaseUnitPrices();
        item(prices, "UN", "10", "12.0000");
        item(prices, "UN", "30", "14.0000");
        item(prices, "UN", "60", "15.5000");

        Summary summary = prices.summary().orElseThrow();

        assertThat(summary.average()).isEqualTo(new BigDecimal("14.7000")); // the simple average would be 13.8333
        assertThat(summary.min()).isEqualTo(new BigDecimal("12.0000"));
        assertThat(summary.max()).isEqualTo(new BigDecimal("15.5000"));
        assertThat(summary.unit()).isEqualTo("UN");
        assertThat(summary.itemsConsidered()).isEqualTo(3);
        assertThat(summary.totalQuantity()).isEqualTo(new BigDecimal("100.0000"));
        assertThat(summary.otherUnits()).isEmpty();
    }

    @Test
    void ct02TheAverageIsRoundedHalfUpTo4Places() {
        PurchaseUnitPrices prices = new PurchaseUnitPrices();
        item(prices, "UN", "1", "10.0000");
        item(prices, "UN", "1", "10.0001");

        assertThat(prices.summary().orElseThrow().average()).isEqualTo(new BigDecimal("10.0001")); // 10.00005
    }

    @Test
    void ct03TheUnitIsTrimmedAndUpperCasedAndOnlyThePredominantOneIsPriced() {
        PurchaseUnitPrices prices = new PurchaseUnitPrices();
        item(prices, "un", "2", "5.0000");
        item(prices, "UN ", "2", "5.0000");
        item(prices, "UN", "1", "6.5000");
        item(prices, "CX", "1", "60.0000");

        Summary summary = prices.summary().orElseThrow();

        assertThat(summary.unit()).isEqualTo("UN");
        assertThat(summary.average()).isEqualTo(new BigDecimal("5.3000")); // 26.5 / 5
        assertThat(summary.min()).isEqualTo(new BigDecimal("5.0000"));
        assertThat(summary.max()).isEqualTo(new BigDecimal("6.5000"));
        assertThat(summary.itemsConsidered()).isEqualTo(3);
        assertThat(summary.otherUnits()).containsExactly(new OtherUnit("CX", 1));
    }

    @Test
    void ct04OnATieOfItemsTheGreatestQuantityWins() {
        PurchaseUnitPrices prices = new PurchaseUnitPrices();
        item(prices, "KG", "1.5", "4.0000");
        item(prices, "KG", "1.5", "4.0000");
        item(prices, "UN", "2", "3.0000");
        item(prices, "UN", "3", "3.0000");

        Summary summary = prices.summary().orElseThrow();

        assertThat(summary.unit()).isEqualTo("UN");
        assertThat(summary.average()).isEqualTo(new BigDecimal("3.0000"));
        assertThat(summary.min()).isEqualByComparingTo(summary.max()); // "preço único"
        assertThat(summary.otherUnits()).containsExactly(new OtherUnit("KG", 2));
    }

    @Test
    void onATieOfItemsAndQuantityTheAlphabeticalOrderWinsAndTheOthersAreByItemsThenUnit() {
        PurchaseUnitPrices prices = new PurchaseUnitPrices();
        item(prices, "PC", "1", "2.0000");
        item(prices, "CX", "1", "3.0000");
        item(prices, "KG", "1", "1.0000");
        item(prices, "KG", "1", "1.0000");
        item(prices, "UN", "1", "1.0000");
        item(prices, "UN", "1", "1.0000");

        Summary summary = prices.summary().orElseThrow();

        assertThat(summary.unit()).isEqualTo("KG");
        assertThat(summary.otherUnits()).containsExactly(new OtherUnit("UN", 2), new OtherUnit("CX", 1), new OtherUnit("PC", 1));
    }

    @Test
    void ct06NoItemWithPriceAndQuantityIsNoPrice() {
        PurchaseUnitPrices prices = new PurchaseUnitPrices();
        prices.add("UN", 1, BigDecimal.ZERO.setScale(4), BigDecimal.ZERO, new BigDecimal("10.0000"), new BigDecimal("10.0000"));
        prices.add("UN", 1, new BigDecimal("2.0000"), null, null, null);

        assertThat(prices.summary()).isEmpty();
        assertThat(new PurchaseUnitPrices().summary()).isEmpty();
    }

    @Test
    void ct08GroupsOfTheSameUnitAreSummedTakingTheSmallestMinAndTheGreatestMax() {
        PurchaseUnitPrices prices = new PurchaseUnitPrices();
        item(prices, "UN", "1", "8.0000");
        item(prices, "UN", "3", "12.0000");

        Summary summary = prices.summary().orElseThrow();

        assertThat(summary.average()).isEqualTo(new BigDecimal("11.0000")); // 44 / 4
        assertThat(summary.min()).isEqualTo(new BigDecimal("8.0000"));
        assertThat(summary.max()).isEqualTo(new BigDecimal("12.0000"));
    }

    @Test
    void ct09ABlankAndANullUnitAreTheSameNoUnit() {
        PurchaseUnitPrices prices = new PurchaseUnitPrices();
        item(prices, "", "1", "2.0000");
        item(prices, null, "1", "4.0000");

        Summary summary = prices.summary().orElseThrow();

        assertThat(summary.unit()).isEqualTo("—");
        assertThat(summary.average()).isEqualTo(new BigDecimal("3.0000"));
        assertThat(summary.itemsConsidered()).isEqualTo(2);
    }

    @Test
    void ct10APriceBelowOneKeeps4Places() {
        PurchaseUnitPrices prices = new PurchaseUnitPrices();
        item(prices, "UN", "1000", "0.0350");

        Summary summary = prices.summary().orElseThrow();

        assertThat(summary.average()).isEqualTo(new BigDecimal("0.0350"));
        assertThat(summary.totalQuantity()).isEqualTo(new BigDecimal("1000.0000"));
    }

    @Test
    void theAverageStaysBetweenMinAndMax() {
        PurchaseUnitPrices prices = new PurchaseUnitPrices();
        item(prices, "UN", "0.0001", "0.0001");
        item(prices, "UN", "3", "9999.9999");
        item(prices, "UN", "7.3333", "1.2345");

        Summary summary = prices.summary().orElseThrow();

        assertThat(summary.average()).isBetween(summary.min(), summary.max());
    }

    /** One item: count 1, Σq = q, Σ(q×p) = q × p, min = max = p - as the query brings a group of one. */
    private static void item(PurchaseUnitPrices prices, String unit, String quantity, String unitPrice) {
        BigDecimal q = new BigDecimal(quantity).setScale(4);
        BigDecimal p = new BigDecimal(unitPrice);
        prices.add(unit, 1, q, q.multiply(p), p, p);
    }
}
