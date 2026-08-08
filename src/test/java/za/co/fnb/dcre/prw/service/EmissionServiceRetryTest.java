package za.co.fnb.dcre.prw.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.infrastructure.support.transaction.ResourcelessTransactionManager;
import org.springframework.dao.CannotAcquireLockException;
import za.co.fnb.dcre.platform.files.ExchangeChannel;
import za.co.fnb.dcre.platform.files.ExchangeLayout;
import za.co.fnb.dcre.platform.files.ExchangeSub;
import za.co.fnb.dcre.prw.data.model.DueArrivalRow;
import za.co.fnb.dcre.prw.data.model.PrwEmissionEntity;
import za.co.fnb.dcre.prw.data.model.PrwEmissionMemberEntity;
import za.co.fnb.dcre.prw.data.repo.PrwEmissionMemberRepo;
import za.co.fnb.dcre.prw.data.repo.PrwEmissionRepo;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CRDB 40001 retry discipline at batch grain. A transient abort is retried in a FRESH
 * transaction (an aborted CRDB transaction rejects every further statement with 25P02, so
 * reusing it cannot work), and a persistently failing arrival fails the stage.
 *
 * <p>CRW's counterpart also asserts that a failed parent is SKIPPED so sibling parents in
 * the same run still emit, and that the window then reports failure at the end. That
 * scenario has no successor here and its absence is a consequence of the fork rather than
 * an omission: CRW's job is a sweep over many parents, so isolating one failure keeps the
 * rest of the run productive. PRW's job is ONE arrival. There is no sibling to protect, so
 * the honest behaviour is to fail, let AGT record the stage outcome, and relaunch.
 * {@code aPersistentlyFailingArrivalFailsTheStage} is the assertion that this is what
 * happens rather than a silent zero.
 */
class EmissionServiceRetryTest {

    private static final UUID ARRIVAL = UUID.fromString("11111111-1111-4111-8111-111111111111");

    private static final CannotAcquireLockException ABORT = new CannotAcquireLockException(
            "PreparedStatementCallback; ERROR: restart transaction: TransactionRetryWithProtoRefreshError:"
                    + " WriteTooOldError");

    @TempDir
    Path root;

    private PrwEmissionRepo emissions;
    private PrwEmissionMemberRepo members;
    private SplitPlanner planner;
    private EmissionService service;

    @BeforeEach
    void setUp() {
        emissions = mock(PrwEmissionRepo.class);
        members = mock(PrwEmissionMemberRepo.class);
        planner = mock(SplitPlanner.class);
        final ExchangeLayout layout = new ExchangeLayout(root, Map.of("FNBRF01",
                Map.of(ExchangeChannel.FINT_REQ, Map.of(ExchangeSub.OUT, "fnbrf01/fint-req/out"))));
        service = new EmissionService(emissions, members, new Pain008Writer(), layout, planner,
                new ResourcelessTransactionManager());
        when(emissions.findDueArrival(ARRIVAL))
                .thenReturn(Optional.of(new DueArrivalRow(ARRIVAL, "FNBRF01", "MSGA")));
    }

    @Test
    void transientAbortRetriesInAFreshTransactionThenEmits() {
        final PrwEmissionEntity batch = stubBatch("MSGA", "FNBRF01_MSGA_PAIN008.xml");
        when(planner.planAndClaim(any())).thenThrow(ABORT).thenThrow(ABORT).thenReturn(List.of(batch));
        stubMembers(batch);

        final int emitted = service.emit(ARRIVAL);

        assertEquals(1, emitted, "two transient aborts must retry then succeed");
        verify(planner, times(3)).planAndClaim(any());
        assertTrue(Files.exists(root.resolve("fnbrf01/fint-req/out/FNBRF01_MSGA_PAIN008.xml")),
                "the retried arrival's pain.008 lands in the per-client leaf");
    }

    /**
     * The retry is BOUNDED. An abort that never clears must surface, not spin: PRW runs as
     * a Kubernetes Job with a deadline, and a silent infinite retry would burn it and look
     * like a hang rather than a failure.
     */
    @Test
    void aPersistentlyFailingArrivalFailsTheStage() {
        when(planner.planAndClaim(any())).thenThrow(ABORT);

        assertThrows(CannotAcquireLockException.class, () -> service.emit(ARRIVAL),
                "PRW is one arrival: a persistent abort fails the stage so AGT records it"
                        + " and relaunches, rather than reporting a clean zero-file run");
        verify(planner, times(CrdbRetryAttempts.MAX)).planAndClaim(any());
    }

    /** Mirrors CrdbRetry.MAX_ATTEMPTS, which is package-private to the service package. */
    private static final class CrdbRetryAttempts {
        static final int MAX = 5;
    }

    /** Committed-plan batch stub: MATERIALIZED with frozen totals matching the one stubbed member. */
    private PrwEmissionEntity stubBatch(final String outboundMsgId, final String fileName) {
        final PrwEmissionEntity batch = mock(PrwEmissionEntity.class);
        when(batch.getId()).thenReturn(UUID.randomUUID());
        when(batch.getState()).thenReturn("MATERIALIZED");
        when(batch.getBatchOrdinal()).thenReturn(1);
        when(batch.getOutboundMsgId()).thenReturn(outboundMsgId);
        when(batch.getFileName()).thenReturn(fileName);
        when(batch.getTxCount()).thenReturn(1L);
        when(batch.getControlSum()).thenReturn(new BigDecimal("10.00"));
        return batch;
    }

    private void stubMembers(final PrwEmissionEntity batch) {
        // read the mocked id into a local FIRST: a mock call inside thenReturn's argument
        // list leaves the stubbing unfinished (Mockito hint 3)
        final UUID id = batch.getId();
        when(members.findByEmissionIdOrderBySequence(id)).thenReturn(List.of(
                PrwEmissionMemberEntity.of(id, 1, "E2E1", new BigDecimal("10.00"))));
    }
}
