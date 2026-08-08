package za.co.fnb.dcre.prw.data.repo;

import org.springframework.jdbc.core.RowMapper;
import za.co.fnb.dcre.prw.data.model.DueArrivalRow;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

public class DueArrivalRowMapper implements RowMapper<DueArrivalRow> {

    @Override
    public DueArrivalRow mapRow(final ResultSet r, final int rowNum) throws SQLException {
        return new DueArrivalRow(r.getObject("arrival_id", UUID.class), r.getString("initg_pty"),
                r.getString("msg_id"));
    }
}
