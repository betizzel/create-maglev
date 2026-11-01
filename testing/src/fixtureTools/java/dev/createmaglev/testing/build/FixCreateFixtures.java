package dev.createmaglev.testing.build;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

/** Writes two test-mod resource overrides; never repackages or replaces the production Create JAR. */
public final class FixCreateFixtures {
    public static final String ATTRIBUTE_FIXTURE = "data/create/structures/gametest/items/attribute_filters.nbt";
    public static final String THRESHOLD_FIXTURE = "data/create/structures/gametest/fluids/threshold_switch.nbt";

    private FixCreateFixtures() {}

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[1]);
        Files.createDirectories(output);
        try (ZipFile create = new ZipFile(args[0])) {
            var metadataEntry = create.getEntry("fabric.mod.json");
            if (metadataEntry == null) throw new IllegalStateException("Packaged Create JAR has no Fabric metadata");
            var metadata = JsonParser.parseString(new String(create.getInputStream(metadataEntry).readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            require(metadata.get("id").getAsString().equals("create"), "Only the manifest-packaged Create artifact may supply fixture data");
            require(metadata.get("version").getAsString().equals(args[2]), "Packaged Create version does not match the pinned dependency");

            CompoundTag attributes = read(create, ATTRIBUTE_FIXTURE);
            CompoundTag tunnel = blockEntity(attributes, 3, 2, 2);
            int changed = 0;
            for (Tag entry : tunnel.getList("Filters", Tag.TAG_COMPOUND)) {
                CompoundTag side = (CompoundTag) entry;
                if (side.getInt("Side") != 2) continue;
                ListTag matched = side.getCompound("Filter").getCompound("tag").getList("MatchedAttributes", Tag.TAG_COMPOUND);
                for (Tag item : matched) {
                    CompoundTag attribute = (CompoundTag) item;
                    if (attribute.getString("attributeId").equals("create:in_tag")
                            && attribute.getString("tag").equals("forge:storage_blocks")) {
                        attribute.putString("tag", "c:storage_blocks");
                        changed++;
                    }
                }
            }
            require(changed == 1, "Expected exactly one Forge-era storage-block tag in the pinned attribute-filter fixture");
            write(output, ATTRIBUTE_FIXTURE, attributes);

            CompoundTag thresholds = read(create, THRESHOLD_FIXTURE);
            CompoundTag detector = blockEntity(thresholds, 1, 1, 1);
            require(detector.getString("id").equals("create:stockpile_switch"), "Expected Create's original fluid threshold switch");
            require(detector.getInt("OnAboveAmount") == 8000 && detector.getInt("CurrentMaxAmount") == 8000,
                    "Expected the pinned legacy eight-bucket threshold fixture");
            require(detector.getInt("OffBelowAmount") == 0, "The original exact-empty lower threshold must remain unchanged");
            // Fabric's Create structure processor converts 4,000-mB tank data to 324,000 droplets,
            // but cannot change the switch's Forge-authored 8,000 threshold.
            detector.putFloat("OnAboveAmount", 648000);
            detector.putInt("CurrentMaxAmount", 648000);
            write(output, THRESHOLD_FIXTURE, thresholds);
        }
        System.out.println("Generated two test-helper fixture data overrides; production Create classes and assertions remain untouched.");
    }

    private static CompoundTag read(ZipFile zip, String entry) throws Exception {
        var resource = zip.getEntry(entry);
        if (resource == null) throw new IllegalStateException("Missing pinned Create test fixture " + entry);
        try (InputStream stream = zip.getInputStream(resource)) {
            return NbtIo.readCompressed(stream);
        }
    }

    private static void write(Path output, String entry, CompoundTag data) throws Exception {
        Path destination = output.resolve(entry);
        Files.createDirectories(destination.getParent());
        try (OutputStream stream = Files.newOutputStream(destination)) {
            NbtIo.writeCompressed(data, stream);
        }
    }

    private static CompoundTag blockEntity(CompoundTag structure, int x, int y, int z) {
        for (Tag entry : structure.getList("blocks", Tag.TAG_COMPOUND)) {
            CompoundTag block = (CompoundTag) entry;
            ListTag pos = block.getList("pos", Tag.TAG_INT);
            if (pos.getInt(0) == x && pos.getInt(1) == y && pos.getInt(2) == z) return block.getCompound("nbt");
        }
        throw new IllegalStateException("Expected original fixture block entity at " + x + "," + y + "," + z);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
