package za.co.fnb.dcre.prw.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import za.co.fnb.dcre.platform.files.ExchangeChannel;
import za.co.fnb.dcre.platform.files.ExchangeLayout;
import za.co.fnb.dcre.platform.files.ExchangeSub;
import za.co.fnb.dcre.platform.files.StagedWrite;
import za.co.fnb.dcre.prw.data.model.DueArrivalRow;
import za.co.fnb.dcre.prw.data.model.PrwEmissionEntity;
import za.co.fnb.dcre.prw.data.model.PrwEmissionMemberEntity;
import za.co.fnb.dcre.prw.data.repo.PrwEmissionMemberRepo;
import za.co.fnb.dcre.prw.data.repo.PrwEmissionRepo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Business tier: emits ONE arrival's pain.008 batches.
 *
 * <p>This is where PRW stops being CRW. CRW is the R-37 Process-Date Executor, a clock
 * window that sweeps every client with work due on a run date, because collections
 * warehouses: CDE estimates a collection day and CRW emits only the slice maturing today.
 * Payments has no CDE and no collection day, so there is no sweep, no run date, no client
 * lane universe and no futured remainder to warn about. PRW is an ordinary DAG stage that
 * AGT launches with an arrival identity after PAI reports, exactly where MRW sits on the
 * mandates sheet.
 *
 * <p>What is kept from CRW, because it is emission behaviour rather than warehousing:
 * TWO durability phases per arrival. The plan transaction commits group, batches, frozen
 * members and totals (MATERIALIZED) BEFORE any file exists; publication then walks the
 * committed batches strictly in ordinal order (_2 never VISIBLE before _1), each batch
 * StagedWrite then markVisible in its own small transaction. Restart keys are per batch
 * (arrival, ordinal) plus the file-existence no-op (R-05), and each file reconciles
 * against its OWN frozen tx_count before building (R-24 frozen-plan integrity).
 */
@Service
public class EmissionService {

    private static final Logger log = LoggerFactory.getLogger(EmissionService.class);

    private final PrwEmissionRepo emissions;
    private final PrwEmissionMemberRepo members;
    private final Pain008Writer painWriter;
    private final ExchangeLayout layout;
    private final SplitPlanner planner;
    private final TransactionTemplate requiresNewTx;

    public EmissionService(final PrwEmissionRepo emissions, final PrwEmissionMemberRepo members,
                           final Pain008Writer painWriter, final ExchangeLayout layout,
                           final SplitPlanner planner, final PlatformTransactionManager txManager) {
        this.emissions = emissions;
        this.members = members;
        this.painWriter = painWriter;
        this.layout = layout;
        this.planner = planner;
        // A CRDB 40001 abort poisons the surrounding transaction (25P02 on any further
        // statement), so a retry needs a FRESH transaction per attempt. The same template
        // serves BOTH the plan transaction and each per-batch publication transaction.
        this.requiresNewTx = new TransactionTemplate(txManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Emits the launched arrival.
     *
     * <p>An ineligible arrival is NOT a failure. PAI verdicts that do not yet cover the
     * PASS set, or an arrival with no PASS rows at all, mean there is nothing to emit;
     * the stage completes having emitted zero files. That distinction matters because PRW
     * is terminal on the payments DAG: failing here would hold the arrival open, and a
     * wholly-rejected instruction book would never complete.
     *
     * @return number of pain.008 FILES emitted for the arrival (batch grain).
     */
    public int emit(final UUID arrivalId) {
        final Optional<DueArrivalRow> arrival = emissions.findDueArrival(arrivalId);
        if (arrival.isEmpty()) {
            log.info("nothing to emit stage=PRW arrival={} reason=NOT_ELIGIBLE", arrivalId);
            return 0;
        }
        return emitArrival(arrival.get());
    }

    /**
     * One arrival, two durability phases. Phase 1 (fresh REQUIRES_NEW tx per bounded-retry
     * attempt) commits the WHOLE plan: group, batches, frozen members and totals,
     * MATERIALIZED. NO file leaves phase 1: a file published before its plan commits can
     * be consumed by Fintegrate while a crash rolls the plan back. Phase 2 publishes
     * strictly in ordinal order; after a kill between plan commit and publication the
     * resume publishes exactly the unpublished ordinals of the SAME plan.
     */
    private int emitArrival(final DueArrivalRow arrival) {
        final List<PrwEmissionEntity> batches = CrdbRetry.run(
                "plan arrival=%s".formatted(arrival.arrivalId()),
                () -> requiresNewTx.execute(status -> planner.planAndClaim(arrival)));
        if (batches == null || batches.isEmpty()) {
            return 0;
        }
        int emitted = 0;
        for (final PrwEmissionEntity batch : batches) {
            if ("VISIBLE".equals(batch.getState())) {
                // Already handed to Fintegrate: a relaunch MUST NOT re-emit this batch.
                log.warn("excluded stage=PRW arrival={} seq=-1 e2e=- reason=ALREADY_VISIBLE batch={}",
                        arrival.arrivalId(), batch.getBatchOrdinal());
                continue;
            }
            publishBatch(arrival, batch);
            emitted++;
        }
        return emitted;
    }

    /**
     * Publishes ONE committed batch: StagedWrite then markVisible inside a small
     * REQUIRES_NEW transaction per bounded-retry attempt. The XML builds strictly from the
     * immutable member snapshot, never the live selection (R-24), and reconciles against
     * the batch's OWN frozen tx_count; identity (outbound MsgId, file name) comes ONLY
     * from the stored row. A replay is a per-batch file-existence StagedWrite no-op (R-05)
     * and markVisible is state-guarded, so a crash anywhere here resumes cleanly.
     */
    private void publishBatch(final DueArrivalRow arrival, final PrwEmissionEntity batch) {
        CrdbRetry.run("publish arrival=%s batch=%d".formatted(arrival.arrivalId(), batch.getBatchOrdinal()),
                () -> requiresNewTx.execute(status -> {
                    List<PrwEmissionMemberEntity> snapshot =
                            members.findByEmissionIdOrderBySequence(batch.getId());
                    if (batch.getTxCount() == null || snapshot.size() != batch.getTxCount()) {
                        throw new IllegalStateException(
                                "frozen-plan mismatch stage=PRW arrival=%s batch=%d members=%d expected=%s"
                                        .formatted(arrival.arrivalId(), batch.getBatchOrdinal(),
                                                snapshot.size(), batch.getTxCount()));
                    }
                    List<String> xml = painWriter.build(batch.getOutboundMsgId(), snapshot, batch.getControlSum());
                    // Per-client fint-req/out leaf. An unconfigured client fails closed here (resolve throws).
                    Path target = layout.resolve(arrival.client(), ExchangeChannel.FINT_REQ, ExchangeSub.OUT)
                            .resolve(batch.getFileName());
                    try {
                        StagedWrite.write(target, xml);      // per-batch file-existence restart no-op (R-05)
                    } catch (final IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    afterStagedWrite(batch);                 // crash-matrix quadrant-4 seam (production no-op)
                    emissions.markVisible(batch.getId());    // stamps visible_at for the SLA timer
                    return null;
                }));
    }

    /**
     * Crash-matrix quadrant-4 seam: runs after StagedWrite has landed the batch file and
     * before markVisible commits. File writes are not transactional, so a kill in this gap
     * leaves the file durable on disk while the publication transaction rolls back with
     * the row still MATERIALIZED; the resume must hit the R-05 file-existence no-op, never
     * a rewrite. Production no-op, package-private so the crash-matrix IT can inject the
     * kill exactly here.
     */
    void afterStagedWrite(final PrwEmissionEntity batch) {
        // production no-op: crash-matrix test seam (quadrant 4)
    }
}
