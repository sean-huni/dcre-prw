package za.co.fnb.dcre.prw.data.repo;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import za.co.fnb.dcre.prw.data.model.PrwEmissionGroupEntity;

import java.util.Optional;
import java.util.UUID;

public interface PrwEmissionGroupRepo extends CrudRepository<PrwEmissionGroupEntity, UUID> {

    Optional<PrwEmissionGroupEntity> findByArrivalId(UUID arrivalId);

    /** Plan claim (R-24 shape): first writer wins; a replay finds the stored plan untouched. */
    @Modifying
    @Query("""
            INSERT INTO prw_emission_group (id, arrival_id, client, source_msg_id, applied_max,
                                            total_tx, total_amount, expected_batch_count, split)
            VALUES (:#{#g.id}, :#{#g.arrivalId}, :#{#g.client}, :#{#g.sourceMsgId},
                    :#{#g.appliedMax}, :#{#g.totalTx}, :#{#g.totalAmount},
                    :#{#g.expectedBatchCount}, :#{#g.split})
            ON CONFLICT (arrival_id) DO NOTHING""")
    void claimPlan(@Param("g") PrwEmissionGroupEntity g);
}
