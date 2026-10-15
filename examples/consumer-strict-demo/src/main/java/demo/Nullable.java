package demo;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Stand-in for whatever the real project already has (JSR-305, JetBrains, JSpecify...).
 * The checker matches by SIMPLE NAME, so any of them works and this library does not
 * drag an annotation dependency into the consumer.
 *
 * <p>RUNTIME retention is required: with CLASS retention the checker does not see the
 * annotation and every rule based on it silently goes quiet. PACKAGE is required so the
 * annotation can be used in package-info.java.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER,
        ElementType.LOCAL_VARIABLE, ElementType.TYPE_USE, ElementType.TYPE,
        ElementType.PACKAGE})
public @interface Nullable {
}
