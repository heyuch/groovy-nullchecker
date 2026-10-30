package io.github.heyuch.groovy.nullchecker

import spock.lang.Specification

/** Q1: the core owns one classification table; the harness only maps its labels. */
class NullCheckCategoriesSpec extends Specification {

    def 'classifies nullness, initialization and resolution messages'() {
        expect:
        NullCheckCategories.classify(message) == expected

        where:
        message                                                            || expected
        "Potential null dereference: 'value' is @Nullable"                 || NullCheckCategories.NULL
        'Cannot dereference null'                                          || NullCheckCategories.NULL
        "Cannot return null from @NonNull method 'm'"                      || NullCheckCategories.NULL
        "Cannot return @Nullable value from @NonNull method 'm'"           || NullCheckCategories.NULL
        "Cannot assign null to @NonNull variable 'v'"                      || NullCheckCategories.NULL
        "Cannot assign null to @MonotonicNonNull variable 'v'"             || NullCheckCategories.NULL
        'Cannot pass null to @NonNull parameter \'x\' of \'m\''            || NullCheckCategories.NULL
        "Cannot pass @Nullable value to @NonNull parameter 'x' of 'm'"     || NullCheckCategories.NULL
        "Cannot unbox @Nullable value to primitive 'int'"                  || NullCheckCategories.NULL
        "Cannot assign @Nullable value to @NonNull variable 'v'"           || NullCheckCategories.NULL
        "@NonNull field 'value' is not initialized"                       || NullCheckCategories.INITIALIZATION
        "Cannot find matching method Foo#bar()"                           || NullCheckCategories.RESOLUTION
        null                                                               || NullCheckCategories.RESOLUTION
    }

    def 'NullCheckFinding uses the same compiler-message fallback'() {
        expect:
        NullCheckFinding.of('A.groovy', 3, "Cannot dereference null").category ==
                NullCheckCategories.NULL
        NullCheckFinding.of('A.groovy', 3, "Cannot find matching method Foo#bar()").category ==
                NullCheckCategories.RESOLUTION
    }

    def 'an explicit finding category is never reclassified from its message'() {
        expect:
        NullCheckFinding.of('A.groovy', 3, NullCheckCategories.RESOLUTION,
                'Cannot dereference null').category == NullCheckCategories.RESOLUTION
        NullCheckFinding.of('A.groovy', 3, NullCheckCategories.NULL,
                'Cannot find matching method Foo#bar()').category == NullCheckCategories.NULL
    }
}
