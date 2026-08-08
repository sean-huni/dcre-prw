package za.co.fnb.dcre.prw.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import za.co.fnb.dcre.prw.PrwTestcontainersBase;
import za.co.fnb.dcre.prw.config.PrwSplitProperties;
import za.co.fnb.dcre.prw.data.model.DueArrivalRow;
import za.co.fnb.dcre.prw.data.model.PrwEmissionEntity;
import za.co.fnb.dcre.prw.data.repo.PrwEmissionGroupRepo;
import za.co.fnb.dcre.prw.data.repo.PrwEmissionMemberRepo;
import za.co.fnb.dcre.prw.data.repo.PrwEmissionRepo;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Snapshot-consistent split plan + set-based ordinal member claims. The plan freezes at
 * first claim: config changes and restarts replay the STORED plan, never a repartition.
 *
 * <p>The SPLIT is kept from CRW deliberately. It is the Fintegrate file-size cap, not
 * warehousing machinery: a 300k-transaction payment arrival needs splitting exactly as
 * much as a collections one. What is gone is the run-date dimension AROUND it, and with it
 * CRW's {@code reEmissionOnALaterRunDateContinuesTheArtifactSequence}, which asserted that
 * a warehoused remainder maturing later continues the artifact sequence (_4 after _1.._3).
 * That scenario cannot occur here, and its absence is asserted structurally in
 * {@code NoClockPathTest} rather than left as a silently deleted test.
 */
class SplitPlannerIT extends PrwTestcontainersBase {

    @Autowired
    PrwEmissionGroupRepo groups;

    @Autowired
    PrwEmissionRepo emissions;

    @Autowired
    PrwEmissionMemberRepo members;

    @Autowired
    PlatformTransactionManager txManager;

    private TransactionTemplate txTemplate;
    private SplitPlanner planner;
    private UUID arrivalId;
    private String msgId;

    @BeforeEach
    void setUp() {
        txTemplate = new TransactionTemplate(txManager);
        arrivalId = UUID.randomUUID();
        setMax(5000);
    }

    private void setMax(final int max) {
        planner = new SplitPlanner(groups, emissions, members, new PrwSplitProperties(max, Map.of()));
    }

    private DueArrivalRow dueArrival() {
        return new DueArrivalRow(arrivalId, "FNBRF01", msgId);
    }

    private int memberCount(final PrwEmissionEntity batch) {
        return jdbc.queryForObject("SELECT count(*) FROM prw_emission_member WHERE emission_id = ?",
                Integer.class, batch.getId());
    }

    @Test
    void plans12001TxAtMax5000IntoThreeOrdinalBatches() {
        msgId = "DCRERF2026080800000001";
        seedEligibleArrival(arrivalId, "FNBRF01", msgId, 12001);

        var batches = txTemplate.execute(s -> planner.planAndClaim(dueArrival()));

        assertThat(batches).extracting(PrwEmissionEntity::getBatchOrdinal).containsExactly(1, 2, 3);
        assertThat(batches).extracting(PrwEmissionEntity::getOutboundMsgId).containsExactly(
                msgId + "_1", msgId + "_2", msgId + "_3");
        assertThat(memberCount(batches.get(0))).isEqualTo(5000);
        assertThat(memberCount(batches.get(2))).isEqualTo(2001);

        var group = groups.findByArrivalId(arrivalId).orElseThrow();
        assertThat(group.getAppliedMax()).isEqualTo(5000);
        assertThat(group.getExpectedBatchCount()).isEqualTo(3);
        assertThat(group.getTotalTx()).isEqualTo(12001L);
    }

    @Test
    void unsplitKeepsBareSourceMsgIdAndFilename() {
        msgId = "DCRERF2026080800000002";
        seedEligibleArrival(arrivalId, "FNBRF01", msgId, 4999);

        var batches = txTemplate.execute(s -> planner.planAndClaim(dueArrival()));

        assertThat(batches).hasSize(1);
        assertThat(batches.get(0).getOutboundMsgId()).isEqualTo(msgId);   // NO suffix
        assertThat(batches.get(0).getFileName()).isEqualTo("FNBRF01_" + msgId + "_PAIN008.xml");
    }

    /**
     * The artifact sequence is exactly 1..N and starts at 1, with no prior-artifact offset.
     * CRW offsets by the batches the parent already claimed on OTHER run dates; here that
     * count is provably always zero, so the offset is removed rather than carried as a
     * term that implies a case which cannot occur.
     */
    @Test
    void theArtifactSequenceStartsAtOneAndNeverOffsets() {
        msgId = "DCRERF2026080800000005";
        seedEligibleArrival(arrivalId, "FNBRF01", msgId, 10001);

        var batches = txTemplate.execute(s -> planner.planAndClaim(dueArrival()));

        assertThat(batches).extracting(PrwEmissionEntity::getFileName).containsExactly(
                "FNBRF01_" + msgId + "_1_PAIN008.xml",
                "FNBRF01_" + msgId + "_2_PAIN008.xml",
                "FNBRF01_" + msgId + "_3_PAIN008.xml");
        // Replay must reuse the SAME names, never continue past _3.
        var replay = txTemplate.execute(s -> planner.planAndClaim(dueArrival()));
        assertThat(replay).extracting(PrwEmissionEntity::getFileName)
                .containsExactlyElementsOf(batches.stream().map(PrwEmissionEntity::getFileName).toList());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM prw_emission WHERE arrival_id = ?",
                Integer.class, arrivalId)).isEqualTo(3);
    }

    @Test
    void configChangePlusRestartNeverRepartitions() {
        msgId = "DCRERF2026080800000003";
        seedEligibleArrival(arrivalId, "FNBRF01", msgId, 12001);

        txTemplate.execute(s -> planner.planAndClaim(dueArrival()));   // plan at max 5000
        setMax(2000);                                                  // config change
        var replay = txTemplate.execute(s -> planner.planAndClaim(dueArrival()));

        assertThat(replay).hasSize(3);                                 // stored plan wins
        assertThat(groups.findByArrivalId(arrivalId).orElseThrow().getAppliedMax()).isEqualTo(5000);
    }

    /**
     * The outbound identity is unique across ARRIVALS, not merely within one. This is the
     * guard that makes the narrowed (arrival, ordinal) claim key safe: two arrivals of the
     * same client carrying the same source MsgId are distinct entities, and the second must
     * fail at the claim rather than produce a duplicate MsgId on the wire.
     */
    @Test
    void twoArrivalsCannotShareAnOutboundMsgId() {
        msgId = "DCRERF2026080800000006";
        seedEligibleArrival(arrivalId, "FNBRF01", msgId, 3);
        txTemplate.execute(s -> planner.planAndClaim(dueArrival()));

        final UUID twin = UUID.randomUUID();
        seedEligibleArrival(twin, "FNBRF01", msgId, 3);

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> txTemplate.execute(s ->
                        planner.planAndClaim(new DueArrivalRow(twin, "FNBRF01", msgId))),
                "a repeated outbound identity must fail at the claim, not at Fintegrate");
    }
}
