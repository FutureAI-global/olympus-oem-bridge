package com.futureai.fdrs.layer2.activetest;

import com.futureai.fdrs.layer2.CommandAdapter;

/**
 * Marker interface for active-test adapters — Ford routines that DRIVE
 * the vehicle (actuator control, misfire injection, relay toggling, etc)
 * as opposed to read-only observers.
 *
 * <p><b>No concrete implementors exist in this PR.</b> PR#E (plan doc
 * #1648) ships the scaffolding + safety-case doc v1 only. Each follow-up
 * PR that registers ONE active-test routine MUST satisfy the 5 gates
 * documented in {@code backend/docs/fdrs-layer2/active-test-safety-case-v1.md}
 * BEFORE its adapter lands:
 *
 * <ol>
 *   <li><b>Per-routine allow-list</b>: the public-name of the adapter
 *       must be explicitly enumerated in
 *       {@code active-test-allowlist.json}. The registry throws at
 *       startup if a concrete {@code ActiveTestAdapter} is registered
 *       without a matching allow-list entry. Defense against silent
 *       registration of new actuator surfaces.</li>
 *   <li><b>Pre-conditions</b>: adapter MUST declare and validate
 *       vehicle state pre-conditions (ignition state, engine RPM band,
 *       gear, speed, dtc presence) before the {@code make()} returns a
 *       Command. A failed pre-condition returns a typed abstain instead
 *       of a Command.</li>
 *   <li><b>Abort watchdog</b>: adapter MUST register an abort hook that
 *       the Router enforces via max-wall-clock-time + kill-switch
 *       polling. Routines that don't complete or abort within the
 *       declared bound are terminated by the watchdog, not by the
 *       routine.</li>
 *   <li><b>Post-conditions</b>: adapter MUST declare expected
 *       post-state (which DTCs may be set, which actuator should
 *       return to default) and verify post-execution. A failed
 *       post-condition is surfaced as an {@code error} result with
 *       kind="routine-post-verify-failed" so the tech knows the
 *       vehicle may be in a transitional state.</li>
 *   <li><b>Audit trail</b>: every active-test dispatch — successful
 *       or aborted — is written to {@code ~/.olympus/audit/active-
 *       tests.jsonl} with VIN, routine name, args, pre/post state,
 *       and elapsed wall time. This is load-bearing for incident
 *       review and safety-case post-merge verification.</li>
 * </ol>
 *
 * <p>See {@code backend/docs/fdrs-layer2/active-test-safety-case-v1.md}
 * for the canonical template. Session K reviews the per-routine safety
 * case in the follow-up PR; Session E signs it before merge.
 *
 * <p><b>This PR:</b> scaffolding only. Registry has zero
 * {@code ActiveTestAdapter} implementors. Allow-list JSON ships empty.
 */
public interface ActiveTestAdapter extends CommandAdapter {
    /**
     * Required side-effect class tag. Active-test adapters MUST return
     * {@link SideEffectClass#WRITE_WITH_ROLLBACK} or
     * {@link SideEffectClass#WRITE_IRREVERSIBLE}.
     *
     * <p>Read-only adapters extend {@link CommandAdapter} directly, not
     * {@link ActiveTestAdapter}. The explicit tag is the seam the
     * TypeScript tool-authority manifest uses to pin the tier at
     * {@code "confirm"}.
     */
    SideEffectClass sideEffectClass();

    /**
     * The allow-list key this adapter must appear under in
     * {@code active-test-allowlist.json}. Typically equals
     * {@link #publicName()} but kept distinct so the allow-list can be
     * reorganized (e.g. by sub-category) without renaming adapters.
     */
    String allowlistKey();

    enum SideEffectClass {
        /**
         * Routine takes the vehicle out of nominal state transiently
         * and the vehicle returns to nominal automatically (e.g. relay
         * click test — click high/low then return to commanded state).
         */
        WRITE_WITH_ROLLBACK,

        /**
         * Routine changes vehicle state persistently — once executed,
         * the tech must manually re-verify or revert (e.g. clear
         * adaptive learning, reset service interval). REQUIRES
         * operator-tier confirm with extended consent prompt.
         */
        WRITE_IRREVERSIBLE,
    }
}
