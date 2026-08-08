@prw
Feature: PRW emits a validated payments arrival to Fintegrate immediately

  PRW is the payments Fintegrate writer: an ordinary DAG stage that AGT launches
  on the parallel fork after PAI. Unlike CRW it is NOT clock-driven, because
  payments does not warehouse. There is no collection day, no cde_schedule row
  and no futured remainder, so a validated arrival emits ONCE, in full, at the
  moment PAI has verdicted it.

  Membership is snapshotted immutably before any file is built (R-24) and a
  pain.008 handed to Fintegrate is never emitted twice.

  Scenario: A fully validated arrival emits every transaction at once
    Given a payments arrival with 5 validated and PAI-verdicted transactions
    When the PRW job runs for that arrival
    Then the PRW job completes
    And the pain.008 for the arrival contains 5 transactions
    And the pain.008 carries the TT2 local instrument

  Scenario: PAI has not finished verdicting, so nothing is emitted yet
    Given a payments arrival with 5 validated transactions and only 3 PAI verdicts
    When the PRW job runs for that arrival
    Then the PRW job completes
    And no pain.008 file exists for the arrival

  Scenario: An arrival with nothing validated completes without emitting
    Given a payments arrival with 4 transactions that all failed validation
    When the PRW job runs for that arrival
    Then the PRW job completes
    And no pain.008 file exists for the arrival

  # arrival.id is the WHOLE job identity, so one arrival is one JobInstance and a
  # completed one can never be relaunched. AGT's real recovery path is a killed pod,
  # which is what "the PRW pod is killed" puts the instance into. PrwJobTest asserts
  # the completed-instance refusal directly.
  Scenario: A killed stage rebuilds the identical member set from the immutable snapshot
    Given a payments arrival with 3 validated and PAI-verdicted transactions
    And the PRW job has run for that arrival
    When a further transaction is validated on the live spine
    And the emission is rolled back to state MATERIALIZED
    And the emitted pain.008 file is deleted
    And the PRW pod is killed
    And the PRW job runs again for that arrival
    Then the PRW job completes
    And the pain.008 for the arrival contains 3 transactions

  Scenario: A restarted stage never re-emits a VISIBLE pain.008
    Given a payments arrival with 3 validated and PAI-verdicted transactions
    And the PRW job has run for that arrival
    And the emitted pain.008 file is deleted
    And the PRW pod is killed
    When the PRW job runs again for that arrival
    Then the PRW job completes
    And no pain.008 file exists for the arrival
    And a file-level exclusion warning is logged for stage PRW with reason ALREADY_VISIBLE
