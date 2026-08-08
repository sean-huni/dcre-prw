package za.co.fnb.dcre.prw.data.repo;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import za.co.fnb.dcre.prw.data.model.DueArrivalRow;
import za.co.fnb.dcre.prw.data.model.PlanTotals;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Plain execution of {@link DueSql}. No arm composition: PRW reads one database whose
 * peer tables are written by the payments stages that precede it in the DAG, so a missing
 * relation is a real fault and is allowed to be loud.
 */
public class PrwDueQueriesImpl implements PrwDueQueries {

    private final NamedParameterJdbcTemplate jdbc;

    public PrwDueQueriesImpl(final NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<DueArrivalRow> findDueArrival(final UUID arrivalId) {
        return jdbc.query(DueSql.DUE_ARRIVAL, params(arrivalId), new DueArrivalRowMapper())
                .stream().findFirst();
    }

    @Override
    public PlanTotals planTotals(final UUID arrivalId) {
        return jdbc.queryForObject(DueSql.PLAN_TOTALS, params(arrivalId), new PlanTotalsRowMapper());
    }

    @Override
    public List<Integer> batchBoundaries(final UUID arrivalId, final int maxSize) {
        return jdbc.query(DueSql.BATCH_BOUNDARIES,
                params(arrivalId).addValue("maxSize", maxSize), new SequenceRowMapper());
    }

    @Override
    public void claimMembers(final UUID emissionId, final UUID arrivalId, final int loSeq, final int hiSeq) {
        jdbc.update(DueSql.CLAIM_MEMBERS, params(arrivalId)
                .addValue("emissionId", emissionId)
                .addValue("loSeq", loSeq)
                .addValue("hiSeq", hiSeq));
    }

    private MapSqlParameterSource params(final UUID arrivalId) {
        return new MapSqlParameterSource("arrivalId", arrivalId);
    }
}
