package corpus.negative

import corpus.support.Nullable
import corpus.support.Validators
import groovy.transform.TypeChecked

/** Guards: test assertions, including a message argument and a fluent chain. */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class GuardsTestAssertions {
    int assertNotNull(@Nullable String s) {
        Validators.assertNotNull(s)
        return s.length()
    }

    int assertNotNullWithMessage(@Nullable String s) {
        Validators.assertNotNull(s, 'must not be null')
        return s.length()
    }

    int fluentAssertThat(@Nullable String s) {
        Validators.assertThat(s).isNotNull()
        return s.length()
    }
}
