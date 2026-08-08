package za.co.fnb.dcre.prw.data.model;

import org.springframework.data.relational.core.mapping.Table;
import za.co.fnb.dcre.platform.persistence.BaseEntity;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One row per ARRIVAL: the durable split plan. The applied max and batch count are
 * FROZEN here at plan time, so a config change never repartitions an existing plan.
 *
 * <p>CRW's equivalent is keyed (arrival_id, run_date) because collections warehouses
 * and the same parent legitimately replans on a later collection day. Payments has no
 * collection day and no futured remainder, so the arrival IS the key and there is no
 * run date on this entity at all.
 */
@Table("prw_emission_group")
public class PrwEmissionGroupEntity extends BaseEntity {

    private UUID arrivalId;
    private String client;
    private String sourceMsgId;
    private int appliedMax;
    private long totalTx;
    private BigDecimal totalAmount;
    private int expectedBatchCount;
    private boolean split;

    public static PrwEmissionGroupEntity planned(final UUID arrivalId, final String client,
            final String sourceMsgId, final int appliedMax, final long totalTx,
            final BigDecimal totalAmount, final int expectedBatchCount, final boolean split) {
        PrwEmissionGroupEntity g = new PrwEmissionGroupEntity();
        g.assignIdIfMissing();
        g.arrivalId = arrivalId;
        g.client = client;
        g.sourceMsgId = sourceMsgId;
        g.appliedMax = appliedMax;
        g.totalTx = totalTx;
        g.totalAmount = totalAmount;
        g.expectedBatchCount = expectedBatchCount;
        g.split = split;
        return g;
    }

    public UUID getArrivalId() { return arrivalId; }
    public String getClient() { return client; }
    public String getSourceMsgId() { return sourceMsgId; }
    public int getAppliedMax() { return appliedMax; }
    public long getTotalTx() { return totalTx; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public int getExpectedBatchCount() { return expectedBatchCount; }
    public boolean isSplit() { return split; }
}
