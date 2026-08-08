package za.co.fnb.dcre.prw.data.repo;

/**
 * PRW's eligibility SQL. ONE query shape, no arms and no composition.
 *
 * <p>CRW's {@code DueSql} is split into per-arm fragments and joined by {@code DueArms}
 * at runtime, because CRW served two families out of one database: a DC arm over
 * {@code cde_schedule} and a pay arm over {@code ais_verdict}. PostgreSQL resolves every
 * relation a statement names, so one absent table killed the arm that could have run,
 * and A-78 composed the statement per arm to survive it.
 *
 * <p>None of that applies here, and keeping it would be the coupling this split exists to
 * remove. PRW reads one database, {@code dcre_pay}, whose tables are created by the
 * payments stages that necessarily precede it in the DAG: PRR writes the spine, PTV the
 * validation log, PAI the verdicts. AGT launches PRW only after PAI has reported, so a
 * missing peer table is not a bootstrap race here, it is a genuine fault that should be
 * loud. There is deliberately no DueArms, no union() and no "nothing due" degradation.
 *
 * <p>WHAT PAYMENTS ELIGIBILITY ACTUALLY MEANS, read off CRW's PAY_DUE_GATES and stated
 * without the clock:
 *
 * <ul>
 *   <li>the PAI verdict set COVERS the PASS set. A count comparison, never a bare
 *       EXISTS: PAI commits verdicts in bounded slices, so a mid-run or died PAI leaves
 *       a partial verdict set, and comparing counts keeps that fail-closed until the
 *       last verdict lands. An EXISTS would emit a partially-verdicted arrival;</li>
 *   <li>at least one PASS row exists. Nothing validated is nothing to emit, and this is
 *       separate from the coverage test on purpose: with zero PASS rows the coverage
 *       comparison is 0 &gt;= 0, which is TRUE, so coverage alone would let a wholly
 *       failed arrival plan an empty emission.</li>
 * </ul>
 *
 * <p>Two gates CRW carries are absent because they are warehousing, not eligibility:
 * {@code :runDate >= CAST(h.created_at AS DATE)} (payments are immediate; there is no run
 * date to compare against) and {@code NOT EXISTS (... e.run_date < :runDate)} (the
 * strictly-earlier-emission guard exists so a day-1 parent is not re-emitted on day 2,
 * which cannot arise without a second run date). Re-emission is prevented here by the
 * claim itself: the group claim is unique on arrival_id and the batch claim on
 * (arrival_id, batch_ordinal), so a replay finds the stored plan and the VISIBLE guard
 * declines to re-publish.
 */
final class DueSql {

    /**
     * The outbound client of a {@code tx_header} row: the R-31 filename token when the arrival
     * carried one, else the mandatory copybook {@code destination_id} (A-43, ruled 2026-08-08).
     *
     * <p>{@code client_token} is the R-31 filename token AGT already matched against the
     * drop-zone directory, so it is the "resolved from trusted route/profile config" identity
     * R-16 demands; {@code initg_pty} is an unresolved header value whose value domain is still
     * open as A-19. {@code initg_pty} is the FALLBACK rather than the source because
     * {@code client_token} is nullable by design (a job launched outside AGT carries no original
     * filename) while {@code prw_emission_group.client} is NOT NULL, so a bare swap would turn a
     * silent mis-selection into an insert failure. Same shape as CRW's, and as
     * {@code mandates/mrw}'s {@code ManRequestHeaderView.client()}, which chose it first.
     *
     * <p>THIS IS THE SINGLE POINT at which the outbound client is resolved, deliberately: the
     * value reaches {@code prw_emission_group.client}, the outbound file NAME and the per-client
     * output DIRECTORY from one row, and resolving it at the column write would leave the
     * directory on the old source with nothing able to object.
     *
     * <p>It also closes the two-homes defect this repo's sibling PRG reported on 2026-08-08:
     * {@code ext_tx_status.client} and {@code prg_watermark.client} carry
     * {@code tx_header.client_token}, so an emission group written from {@code initg_pty} could
     * name a parent whose rows the watermark then cannot select, leaving the parent due forever
     * with no error anywhere.
     *
     * <p>ASSEMBLY TRAP: a Java text block strips trailing whitespace from every line, so joining a
     * block that ends {@code ... AND } to this constant yields {@code ANDCOALESCE(...)} and a
     * statement CockroachDB rejects at runtime with nothing visible at compile time. It cost CRW a
     * red build on 2026-08-08. State every separator OUTSIDE the block, and let
     * {@code DueSqlAssemblyTest} assert the assembled statement.
     */
    static final String CLIENT_EXPR = "COALESCE(h.client_token, h.initg_pty)";

    /**
     * SINGLE SOURCE for payments eligibility. Every query below composes this, so a gate
     * cannot be tightened in the arrival lookup and left loose in the member claim.
     */
    static final String DUE_GATES = """
            (SELECT count(*) FROM pai_verdict pv WHERE pv.arrival_id = h.arrival_id)
                >= (SELECT count(*) FROM validation_log vp WHERE vp.arrival_id = h.arrival_id
                    AND vp.outcome = 'PASS')
              AND EXISTS (SELECT 1 FROM validation_log vp WHERE vp.arrival_id = h.arrival_id
                          AND vp.outcome = 'PASS')""";

    /**
     * The eligible member rows of ONE arrival: its PASS rows joined to the spine.
     *
     * <p>The {@code h.arrival_id = :arrivalId} predicate and the gates are applied to the
     * SAME header row, so a member set can never be produced for an arrival that failed
     * the gates.
     */
    static final String MEMBER_ROWS = """
            FROM tx_header h
                JOIN validation_log v ON v.arrival_id = h.arrival_id AND v.outcome = 'PASS'
                JOIN tx_entry t ON t.arrival_id = h.arrival_id AND t.sequence = v.sequence
                WHERE h.arrival_id = :arrivalId AND
            """ + DUE_GATES;

    /** The launched arrival's identity, or no row at all when it is not (yet) eligible. */
    static final String DUE_ARRIVAL = "SELECT h.arrival_id, " + CLIENT_EXPR + """
             AS client, h.msg_id
            FROM tx_header h
            WHERE h.arrival_id = :arrivalId AND
            """ + DUE_GATES;

    static final String PLAN_TOTALS = "SELECT count(*) AS total_tx,"
            + " COALESCE(sum(m.amount), 0) AS total_amount FROM ("
            + "SELECT t.sequence, t.amount " + MEMBER_ROWS + ") AS m";

    /** Ordinal-th boundary sequences: eligible rows ranked by original sequence, every maxSize-th. */
    static final String BATCH_BOUNDARIES = "SELECT sequence FROM (SELECT m.sequence,"
            + " row_number() OVER (ORDER BY m.sequence) AS rn FROM ("
            + "SELECT t.sequence " + MEMBER_ROWS + ") AS m) AS ranked"
            + " WHERE rn % :maxSize = 0 ORDER BY sequence";

    /**
     * Set-based ordinal member claim (SCRUM-55): the [loSeq, hiSeq] slice is claimed in ONE
     * statement inside the arrival transaction; hiSeq -1 means the open-ended tail. No Java
     * list of 300k rows, ever (the SCRUM-42 memory lesson).
     */
    static final String CLAIM_MEMBERS = """
            INSERT INTO prw_emission_member (id, emission_id, sequence, e2e, amount)
            SELECT gen_random_uuid(), :emissionId, m.sequence, m.e2e, m.amount FROM (
            """ + "SELECT t.sequence, t.e2e, t.amount " + MEMBER_ROWS + """

            ) AS m
            WHERE m.sequence >= :loSeq AND (:hiSeq = -1 OR m.sequence <= :hiSeq)
            ON CONFLICT (emission_id, sequence) DO NOTHING""";

    private DueSql() {
    }
}
