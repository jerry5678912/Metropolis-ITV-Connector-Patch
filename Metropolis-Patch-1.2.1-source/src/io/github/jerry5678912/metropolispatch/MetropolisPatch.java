package io.github.jerry5678912.metropolispatch;

import net.minecraftforge.fml.common.Mod;

import java.lang.reflect.Method;

@Mod("metropolis_patch")
public final class MetropolisPatch {
    public static final String VERSION = "1.2.1";

    public MetropolisPatch() {
        rejectLegacyPatch();
        System.out.println("[Metropolis Patch] v" + VERSION + " loaded: ITV terrain-lighting compatibility + Security Door crash guard enabled.");
    }

    private static void rejectLegacyPatch() {
        try {
            Class<?> modListClass = Class.forName("net.minecraftforge.fml.ModList");
            Method get = modListClass.getMethod("get");
            Object modList = get.invoke(null);
            Method isLoaded = modListClass.getMethod("isLoaded", String.class);
            if (Boolean.TRUE.equals(isLoaded.invoke(modList, "metropolis_itv_patch"))) {
                throw new IllegalStateException(
                        "The legacy Metropolis ITV Connector Patch is still installed. Remove it before using Metropolis Patch.");
            }
        } catch (ClassNotFoundException ignored) {
            // Forge is a mandatory dependency; this is only defensive.
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not verify legacy Metropolis patch state", e);
        }
    }
}
