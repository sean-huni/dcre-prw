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
    static final String DUE_ARRIVAL = """
            SELECT h.arrival_id, h.initg_pty, h.msg_id
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
