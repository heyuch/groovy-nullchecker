package corpus.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A conditional postcondition: when the method returns {@code result}, the named arguments
 * are non-null. The name and members follow Checker Framework's annotation, which the checker
 * matches by simple name so a project does not have to depend on it.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface EnsuresNonNullIf {
    String[] expression();

    boolean result();
}
