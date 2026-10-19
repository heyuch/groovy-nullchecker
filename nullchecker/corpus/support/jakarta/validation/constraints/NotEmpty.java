package jakarta.validation.constraints;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Bean Validation's @NotEmpty: rejects null before checking content, so it is non-null too. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER,
        ElementType.LOCAL_VARIABLE, ElementType.TYPE_USE})
public @interface NotEmpty {
}
