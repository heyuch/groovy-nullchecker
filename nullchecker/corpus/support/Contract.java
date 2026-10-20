package corpus.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Stand-in for JetBrains' {@code @Contract}, matched by simple name.
 *
 * <p>The real annotation has CLASS retention; this copy is RUNTIME so a fixture can use it
 * either way. Retention is not a limitation for the checker -- annotations are read from the
 * class file, so a contract compiled into a dependency works -- but the annotation's own class
 * has to be on the compile classpath. Projects that already use it for IntelliJ keep one
 * annotation for both tools.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface Contract {
    String value();

    boolean pure() default false;

    boolean mutated() default false;
}
