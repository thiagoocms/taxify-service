package br.com.cshub.taxify.enumerated;

/** How complete the capture of the 2026 inbound notes is (CE-RN-06) - a capture indicator, not a tax rule. */
public enum StockSurveyCoverage {
    /** No inbound note in 2026. */
    NO_INBOUND,
    /** Every evaluated month has at least one inbound note. */
    COMPLETE,
    /** Some evaluated month has none. */
    PARTIAL
}
