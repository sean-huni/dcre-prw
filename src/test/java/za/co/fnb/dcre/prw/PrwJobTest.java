package za.co.fnb.dcre.prw;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole job, end to end, launched exactly the way AGT launches it: ONE identifying job
 * parameter, {@code arrival.id}, and nothing else. That parameter set is the MRW shape and
 * it has a consequence worth stating, because it caught these tests out first:
 *
 * <p><b>One arrival is one JobInstance, forever.</b> Spring Batch refuses to re-run an
 * instance whose last execution COMPLETED, so a relaunch of a finished arrival cannot
 * happen at all. AGT's real recovery path is a KILLED pod, whose execution is left
 * STARTED (then abandoned by StaleExecutionSweeper) or FAILED, and only that is
 * restartable. Both branches are asserted below, and neither invents an extra identifying
 * parameter to make relaunching easy: AGT supplies none, so a test that did would be
 * proving something production cannot reach.
 *
 * <p>CRW's counterpart launches with {@code (run.date, window)} and its central assertion
 * is that only the rows scheduled for the run date emit while the rest stay warehoused.
 * Neither the parameters nor that assertion has a successor here. The replacement is the
 * opposite claim: EVERY validated row emits, immediately, in one pass.
 */
class PrwJobTest extends PrwTestcontainersBase {

    @Autowired
    Job prwJob;

    @Autowired
    JobOperator jobOperator;

    private JobExecution launch(final UUID arrivalId) throws Exception {
        return jobOperator.start(prwJob, new JobParametersBuilder()
                .addString("arrival.id", arrivalId.toString(), true).toJobParameters());
    }

    private Path fileFor(final String msgId) {
        return Path.of("build/test-exchange/fnbrf01/fint-req/out", "FNBRF01_" + msgId + "_PAIN008.xml");
    }

    @Test
    void everyValidatedRowEmitsInOnePass() throws Exception {
        final UUID arrival = UUID.randomUUID();
        final String msgId = "DCRERFPRW" + arrival.toString().substring(0, 6);
        seedEligibleArrival(arrival, "FNBRF01", msgId, 6);

        assertEquals(BatchStatus.COMPLETED, launch(arrival).getStatus());

        final List<String> xml = Files.readAllLines(fileFor(msgId));
        assertEquals(1, xml.stream().filter(l -> l.contains("<NbOfTxs>6</NbOfTxs>")).count(),
                "ALL 6 validated rows emit at once: payments has no collection day, so"
                        + " nothing is held back for a later date");
        assertTrue(xml.stream().anyMatch(l -> l.contains("<Cd>TT2</Cd>")), "R-02 TT2 assertion slot");
        assertTrue(xml.stream().anyMatch(l -> l.contains("<MsgId>" + msgId + "</MsgId>")),
                "unsplit arrival keeps the bare source MsgId as its outbound identity");
    }

    /**
     * The job-identity guarantee. A completed arrival cannot be re-run at all, so a
     * duplicate emission is unreachable from the launch surface even before the VISIBLE
     * state guard gets a chance to decline it. Two independent controls, which is what
     * makes a duplicate pain.008 on the wire genuinely hard rather than merely unlikely.
     */
    @Test
    void aCompletedArrivalCannotBeRelaunchedAtAll() throws Exception {
        final UUID arrival = UUID.randomUUID();
        final String msgId = "DCRERFONE" + arrival.toString().substring(0, 6);
        seedEligibleArrival(arrival, "FNBRF01", msgId, 4);

        assertEquals(BatchStatus.COMPLETED, launch(arrival).getStatus());
        Files.delete(fileFor(msgId));

        assertThrows(JobInstanceAlreadyCompleteException.class, () -> launch(arrival),
                "arrival.id is the whole job identity, so a finished arrival is finished");
        assertTrue(Files.notExists(fileFor(msgId)),
                "and nothing was written: the refused launch never reached the exchange");
    }

    /**
     * AGT's real recovery path. The pod died mid-stage, so the execution is restartable,
     * and the resume must rebuild the IDENTICAL member set from the frozen snapshot rather
     * than from the live spine, which has drifted since (R-24).
     */
    @Test
    void aKilledStageResumesFromTheFrozenSnapshotNotTheDriftedSpine() throws Exception {
        final UUID arrival = UUID.randomUUID();
        final String msgId = "DCRERFKIL" + arrival.toString().substring(0, 6);
        seedEligibleArrival(arrival, "FNBRF01", msgId, 6);

        final JobExecution first = launch(arrival);
        assertEquals(BatchStatus.COMPLETED, first.getStatus());

        // The spine drifts: a 7th transaction is validated and verdicted AFTER the plan froze.
        jdbc.update("INSERT INTO tx_entry (arrival_id, sequence, e2e, amount) VALUES (?,7,'E2EDRIFT',10.00)"
                + " ON CONFLICT (arrival_id, sequence) DO NOTHING", arrival);
        jdbc.update("INSERT INTO validation_log (arrival_id, sequence, outcome) VALUES (?,7,'PASS')"
                + " ON CONFLICT (arrival_id, sequence) DO NOTHING", arrival);
        jdbc.update("INSERT INTO pai_verdict (arrival_id, sequence, action) VALUES (?,7,'CREATED')"
                + " ON CONFLICT (arrival_id, sequence) DO NOTHING", arrival);
        // ...and the pod died before markVisible committed, with its file lost.
        jdbc.update("UPDATE prw_emission SET state = 'MATERIALIZED' WHERE arrival_id = ?", arrival);
        Files.delete(fileFor(msgId));
        simulatePodKill(first.getId());

        assertEquals(BatchStatus.COMPLETED, launch(arrival).getStatus(),
                "a killed instance is restartable, unlike a completed one");

        final List<String> rerun = Files.readAllLines(fileFor(msgId));
        assertTrue(rerun.stream().anyMatch(l -> l.contains("<NbOfTxs>6</NbOfTxs>")),
                "6, not 7: the restart rebuilt from the immutable snapshot, never the"
                        + " drifted live selection");
    }

    /**
     * The other half of the recovery path: the pod died AFTER the batch went VISIBLE, so
     * the file was already handed to Fintegrate. The restart must not re-send it.
     */
    @Test
    void aKilledStageWhoseBatchWasAlreadyVisibleDoesNotReEmit() throws Exception {
        final UUID arrival = UUID.randomUUID();
        final String msgId = "DCRERFVIS" + arrival.toString().substring(0, 6);
        seedEligibleArrival(arrival, "FNBRF01", msgId, 3);

        final JobExecution first = launch(arrival);
        assertEquals(BatchStatus.COMPLETED, first.getStatus());
        Files.delete(fileFor(msgId));          // Fintegrate consumed it
        simulatePodKill(first.getId());

        assertEquals(BatchStatus.COMPLETED, launch(arrival).getStatus());
        assertTrue(Files.notExists(fileFor(msgId)),
                "a VISIBLE batch was already handed to Fintegrate: the restart must never re-send it");
    }
}
