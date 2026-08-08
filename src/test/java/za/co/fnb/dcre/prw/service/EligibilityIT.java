package za.co.fnb.dcre.prw.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import za.co.fnb.dcre.prw.PrwTestcontainersBase;
import za.co.fnb.dcre.prw.config.PrwSplitProperties;
import za.co.fnb.dcre.prw.data.model.DueArrivalRow;
import za.co.fnb.dcre.prw.data.repo.PrwEmissionGroupRepo;
import za.co.fnb.dcre.prw.data.repo.PrwEmissionMemberRepo;
import za.co.fnb.dcre.prw.data.repo.PrwEmissionRepo;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What payments eligibility actually MEANS, now that it is PRW's own contract rather than
 * an arm of CRW's union.
 *
 * <p>This is the direct successor of CRW's {@code PayFlowEligibilityIT}, restated without
 * the clock. Two of that class's gates are gone because they are warehousing rather than
 * eligibility, and each removal is asserted here in the form it now takes:
 *
 * <ul>
 *   <li>CRW's {@code payArrivalIsNotDueBeforeItsIngestDay} tested
 *       {@code :runDate >= created_at}. A payment is immediate, so there is no day to be
 *       "before". Its replacement is
 *       {@code aFullyValidatedArrivalIsEligibleImmediately}, which asserts eligibility
 *       with no date input at all;</li>
 *   <li>CRW's {@code payParentEmittedOnDayOneIsNotDueOnDayTwo} and
 *       {@code crashShapedDayOnePlanResumesOnDayOneButNotOnDayTwo} tested the
 *       strictly-earlier-run-date guard, which exists so a warehoused parent is not
 *       re-emitted on a later sweep. There is no later sweep. Re-emission is prevented by
 *       the CLAIM instead, which
 *       {@code replayNeverDuplicatesTheEmission} asserts directly;</li>
 *   <li>CRW's {@code dcArrivalWithoutCdeScheduleStaysIneligible} tested that the DC arm
 *       stayed shut. There is no DC arm.</li>
 * </ul>
 *
 * <p>The two gates that ARE eligibility survive unchanged and are tested unchanged: PAI
 * verdict coverage, and the existence of at least one PASS row.
 */
class EligibilityIT extends PrwTestcontainersBase {

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

    @BeforeEach
    void setUp() {
        txTemplate = new TransactionTemplate(txManager);
        planner = new SplitPlanner(groups, emissions, members, new PrwSplitProperties(5000, Map.of()));
        arrivalId = UUID.randomUUID();
    }

    private int memberCount(final UUID emissionId) {
        return jdbc.queryForObject("SELECT count(*) FROM prw_emission_member WHERE emission_id = ?",
                Integer.class, emissionId);
    }

    @Test
    void aFullyValidatedArrivalIsEligibleImmediately() {
        final String msgId = "DCRERF2026080800000701";
        seedEligibleArrival(arrivalId, "FNBRF71", msgId, 5);

        assertThat(emissions.findDueArrival(arrivalId))
                .as("no run date, no schedule row, no waiting: PAI reported, so PRW emits")
                .contains(new DueArrivalRow(arrivalId, "FNBRF71", msgId));

        final var batches = txTemplate.execute(s ->
                planner.planAndClaim(new DueArrivalRow(arrivalId, "FNBRF71", msgId)));
        assertThat(batches).hasSize(1);
        assertThat(batches.get(0).getOutboundMsgId()).isEqualTo(msgId);
        assertThat(batches.get(0).getTxCount()).isEqualTo(5);
        assertThat(memberCount(batches.get(0).getId())).isEqualTo(5);
    }

    @Test
    void replayNeverDuplicatesTheEmission() {
        // The idempotency that CRW gets from (arrival, run_date, ordinal) plus the
        // earlier-run-date guard, PRW gets from (arrival, ordinal) alone, because the
        // dimension removed cannot vary for a payments arrival.
        final String msgId = "DCRERF2026080800000702";
        seedEligibleArrival(arrivalId, "FNBRF72", msgId, 4);
        final DueArrivalRow due = new DueArrivalRow(arrivalId, "FNBRF72", msgId);

        txTemplate.execute(s -> planner.planAndClaim(due));
        final var replay = txTemplate.execute(s -> planner.planAndClaim(due));

        assertThat(replay).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM prw_emission WHERE arrival_id = ?",
                Integer.class, arrivalId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM prw_emission_group WHERE arrival_id = ?",
                Integer.class, arrivalId)).isEqualTo(1);
        assertThat(memberCount(replay.get(0).getId())).isEqualTo(4);
    }

    @Test
    void partialPaiCoverageIsNotEligibleUntilTheLastVerdictLands() {
        // PAI slice-commits verdicts (verdict-slice-size), so the presence of SOME verdict
        // must never trigger emission. This is a COUNT comparison rather than an EXISTS
        // precisely so a mid-run or died PAI stays fail-closed.
        final String msgId = "DCRERF2026080800000707";
        seedArrival(arrivalId, "FNBRF77", msgId, 3, 2, "PASS");

        assertThat(emissions.findDueArrival(arrivalId))
                .as("2 verdicts against 3 PASS rows: PAI has not finished")
                .isEmpty();

        jdbc.update("INSERT INTO pai_verdict (arrival_id, sequence, action) VALUES (?, 3, 'CREATED')"
                + " ON CONFLICT (arrival_id, sequence) DO NOTHING", arrivalId);

        assertThat(emissions.findDueArrival(arrivalId))
                .as("the last verdict landed, so coverage is complete")
                .contains(new DueArrivalRow(arrivalId, "FNBRF77", msgId));
    }

    @Test
    void anArrivalWithoutAnyPaiVerdictIsNotEligible() {
        // A PAI that never ran, or died before its first slice, stays fail-closed.
        final String msgId = "DCRERF2026080800000708";
        seedArrival(arrivalId, "FNBRF78", msgId, 3, 0, "PASS");

        assertThat(emissions.findDueArrival(arrivalId)).isEmpty();
    }

    /**
     * The gate that looks redundant and is not. With zero PASS rows the coverage
     * comparison is {@code 0 >= 0}, which is TRUE, so the count gate alone would admit a
     * wholly-rejected arrival and plan an empty emission against it. This is the single
     * assertion that separates the two gates, and it goes red if the EXISTS clause is
     * dropped as "already covered".
     */
    @Test
    void anArrivalWithZeroPassRowsIsNotEligibleAndPlansEmpty() {
        final String msgId = "DCRERF2026080800000709";
        seedArrival(arrivalId, "FNBRF79", msgId, 3, 3, "FAIL");

        assertThat(emissions.findDueArrival(arrivalId))
                .as("nothing validated is nothing to emit, even with FULL PAI coverage of"
                        + " an empty PASS set")
                .isEmpty();

        final var plan = txTemplate.execute(s ->
                planner.planAndClaim(new DueArrivalRow(arrivalId, "FNBRF79", msgId)));
        assertThat(plan).isEmpty();
    }

    /**
     * The service-level consequence of the gate above, and the reason it must not throw.
     * PRW is TERMINAL on the payments DAG, so a failure here holds the arrival open
     * forever; a wholly-rejected instruction book must complete having emitted nothing.
     */
    @Test
    void anIneligibleArrivalCompletesWithZeroFilesRatherThanFailing() {
        final String msgId = "DCRERF2026080800000710";
        seedArrival(arrivalId, "FNBRF80", msgId, 3, 0, "PASS");

        final EmissionService service = new EmissionService(emissions, members,
                new Pain008Writer(), failClosedLayout(), planner, txManager);

        assertThat(service.emit(arrivalId))
                .as("not eligible is not a failure: PRW is terminal, so throwing here would"
                        + " strand the arrival in DAG_RUNNING")
                .isZero();
    }

    /**
     * A layout configured for NO client at all. ExchangeLayout is fail-closed, so any
     * attempt to resolve a publication directory throws IllegalArgumentException.
     *
     * <p>That is the assertion, not a convenience: if the service ever tried to publish an
     * ineligible arrival, this test fails loudly instead of quietly writing a file into a
     * shared directory. A real layout here would let that defect pass.
     */
    private za.co.fnb.dcre.platform.files.ExchangeLayout failClosedLayout() {
        return new za.co.fnb.dcre.platform.files.ExchangeLayout(
                java.nio.file.Path.of("build/test-exchange"), Map.of());
    }
}
