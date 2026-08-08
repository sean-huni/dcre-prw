package za.co.fnb.dcre.prw.data.repo;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import za.co.fnb.dcre.prw.data.model.PrwEmissionEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface PrwEmissionRepo extends CrudRepository<PrwEmissionEntity, UUID>, PrwDueQueries {

    /** Freezes the batch's member totals at plan time; R-24 reconciles each file against its OWN frozen values. */
    @Modifying
    @Query("UPDATE prw_emission SET tx_count = :c, control_sum = :s, updated_at = now() WHERE id = :id")
    void freezeTotals(@Param("id") UUID id, @Param("c") long c, @Param("s") BigDecimal s);

    /**
     * Snapshot claim (R-24) at batch grain: first writer wins on the FULL identity
     * (arrival_id, batch_ordinal); a restart no-ops and reuses the existing batch row.
     *
     * <p>CRW's conflict target is (arrival_id, run_date, batch_ordinal). The run date is
     * gone here, and this is the one narrowing in the fork that deserves justification
     * rather than assertion: it is safe ONLY because the dimension removed cannot vary
     * for a payments arrival. A payment has no collection day, so it is never replanned
     * on a second date, so (arrival, ordinal) already enumerates every physical artifact
     * the arrival can ever produce. The cross-arrival guard is the unique index on
     * outbound_msg_id, which fails a repeated outbound identity at the claim rather than
     * on the wire.
     */
    @Modifying
    @Query("""
            INSERT INTO prw_emission (id, group_id, arrival_id, batch_ordinal,
                                      outbound_msg_id, file_name, state)
            VALUES (:#{#e.id}, :#{#e.groupId}, :#{#e.arrivalId}, :#{#e.batchOrdinal},
                    :#{#e.outboundMsgId}, :#{#e.fileName}, :#{#e.state})
            ON CONFLICT (arrival_id, batch_ordinal) DO NOTHING""")
    void claimSnapshot(@Param("e") PrwEmissionEntity e);

    List<PrwEmissionEntity> findByArrivalIdOrderByBatchOrdinal(UUID arrivalId);

    @Modifying
    @Query("UPDATE prw_emission SET state = :state, updated_at = now() WHERE id = :id AND state <> :state")
    void transition(@Param("id") UUID id, @Param("state") String state);

    /** Ordinal publication: the atomic VISIBLE move stamps visible_at for SLA timers. */
    @Modifying
    @Query("UPDATE prw_emission SET state = 'VISIBLE', visible_at = now(), updated_at = now()"
            + " WHERE id = :id AND state <> 'VISIBLE'")
    void markVisible(@Param("id") UUID id);
}
