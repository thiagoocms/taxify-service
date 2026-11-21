package br.com.cshub.taxify.integration.billing;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * @param customerIds     only these customers (the firm's linked companies)
 * @param categoryIds     only receivables in these categories; empty = all of them
 * @param dueFrom         due date window (most providers require one)
 * @param dueTo           due date window end
 * @param changedSince    only receivables changed after this (incremental sync); null = all in the window
 */
public record ChargeQuery(
        List<String> customerIds,
        List<String> categoryIds,
        LocalDate dueFrom,
        LocalDate dueTo,
        LocalDateTime changedSince
) {
}
