package za.co.fnb.dcre.prw.data.repo;

import za.co.fnb.dcre.prw.data.model.DueArrivalRow;
import za.co.fnb.dcre.prw.data.model.PlanTotals;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The eligibility queries, as a repository fragment rather than {@code @Query}
 * annotations, because they share one gate fragment ({@link DueSql#DUE_GATES}) that must
 * be spliced into four statements from a single source.
 *
 * <p>Every method is scoped to ONE arrival. PRW is a DAG stage: AGT launches it with the
 * arrival identity after PAI reports, so there is no run-date sweep, no client lane
 * universe and no due-set to discover. CRW's {@code findDueArrivals(runDate)} and
 * {@code findDueClients(runDate)} have no successor here.
 */
public interface PrwDueQueries {

    /**
     * The launched arrival, if it is eligible to emit. Empty means the gates are not
     * satisfied, which is a legitimate outcome (a wholly failed arrival has nothing to
     * emit) and never an error.
     */
    Optional<DueArrivalRow> findDueArrival(UUID arrivalId);

    /**
     * Whole-arrival totals for the frozen plan (SCRUM-55): executes inside the caller's
     * arrival transaction, so totals, boundaries and member claims share one CRDB
     * snapshot and the eligible set cannot shift mid-plan.
     */
    PlanTotals planTotals(UUID arrivalId);

    /** Ordinal-th boundary sequences: eligible rows ranked by original sequence, every maxSize-th. */
    List<Integer> batchBoundaries(UUID arrivalId, int maxSize);

    /**
     * Set-based ordinal member claim: the [loSeq, hiSeq] slice of the eligible rows is
     * claimed in ONE statement inside the arrival transaction; hiSeq -1 is the open tail.
     */
    void claimMembers(UUID emissionId, UUID arrivalId, int loSeq, int hiSeq);
}
