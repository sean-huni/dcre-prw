# dcre-prw

Payments Request Writer: the DCRE stage that generates and writes the Fintegrate request
(`pain.008`) for a validated payments arrival, into that client's `fint-req/out` exchange directory.

Caption on the payments REQ sheet: **"Generates & Writes Fintegrate Request to Directory"**.

## Where it sits

```
PRR -> PTV -> PAI -> || -> { PRW -> Fint Req ,  PIR -> OnHost Resp }
```

PRW is an ordinary DAG stage on the parallel fork after PAI, exactly where MRW sits on the mandates
sheet. AGT launches it as a short-lived Kubernetes Job with ONE identifying job parameter,
`arrival.id` (R-16). It is terminal on the payments DAG, alongside PIR.

## What it does

Reads the arrival's PASS-validated, PAI-verdicted transactions from `dcre_pay`, freezes their
membership into an immutable batch plan (at most `max-size` transactions per outbound file), and
publishes the plan's synthetic `pain.008` XML in ordinal order for Fintegrate.

## It was forked from CRW, and the fork REMOVED a mechanism

CRW is the collections writer and emits the same ISO message, `pain.008`, which is why it is the
source rather than MRW (MRW emits `pain.009` mandate initiation, a different message entirely).
But CRW is **clock-driven**, because collections **warehouses**: CDE estimates a collection day per
transaction and CRW's window emits only the slice maturing on the run date, leaving a futured
remainder to re-emit later.

**Payments has no CDE, no collection day and no warehousing. A payment is processed immediately.**
So PRW carries none of the machinery that exists to serve a second run date:

| Removed | Why it has no successor here |
|---|---|
| `cde_schedule` dependency, every `process_date` predicate | there is no collection day to compare against |
| `run_date` in the business key (`crw_emission*`) | a payments arrival emits once, so a date in the identity varies for non-business reasons |
| `CrwScheduler` and the whole clock path | AGT launches PRW as a DAG stage after PAI |
| the pay arm of `DueArms` / `DueSql`, and arm composition entirely | that arm existed ONLY because payments had no writer of its own; it is the coupling this split removes |
| `ClientLanePartitioner`, `LaneEmissionService` | they fan a run date's due-CLIENT universe across lanes; one arrival has one client |
| `countPriorArtifacts` (cross-run-date artifact offset) | provably always zero without a second run date |
| `crw_emission_owed` view | it exists because CRW is not a DAG stage, so AGT needed a published completion predicate. PRW is terminal, so `RouteDags.PAY` carries `Emission.NONE` |
| every `flow` discriminator | the DATABASE is the discriminator now: a row in `dcre_pay` IS a payment |

This absence is **tested, not merely intended**. `NoClockPathTest` scans the shipped code (comments
stripped, so the explanations above can survive) for every clock and warehouse token, reads the
persisted shape off the entity classes, rejects any method taking a `LocalDate`, and asserts the
tasklet binds `arrival.id` and nothing else. `EmissionSchemaIT` asserts the same absence against the
live database catalog. Both have been seen red under mutation.

## What it KEEPS from CRW

The `pain.008` builder, the staged write, the R-24 frozen-plan reconciliation, and the CockroachDB
40001 retry discipline. Also the **split**, which is a Fintegrate file-size cap rather than
warehousing machinery: a 300k-transaction payment arrival needs it exactly as much as a collections
one.

## Eligibility

An arrival is eligible when BOTH hold, and they are two gates rather than one on purpose:

1. the PAI verdict set COVERS the PASS set (a count comparison, never a bare `EXISTS`: PAI
   slice-commits, so a mid-run or died PAI must stay fail-closed);
2. at least one PASS row exists. With zero PASS rows the coverage comparison is `0 >= 0`, which is
   TRUE, so gate 1 alone would plan an empty emission for a wholly-rejected arrival.

An ineligible arrival is **not a failure**: PRW is terminal, so throwing would strand the arrival in
`DAG_RUNNING`. The stage completes having emitted zero files.

## Architecture and principles

- SOLID, 3-tier, layer-first packages: `EmissionTasklet` is a thin entry adapter calling ONE
  business-tier method; logic lives in `service` (`EmissionService` per arrival, `SplitPlanner` for
  the frozen plan); persistence only via `data/repo`, entities in `data/model` extending the platform
  `BaseEntity`.
- 12FactorApp Alignment - https://12factor.net/ : config strictly from the environment with committed
  working dev defaults (a clean clone runs with no `.env`), a stateless one-shot process, CockroachDB
  and the exchange filesystem as attached backing services.
- **Idempotent restart at batch grain (R-24).** Per arrival a plan group is claimed
  `INSERT ... ON CONFLICT (arrival_id) DO NOTHING` and its batches on the FULL identity
  `ON CONFLICT (arrival_id, batch_ordinal) DO NOTHING`. Members freeze set-based; each batch's
  `tx_count`/`control_sum` freeze with them; every file builds strictly from that snapshot and
  reconciles against its OWN frozen `tx_count` first. States walk `PLANNED -> MATERIALIZED -> VISIBLE`.
- **Outbound client (A-43, ruled 2026-08-08).** `<client>` below is `tx_header.client_token`, the R-31
  filename token, falling back to the copybook `initg_pty` when an arrival carried no filename. It is
  resolved ONCE, in `DueSql.CLIENT_EXPR`, so `prw_emission_group.client`, the file name and the
  per-client output directory cannot come from different sources. `ext_tx_status.client` and
  `prg_watermark.client` in `dcre_pay` already carry `client_token`, so this is what stops a parent
  being named in `prg_report_due` under an identity the watermark cannot select. The emitted pain.008
  is unchanged: no message this fleet emits carries an initiating party.
- **Outbound identity.** Unsplit: bare source MsgId, file `<client>_<msg_id>_PAIN008.xml`. Split:
  children suffixed `_1.._N`. The sequence is exactly 1..N with no offset. A unique index on
  `outbound_msg_id` is the cross-ARRIVAL guard that makes the two-column claim key safe.
- **Durable-effect ordering.** The plan transaction commits group, batches, members and frozen totals
  (MATERIALIZED) BEFORE any file exists; publication then walks batches strictly in ordinal order
  (`_2` never VISIBLE before `_1`), per batch `StagedWrite` (tmp + atomic move, restart no-op, R-05)
  then `markVisible` in its own small transaction.
- **CRDB 40001 aborts are retried, never skipped**: plan and publication transactions run
  `REQUIRES_NEW` inside `CrdbRetry` (5 attempts, exponential backoff), and the production `emitStep`
  carries the shared platform `CrdbRetryExceptionHandler` at the step boundary.

### Job identity, and what a relaunch means

`arrival.id` is the WHOLE job identity, so **one arrival is one JobInstance forever**. Spring Batch
refuses to re-run an instance whose last execution COMPLETED, which is a second independent control
against a duplicate `pain.008` on top of the VISIBLE state guard. AGT's real recovery path is a
KILLED pod, whose execution is left STARTED (then abandoned by `StaleExecutionSweeper`) or FAILED,
and only that is restartable. The test suite models both and never fabricates an extra identifying
parameter, because AGT supplies none.

### Data

Reads (grants-based): `tx_header` + `tx_entry` (PRR), `validation_log` (PTV), `pai_verdict` (PAI).
Writes (PRW single-writer): `prw_emission_group` (UNIQUE `arrival_id`, and `(client, source_msg_id)`),
`prw_emission` (UNIQUE `(arrival_id, batch_ordinal)`, unique `outbound_msg_id`),
`prw_emission_member` (UNIQUE `(emission_id, sequence)`), plus the outbound batch files. Liquibase
owns all DDL with per-service history tables `prw_databasechangelog` / `prw_databasechangeloglock`;
Spring Batch metadata sits under the `PRW_BATCH_` prefix with `initialize-schema: never`.

`Pain008Writer` is the SYNTHETIC-CONTRACT (R-35, A-9) `pain.008`-shaped skeleton: GrpHdr `MsgId` =
the batch's outbound MsgId, `NbOfTxs`/`CtrlSum` = the batch's frozen totals,
`PmtTpInf/LclInstrm/Cd = TT2` (R-02/R-18), one `DrctDbtTxInf` per member with the canonical
EndToEndId (R-15) and `InstdAmt Ccy="ZAR"`.

## Prerequisites

- Java 25 (Gradle toolchain; wrapper 9.5.1 included)
- Docker (Testcontainers test suite and image build)
- Platform libs in Maven Local: `za.co.fnb.dcre:platform-persistence:0.1.0` and
  `za.co.fnb.dcre:platform-batch:0.1.0` (`platform-batch` brings `platform-files` and
  `platform-model` transitively; all resolve from `mavenLocal` only)
- A reachable CockroachDB for a real run (the dcre-infra kind cluster, or any CRDB at `DCRE_DB_URL`)

## Quickstart

```bash
# one-time: publish the platform libs (order matters for the batch chain)
(cd ../../platform/platform-model && ./gradlew publishToMavenLocal)
(cd ../../platform/platform-files && ./gradlew publishToMavenLocal)
(cd ../../platform/platform-batch && ./gradlew publishToMavenLocal)
(cd ../../platform/platform-persistence && ./gradlew publishToMavenLocal)

./gradlew build        # compile + full test suite (Docker required)
```

Local one-shot run against the kind cluster's CRDB (dcre-infra `scripts/crdb-forward.sh` forwards
host 26258 to cluster 26257):

```bash
DCRE_DB_URL="jdbc:postgresql://localhost:26258/dcre_pay?sslmode=disable" \
  java -jar build/libs/prw-2.0.jar arrival.id=<uuid>
```

The JVM exit code carries the Batch verdict (R-34). A clean clone runs with NO `.env`:
`application.yml` commits working dev defaults.

Image build is Paketo buildpacks, never a hand-rolled prod JVM Dockerfile:

```bash
./gradlew bootBuildImage      # dcre-prw:2.0, BP_JVM_VERSION=25
```

## Configuration

| Env var | Default | Purpose |
|---|---|---|
| `DCRE_DB_URL` | `jdbc:postgresql://localhost:26257/dcre_pay?sslmode=disable` | payments CockroachDB JDBC URL |
| `DCRE_DB_USER` | `root` | DB user |
| `DCRE_DB_PASSWORD` | (empty) | DB password |
| `DCRE_AGTOPS_DB_URL` | `jdbc:postgresql://localhost:26257/agt_ops?sslmode=disable` | heartbeat datasource |
| `DCRE_EXCHANGE_ROOT` | `../../../../../../infra/dcre-infra/exchange` | exchange root (RELATIVE default, six levels up; verified with `realpath` from this module) |
| `DCRE_AMOUNT_SCALE` | `2` | money scale |
| `DCRE_PRW_MAX_SPLIT_SIZE` | `5000` | max tx per outbound `pain.008` |
| `DCRE_PRW_SPLIT_FNBRF01` | `5000` | per-client split override |

Precedence: yml default < `.env` < real environment variable.

**The `dcre.prw.split.overrides` map carries a committed, behaviour-neutral entry on purpose.** An
absent map cannot be distinguished from a Java-prefix/yml-key drift, which binds an EMPTY map, drops
every client to the constant default, and still exits 0. That defect has shipped three times in this
estate. `SplitPropertiesBindingTest` asserts the map is POPULATED from the shipped yml and that the
declared prefix matches the yml key path; it has been seen red for exactly that mutation.

## Testing

```bash
./gradlew clean build   # 46 tests, Docker required
```

Structural guards worth knowing about before editing: `NoClockPathTest` and `PayFlowOnlyTest` will
fail if the clock path, the warehousing vocabulary, arm composition or a flow discriminator is
reintroduced. That is their job. If one fires, the fix is almost never to relax the test.

## Follow-ups

- `Pain008Writer` is currently identical in `crw` and `prw`. The family design
  (`2026-08-07-payments-family-build-design.md`) homes the shared builder in a `platform-fintegrate`
  module consumed by both. That module does not exist yet and extracting it means repointing CRW,
  which was out of scope for this fork. The duplication is known and scheduled, not accidental.
