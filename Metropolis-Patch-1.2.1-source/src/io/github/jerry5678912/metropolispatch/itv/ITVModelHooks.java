package io.github.jerry5678912.metropolispatch.itv;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reflection-based model wrapper so the patch remains independent of Forge's
 * production method-name remapping. The wrapper is installed into the normal
 * BlockModelShaper cache, which lets Minecraft/Embeddium treat the ITV as
 * ordinary terrain geometry rather than a manually tessellated BER.
 */
public final class ITVModelHooks {
    private static final String ITV_BLOCK = "team.dovecotmc.metropolis.block.BlockITVMonitor";
    private static final String BAKED_MODEL = "net.minecraft.client.resources.model.BakedModel";
    private static final String BAKED_QUAD = "net.minecraft.client.renderer.block.model.BakedQuad";
    private static final String DIRECTION = "net.minecraft.core.Direction";
    private static final String RENDER_SHAPE = "net.minecraft.world.level.block.RenderShape";

    private static final Map<Object, Object> WRAPPERS = Collections.synchronizedMap(new IdentityHashMap<>());
    private static volatile boolean reportedWrapFailure;
    private static volatile boolean reportedQuadFailure;

    private ITVModelHooks() {
    }

    public static Object modelRenderShape() {
        try {
            Class<?> renderShape = Class.forName(RENDER_SHAPE);
            @SuppressWarnings({"rawtypes", "unchecked"})
            Object model = Enum.valueOf((Class<? extends Enum>) renderShape.asSubclass(Enum.class), "MODEL");
            return model;
        } catch (Throwable t) {
            throw new IllegalStateException("Unable to resolve RenderShape.MODEL", t);
        }
    }

    public static boolean isItvState(Object state) {
        if (state == null) return false;
        try {
            Object block = findNoArgReturning(state.getClass(), "net.minecraft.world.level.block.Block").invoke(state);
            return block != null && ITV_BLOCK.equals(block.getClass().getName());
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void wrapModelCache(Object blockModelShaper) {
        try {
            synchronized (WRAPPERS) {
                WRAPPERS.clear();
            }
            Field mapField = null;
            for (Field field : blockModelShaper.getClass().getDeclaredFields()) {
                if (Map.class.isAssignableFrom(field.getType())) {
                    mapField = field;
                    break;
                }
            }
            if (mapField == null) throw new NoSuchFieldException("BlockModelShaper model cache");
            mapField.setAccessible(true);
            Object raw = mapField.get(blockModelShaper);
            if (!(raw instanceof Map<?, ?> rawMap)) return;

            @SuppressWarnings("unchecked")
            Map<Object, Object> cache = (Map<Object, Object>) rawMap;
            List<Map.Entry<Object, Object>> snapshot = new ArrayList<>(cache.entrySet());
            for (Map.Entry<Object, Object> entry : snapshot) {
                if (isItvState(entry.getKey()) && entry.getValue() != null) {
                    cache.put(entry.getKey(), wrap(entry.getValue()));
                }
            }
        } catch (Throwable t) {
            reportWrapFailure(t);
        }
    }

    private static Object wrap(Object delegate) throws Exception {
        if (Proxy.isProxyClass(delegate.getClass())) {
            try {
                if (Proxy.getInvocationHandler(delegate) instanceof RotatedModelHandler) return delegate;
            } catch (IllegalArgumentException ignored) {
            }
        }
        Object existing = WRAPPERS.get(delegate);
        if (existing != null) return existing;

        Class<?> bakedModelClass = Class.forName(BAKED_MODEL);
        if (!bakedModelClass.isInstance(delegate)) return delegate;

        Object proxy = Proxy.newProxyInstance(
                bakedModelClass.getClassLoader(),
                new Class<?>[]{bakedModelClass},
                new RotatedModelHandler(delegate));
        WRAPPERS.put(delegate, proxy);
        return proxy;
    }

    private static final class RotatedModelHandler implements InvocationHandler {
        private final Object delegate;
        private final Map<Integer, List<Object>> quadsByRotation = new ConcurrentHashMap<>();

        private RotatedModelHandler(Object delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "MetropolisPatchRotatedModel[" + delegate + "]";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == (args == null ? null : args[0]);
                    default -> method.invoke(delegate, args);
                };
            }

            if (isGetQuads(method, args)) {
                Object state = args[0];
                Object requestedSide = args[1];
                if (state == null || !isItvState(state)) {
                    return invokeDelegate(method, args);
                }

                // All transformed quads are exposed as unculled/general quads.
                // 22.5-degree rotations cannot truthfully map cull faces onto the
                // six cardinal Direction buckets.
                if (requestedSide != null) return Collections.emptyList();

                int rotation = readRotation(state) & 15;
                try {
                    return quadsByRotation.computeIfAbsent(rotation, ignored -> {
                        try {
                            return Collections.unmodifiableList(buildRotatedQuads(method, args, rotation));
                        } catch (Throwable t) {
                            throw new QuadBuildException(t);
                        }
                    });
                } catch (QuadBuildException wrapped) {
                    reportQuadFailure(wrapped.getCause());
                    return invokeDelegate(method, args);
                } catch (RuntimeException e) {
                    reportQuadFailure(e);
                    return invokeDelegate(method, args);
                }
            }

            return invokeDelegate(method, args);
        }

        private List<Object> buildRotatedQuads(Method getQuads, Object[] originalArgs, int rotation) throws Throwable {
            Object state = originalArgs[0];
            Object random = originalArgs[2];
            List<Object> source = new ArrayList<>();

            collect(source, invokeDelegate(getQuads, new Object[]{state, null, random}));
            Class<?> directionClass = Class.forName(DIRECTION);
            Object[] directions = directionClass.getEnumConstants();
            if (directions != null) {
                for (Object direction : directions) {
                    collect(source, invokeDelegate(getQuads, new Object[]{state, direction, random}));
                }
            }

            List<Object> transformed = new ArrayList<>(source.size());
            for (Object quad : source) {
                transformed.add(rotateQuad(quad, rotation));
            }
            return transformed;
        }

        private Object invokeDelegate(Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(delegate, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    private static boolean isGetQuads(Method method, Object[] args) {
        if (!List.class.isAssignableFrom(method.getReturnType())) return false;
        Class<?>[] p = method.getParameterTypes();
        return p.length == 3 && args != null && args.length == 3
                && p[0].getName().equals("net.minecraft.world.level.block.state.BlockState")
                && p[1].getName().equals(DIRECTION);
    }

    private static void collect(List<Object> target, Object value) {
        if (!(value instanceof List<?> list)) return;
        for (Object item : list) if (item != null) target.add(item);
    }

    private static Object rotateQuad(Object quad, int rotation) throws Exception {
        Class<?> quadClass = Class.forName(BAKED_QUAD);
        if (!quadClass.isInstance(quad)) return quad;

        Method verticesMethod = findNoArgReturning(quadClass, "[I");
        Method tintMethod = findNoArgPrimitive(quadClass, int.class);
        Method directionMethod = findNoArgReturning(quadClass, DIRECTION);
        Method spriteMethod = findNoArgReturning(quadClass, "net.minecraft.client.renderer.texture.TextureAtlasSprite");
        Method shadeMethod = findNamedNoArg(quadClass, boolean.class, "isShade", "m_111307_", "method_24874");

        int[] data = ((int[]) verticesMethod.invoke(quad)).clone();
        int stride = data.length / 4;
        if (stride < 3) return quad;

        double angle = Math.toRadians(rotation * -22.5d);
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);

        for (int vertex = 0; vertex < 4; vertex++) {
            int base = vertex * stride;
            float x = Float.intBitsToFloat(data[base]);
            float z = Float.intBitsToFloat(data[base + 2]);
            double dx = x - 0.5d;
            double dz = z - 0.5d;
            float rx = (float) (cos * dx + sin * dz + 0.5d);
            float rz = (float) (-sin * dx + cos * dz + 0.5d);
            data[base] = Float.floatToRawIntBits(rx);
            data[base + 2] = Float.floatToRawIntBits(rz);

            // DefaultVertexFormat.BLOCK stores packed normal at element 7.
            // Preserve any higher-format extension fields and rotate only when
            // the standard normal slot exists.
            if (stride >= 8) {
                int packed = data[base + 7];
                int nx = (byte) (packed & 0xFF);
                int ny = (byte) ((packed >>> 8) & 0xFF);
                int nz = (byte) ((packed >>> 16) & 0xFF);
                double rnx = cos * nx + sin * nz;
                double rnz = -sin * nx + cos * nz;
                int outX = clampSignedByte((int) Math.round(rnx));
                int outY = clampSignedByte(ny);
                int outZ = clampSignedByte((int) Math.round(rnz));
                data[base + 7] = (packed & 0xFF000000)
                        | (outX & 0xFF)
                        | ((outY & 0xFF) << 8)
                        | ((outZ & 0xFF) << 16);
            }
        }

        int tint = (Integer) tintMethod.invoke(quad);
        Object originalDirection = directionMethod.invoke(quad);
        Object rotatedDirection = rotateDirection(originalDirection, angle);
        Object sprite = spriteMethod.invoke(quad);
        boolean shade = (Boolean) shadeMethod.invoke(quad);

        Constructor<?> ctor = findQuadConstructor(quadClass, rotatedDirection.getClass(), sprite.getClass());
        return ctor.newInstance(data, tint, rotatedDirection, sprite, shade);
    }

    private static Constructor<?> findQuadConstructor(Class<?> quadClass, Class<?> directionClass, Class<?> spriteClass)
            throws NoSuchMethodException {
        for (Constructor<?> ctor : quadClass.getConstructors()) {
            Class<?>[] p = ctor.getParameterTypes();
            if (p.length == 5 && p[0] == int[].class && p[1] == int.class
                    && p[2].isAssignableFrom(directionClass)
                    && p[3].isAssignableFrom(spriteClass) && p[4] == boolean.class) {
                return ctor;
            }
        }
        throw new NoSuchMethodException("BakedQuad(int[],int,Direction,TextureAtlasSprite,boolean)");
    }

    private static Object rotateDirection(Object direction, double angle) {
        if (!(direction instanceof Enum<?> e)) return direction;
        String name = e.name().toUpperCase(Locale.ROOT);
        if (name.equals("UP") || name.equals("DOWN")) return direction;

        double x;
        double z;
        switch (name) {
            case "NORTH" -> { x = 0; z = -1; }
            case "SOUTH" -> { x = 0; z = 1; }
            case "WEST" -> { x = -1; z = 0; }
            case "EAST" -> { x = 1; z = 0; }
            default -> { return direction; }
        }

        double rx = Math.cos(angle) * x + Math.sin(angle) * z;
        double rz = -Math.sin(angle) * x + Math.cos(angle) * z;
        String target;
        if (Math.abs(rx) > Math.abs(rz)) target = rx > 0 ? "EAST" : "WEST";
        else target = rz > 0 ? "SOUTH" : "NORTH";

        @SuppressWarnings({"rawtypes", "unchecked"})
        Object result = Enum.valueOf((Class<? extends Enum>) e.getDeclaringClass().asSubclass(Enum.class), target);
        return result;
    }

    private static int readRotation(Object state) throws Exception {
        Class<?> blockClass = Class.forName(ITV_BLOCK);
        Object rotationProperty = null;
        for (Field field : blockClass.getFields()) {
            if (Modifier.isStatic(field.getModifiers())
                    && field.getType().getName().equals("net.minecraft.world.level.block.state.properties.IntegerProperty")) {
                rotationProperty = field.get(null);
                break;
            }
        }
        if (rotationProperty == null) throw new NoSuchFieldException("BlockITVMonitor.ROTATION");

        Object property = rotationProperty;
        Method getValue = findMethod(state.getClass(), m -> m.getParameterCount() == 1
                && m.getParameterTypes()[0].isAssignableFrom(property.getClass())
                && Comparable.class.isAssignableFrom(m.getReturnType()));
        return ((Number) getValue.invoke(state, property)).intValue();
    }

    private static Method findNoArgReturning(Class<?> owner, String returnType) throws Exception {
        return findMethod(owner, m -> m.getParameterCount() == 0 && m.getReturnType().getName().equals(returnType));
    }

    private static Method findNoArgPrimitive(Class<?> owner, Class<?> primitive) throws Exception {
        return findMethod(owner, m -> m.getParameterCount() == 0 && m.getReturnType() == primitive);
    }

    private static Method findNamedNoArg(Class<?> owner, Class<?> returnType, String... names) throws Exception {
        for (String name : names) {
            try {
                Method method = owner.getMethod(name);
                if (method.getParameterCount() == 0 && method.getReturnType() == returnType) return method;
            } catch (NoSuchMethodException ignored) {
            }
        }
        throw new NoSuchMethodException("No named no-arg method on " + owner.getName());
    }

    private static Method findMethod(Class<?> owner, MethodPredicate predicate) throws Exception {
        for (Method method : owner.getMethods()) if (predicate.test(method)) return method;
        throw new NoSuchMethodException("No compatible method on " + owner.getName());
    }

    private static int clampSignedByte(int value) {
        return Math.max(-127, Math.min(127, value));
    }

    private static void reportWrapFailure(Throwable t) {
        if (!reportedWrapFailure) {
            reportedWrapFailure = true;
            System.err.println("[Metropolis Patch/ITV] Could not install rotated terrain model; falling back to the underlying model.");
            t.printStackTrace();
        }
    }

    private static void reportQuadFailure(Throwable t) {
        if (!reportedQuadFailure) {
            reportedQuadFailure = true;
            System.err.println("[Metropolis Patch/ITV] Rotated quad transformation failed; using original quads instead of crashing.");
            t.printStackTrace();
        }
    }

    private interface MethodPredicate {
        boolean test(Method method);
    }

    private static final class QuadBuildException extends RuntimeException {
        private QuadBuildException(Throwable cause) {
            super(cause);
        }
    }
}
