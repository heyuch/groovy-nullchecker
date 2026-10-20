package corpus.negative

import groovy.transform.TypeChecked

/**
 * Guards: lookups that are safe because the lookup cannot miss, or because it is checked.
 *
 * <p>{@code containsKey} narrows {@code get} when the values are non-null; a default makes
 * {@code getOrDefault} non-null; safe navigation avoids the dereference. {@code first()} is
 * not a lookup in this sense: on an empty collection it throws rather than returning null.
 */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class ContainerLookupGuarded {
    static int guarded(Map<String, String> values) {
        if (values.containsKey('k')) {
            return values.get('k').length()
        }
        return 0
    }

    static int guardedSubscript(Map<String, String> values) {
        if (values.containsKey('k')) {
            return values['k'].length()
        }
        return 0
    }

    static int defaulted(Map<String, String> values) {
        return values.getOrDefault('k', 'x').length()
    }

    static int safeNavigation(Map<String, String> values) {
        return values.get('k')?.length() ?: 0
    }

    static int first(List<String> values) {
        return values.first().length()
    }
}
