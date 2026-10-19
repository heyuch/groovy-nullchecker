package corpus.negative

import corpus.support.Nullable
import groovy.transform.TypeChecked

/**
 * Guards: `continue` and `break` end the current path just as `return` does.
 *
 * The statements after the `if` are reached only when the condition was false, so the
 * variable is non-null there. Only `return` and `throw` used to count, which made the
 * narrowest and most common loop guard in real code look like a null dereference.
 */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class GuardsLoopExit {
    static String firstTag(List<String> texts) {
        for (String text : texts) {
            String tagBody = text?.trim()
            if (tagBody == null) {
                continue
            }
            return tagBody.charAt(0) as String
        }
        return ''
    }

    static Integer firstLength(List<String> texts) {
        for (String text : texts) {
            String tagBody = text?.trim()
            if (tagBody == null) {
                break
            }
            return tagBody.length()
        }
        return null
    }

    static int countWhile(List<String> texts) {
        int seen = 0
        for (String text : texts) {
            String tagBody = text?.trim()
            if (tagBody == null) {
                continue
            }
            seen += tagBody.length()
        }
        return seen
    }
}
