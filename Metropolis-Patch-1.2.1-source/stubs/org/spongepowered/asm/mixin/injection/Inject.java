package org.spongepowered.asm.mixin.injection;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Inject {
    String[] method();
    At at();
    boolean cancellable() default false;
    int require() default -1;
    int expect() default 1;
    boolean remap() default true;
}
