package corpus.positive

import groovy.transform.TypeChecked

import java.util.stream.Stream

/** C1: Optional.orElse follows its argument; get/orElseThrow and findFirst are non-null. */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class OptionalOrElse {

    static int bad(Optional<String> optional) {
        return optional.orElse(null).length()
    }

    static int good(Optional<String> optional) {
        return optional.orElse('x').length()
    }

    static int get(Optional<String> optional) {
        return optional.get().length()
    }

    static int stream(Stream<String> stream) {
        return stream.findFirst().orElse(null).length()
    }
}
