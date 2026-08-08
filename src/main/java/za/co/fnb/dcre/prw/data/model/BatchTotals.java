package za.co.fnb.dcre.prw.data.model;

import java.math.BigDecimal;

/** Per-batch member totals frozen onto the batch row after the set-based claim (SCRUM-55). */
public record BatchTotals(long count, BigDecimal sum) {
}
