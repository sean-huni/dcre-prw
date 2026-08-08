package za.co.fnb.dcre.prw.service;

import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

import java.util.UUID;

/**
 * Thin entry adapter (3-tier): one job execution = one arrival. Identifying JobParameter
 * is {@code arrival.id} (R-16), the same shape MRW uses, because PRW is a per-arrival DAG
 * stage and not a clock window.
 *
 * <p>CRW's equivalent takes {@code run.date} plus a comma-joined client lane, because it
 * is a partitioned sweep of a run date. PRW takes neither: there is no run date, and one
 * arrival belongs to exactly one client, so there is nothing to partition. The batches
 * WITHIN an arrival must publish in strict ordinal order anyway, so parallelising them is
 * not available either.
 */
public class EmissionTasklet implements Tasklet {

    private final EmissionService emission;
    private final UUID arrivalId;

    public EmissionTasklet(final EmissionService emission, final UUID arrivalId) {
        this.emission = emission;
        this.arrivalId = arrivalId;
    }

    @Override
    public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext) {
        final int emitted = emission.emit(arrivalId);
        chunkContext.getStepContext().getStepExecution().getExecutionContext().putInt("emitted", emitted);
        return RepeatStatus.FINISHED;
    }
}
