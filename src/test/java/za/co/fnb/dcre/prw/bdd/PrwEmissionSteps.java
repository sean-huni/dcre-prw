package za.co.fnb.dcre.prw.bdd;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import za.co.fnb.dcre.prw.PrwTestcontainersBase;
import za.co.fnb.dcre.prw.service.EmissionService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Step definitions for the @prw emission scenarios. */
public class PrwEmissionSteps {

    private static final String CLIENT = "FNBRF01";

    @Autowired
    Job prwJob;

    @Autowired
    JobOperator jobOperator;

    @Autowired
    JdbcTemplate jdbc;

    private UUID arrivalId;
    private String msgId;
    private JobExecution execution;
    private ListAppender<ILoggingEvent> warns;
    private Logger emissionLogger;

    @Before
    public void captureWarns() {
        emissionLogger = (Logger) LoggerFactory.getLogger(EmissionService.class);
        warns = new ListAppender<>();
        warns.start();
        emissionLogger.addAppender(warns);
    }

    @After
    public void releaseWarns() {
        if (emissionLogger != null && warns != null) {
            emissionLogger.detachAppender(warns);
        }
    }

    private Path emittedFile() {
        return Path.of("build/test-exchange/fnbrf01/fint-req/out", CLIENT + "_" + msgId + "_PAIN008.xml");
    }

    private void seed(final int total, final int verdicts, final String outcome) {
        arrivalId = UUID.randomUUID();
        msgId = "DCRERFBDD" + arrivalId.toString().substring(0, 6);
        PrwTestcontainersBase.ensureSpineTables(jdbc);
        jdbc.update("UPSERT INTO tx_header (arrival_id, msg_id, initg_pty) VALUES (?,?,?)",
                arrivalId, msgId, CLIENT);
        jdbc.update("INSERT INTO tx_entry (arrival_id, sequence, e2e, amount)"
                + " SELECT ?, i, 'E2E" + msgId + "' || i::STRING, 10.00 FROM generate_series(1, ?) AS g(i)"
                + " ON CONFLICT (arrival_id, sequence) DO NOTHING", arrivalId, total);
        jdbc.update("INSERT INTO validation_log (arrival_id, sequence, outcome)"
                + " SELECT ?, i, ? FROM generate_series(1, ?) AS g(i)"
                + " ON CONFLICT (arrival_id, sequence) DO NOTHING", arrivalId, outcome, total);
        if (verdicts > 0) {
            jdbc.update("INSERT INTO pai_verdict (arrival_id, sequence, action)"
                    + " SELECT ?, i, 'CREATED' FROM generate_series(1, ?) AS g(i)"
                    + " ON CONFLICT (arrival_id, sequence) DO NOTHING", arrivalId, verdicts);
        }
    }

    /**
     * The launch AGT performs: ONE identifying parameter, arrival.id, and nothing else.
     *
     * <p>Deliberately no synthetic uniqueness token. An earlier draft added a random
     * identifying "attempt" parameter so that every scenario could relaunch freely; that
     * made each run a NEW JobInstance and so proved a relaunch path production cannot
     * reach, since AGT supplies no such parameter. One arrival is one JobInstance, and a
     * scenario that relaunches must first put the instance into the restartable state a
     * killed pod leaves (see killTheStage).
     */
    private void run() throws Exception {
        execution = jobOperator.start(prwJob, new JobParametersBuilder()
                .addString("arrival.id", arrivalId.toString(), true).toJobParameters());
    }

    /** Marks the last execution FAILED: the state a SIGKILLed PRW pod leaves behind. */
    private void killTheStage() {
        jdbc.update("UPDATE PRW_BATCH_STEP_EXECUTION SET STATUS = 'FAILED', EXIT_CODE = 'FAILED'"
                + " WHERE JOB_EXECUTION_ID = ?", execution.getId());
        jdbc.update("UPDATE PRW_BATCH_JOB_EXECUTION SET STATUS = 'FAILED', EXIT_CODE = 'FAILED'"
                + " WHERE JOB_EXECUTION_ID = ?", execution.getId());
    }

    @Given("a payments arrival with {int} validated and PAI-verdicted transactions")
    public void aFullyEligibleArrival(final int total) {
        seed(total, total, "PASS");
    }

    @Given("a payments arrival with {int} validated transactions and only {int} PAI verdicts")
    public void aPartiallyVerdictedArrival(final int total, final int verdicts) {
        seed(total, verdicts, "PASS");
    }

    @Given("a payments arrival with {int} transactions that all failed validation")
    public void aWhollyFailedArrival(final int total) {
        seed(total, total, "FAIL");
    }

    @Given("the PRW job has run for that arrival")
    public void thePrwJobHasRun() throws Exception {
        run();
        assertEquals(BatchStatus.COMPLETED, execution.getStatus());
    }

    @When("the PRW job runs for that arrival")
    public void thePrwJobRuns() throws Exception {
        run();
    }

    @When("the PRW job runs again for that arrival")
    public void thePrwJobRunsAgain() throws Exception {
        run();
    }

    @And("a further transaction is validated on the live spine")
    public void aFurtherTransactionIsValidated() {
        final int next = jdbc.queryForObject(
                "SELECT COALESCE(max(sequence), 0) + 1 FROM tx_entry WHERE arrival_id = ?",
                Integer.class, arrivalId);
        jdbc.update("INSERT INTO tx_entry (arrival_id, sequence, e2e, amount)"
                + " VALUES (?,?,'E2EDRIFT',10.00) ON CONFLICT (arrival_id, sequence) DO NOTHING",
                arrivalId, next);
        jdbc.update("INSERT INTO validation_log (arrival_id, sequence, outcome) VALUES (?,?,'PASS')"
                + " ON CONFLICT (arrival_id, sequence) DO NOTHING", arrivalId, next);
        jdbc.update("INSERT INTO pai_verdict (arrival_id, sequence, action) VALUES (?,?,'CREATED')"
                + " ON CONFLICT (arrival_id, sequence) DO NOTHING", arrivalId, next);
    }

    @And("the emission is rolled back to state MATERIALIZED")
    public void rollBackToMaterialized() {
        jdbc.update("UPDATE prw_emission SET state = 'MATERIALIZED' WHERE arrival_id = ?", arrivalId);
    }

    @And("the PRW pod is killed")
    public void thePodIsKilled() {
        killTheStage();
    }

    @And("the emitted pain.008 file is deleted")
    public void deleteEmittedFile() throws Exception {
        Files.deleteIfExists(emittedFile());
    }

    @Then("the PRW job completes")
    public void thePrwJobCompletes() {
        assertEquals(BatchStatus.COMPLETED, execution.getStatus());
    }

    @Then("the pain.008 for the arrival contains {int} transactions")
    public void thePainContains(final int count) throws Exception {
        final List<String> xml = Files.readAllLines(emittedFile());
        assertTrue(xml.stream().anyMatch(l -> l.contains("<NbOfTxs>" + count + "</NbOfTxs>")),
                "expected <NbOfTxs>" + count + "</NbOfTxs> in " + emittedFile());
    }

    @Then("the pain.008 carries the TT2 local instrument")
    public void thePainCarriesTt2() throws Exception {
        assertTrue(Files.readAllLines(emittedFile()).stream().anyMatch(l -> l.contains("<Cd>TT2</Cd>")));
    }

    @Then("no pain.008 file exists for the arrival")
    public void noPainFileExists() {
        assertTrue(Files.notExists(emittedFile()), "unexpected file " + emittedFile());
    }

    @Then("a file-level exclusion warning is logged for stage PRW with reason ALREADY_VISIBLE")
    public void anAlreadyVisibleWarnIsLogged() {
        assertTrue(warns.list.stream()
                        .filter(e -> e.getLevel() == Level.WARN)
                        .map(ILoggingEvent::getFormattedMessage)
                        .anyMatch(m -> m.equals("excluded stage=PRW arrival=" + arrivalId
                                + " seq=-1 e2e=- reason=ALREADY_VISIBLE batch=1")),
                "expected the file-level ALREADY_VISIBLE WARN");
    }
}
