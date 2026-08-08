package za.co.fnb.dcre.prw.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import za.co.fnb.dcre.platform.files.ExchangeChannel;
import za.co.fnb.dcre.platform.files.ExchangeLayout;
import za.co.fnb.dcre.platform.files.ExchangeSub;
import za.co.fnb.dcre.prw.PrwTestcontainersBase;
import za.co.fnb.dcre.prw.data.model.PrwEmissionEntity;
import za.co.fnb.dcre.prw.data.model.PrwEmissionMemberEntity;
import za.co.fnb.dcre.prw.data.repo.PrwEmissionMemberRepo;
import za.co.fnb.dcre.prw.data.repo.PrwEmissionRepo;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ordinal batch emission and the crash matrix. Publication is strictly ordinal within an
 * arrival, restart keys are per batch (arrival, ordinal) plus the file-existence no-op
 * (R-05), and a VISIBLE batch is never re-sent.
 *
 * <p>Every scenario here is EMISSION behaviour and is kept verbatim in substance from CRW,
 * because none of it depends on warehousing: the two-phase durability ordering, the
 * plan-commit/publication seam, the quadrant-4 kill after a file lands, and the frozen-plan
 * reconciliation are properties of writing a pain.008 safely, not of collection days.
 *
 * <p>The ONE scenario dropped from CRW's class is
 * {@code futuredRowsMaturingOnALaterRunDateEmitANewOutboundArtifact}, which seeds an
 * arrival across two process dates and asserts the later one emits a NEW artifact. It has
 * no successor because it has no fixture: a payments arrival cannot have a futured
 * remainder, and the two-date seed helper it needs does not exist in this base class.
 */
class EmissionSplitIT extends PrwTestcontainersBase {

    @Autowired
    EmissionService service;

    @Autowired
    PrwEmissionRepo emissions;

    @Autowired
    PrwEmissionMemberRepo members;

    @Autowired
    SplitPlanner planner;

    @Autowired
    ExchangeLayout layout;

    @Autowired
    Pain008Writer painWriter;

    @Autowired
    PlatformTransactionManager txManager;

    private Path out() {
        return layout.resolve("FNBRF01", ExchangeChannel.FINT_REQ, ExchangeSub.OUT);
    }

    private long memberTotalAcrossBatches(final UUID arrivalId) {
        return jdbc.queryForObject("SELECT count(*) FROM prw_emission_member m"
                + " JOIN prw_emission e ON e.id = m.emission_id WHERE e.arrival_id = ?",
                Long.class, arrivalId);
    }

    private long distinctMemberSequences(final UUID arrivalId) {
        return jdbc.queryForObject("SELECT count(DISTINCT m.sequence) FROM prw_emission_member m"
                + " JOIN prw_emission e ON e.id = m.emission_id WHERE e.arrival_id = ?",
                Long.class, arrivalId);
    }

    /** Per-run MsgId: build/test-exchange survives between gradle runs. */
    private static String msgId(final String tag, final UUID arrivalId) {
        return "DCRERF" + tag + arrivalId.toString().substring(0, 8);
    }

    @Test
    void splitArrivalEmitsOrdinalFilesInOrder() {
        UUID arrivalId = UUID.randomUUID();
        String msgId = msgId("ORD", arrivalId);
        seedEligibleArrival(arrivalId, "FNBRF01", msgId, 12001);

        assertThat(service.emit(arrivalId)).isEqualTo(3);

        Path out = out();
        assertThat(out.resolve("FNBRF01_" + msgId + "_1_PAIN008.xml")).exists();
        assertThat(out.resolve("FNBRF01_" + msgId + "_3_PAIN008.xml")).exists();
        var batches = emissions.findByArrivalIdOrderByBatchOrdinal(arrivalId);
        assertThat(batches).extracting(PrwEmissionEntity::getState).containsOnly("VISIBLE");
        // ordinal publication: visible_at non-decreasing by ordinal (_2 never before _1)
        assertThat(batches.get(0).getVisibleAt()).isBeforeOrEqualTo(batches.get(1).getVisibleAt());
        assertThat(batches.get(1).getVisibleAt()).isBeforeOrEqualTo(batches.get(2).getVisibleAt());
    }

    @Test
    void killBetweenBatch1VisibleAndBatch2ResumesExactlyTail() throws Exception {
        UUID arrivalId = UUID.randomUUID();
        String msgId = msgId("TAIL", arrivalId);
        seedEligibleArrival(arrivalId, "FNBRF01", msgId, 10001);
        FlakyPain008Writer flaky = new FlakyPain008Writer();
        EmissionService flakyService =
                new EmissionService(emissions, members, flaky, layout, planner, txManager);
        flaky.failAfterFirstBuild();

        assertThatThrownBy(() -> flakyService.emit(arrivalId)).isInstanceOf(IllegalStateException.class);

        Path b1 = out().resolve("FNBRF01_" + msgId + "_1_PAIN008.xml");
        assertThat(b1).exists();
        byte[] before = Files.readAllBytes(b1);

        flaky.heal();
        flakyService.emit(arrivalId); // resume

        assertThat(Files.readAllBytes(b1)).isEqualTo(before);             // batch 1 untouched
        assertThat(out().resolve("FNBRF01_" + msgId + "_2_PAIN008.xml")).exists();
        assertThat(memberTotalAcrossBatches(arrivalId)).isEqualTo(10001L); // no dup members
    }

    /**
     * Durable-effect ordering: the plan transaction commits plan + members + frozen totals
     * (MATERIALIZED) BEFORE any file is published. A kill between that commit and
     * publication leaves the whole frozen plan durable with ZERO files; the resume
     * publishes exactly the missing batches from the SAME committed rows, with identical
     * batch ids, names and member set.
     */
    @Test
    void killBetweenPlanCommitAndPublicationResumesExactlyTheMissingBatches() throws Exception {
        UUID arrivalId = UUID.randomUUID();
        String msgId = msgId("SEAM", arrivalId);
        seedEligibleArrival(arrivalId, "FNBRF01", msgId, 12001);
        FlakyPain008Writer flaky = new FlakyPain008Writer();
        EmissionService flakyService =
                new EmissionService(emissions, members, flaky, layout, planner, txManager);
        flaky.failEveryBuild(); // crash at the seam: plan committed, no batch published

        assertThatThrownBy(() -> flakyService.emit(arrivalId)).isInstanceOf(IllegalStateException.class);

        var planned = emissions.findByArrivalIdOrderByBatchOrdinal(arrivalId);
        assertThat(planned).hasSize(3);
        assertThat(planned).extracting(PrwEmissionEntity::getState).containsOnly("MATERIALIZED");
        assertThat(memberTotalAcrossBatches(arrivalId)).isEqualTo(12001L);
        for (final PrwEmissionEntity batch : planned) {
            assertThat(out().resolve(batch.getFileName())).doesNotExist();
        }
        List<UUID> plannedIds = planned.stream().map(PrwEmissionEntity::getId).toList();

        flaky.heal();
        assertThat(flakyService.emit(arrivalId)).isEqualTo(3); // exactly the 3 missing batches

        var resumed = emissions.findByArrivalIdOrderByBatchOrdinal(arrivalId);
        assertThat(resumed).extracting(PrwEmissionEntity::getId).containsExactlyElementsOf(plannedIds);
        assertThat(resumed).extracting(PrwEmissionEntity::getState).containsOnly("VISIBLE");
        for (final PrwEmissionEntity batch : resumed) {
            assertThat(out().resolve(batch.getFileName())).exists();
        }
        assertThat(memberTotalAcrossBatches(arrivalId)).isEqualTo(12001L); // no member drift
    }

    /**
     * Crash-matrix quadrant 4: kill AFTER StagedWrite lands a batch file but BEFORE
     * markVisible commits. File writes are not transactional, so this gap leaves the file
     * durable on disk while the publication transaction rolls back with the row still
     * MATERIALIZED. The resume must treat the landed file as a completed write (R-05
     * restart no-op): byte-identical, never rewritten, marked VISIBLE, remaining ordinals
     * published, zero member drift.
     */
    @Test
    void killAfterFileLandedBeforeMarkVisibleResumesWithoutRewritingTheLandedFile() throws Exception {
        UUID arrivalId = UUID.randomUUID();
        String msgId = msgId("Q4", arrivalId);
        seedEligibleArrival(arrivalId, "FNBRF01", msgId, 12001);
        KillAfterFileLandedService killable =
                new KillAfterFileLandedService(emissions, members, painWriter, layout, planner, txManager);
        killable.arm();

        assertThatThrownBy(() -> killable.emit(arrivalId)).isInstanceOf(IllegalStateException.class);

        var planned = emissions.findByArrivalIdOrderByBatchOrdinal(arrivalId);
        assertThat(planned).hasSize(3);
        assertThat(planned).extracting(PrwEmissionEntity::getState).containsOnly("MATERIALIZED");
        Path landed = out().resolve(planned.get(0).getFileName());
        assertThat(landed).exists();
        assertThat(out().resolve(planned.get(1).getFileName())).doesNotExist();
        assertThat(out().resolve(planned.get(2).getFileName())).doesNotExist();
        byte[] before = Files.readAllBytes(landed);
        FileTime mtimeBefore = Files.getLastModifiedTime(landed);

        killable.heal();
        assertThat(killable.emit(arrivalId)).isEqualTo(3); // batch 1 resumes as no-op write + markVisible

        assertThat(Files.readAllBytes(landed)).isEqualTo(before);             // byte-identical
        assertThat(Files.getLastModifiedTime(landed)).isEqualTo(mtimeBefore); // not even touched
        var resumed = emissions.findByArrivalIdOrderByBatchOrdinal(arrivalId);
        assertThat(resumed).extracting(PrwEmissionEntity::getState).containsOnly("VISIBLE");
        assertThat(resumed.get(0).getVisibleAt()).isNotNull();
        // exactly the 3 planned artifacts: no duplicates, no .tmp leftovers
        try (Stream<Path> files = Files.list(out())) {
            assertThat(files.map(p -> p.getFileName().toString()).filter(n -> n.contains(msgId)))
                    .containsExactlyInAnyOrder(planned.get(0).getFileName(),
                            planned.get(1).getFileName(), planned.get(2).getFileName());
        }
        assertThat(memberTotalAcrossBatches(arrivalId)).isEqualTo(12001L);    // zero member drift
        assertThat(distinctMemberSequences(arrivalId)).isEqualTo(12001L);     // no cross-batch duplication
    }

    /**
     * R-24 frozen-plan reconciliation. A file is built from the immutable member snapshot
     * and checked against the batch's OWN frozen tx_count first, so a snapshot that no
     * longer matches its plan fails LOUDLY instead of shipping a short pain.008.
     */
    @Test
    void aMemberSnapshotThatNoLongerMatchesItsFrozenCountRefusesToPublish() {
        UUID arrivalId = UUID.randomUUID();
        String msgId = msgId("FROZEN", arrivalId);
        seedEligibleArrival(arrivalId, "FNBRF01", msgId, 6);

        assertThat(service.emit(arrivalId)).isEqualTo(1);
        var batch = emissions.findByArrivalIdOrderByBatchOrdinal(arrivalId).get(0);

        // Manufacture the drift: roll the batch back to MATERIALIZED, delete the file and
        // remove one frozen member. The count no longer matches, so publication must throw.
        jdbc.update("UPDATE prw_emission SET state = 'MATERIALIZED' WHERE id = ?", batch.getId());
        jdbc.update("DELETE FROM prw_emission_member WHERE emission_id = ? AND sequence = 6",
                batch.getId());
        try {
            Files.deleteIfExists(out().resolve(batch.getFileName()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        assertThatThrownBy(() -> service.emit(arrivalId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("frozen-plan mismatch")
                .hasMessageContaining("members=5")
                .hasMessageContaining("expected=6");
    }

    @Test
    void anAlreadyVisibleBatchIsANoOpWarnRatherThanAReEmission() {
        UUID arrivalId = UUID.randomUUID();
        String msgId = msgId("VIS", arrivalId);
        seedEligibleArrival(arrivalId, "FNBRF01", msgId, 5);
        assertThat(service.emit(arrivalId)).isEqualTo(1);

        Logger emissionLogger = (Logger) LoggerFactory.getLogger(EmissionService.class);
        ListAppender<ILoggingEvent> warns = new ListAppender<>();
        warns.start();
        emissionLogger.addAppender(warns);
        try {
            assertThat(service.emit(arrivalId))
                    .as("a relaunch of a fully published arrival emits nothing")
                    .isZero();
        } finally {
            emissionLogger.detachAppender(warns);
        }
        assertThat(warns.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage))
                .contains("excluded stage=PRW arrival=" + arrivalId
                        + " seq=-1 e2e=- reason=ALREADY_VISIBLE batch=1");
    }

    /**
     * Quadrant-4 kill switch: overrides the package-private production seam that runs after
     * StagedWrite lands a batch file and before markVisible, so the test crashes exactly in
     * that gap. Everything else is the real EmissionService against real beans. The
     * FlakyPain008Writer harness cannot reach this state: it fails during build, BEFORE the
     * file lands.
     */
    static final class KillAfterFileLandedService extends EmissionService {

        private volatile boolean armed;

        KillAfterFileLandedService(final PrwEmissionRepo emissions, final PrwEmissionMemberRepo members,
                                   final Pain008Writer painWriter, final ExchangeLayout layout,
                                   final SplitPlanner planner, final PlatformTransactionManager txManager) {
            super(emissions, members, painWriter, layout, planner, txManager);
        }

        void arm() {
            armed = true;
        }

        void heal() {
            armed = false;
        }

        @Override
        void afterStagedWrite(final PrwEmissionEntity batch) {
            if (armed) {
                throw new IllegalStateException(
                        "simulated kill: file landed for batch %d, markVisible never committed"
                                .formatted(batch.getBatchOrdinal()));
            }
        }
    }

    /** Delegates to the real writer; while failing, every build past the threshold throws (0 = all). */
    static final class FlakyPain008Writer extends Pain008Writer {

        private final AtomicInteger builds = new AtomicInteger();
        private volatile int failAfterBuilds = Integer.MAX_VALUE;

        /** Crash between batch 1 VISIBLE and batch 2: file 1 lands, the second build throws. */
        void failAfterFirstBuild() {
            failAfterBuilds = 1;
            builds.set(0);
        }

        /** Crash at the plan-commit/publication seam: no batch of the arrival ever publishes. */
        void failEveryBuild() {
            failAfterBuilds = 0;
            builds.set(0);
        }

        void heal() {
            failAfterBuilds = Integer.MAX_VALUE;
        }

        @Override
        public List<String> build(final String outboundMsgId, final List<PrwEmissionMemberEntity> snapshot,
                                  final BigDecimal controlSum) {
            if (builds.incrementAndGet() > failAfterBuilds) {
                throw new IllegalStateException("simulated crash during publication (build %d past threshold %d)"
                        .formatted(builds.get(), failAfterBuilds));
            }
            return super.build(outboundMsgId, snapshot, controlSum);
        }
    }
}
