package za.co.fnb.dcre.prw.data.model;

import org.springframework.data.relational.core.mapping.Table;
import za.co.fnb.dcre.platform.persistence.BaseEntity;

import java.math.BigDecimal;
import java.util.UUID;

/** The immutable member snapshot a pain.008 is built from (R-24), never the live spine. */
@Table("prw_emission_member")
public class PrwEmissionMemberEntity extends BaseEntity {

    private UUID emissionId;
    private Integer sequence;
    private String e2e;
    private BigDecimal amount;

    public static PrwEmissionMemberEntity of(final UUID emissionId, final int sequence,
                                             final String e2e, final BigDecimal amount) {
        PrwEmissionMemberEntity e = new PrwEmissionMemberEntity();
        e.emissionId = emissionId;
        e.sequence = sequence;
        e.e2e = e2e;
        e.amount = amount;
        return e;
    }

    public UUID getEmissionId() { return emissionId; }
    public Integer getSequence() { return sequence; }
    public String getE2e() { return e2e; }
    public BigDecimal getAmount() { return amount; }
}
