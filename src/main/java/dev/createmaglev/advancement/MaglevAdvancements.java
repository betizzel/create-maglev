package dev.createmaglev.advancement;

import com.google.gson.JsonObject;
import dev.createmaglev.content.MaglevContent;
import net.fabricmc.fabric.api.object.builder.v1.advancement.CriterionRegistry;
import net.minecraft.advancements.critereon.AbstractCriterionTriggerInstance;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.DeserializationContext;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

public final class MaglevAdvancements {
    private static final ResourceLocation LEVITATING_RIDE_ID = new ResourceLocation(MaglevContent.MOD_ID,
        "levitating_ride");
    private static LevitatingRideTrigger levitatingRide;

    private MaglevAdvancements() {}

    public static synchronized void register() {
        if (levitatingRide != null)
            return;
        levitatingRide = CriterionRegistry.register(new LevitatingRideTrigger());
    }

    public static void rodeLevitatingMaglev(ServerPlayer player) {
        LevitatingRideTrigger trigger = levitatingRide;
        if (trigger == null)
            throw new IllegalStateException("Maglev advancements are not registered");
        trigger.triggerFor(player);
    }

    private static final class LevitatingRideTrigger extends SimpleCriterionTrigger<LevitatingRideTrigger.Instance> {
        @Override
        protected Instance createInstance(JsonObject json, ContextAwarePredicate player,
                DeserializationContext context) {
            return new Instance(player);
        }

        @Override
        public ResourceLocation getId() {
            return LEVITATING_RIDE_ID;
        }

        private void triggerFor(ServerPlayer player) {
            trigger(player, instance -> true);
        }

        private static final class Instance extends AbstractCriterionTriggerInstance {
            private Instance(ContextAwarePredicate player) {
                super(LEVITATING_RIDE_ID, player);
            }
        }
    }
}
