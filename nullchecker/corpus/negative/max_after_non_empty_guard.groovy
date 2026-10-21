package corpus.negative

import groovy.transform.TypeChecked

/** Guards: a collection proved non-empty makes max() non-null. */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class MaxAfterNonEmptyGuard {
    static class Item {
        int id
    }

    static boolean isNotNullOrEmpty(List<Item> items) {
        return items != null && !items.isEmpty()
    }

    int newest(List<Item> items) {
        if (isNotNullOrEmpty(items)) {
            Item item = items.max { it.id }
            return item.id
        }
        return 0
    }
}
