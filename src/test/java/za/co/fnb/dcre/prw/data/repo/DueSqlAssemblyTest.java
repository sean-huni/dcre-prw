package za.co.fnb.dcre.prw.data.repo;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The due statement is ASSEMBLED from a text block and {@link DueSql#CLIENT_EXPR}, and a Java text
 * block strips trailing whitespace from every line. The sibling CRW repo shipped
 * {@code ...:runDate ANDCOALESCE(...)} from exactly this shape on 2026-08-08: rejected by
 * CockroachDB at runtime, invisible to the compiler. PRW takes the guard rather than the lesson.
 */
class DueSqlAssemblyTest {

    @Test
    void theClientProjectionIsAliasedSoTheRowMapperCanNameIt() {
        assertThat(DueSql.DUE_ARRIVAL).contains("COALESCE(h.client_token, h.initg_pty) AS client");
    }

    /**
     * A-43: no statement may read {@code initg_pty} except through the fallback arm. A bare
     * reference would be a second, unaliased authority, which is the two-homes shape this ruling
     * removes.
     */
    @Test
    void noStatementReadsTheHeaderColumnOutsideTheFallback() {
        for (final String sql : new String[] {DueSql.DUE_ARRIVAL, DueSql.MEMBER_ROWS, DueSql.PLAN_TOTALS,
                DueSql.BATCH_BOUNDARIES, DueSql.CLAIM_MEMBERS, DueSql.DUE_GATES}) {
            assertThat(sql.replace(DueSql.CLIENT_EXPR, ""))
                    .as("statement reads initg_pty outside COALESCE: %s", sql)
                    .doesNotContain("initg_pty");
        }
    }
}
