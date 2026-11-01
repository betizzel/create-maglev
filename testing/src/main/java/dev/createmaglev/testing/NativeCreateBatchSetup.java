package dev.createmaglev.testing;

import com.simibubi.create.Create;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.server.level.ServerLevel;

/** Makes Create's native 80%-success sequenced-assembly test reproducible without altering its recipe. */
public final class NativeCreateBatchSetup {
    public NativeCreateBatchSetup() {}

    @BeforeBatch(batch = "defaultBatch")
    public static void seedNativeRecipeRolls(ServerLevel level) {
        Create.RANDOM.setSeed(0L);
    }
}
