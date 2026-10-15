package demo

import groovy.transform.CompileStatic

/** The negative control: correctly guarded, so it must stay silent. */
@CompileStatic
class Clean {
    private @Nullable String value

    int fine() {
        return value == null ? 0 : value.length()
    }
}
