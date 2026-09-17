package io.github.jerry5678912.metropolisitvpatch.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import team.dovecotmc.metropolis.block.entity.BlockEntityITVMonitor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Connector-safe replacement renderer for Metropolis' ITV monitor.
 *
 * Metropolis manually calls the world block tessellator from its BER. That path
 * is what fails in the user's Forge + Sinytra Connector renderer stack. This
 * mixin replaces it with ModelBlockRenderer.renderModel(), which consumes the
 * already-baked model directly and does not enter world tessellation/AO.
 *
 * Reflection is deliberate: it keeps this tiny compatibility mod independent
 * of Forge's production method-name remapping while still targeting the exact
 * 1.20.1 runtime classes by type signature.
 */
@Mixin(targets = "team.dovecotmc.metropolis.client.block.entity.ITVMonitorBlockEntityRenderer", remap = false)
public abstract class ITVMonitorRendererMixin {
    private static boolean metropolisITVPatch$reportedFailure;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void metropolisITVPatch$renderSafely(
            BlockEntityITVMonitor blockEntity,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int packedLight,
            int packedOverlay,
            CallbackInfo ci) {

        try {
            final Object state = findNoArgReturning(blockEntity, "net.minecraft.world.level.block.state.BlockState").invoke(blockEntity);
            final int rotation = readRotation(state);

            final Method push = findNamedOrNoArgVoid(poseStack.getClass(), true);
            final Method pop = findNamedOrNoArgVoid(poseStack.getClass(), false);
            final Method translate = findMethod(poseStack.getClass(), m ->
                    m.getParameterCount() == 3 &&
                    m.getParameterTypes()[0] == double.class &&
                    m.getParameterTypes()[1] == double.class &&
                    m.getParameterTypes()[2] == double.class &&
                    m.getReturnType() == void.class);
            final Method mulPose = findMethod(poseStack.getClass(), m ->
                    m.getParameterCount() == 1 &&
                    m.getParameterTypes()[0].getName().equals("org.joml.Quaternionf") &&
                    m.getReturnType() == void.class);

            push.invoke(poseStack);
            try {
                translate.invoke(poseStack, 0.5d, 0.5d, 0.5d);

                final Class<?> quaternionClass = Class.forName("org.joml.Quaternionf");
                final Object quaternion = quaternionClass.getConstructor().newInstance();
                quaternionClass.getMethod("rotationY", float.class)
                        .invoke(quaternion, (float) Math.toRadians(rotation * -22.5f));
                mulPose.invoke(poseStack, quaternion);

                translate.invoke(poseStack, -0.5d, -0.5d, -0.5d);

                final Class<?> minecraftClass = Class.forName("net.minecraft.client.Minecraft");
                final Method getMinecraft = findMethod(minecraftClass, m ->
                        Modifier.isStatic(m.getModifiers()) &&
                        m.getParameterCount() == 0 &&
                        m.getReturnType() == minecraftClass);
                final Object minecraft = getMinecraft.invoke(null);

                final Object dispatcher = findNoArgReturning(minecraft, "net.minecraft.client.renderer.block.BlockRenderDispatcher")
                        .invoke(minecraft);
                final Object model = findOneArgReturning(dispatcher, state.getClass(), "net.minecraft.client.resources.model.BakedModel")
                        .invoke(dispatcher, state);
                final Object modelRenderer = findNoArgReturning(dispatcher, "net.minecraft.client.renderer.block.ModelBlockRenderer")
                        .invoke(dispatcher);

                final Class<?> itemBlockRenderTypes = Class.forName("net.minecraft.client.renderer.ItemBlockRenderTypes");
                final Method getRenderType = findMethod(itemBlockRenderTypes, m ->
                        Modifier.isStatic(m.getModifiers()) &&
                        m.getParameterCount() == 1 &&
                        m.getParameterTypes()[0].getName().equals("net.minecraft.world.level.block.state.BlockState") &&
                        m.getReturnType().getName().equals("net.minecraft.client.renderer.RenderType"));
                final Object renderType = getRenderType.invoke(null, state);

                final Method getBuffer = findMethod(bufferSource.getClass(), m ->
                        m.getParameterCount() == 1 &&
                        m.getParameterTypes()[0].getName().equals("net.minecraft.client.renderer.RenderType") &&
                        m.getReturnType().getName().equals("com.mojang.blaze3d.vertex.VertexConsumer"));
                final Object vertexConsumer = getBuffer.invoke(bufferSource, renderType);

                final Method last = findMethod(poseStack.getClass(), m ->
                        m.getParameterCount() == 0 &&
                        m.getReturnType().getName().equals("com.mojang.blaze3d.vertex.PoseStack$Pose"));
                final Object pose = last.invoke(poseStack);

                final Method renderModel = findMethod(modelRenderer.getClass(), m -> {
                    final Class<?>[] p = m.getParameterTypes();
                    return p.length == 9 &&
                            p[0].getName().equals("com.mojang.blaze3d.vertex.PoseStack$Pose") &&
                            p[1].getName().equals("com.mojang.blaze3d.vertex.VertexConsumer") &&
                            p[2].getName().equals("net.minecraft.world.level.block.state.BlockState") &&
                            p[3].getName().equals("net.minecraft.client.resources.model.BakedModel") &&
                            p[4] == float.class && p[5] == float.class && p[6] == float.class &&
                            p[7] == int.class && p[8] == int.class &&
                            m.getReturnType() == void.class;
                });

                renderModel.invoke(modelRenderer, pose, vertexConsumer, state, model,
                        1.0f, 1.0f, 1.0f, packedLight, packedOverlay);
            } finally {
                pop.invoke(poseStack);
            }
        } catch (Throwable t) {
            // Never let a compatibility renderer corrupt/crash the world again.
            if (!metropolisITVPatch$reportedFailure) {
                metropolisITVPatch$reportedFailure = true;
                System.err.println("[Metropolis ITV Patch] Safe renderer failed; ITV will be hidden instead of crashing.");
                t.printStackTrace();
            }
        }

        // Always suppress Metropolis' original manual tessellation path.
        ci.cancel();
    }

    private static int readRotation(Object state) throws Exception {
        final Class<?> blockClass = Class.forName("team.dovecotmc.metropolis.block.BlockITVMonitor");
        Object rotationProperty = null;
        for (Field field : blockClass.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) &&
                    field.getType().getName().equals("net.minecraft.world.level.block.state.properties.IntegerProperty")) {
                rotationProperty = field.get(null);
                break;
            }
        }
        if (rotationProperty == null) {
            throw new NoSuchFieldException("BlockITVMonitor rotation property");
        }

        final Object propertyFinal = rotationProperty;
        final Method getValue = findMethod(state.getClass(), m ->
                m.getParameterCount() == 1 &&
                m.getParameterTypes()[0].isAssignableFrom(propertyFinal.getClass()) &&
                Comparable.class.isAssignableFrom(m.getReturnType()));
        final Object value = getValue.invoke(state, rotationProperty);
        return ((Number) value).intValue();
    }

    private static Method findNoArgReturning(Object owner, String returnType) throws Exception {
        return findMethod(owner.getClass(), m -> m.getParameterCount() == 0 && m.getReturnType().getName().equals(returnType));
    }

    private static Method findOneArgReturning(Object owner, Class<?> argType, String returnType) throws Exception {
        return findMethod(owner.getClass(), m ->
                m.getParameterCount() == 1 &&
                m.getParameterTypes()[0].isAssignableFrom(argType) &&
                m.getReturnType().getName().equals(returnType));
    }

    private static Method findNamedOrNoArgVoid(Class<?> owner, boolean push) throws Exception {
        final String[] names = push
                ? new String[]{"pushPose", "m_85836_"}
                : new String[]{"popPose", "m_85849_"};
        for (String name : names) {
            try {
                Method method = owner.getMethod(name);
                if (method.getReturnType() == void.class) return method;
            } catch (NoSuchMethodException ignored) {
            }
        }
        throw new NoSuchMethodException(push ? "PoseStack.pushPose" : "PoseStack.popPose");
    }

    private interface MethodPredicate {
        boolean test(Method method);
    }

    private static Method findMethod(Class<?> owner, MethodPredicate predicate) throws Exception {
        for (Method method : owner.getMethods()) {
            if (predicate.test(method)) {
                return method;
            }
        }
        throw new NoSuchMethodException("No compatible method on " + owner.getName());
    }
}
