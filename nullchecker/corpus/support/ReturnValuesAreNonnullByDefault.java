package corpus.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Stand-in for the JSR-305 return-value default. It is part of the specification but not of
 * the reference jar, so a project that uses it supplies the annotation itself; the checker
 * matches by simple name, which is why the same fixture works either way.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PACKAGE, ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface ReturnValuesAreNonnullByDefault {
}
