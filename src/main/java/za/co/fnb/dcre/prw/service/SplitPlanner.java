package za.co.fnb.dcre.prw.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import za.co.fnb.dcre.prw.config.PrwSplitProperties;
import za.co.fnb.dcre.prw.data.model.DueArrivalRow;
import za.co.fnb.dcre.prw.data.model.PlanTotals;
import za.co.fnb.dcre.prw.data.model.PrwEmissionEntity;
import za.co.fnb.dcre.prw.data.model.PrwEmissionGroupEntity;
import za.co.fnb.dcre.prw.data.repo.PrwEmissionGroupRepo;
import za.co.fnb.dcre.prw.data.repo.PrwEmissionMemberRepo;
import za.co.fnb.dcre.prw.data.repo.PrwEmissionRepo;

import java.util.List;
import java.util.UUID;

/**
 * Business tier: forms the split plan inside the caller's arrival transaction (one CRDB
 * snapshot) and claims group, batches and members set-based. Claim-once at every level: a
 * replay finds the stored plan and returns it; a config change never repartitions an
 * existing plan. The plan transaction is publication-free: batches end MATERIALIZED and
 * files are written only after it commits (durable-effect ordering).
 *
 * <p>The SPLIT is kept from CRW and is not warehousing machinery: it is the Fintegrate
 * file-size cap (max tx per outbound pain.008), which a 300k-transaction payment arrival
 * needs exactly as much as a collections one. What is gone is the run-date dimension
 * around it.
 */
@Service
public class SplitPlanner {

    private final PrwEmissionGroupRepo groups;
    private final PrwEmissionRepo emissions;
    private final PrwEmissionMemberRepo members;
    private final PrwSplitProperties split;

    public SplitPlanner(final PrwEmissionGroupRepo groups, final PrwEmissionRepo emissions,
                        final PrwEmissionMemberRepo members, final PrwSplitProperties split) {
        this.groups = groups;
        this.emissions = emissions;
        this.members = members;
        this.split = split;
    }

    /**
     * Inside the caller's arrival tx. Claim-once: a replay finds the stored plan and
     * returns it. The returned rows carry state AND frozen identity (outbound MsgId, file
     * name) read in the SAME snapshot, so the caller publishes the unpublished batches
     * from exactly these rows and never recomputes identity later.
     */
    public List<PrwEmissionEntity> planAndClaim(final DueArrivalRow arrival) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "planAndClaim must run inside the caller's arrival transaction (snapshot consistency)");
        }
        final UUID arrivalId = arrival.arrivalId();
        if (groups.findByArrivalId(arrivalId).isEmpty()) {
            final int max = split.maxFor(arrival.client());
            final PlanTotals totals = emissions.planTotals(arrivalId);
            if (totals.totalTx() == 0) {
                return List.of();
            }
            final int count = (int) Math.ceil(totals.totalTx() / (double) max);
            groups.claimPlan(PrwEmissionGroupEntity.planned(arrivalId, arrival.client(),
                    arrival.msgId(), max, totals.totalTx(), totals.totalAmount(), count, count > 1));
        }
        final PrwEmissionGroupEntity group = groups.findByArrivalId(arrivalId).orElseThrow();
        claimBatches(group);
        return emissions.findByArrivalIdOrderByBatchOrdinal(arrivalId);
    }

    /**
     * Outbound identity. An unsplit arrival keeps the bare source MsgId; a split arrival's
     * children are suffixed _1.._N. Bare and _1 never coexist within an arrival, so the
     * identity space is collision-free.
     *
     * <p>CRW additionally offsets the ordinal by {@code countPriorArtifacts}, the batches
     * the parent already claimed on OTHER run dates, because a warehoused remainder
     * maturing later is a NEW physical artifact that must not reuse a prior name. There is
     * no such thing here: a payments arrival is emitted once, so its artifact sequence is
     * exactly 1..N and the offset would always be zero. Carrying a term that is provably
     * always zero is worse than removing it, because it implies a case that cannot occur.
     */
    private void claimBatches(final PrwEmissionGroupEntity g) {
        final List<Integer> bounds = g.getExpectedBatchCount() > 1
                ? emissions.batchBoundaries(g.getArrivalId(), g.getAppliedMax()) : List.of();
        final boolean suffixed = g.isSplit();
        int lo = 0; // exclusive lower bound sequence; first batch starts at the beginning
        for (int ordinal = 1; ordinal <= g.getExpectedBatchCount(); ordinal++) {
            final int hi = ordinal <= bounds.size() ? bounds.get(ordinal - 1) : -1; // -1 = open tail
            final String outbound = suffixed
                    ? "%s_%d".formatted(g.getSourceMsgId(), ordinal) : g.getSourceMsgId();
            final String file = suffixed
                    ? "%s_%s_%d_PAIN008.xml".formatted(g.getClient(), g.getSourceMsgId(), ordinal)
                    : "%s_%s_PAIN008.xml".formatted(g.getClient(), g.getSourceMsgId());
            emissions.claimSnapshot(PrwEmissionEntity.plannedBatch(g.getId(), g.getArrivalId(),
                    ordinal, outbound, file));
            final PrwEmissionEntity batch = emissions.findByArrivalIdOrderByBatchOrdinal(g.getArrivalId())
                    .get(ordinal - 1);
            if ("PLANNED".equals(batch.getState())) {
                // The member claim lives on PrwEmissionRepo's PrwDueQueries fragment,
                // beside the eligibility gates it must apply identically. CRW splits it
                // into a separate CrwMemberClaims fragment only because its two-arm
                // composition had to be shared with the due queries; with one arm there
                // is nothing to share and the extra interface is indirection.
                emissions.claimMembers(batch.getId(), g.getArrivalId(), lo + 1, hi);
                final var totals = members.batchTotals(batch.getId());
                emissions.freezeTotals(batch.getId(), totals.count(), totals.sum());
                // MATERIALIZED is the plan tx's terminal state: members and totals frozen,
                // NO file yet. Publication (StagedWrite then markVisible) happens only
                // AFTER this transaction commits (durable-effect ordering).
                emissions.transition(batch.getId(), "MATERIALIZED");
            }
            lo = hi == -1 ? lo : hi;
        }
    }
}
