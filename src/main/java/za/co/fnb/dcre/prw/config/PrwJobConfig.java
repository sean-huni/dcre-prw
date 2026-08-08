package za.co.fnb.dcre.prw.config;

import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.transaction.PlatformTransactionManager;
import za.co.fnb.dcre.platform.batch.CrdbRetryExceptionHandler;
import za.co.fnb.dcre.platform.batch.HeartbeatWriter;
import za.co.fnb.dcre.platform.batch.OutcomeSeamListener;
import za.co.fnb.dcre.platform.batch.StaleExecutionSweeper;
import za.co.fnb.dcre.prw.service.EmissionService;
import za.co.fnb.dcre.prw.service.EmissionTasklet;

import javax.sql.DataSource;
import java.util.UUID;

/**
 * PRW job shape: ONE tasklet, un-partitioned, identifying JobParameter {@code arrival.id}
 * (R-16). This is MRW's shape, not CRW's, and deliberately so: PRW is a per-arrival DAG
 * stage on the payments sheet, launched by AGT on the parallel fork after PAI.
 *
 * <p>CRW's job is a partitioned {@code emitStep} over per-client lanes keyed by run date,
 * with a {@code ClientLanePartitioner} and a {@code LaneEmissionService}. None of that has
 * a successor here: the partitioner exists to fan a run date's DUE CLIENT UNIVERSE across
 * virtual threads, and a single arrival has exactly one client and one ordered batch
 * sequence that must publish serially.
 *
 * <p>There is likewise no scheduler bean and no clock trigger anywhere in this service.
 */
@Configuration
@EnableConfigurationProperties(PrwSplitProperties.class)
public class PrwJobConfig {

    @Bean
    @StepScope
    public EmissionTasklet emissionTasklet(final EmissionService emission,
            @Value("#{jobParameters['arrival.id']}") final String arrivalId) {
        return new EmissionTasklet(emission, UUID.fromString(arrivalId));
    }

    @Bean
    public Step emitStep(final JobRepository repo, final PlatformTransactionManager tx,
                         final EmissionTasklet emissionTasklet) {
        // CRDB 40001 aborts hit the tasklet's commit boundary under contention; the shared
        // platform handler re-runs the WHOLE tasklet in a fresh transaction, which is safe
        // here by design: claim-once snapshot (R-24), member ON CONFLICT no-ops,
        // StagedWrite restart no-op (R-05) and the VISIBLE guard. Retry, never skip.
        return new StepBuilder("emitStep", repo).tasklet(emissionTasklet, tx)
                .exceptionHandler(new CrdbRetryExceptionHandler("PRW")).build();
    }

    @Bean
    public Job prwJob(final JobRepository repo, final Step emitStep, final HeartbeatWriter heartbeatWriter,
                      @Value("${dcre.exchange-root}") final String exchangeRoot) {
        // Shared platform-batch seam listener (COMPLETED-gated, constant BUSINESS_ACCEPTED
        // verdict; local fallback local-prw-<executionId>). heartbeatWriter ticks
        // agt_ops.launch_intent while the job runs.
        return new JobBuilder("prwJob", repo)
                .listener(new OutcomeSeamListener("prw", exchangeRoot, execution -> "BUSINESS_ACCEPTED"))
                .listener(heartbeatWriter)
                .start(emitStep)
                .build();
    }

    @Bean
    @Order(-10)
    public ApplicationRunner staleExecutionSweep(final DataSource dataSource) {
        return args -> StaleExecutionSweeper.abandonStale(dataSource, "PRW_BATCH_", 60);
    }
}
