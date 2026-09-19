package io.github.jerry5678912.metropolispatch.securitydoor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;

/**
 * Validates the double-block counterpart before Metropolis' destroy callback
 * reads its HALF property. Transient multiplayer states can legitimately have
 * one half already replaced with air.
 */
public final class SecurityDoorGuard {
    private static final String SECURITY_DOOR = "team.dovecotmc.old.metropolis.block.BlockSecurityDoor";
    private static volatile boolean reportedReflectionFailure;
    private static volatile boolean reportedInvalidCounterpart;

    private SecurityDoorGuard() {
    }

    /**
     * @return true when it is safe to let Metropolis' original destroy method run.
     */
    public static boolean canRunOriginal(Object world, Object pos, Object state) {
        try {
            Object halfProperty = findHalfProperty();
            if (!hasProperty(state, halfProperty)) return false;

            Object thisHalf = getValue(state, halfProperty);
            String halfName = String.valueOf(thisHalf).toLowerCase(Locale.ROOT);
            boolean currentUpper;
            if (halfName.contains("upper")) currentUpper = true;
            else if (halfName.contains("lower")) currentUpper = false;
            else return false;

            Object otherPos = invokePosition(pos, currentUpper ? "below" : "above");
            Object otherState = getBlockState(world, otherPos);
            if (otherState == null || !hasProperty(otherState, halfProperty)) {
                reportInvalidCounterpart();
                return false;
            }

            Object thisBlock = getBlock(state);
            Object otherBlock = getBlock(otherState);
            if (thisBlock == null || thisBlock != otherBlock) {
                reportInvalidCounterpart();
                return false;
            }

            Object otherHalf = getValue(otherState, halfProperty);
            String otherName = String.valueOf(otherHalf).toLowerCase(Locale.ROOT);
            boolean validOpposite = currentUpper ? otherName.contains("lower") : otherName.contains("upper");
            if (!validOpposite) reportInvalidCounterpart();
            return validOpposite;
        } catch (Throwable t) {
            if (!reportedReflectionFailure) {
                reportedReflectionFailure = true;
                System.err.println("[Metropolis Patch/SecurityDoor] Could not validate the counterpart state; cancelling the unsafe destroy callback.");
                t.printStackTrace();
            }
            return false;
        }
    }

    private static Object findHalfProperty() throws Exception {
        Class<?> door = Class.forName(SECURITY_DOOR);
        try {
            Field half = door.getField("HALF");
            return half.get(null);
        } catch (NoSuchFieldException ignored) {
            for (Field field : door.getFields()) {
                if (Modifier.isStatic(field.getModifiers())
                        && field.getType().getName().endsWith("EnumProperty")) {
                    return field.get(null);
                }
            }
            throw new NoSuchFieldException("BlockSecurityDoor.HALF");
        }
    }

    private static boolean hasProperty(Object state, Object property) throws Exception {
        for (Method method : state.getClass().getMethods()) {
            if (method.getParameterCount() == 1
                    && method.getReturnType() == boolean.class
                    && method.getParameterTypes()[0].isAssignableFrom(property.getClass())) {
                return (Boolean) method.invoke(state, property);
            }
        }

        // Fallback: StateDefinition/values implementations differ slightly across
        // mappings. A getValue probe is safe here because we catch exactly the
        // missing-property reflection exception before Metropolis can crash.
        try {
            getValue(state, property);
            return true;
        } catch (ReflectiveOperationException | IllegalArgumentException ignored) {
            return false;
        }
    }

    private static Object getValue(Object state, Object property) throws Exception {
        for (Method method : state.getClass().getMethods()) {
            if (method.getParameterCount() == 1
                    && method.getParameterTypes()[0].isAssignableFrom(property.getClass())
                    && Comparable.class.isAssignableFrom(method.getReturnType())) {
                return method.invoke(state, property);
            }
        }
        throw new NoSuchMethodException("BlockState#getValue(Property)");
    }

    private static Object getBlock(Object state) throws Exception {
        for (Method method : state.getClass().getMethods()) {
            if (method.getParameterCount() == 0
                    && method.getReturnType().getName().equals("net.minecraft.world.level.block.Block")) {
                return method.invoke(state);
            }
        }
        throw new NoSuchMethodException("BlockState#getBlock()");
    }

    private static Object getBlockState(Object world, Object pos) throws Exception {
        for (Method method : world.getClass().getMethods()) {
            if (method.getParameterCount() == 1
                    && method.getParameterTypes()[0].isAssignableFrom(pos.getClass())
                    && method.getReturnType().getName().equals("net.minecraft.world.level.block.state.BlockState")) {
                return method.invoke(world, pos);
            }
        }
        throw new NoSuchMethodException("LevelAccessor#getBlockState(BlockPos)");
    }

    private static Object invokePosition(Object pos, String direction) throws Exception {
        String[] names = direction.equals("above")
                ? new String[]{"above", "m_7494_", "method_10084"}
                : new String[]{"below", "m_7495_", "method_10074"};
        for (String name : names) {
            try {
                Method method = pos.getClass().getMethod(name);
                if (method.getParameterCount() == 0 && method.getReturnType().isAssignableFrom(pos.getClass())) {
                    return method.invoke(pos);
                }
            } catch (NoSuchMethodException ignored) {
            }
        }
        throw new NoSuchMethodException("BlockPos#" + direction + "()");
    }

    private static void reportInvalidCounterpart() {
        if (!reportedInvalidCounterpart) {
            reportedInvalidCounterpart = true;
            System.out.println("[Metropolis Patch/SecurityDoor] Ignored a transient/malformed door counterpart instead of reading HALF from an invalid state.");
        }
    }
}
