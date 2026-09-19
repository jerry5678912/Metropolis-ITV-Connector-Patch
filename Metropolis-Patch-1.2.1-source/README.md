# Metropolis Patch 1.2.1

Minecraft 1.20.1 Forge/Sinytra Connector compatibility patch for Metropolis 0.6.0-mtr4-beta.

## Fixes

### ITV monitor rendering / lighting
The old Metropolis ITV renderer manually tessellates a block model from a block-entity renderer. The previous compatibility patch replaced that crash path with a visible BER, but that still left the monitor outside the normal terrain model pipeline.

This release instead:

- reports the ITV as a normal MODEL render shape;
- wraps its baked model in the normal `BlockModelShaper` cache;
- rotates baked quad positions and packed normals for all 16 rotation states;
- exposes transformed geometry as general/unculled quads so 22.5-degree rotations do not use false cardinal cull metadata;
- suppresses the original ITV BER to avoid duplicate geometry and the old Connector crash path.

If quad transformation unexpectedly fails, the wrapper returns the underlying model rather than crashing the client.

### Security Door crash guard
Metropolis' `BlockSecurityDoor.destroy` callback assumes the opposite half still exists and directly reads its `HALF` property. During multiplayer block updates the counterpart can already be `minecraft:air`, which throws `IllegalArgumentException`.

The patch validates that the counterpart:

- still has the `HALF` property;
- is the same door block;
- has the opposite half value;

before allowing the original Metropolis destroy callback to execute. Invalid/transient counterparts cancel only that unsafe callback.

## Installation

Remove `Metropolis-ITV-Connector-Patch-1.1.0.jar` and install `Metropolis-Patch-1.2.1.jar`.
Do not install both. The new mod checks for the legacy mod ID and stops with an explicit message if both are present.


## 1.2.1 startup fix
Recompiled all mixin classes with the correct Sponge Mixin `@Mixin` CLASS retention metadata. Version 1.2.0 encoded `@Mixin` as a runtime-visible annotation because its compile-only stub had the wrong retention, causing Mixin 0.8.5 to report that `SecurityDoorMixin` was missing `@Mixin` during PREPARE.
