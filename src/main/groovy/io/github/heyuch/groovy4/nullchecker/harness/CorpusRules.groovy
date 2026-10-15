package io.github.heyuch.groovy4.nullchecker.harness

import groovy.transform.CompileStatic

/**
 * The complete set of rules the checker can report, keyed by a stable id.
 *
 * <p>The ids are derived from the checker's own error messages and are used by the
 * corpus gate in two ways: every id must be exercised by at least one fixture
 * (coverage), and every fixture must name a known id (no typo can hide a dead
 * fixture). A rule no fixture can trigger is a rule nobody has ever seen fire.
 */
@CompileStatic
class CorpusRules {

    /**
     * rule id -> message fragment that must appear in every finding for that rule.
     * Ids are deliberately one per distinct message, so a fixture can always be
     * attributed to exactly one rule.
     */
    static final Map<String, String> RULES = [
            R01: 'Cannot return null from @NonNull method',
            R02: 'Cannot return @Nullable value from @NonNull method',
            R03: 'Cannot assign null to @NonNull variable',
            R04: 'Cannot assign null to @MonotonicNonNull variable',
            R05: 'Potential null dereference:',
            R06: 'Cannot dereference null',
            R07: 'Cannot pass null to @NonNull parameter',
            R08: 'Cannot pass @Nullable value to @NonNull parameter',
            R09: '@NonNull field',
            /** Package-level defaults: a @NullMarked package makes its classes non-null
             *  by default, so a plain method returning null is reported even though the
             *  class carries no annotation of its own. */
            R10: 'Cannot return null from @NonNull method',
    ] as Map<String, String>

    /**
     * Rule ids the checker only reaches in strict mode. Everything else must behave
     * identically whether or not strict is on, which is what keeps the two modes from
     * silently diverging.
     */
    static final Set<String> STRICT_ONLY_RULES = ['R05'] as Set<String>

    /**
     * Rule ids for the negative (must-not-report) fixtures, one per guard family.
     * These have no message fragment: a negative fixture asserts absence, not presence.
     */
    static final Map<String, String> GUARD_RULES = [
            N01: 'null comparison (== / !=)',
            N02: 'short-circuit conjunction and disjunction',
            N03: 'safe navigation (?.)',
            N04: 'Groovy truth (if (x) / if (!x))',
            N05: 'instanceof narrowing',
            N06: 'Objects.nonNull / Objects.isNull',
            N07: 'assert statements',
            N08: 'throwing validators (requireNonNull / checkNotNull)',
            N09: 'test assertions (assertNotNull, assertThat().isNotNull())',
            N10: '@NullUnmarked opts out of a package/class default',
            N11: 'no marking in scope (subpackage or unmarked package)',
    ] as Map<String, String>

    static boolean isKnown(String rule) {
        RULES.containsKey(rule) || GUARD_RULES.containsKey(rule)
    }

    static boolean isStrictOnly(String rule) {
        STRICT_ONLY_RULES.contains(rule)
    }

    static String fragmentFor(String rule) {
        RULES[rule]
    }
}
