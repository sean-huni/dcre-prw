package za.co.fnb.dcre.prw;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.CockroachContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

/**
 * Shared Testcontainers base: one CockroachDB container + one Spring context (context
 * caching) across every subclass, with Liquibase on, the batch schema never initialized
 * and files under build/test-exchange.
 */
@SpringBootTest(properties = {"spring.batch.job.enabled=false", "dcre.exchange-root=build/test-exchange",
        "DCRE_EXCHANGE_ROOT=build/test-exchange"})
public abstract class PrwTestcontainersBase {

    static final CockroachContainer CRDB =
            new CockroachContainer(DockerImageName.parse("cockroachdb/cockroach:v26.2.3"));

    static {
        CRDB.start();
    }

    @DynamicPropertySource
    static void props(final DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", CRDB::getJdbcUrl);
        registry.add("spring.datasource.username", CRDB::getUsername);
        registry.add("spring.datasource.password", CRDB::getPassword);
    }

    @Autowired
    protected JdbcTemplate jdbc;

    /**
     * The peer tables PRW reads, owned in production by the payments stages that precede
     * it: tx_header and tx_entry (PRR), validation_log (PTV), pai_verdict (PAI).
     *
     * <p>THREE tables CRW's equivalent creates are absent here, and their absence is the
     * fork:
     * <ul>
     *   <li>{@code cde_schedule}: payments has no CDE and no collection day;</li>
     *   <li>{@code ais_verdict}: AIS is PAI now, and the verdict table with it;</li>
     *   <li>the {@code flow} column on tx_header: the DATABASE is the discriminator.</li>
     * </ul>
     *
     * <p>These are deliberately NOT created even as empty stubs. A fixture that provides a
     * column the shipped schema does not have is a monoculture in exactly the dimension
     * this fork changes: a stray {@code process_date} predicate would keep passing against
     * it. {@code NoClockPathTest} closes the same gap from the source side.
     *
     * <p>{@code client_token} IS created, at PRR's shape and nullability: it is the outbound
     * client authority PRW reads (A-43, ruled 2026-08-08). Its previous absence was the
     * opposite failure to the one above, and a worse one: the fixture omitted a column the
     * shipped schema DOES have, so no test could express the behaviour in either direction.
     */
    public static void ensureSpineTables(final JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE IF NOT EXISTS tx_header (id UUID DEFAULT gen_random_uuid() PRIMARY KEY,"
                + " arrival_id UUID UNIQUE, msg_id VARCHAR(35), initg_pty VARCHAR(35),"
                + " client_token VARCHAR(16),"
                + " created_at TIMESTAMPTZ NOT NULL DEFAULT now())");
        jdbc.execute("CREATE TABLE IF NOT EXISTS tx_entry (id UUID DEFAULT gen_random_uuid() PRIMARY KEY,"
                + " arrival_id UUID, sequence INT, e2e VARCHAR(35), amount DECIMAL(18,2),"
                + " UNIQUE (arrival_id, sequence))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS validation_log (id UUID DEFAULT gen_random_uuid() PRIMARY KEY,"
                + " arrival_id UUID, sequence INT, outcome VARCHAR(32), UNIQUE (arrival_id, sequence))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS pai_verdict (id UUID DEFAULT gen_random_uuid() PRIMARY KEY,"
                + " arrival_id UUID, sequence INT, action VARCHAR(16), UNIQUE (arrival_id, sequence))");
    }

    /** Instance convenience over the shared static DDL source. */
    protected void ensureSpineTables() {
        ensureSpineTables(jdbc);
    }

    /**
     * Manufactures the state a SIGKILLed pod leaves behind, so a relaunch is a genuine
     * Spring Batch RESTART of the same JobInstance.
     *
     * <p>This matters because PRW's only identifying JobParameter is {@code arrival.id}
     * (the shape AGT launches, and MRW's). One arrival is therefore one JobInstance
     * forever, and Spring Batch refuses to re-run an instance whose last execution
     * COMPLETED. That refusal is a feature, and it is asserted directly in
     * {@code PrwJobTest}; but it also means a test cannot simulate AGT's recovery path by
     * simply launching twice, and it must not fabricate a random extra identifying
     * parameter to get around it, because AGT supplies no such thing. Faking uniqueness
     * would prove a relaunch works in a way production can never reach.
     *
     * <p>What AGT actually recovers from is a killed pod: the execution is left STARTED
     * and {@code StaleExecutionSweeper} abandons it, or it is already FAILED. Either way
     * the instance is restartable. Marking the execution FAILED is that state.
     */
    protected void simulatePodKill(final Long jobExecutionId) {
        jdbc.update("UPDATE PRW_BATCH_STEP_EXECUTION SET STATUS = 'FAILED', EXIT_CODE = 'FAILED'"
                + " WHERE JOB_EXECUTION_ID = ?", jobExecutionId);
        jdbc.update("UPDATE PRW_BATCH_JOB_EXECUTION SET STATUS = 'FAILED', EXIT_CODE = 'FAILED'"
                + " WHERE JOB_EXECUTION_ID = ?", jobExecutionId);
    }

    /**
     * A fully eligible payments arrival: every sequence 1..total PASS-validated and
     * PAI-verdicted. Set-based, so the split-scale fixtures (12k+ rows) stay 4 statements.
     *
     * <p>No run date and no schedule rows anywhere: a payment is emitted immediately, in
     * full, so "due" is not a function of any date.
     */
    protected void seedEligibleArrival(final UUID arrival, final String client, final String msgId,
                                       final int total) {
        seedArrival(arrival, client, msgId, total, total, "PASS");
    }

    /**
     * Explicit control over the two gates, so a fixture can express PARTIAL PAI coverage
     * and a non-PASS validation outcome. Both are dimensions the eligibility SQL branches
     * on, and a fixture that always passes both cannot exercise either.
     */
    protected void seedArrival(final UUID arrival, final String client, final String msgId,
                               final int total, final int verdicts, final String outcome) {
        seedArrival(arrival, client, client, msgId, total, verdicts, outcome);
    }

    /**
     * The same fixture with the two client homes stated SEPARATELY, so a test can express an
     * arrival whose R-31 filename token and copybook {@code destination_id} differ, or one
     * that carries no filename token at all (A-43).
     *
     * <p>Every other fixture here seeds one value into both columns and is therefore
     * structurally incapable of seeing which column PRW reads: it would pass whether the
     * authority were applied correctly, incorrectly or not at all.
     * {@code ClientAuthorityIT} is the only caller that can distinguish them.
     *
     * @param clientToken the R-31 filename token; {@code null} models a job launched outside
     *                    AGT, where the copybook header must carry the emission
     * @param initgPty    the copybook {@code destination_id}, never null in production
     */
    protected void seedArrival(final UUID arrival, final String clientToken, final String initgPty,
                               final String msgId, final int total, final int verdicts,
                               final String outcome) {
        ensureSpineTables();
        jdbc.update("UPSERT INTO tx_header (arrival_id, msg_id, initg_pty, client_token) VALUES (?,?,?,?)",
                arrival, msgId, initgPty, clientToken);
        jdbc.update("INSERT INTO tx_entry (arrival_id, sequence, e2e, amount)"
                + " SELECT ?, i, 'E2E' || i::STRING, 10.00 FROM generate_series(1, ?) AS g(i)"
                + " ON CONFLICT (arrival_id, sequence) DO NOTHING", arrival, total);
        jdbc.update("INSERT INTO validation_log (arrival_id, sequence, outcome)"
                + " SELECT ?, i, ? FROM generate_series(1, ?) AS g(i)"
                + " ON CONFLICT (arrival_id, sequence) DO NOTHING", arrival, outcome, total);
        if (verdicts > 0) {
            jdbc.update("INSERT INTO pai_verdict (arrival_id, sequence, action)"
                    + " SELECT ?, i, 'CREATED' FROM generate_series(1, ?) AS g(i)"
                    + " ON CONFLICT (arrival_id, sequence) DO NOTHING", arrival, verdicts);
        }
    }
}
