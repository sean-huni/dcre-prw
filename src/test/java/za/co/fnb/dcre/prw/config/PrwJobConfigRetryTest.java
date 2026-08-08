package za.co.fnb.dcre.prw.config;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.repository.support.ResourcelessJobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.support.transaction.ResourcelessTransactionManager;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.support.DefaultTransactionStatus;
import za.co.fnb.dcre.prw.service.EmissionService;
import za.co.fnb.dcre.prw.service.EmissionTasklet;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Registration proof for the shared CRDB 40001 retry handler on the PRODUCTION emitStep
 * (platform-batch CrdbRetryExceptionHandler). The only injected failure is thrown from
 * PlatformTransactionManager.doCommit, the observed live failure mode, which is reachable
 * ONLY at the step's own commit boundary and therefore invisible to the per-transaction
 * CrdbRetry inside the service.
 *
 * <p>A step without the handler fails on the first abort; a wired one re-runs the whole
 * tasklet in a fresh transaction, which the EmissionService idempotency design (R-24
 * claim-once, R-05 StagedWrite no-op, VISIBLE guard) makes safe.
 *
 * <p>CRW puts this handler on the partitioned WORKER step and deliberately not on the
 * manager. PRW has one step and no partitioning, so there is no manager to keep clean.
 */
class PrwJobConfigRetryTest {

    /** Fails the first {@code failures} commits the way JdbcTransactionManager surfaces a CRDB 40001. */
    static final class CommitFailingTxManager extends ResourcelessTransactionManager {

        private final int failures;
        private int commits;

        CommitFailingTxManager(final int failures) {
            this.failures = failures;
        }

        @Override
        protected void doCommit(final DefaultTransactionStatus status) {
            if (++commits <= failures) {
                throw new CannotAcquireLockException(
                        "JDBC commit; ERROR: restart transaction: TransactionRetryWithProtoRefreshError:"
                                + " RETRY_ASYNC_WRITE_FAILURE");
            }
            super.doCommit(status);
        }
    }

    @Test
    void emitStepRetriesCommitTimeCrdbAbortsThenCompletes() throws Exception {
        final UUID arrivalId = UUID.randomUUID();
        final var taskletRuns = new AtomicInteger();
        final EmissionService counting = new EmissionService(null, null, null, null, null,
                new ResourcelessTransactionManager()) {
            @Override
            public int emit(final UUID id) {
                taskletRuns.incrementAndGet();
                return 0;
            }
        };

        final var repo = new ResourcelessJobRepository();
        final Step step = new PrwJobConfig().emitStep(repo, new CommitFailingTxManager(2),
                new EmissionTasklet(counting, arrivalId));

        final JobParameters params = new JobParametersBuilder()
                .addString("arrival.id", arrivalId.toString(), true).toJobParameters();
        final JobInstance instance = repo.createJobInstance("prwJob", params);
        final JobExecution jobExecution = repo.createJobExecution(instance, params, new ExecutionContext());
        final StepExecution stepExecution = repo.createStepExecution("emitStep", jobExecution);
        step.execute(stepExecution);

        assertEquals(BatchStatus.COMPLETED, stepExecution.getStatus(),
                "two commit-time 40001 aborts must be retried on emitStep, not fail it");
        assertEquals(3, taskletRuns.get(),
                "the whole tasklet re-runs in a fresh transaction per aborted commit");
    }
}
