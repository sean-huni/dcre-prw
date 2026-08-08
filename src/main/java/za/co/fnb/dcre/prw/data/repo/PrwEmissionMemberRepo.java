package za.co.fnb.dcre.prw.data.repo;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import za.co.fnb.dcre.prw.data.model.BatchTotals;
import za.co.fnb.dcre.prw.data.model.PrwEmissionMemberEntity;

import java.util.List;
import java.util.UUID;

public interface PrwEmissionMemberRepo extends CrudRepository<PrwEmissionMemberEntity, UUID> {

    /** Claimed-member totals for the freeze; count and sum come from the immutable snapshot, not the live spine. */
    @Query(value = "SELECT count(*) AS c, COALESCE(sum(amount), 0) AS s FROM prw_emission_member"
            + " WHERE emission_id = :id", rowMapperClass = BatchTotalsRowMapper.class)
    BatchTotals batchTotals(@Param("id") UUID id);

    List<PrwEmissionMemberEntity> findByEmissionIdOrderBySequence(UUID emissionId);
}
