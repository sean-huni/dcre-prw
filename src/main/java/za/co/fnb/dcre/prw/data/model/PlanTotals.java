package za.co.fnb.dcre.prw.data.model;

import java.math.BigDecimal;

/** Whole-arrival totals frozen into the emission group at plan time (SCRUM-55). */
public record PlanTotals(long totalTx, BigDecimal totalAmount) {
}
