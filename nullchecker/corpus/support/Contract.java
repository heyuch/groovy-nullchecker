package corpus.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Stand-in for JetBrains' {@code @Contract}, matched by simple name.
 *
 * <p>The real annotation has CLASS retention, so a compiled dependency's contract is not
 * visible to a checker that reads bytecode annotations; source code and RUNTIME-retained
 * copies work. Projects that already use it for IntelliJ keep one annotation for both tools.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface Contract {
    String value();

    boolean pure() default false;

    boolean mutated() default false;
}
