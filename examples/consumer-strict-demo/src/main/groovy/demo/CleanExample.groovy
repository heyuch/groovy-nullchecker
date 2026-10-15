package demo

/** The control: correctly annotated and guarded code must be completely silent. */
class CleanExample {

    @NonNull
    String fine() {
        return 'value'
    }

    int guarded(@Nullable String input) {
        if (input == null) {
            return 0
        }
        return input.length()
    }

    Integer safeChain(@Nullable String input) {
        return input?.trim()?.length()
    }

    String elvis(@Nullable String input) {
        return input ?: 'default'
    }
}
