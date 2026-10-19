package jakarta.validation.constraints;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Stand-in for Bean Validation's {@code @NotNull}, in its real package.
 *
 * <p>Package matters: the checker treats this constraint as a non-null contract but not as
 * a requirement to initialise the field in source, and it identifies the constraint by
 * package rather than by simple name.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER,
        ElementType.LOCAL_VARIABLE, ElementType.TYPE_USE})
public @interface NotNull {
}
