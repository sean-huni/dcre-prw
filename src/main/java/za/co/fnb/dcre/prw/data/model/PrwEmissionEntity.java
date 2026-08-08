package za.co.fnb.dcre.prw.data.model;

import org.springframework.data.relational.core.mapping.Table;
import za.co.fnb.dcre.platform.persistence.BaseEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Batch grain: one row per (arrival, batch ordinal). An unsplit arrival keeps exactly
 * one batch carrying the bare source MsgId as its outbound identity.
 * {@code txCount}/{@code controlSum} are frozen at plan time (R-24) and every file
 * reconciles against its OWN frozen values before it is built.
 *
 * <p>No run date. CRW carries one because a warehoused remainder re-emits as a new
 * physical artifact on a later collection day; a payment is emitted once, in full, by
 * the PRW stage AGT launches after PAI.
 */
@Table("prw_emission")
public class PrwEmissionEntity extends BaseEntity {

    private UUID groupId;
    private UUID arrivalId;
    private int batchOrdinal;
    private String outboundMsgId;
    private String fileName;
    private String state;
    private Long txCount;
    private BigDecimal controlSum;
    private Instant visibleAt;

    public static PrwEmissionEntity plannedBatch(final UUID groupId, final UUID arrivalId,
            final int batchOrdinal, final String outboundMsgId, final String fileName) {
        PrwEmissionEntity e = new PrwEmissionEntity();
        e.assignIdIfMissing();
        e.groupId = groupId;
        e.arrivalId = arrivalId;
        e.batchOrdinal = batchOrdinal;
        e.outboundMsgId = outboundMsgId;
        e.fileName = fileName;
        e.state = "PLANNED";
        return e;
    }

    public UUID getGroupId() { return groupId; }
    public UUID getArrivalId() { return arrivalId; }
    public int getBatchOrdinal() { return batchOrdinal; }
    public String getOutboundMsgId() { return outboundMsgId; }
    public String getFileName() { return fileName; }
    public String getState() { return state; }
    public Long getTxCount() { return txCount; }
    public BigDecimal getControlSum() { return controlSum; }
    public Instant getVisibleAt() { return visibleAt; }
}
