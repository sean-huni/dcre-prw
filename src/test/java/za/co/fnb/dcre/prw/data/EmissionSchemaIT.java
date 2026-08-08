package za.co.fnb.dcre.prw.data;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import za.co.fnb.dcre.prw.PrwTestcontainersBase;
import za.co.fnb.dcre.prw.data.model.PrwEmissionEntity;
import za.co.fnb.dcre.prw.data.model.PrwEmissionGroupEntity;
import za.co.fnb.dcre.prw.data.repo.PrwEmissionGroupRepo;
import za.co.fnb.dcre.prw.data.repo.PrwEmissionRepo;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Group/batch grain round trip against the real schema. The emission identity is
 * (arrival_id, batch_ordinal); a same-ordinal replay is a restart no-op, never a violation.
 *
 * <p>CRW's counterpart also carries {@code backfillAssignsDistinctOutboundIdsToLegacy
 * MultiDateArrivals}, which executes the 003-split-backfill DML against pre-split legacy
 * rows. It has no successor because it has no subject: dcre_pay is a new database, PRW's
 * tables are BORN at batch grain, and there is no legacy row to migrate. The changeset it
 * tests does not exist here.
 */
class EmissionSchemaIT extends PrwTestcontainersBase {

    @Autowired
    PrwEmissionGroupRepo groups;

    @Autowired
    PrwEmissionRepo emissions;

    @Test
    void groupAndOrdinalBatchesRoundTrip() {
        final UUID arrival = UUID.randomUUID();
        final String msgId = "DCRERF" + arrival.toString().substring(0, 8);
        final var g = PrwEmissionGroupEntity.planned(arrival, "FNBRF01", msgId,
                5000, 12001L, new BigDecimal("120010.00"), 3, true);
        groups.save(g);

        emissions.claimSnapshot(PrwEmissionEntity.plannedBatch(g.getId(), arrival, 1,
                msgId + "_1", "FNBRF01_" + msgId + "_1_PAIN008.xml"));
        emissions.claimSnapshot(PrwEmissionEntity.plannedBatch(g.getId(), arrival, 2,
                msgId + "_2", "FNBRF01_" + msgId + "_2_PAIN008.xml"));
        // same ordinal again = restart no-op, not a violation
        emissions.claimSnapshot(PrwEmissionEntity.plannedBatch(g.getId(), arrival, 2,
                msgId + "_2", "FNBRF01_" + msgId + "_2_PAIN008.xml"));

        final List<PrwEmissionEntity> batches = emissions.findByArrivalIdOrderByBatchOrdinal(arrival);
        assertThat(batches).hasSize(2);
        assertThat(batches).extracting(PrwEmissionEntity::getBatchOrdinal).containsExactly(1, 2);
        assertThat(groups.findByArrivalId(arrival)).isPresent();
    }

    /**
     * The schema carries no run_date column on either emission table. Asserted against the
     * live catalog rather than against the changelog text, because the changelog is what
     * the entity classes are written to match and the catalog is what the queries actually
     * hit. {@code NoClockPathTest} covers the source side; this covers the database side.
     */
    @Test
    void neitherEmissionTableHasARunDateColumn() {
        for (final String table : List.of("prw_emission", "prw_emission_group")) {
            final List<String> columns = jdbc.queryForList(
                    "SELECT column_name FROM information_schema.columns"
                            + " WHERE table_schema = current_schema() AND table_name = ?",
                    String.class, table);
            assertThat(columns)
                    .as("%s must have no run_date: payments emits once, so a date in the"
                            + " business key varies for non-business reasons", table)
                    .doesNotContain("run_date");
            // Control: the catalog query CAN see this table's columns, so the absence
            // above is a missing column and not an empty read of a misspelt table name.
            assertThat(columns).as("control: real columns are read for %s", table)
                    .contains("arrival_id", "created_at");
        }
    }

    /**
     * The claim key is exactly (arrival_id, batch_ordinal), and the cross-arrival guard is
     * the unique outbound_msg_id. Both are read off the catalog: a constraint that exists
     * in the changelog but failed to apply would leave the ON CONFLICT target missing, and
     * a restarted stage would DUPLICATE instead of no-opping.
     */
    @Test
    void theIdempotencyConstraintsExistInTheDatabase() {
        final List<String> indexes = jdbc.queryForList(
                "SELECT index_name FROM information_schema.statistics"
                        + " WHERE table_schema = current_schema() AND table_name = 'prw_emission'",
                String.class);
        assertThat(indexes).contains("uq_prw_emission_arrival_ordinal", "uq_prw_emission_outbound_msg");

        final List<String> groupIndexes = jdbc.queryForList(
                "SELECT index_name FROM information_schema.statistics"
                        + " WHERE table_schema = current_schema() AND table_name = 'prw_emission_group'",
                String.class);
        assertThat(groupIndexes).contains("uq_prw_group_arrival", "uq_prw_group_client_msg");
    }
}
