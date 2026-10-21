package io.github.heyuch.groovy.nullchecker

import groovy.transform.PackageScope

/**
 * What a call promises: its result, which arguments it narrows, and whether it returns.
 *
 * <p>One model for every source. Annotations ({@code @Contract}, {@code @EnsuresNonNullIf},
 * {@code @EnsuresNonNull}, {@code @NoReturn}), the project's configuration, and the built-in
 * table for library methods all resolve to this, in that order of precedence (configuration
 * first, then annotations, then built-ins). Keeping one model is what makes a configured
 * contract and an annotated one behave identically.
 */
@PackageScope
class Contract {

    /** Marks a cached "no source had a contract" answer, distinct from "not computed yet". */
    boolean unresolved

    ResultNullness result = ResultNullness.NON_NULL

    int resultArgument = -1

    boolean neverReturns

    Set<Integer> nonNullWhenTrue = new LinkedHashSet<Integer>()

    Set<Integer> nonNullWhenFalse = new LinkedHashSet<Integer>()

    Set<Integer> nonNullAfterReturn = new LinkedHashSet<Integer>()

    PresenceRule presence

    /** True when the contract came from the built-in table, so the family switch applies. */
    boolean builtIn

    static Contract absent() {
        Contract contract = new Contract()
        contract.unresolved = true
        contract
    }

    static Contract never() {
        Contract contract = new Contract()
        contract.neverReturns = true
        contract
    }

    static Contract fact(boolean whenTrue) {
        Contract contract = new Contract()
        (whenTrue ? contract.nonNullWhenTrue : contract.nonNullWhenFalse).add(0)
        contract
    }

    static Contract afterReturn(int index) {
        Contract contract = new Contract()
        contract.nonNullAfterReturn.add(index)
        contract
    }

    static Contract nullableResult() {
        Contract contract = new Contract()
        contract.result = ResultNullness.NULLABLE
        contract
    }

    /** A nullable lookup: non-null only when the same key is known present. */
    static Contract lookupResult(int keyArgument) {
        Contract contract = nullableResult()
        contract.presence = new PresenceRule(argument: keyArgument)
        contract
    }

    static Contract nullableIfArgNullable(int argument) {
        Contract contract = new Contract()
        contract.result = ResultNullness.NULLABLE_IF_ARG_NULLABLE
        contract.resultArgument = argument
        contract
    }

    static Contract whenTrue(int argument) {
        Contract contract = new Contract()
        contract.nonNullWhenTrue.add(argument)
        contract
    }

    static Contract whenFalse(int argument) {
        Contract contract = new Contract()
        contract.nonNullWhenFalse.add(argument)
        contract
    }
}
