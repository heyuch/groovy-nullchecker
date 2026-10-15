package demo;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Non-null-by-default marking for a package or type, matched by SIMPLE NAME like the
 * others. Mirrors JSpecify's @NullMarked.
 *
 * <p>Only this family ("NullMarked", "NonNullByDefault", "NonnullByDefault") turns the
 * default ON. A plain @NonNull marks one element and is deliberately not a default.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PACKAGE, ElementType.TYPE, ElementType.METHOD})
public @interface NullMarked {
}
