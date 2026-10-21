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
        'Cannot pass null to @NonNull parameter \'x\' of \'m\''            || NullCheckCategories.NULL
        "@NonNull field 'value' is not initialized"                       || NullCheckCategories.INITIALIZATION
        "Cannot find matching method Foo#bar()"                           || NullCheckCategories.RESOLUTION
        null                                                               || NullCheckCategories.RESOLUTION
    }

    def 'NullCheckFinding uses the same classification'() {
        expect:
        NullCheckFinding.of('A.groovy', 3, "Cannot dereference null").category ==
                NullCheckCategories.NULL
        NullCheckFinding.of('A.groovy', 3, "Cannot find matching method Foo#bar()").category ==
                NullCheckCategories.RESOLUTION
    }
}
