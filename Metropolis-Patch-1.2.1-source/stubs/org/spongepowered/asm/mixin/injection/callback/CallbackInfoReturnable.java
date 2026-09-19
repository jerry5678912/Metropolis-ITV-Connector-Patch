package org.spongepowered.asm.mixin.injection.callback;
public class CallbackInfoReturnable<T> extends CallbackInfo {
    private T value;
    public T getReturnValue() { return value; }
    public void setReturnValue(T value) { this.value = value; cancel(); }
}
