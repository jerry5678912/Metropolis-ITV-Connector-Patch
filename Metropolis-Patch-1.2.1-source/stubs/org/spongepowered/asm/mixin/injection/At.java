package org.spongepowered.asm.mixin.injection;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.PARAMETER})
public @interface At {
    String value();
    String target() default "";
    int ordinal() default -1;
    Shift shift() default Shift.NONE;
    enum Shift { BEFORE, AFTER, BY, NONE }
}
