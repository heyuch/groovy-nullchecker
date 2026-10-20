package corpus.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method that never returns to its caller.
 *
 * <p>Matched by simple name, like the nullness annotations, so a project may use this one or
 * any annotation with the same name. {@code @Contract("_, _ -> fail")} and the
 * {@code neverReturns} list say the same thing.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface NoReturn {
}
