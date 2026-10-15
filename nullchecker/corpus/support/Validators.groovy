package corpus.support

/**
 * Stand-in for Objects.requireNonNull / Guava checkNotNull / JUnit assertNotNull /
 * AssertJ assertThat, so the guard corpus needs no third-party dependency.
 * The checker matches these by SIMPLE NAME, which is exactly the property under test.
 */
class Validators {
    static <T> T requireNonNull(T value) {
        Objects.requireNonNull(value)
        value
    }

    static <T> T checkNotNull(T value) {
        Objects.requireNonNull(value)
        value
    }

    static void assertNotNull(Object value) {
        if (value == null) {
            throw new AssertionError('null')
        }
    }

    static void assertNotNull(Object value, String message) {
        if (value == null) {
            throw new AssertionError(message)
        }
    }

    static Assertions assertThat(Object value) {
        new Assertions(value)
    }

    static class Assertions {
        private final Object value

        Assertions(Object value) {
            this.value = value
        }

        Assertions isNotNull() {
            if (value == null) {
                throw new AssertionError('null')
            }
            this
        }
    }
}
