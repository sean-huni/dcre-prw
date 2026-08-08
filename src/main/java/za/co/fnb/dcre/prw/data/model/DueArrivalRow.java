package za.co.fnb.dcre.prw.data.model;

import java.util.UUID;

/**
 * Arrival-level due listing: scalars only, one row per arrival. The per-transaction
 * rows are fetched inside the arrival's own transaction; the whole-backlog per-tx
 * join blew CRDB's sql memory budget (joinreader-mem) on CRW at 23 arrivals x 300k
 * transactions, and PRW inherits that lesson rather than re-learning it.
 */
public record DueArrivalRow(UUID arrivalId, String client, String msgId) {
}
