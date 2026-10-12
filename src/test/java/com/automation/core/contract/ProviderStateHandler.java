package com.automation.core.contract;

/**
 * Puts the provider into the state an interaction assumes ("a user with id 1 exists") before it is
 * replayed. Implement it, then name the class in {@code contract.state.handler} (it needs a public
 * no-argument constructor). Without a handler, interactions that declare a state are still
 * verified - they just rely on the provider already being in that state.
 */
@FunctionalInterface
public interface ProviderStateHandler {

    /** Called once per state, before the interaction that names it; may create data through an API or the DB. */
    void setUp(String state) throws Exception;
}
