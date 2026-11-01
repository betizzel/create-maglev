package dev.createmaglev.mixin;

import com.simibubi.create.content.trains.entity.Navigation;
import com.simibubi.create.content.trains.graph.TrackNode;
import java.util.List;
import net.createmod.catnip.data.Couple;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = Navigation.class, remap = false)
public interface NavigationAccessor {
    @Accessor("currentPath")
    List<Couple<TrackNode>> maglev$path();
}
