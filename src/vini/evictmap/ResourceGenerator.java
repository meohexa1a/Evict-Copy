package vini.evictmap;

import arc.util.noise.Simplex;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.world.Block;
import mindustry.world.Tile;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Evict-style resource generator: global coherent noise fields.
 *
 * The first prototype grew a fixed number of blobs inside each individual
 * room. That looked too artificial and made many rooms visibly poor.
 *
 * This version follows the same basic approach as Mindustry's editor Ore
 * filter: evaluate coherent noise for each tile over the whole map, compare
 * it against a threshold and paint matching tiles. Rooms only act as masks;
 * they no longer generate isolated resource blobs independently.
 */
final class ResourceGenerator {

    // ---------------------------------------------------------------------
    // Independent seed stream
    // ---------------------------------------------------------------------

    private static final long RESOURCE_SEED_XOR = 0x4f52452d45564943L;

    // ---------------------------------------------------------------------
    // Placement masks
    // ---------------------------------------------------------------------

    /**
     * Ores may approach the wall edges and may cross visual room boundaries,
     * but they are only placed on open Dark Sand floor.
     */
    private static final int ORE_MAX_RADIUS = 39;
    private static final int ORE_MAX_RADIUS_SQUARED =
        ORE_MAX_RADIUS * ORE_MAX_RADIUS;

    /**
     * Do not place ore directly below the centered Nucleus footprint.
     */
    private static final int ORE_CORE_SAFE_RADIUS = 4;
    private static final int ORE_CORE_SAFE_RADIUS_SQUARED =
        ORE_CORE_SAFE_RADIUS * ORE_CORE_SAFE_RADIUS;

    /**
     * Water and tar stay away from narrow connections so they cannot block
     * access between rooms. They remain inside the stable inner room area.
     */
    private static final int LIQUID_MAX_RADIUS = 31;
    private static final int LIQUID_MAX_RADIUS_SQUARED =
        LIQUID_MAX_RADIUS * LIQUID_MAX_RADIUS;

    private static final int LIQUID_CORE_SAFE_RADIUS = 9;
    private static final int LIQUID_CORE_SAFE_RADIUS_SQUARED =
        LIQUID_CORE_SAFE_RADIUS * LIQUID_CORE_SAFE_RADIUS;

    // ---------------------------------------------------------------------
    // Global noise presets
    // ---------------------------------------------------------------------

    /**
     * Approximate first test values.
     *
     * Scale controls average feature size.
     * Threshold controls how much of the map survives.
     * Octaves and falloff add the irregular smaller details visible in Evict.
     *
     * Ores use independent fields and are applied common -> rare so the rare
     * ores may overwrite a few common tiles where fields overlap.
     */
    private static final NoisePreset[] ORE_PRESETS = new NoisePreset[]{
        // Larger scale -> wider patches. Higher threshold -> fewer patches.
        new NoisePreset(Blocks.oreCopper,   101, 34.0f, 0.815f, 2f, 0.30f,  0.00f),
        new NoisePreset(Blocks.oreLead,     211, 33.0f, 0.823f, 2f, 0.30f,  0.00f),
        new NoisePreset(Blocks.oreCoal,     307, 30.0f, 0.838f, 2f, 0.32f,  0.00f),
        new NoisePreset(Blocks.oreTitanium, 401, 27.0f, 0.858f, 2f, 0.30f,  0.00f),
        new NoisePreset(Blocks.oreThorium,  503, 27.5f, 0.866f, 2f, 0.30f,  0.00f)
    };

    /**
     * Water should appear less often but form wider patches.
     * Tar/oil is intentionally much rarer than water.
     */
    private static final NoisePreset WATER_PRESET =
        // Water was far too common in 0.4.1: make it much rarer but wider.
        new NoisePreset(Blocks.darksandWater, 701, 24.0f, 0.900f, 2f, 0.38f, 0.00f);

    private static final NoisePreset TAR_PRESET =
        // Tar/oil should be rarer than water and appear in occasional larger patches.
        new NoisePreset(Blocks.tar, 809, 21.0f, 0.945f, 2f, 0.35f, 0.00f);

    private ResourceGenerator() {
    }

    static Summary generate(long mapSeed, List<HexCenter> centers) {
        int seed = foldSeed(mapSeed ^ RESOURCE_SEED_XOR);
        MutableSummary summary = new MutableSummary();

        /**
         * Floors first, overlays second:
         * - water and tar replace Dark Sand floor
         * - ore is placed afterward only where a surface still exists
         */
        generateFloorNoise(seed, centers, WATER_PRESET, summary, true);
        generateFloorNoise(seed, centers, TAR_PRESET, summary, false);

        for (NoisePreset preset : ORE_PRESETS) {
            generateOreNoise(seed, centers, preset, summary);
        }

        return summary.freeze();
    }

    static String presetDescription() {
        return "global coherent-noise test preset for ores, water and tar";
    }

    private static void generateFloorNoise(
        int seed,
        List<HexCenter> centers,
        NoisePreset preset,
        MutableSummary summary,
        boolean water
    ) {
        int width = Vars.world.width();
        int height = Vars.world.height();

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                Tile tile = Vars.world.tile(x, y);

                if (
                    tile == null
                        || tile.block() != Blocks.air
                        || tile.floor() != Blocks.darksand
                        || !insideLiquidMask(x, y, centers)
                ) {
                    continue;
                }

                if (sample(seed, preset, x, y) > preset.threshold) {
                    Tile.setFloor(tile, preset.block, Blocks.air);

                    if (water) {
                        summary.waterTiles++;
                    } else {
                        summary.tarTiles++;
                    }
                }
            }
        }
    }

    private static void generateOreNoise(
        int seed,
        List<HexCenter> centers,
        NoisePreset preset,
        MutableSummary summary
    ) {
        int width = Vars.world.width();
        int height = Vars.world.height();

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                Tile tile = Vars.world.tile(x, y);

                if (
                    tile == null
                        || tile.block() != Blocks.air
                        || tile.floor() != Blocks.darksand
                        || !insideOreMask(x, y, centers)
                ) {
                    continue;
                }

                if (sample(seed, preset, x, y) > preset.threshold) {
                    tile.setOverlay(preset.block);
                    summary.addOre(preset.block);
                }
            }
        }
    }

    private static boolean insideOreMask(
        int x,
        int y,
        List<HexCenter> centers
    ) {
        int closestDistanceSquared = closestCenterDistanceSquared(x, y, centers);

        return closestDistanceSquared >= ORE_CORE_SAFE_RADIUS_SQUARED
            && closestDistanceSquared <= ORE_MAX_RADIUS_SQUARED;
    }

    private static boolean insideLiquidMask(
        int x,
        int y,
        List<HexCenter> centers
    ) {
        int closestDistanceSquared = closestCenterDistanceSquared(x, y, centers);

        return closestDistanceSquared >= LIQUID_CORE_SAFE_RADIUS_SQUARED
            && closestDistanceSquared <= LIQUID_MAX_RADIUS_SQUARED;
    }

    private static int closestCenterDistanceSquared(
        int x,
        int y,
        List<HexCenter> centers
    ) {
        int closest = Integer.MAX_VALUE;

        for (HexCenter center : centers) {
            int deltaX = x - center.x;
            int deltaY = y - center.y;
            int distanceSquared = deltaX * deltaX + deltaY * deltaY;

            if (distanceSquared < closest) {
                closest = distanceSquared;
            }
        }

        return closest;
    }

    private static float sample(
        int baseSeed,
        NoisePreset preset,
        int x,
        int y
    ) {
        /**
         * Mirrors Mindustry's editor OreFilter approach:
         * noise(x, y + x * tilt, scale, 1, octaves, falloff)
         */
        return Simplex.noise2d(
            baseSeed + preset.seedOffset,
            preset.octaves,
            preset.falloff,
            1f / preset.scale,
            x + 10f,
            y + x * preset.tilt + 10f
        );
    }

    private static int foldSeed(long seed) {
        return (int)(seed ^ (seed >>> 32));
    }

    record HexCenter(int x, int y) {
    }

    record Summary(
        int waterTiles,
        int tarTiles,
        int copperTiles,
        int leadTiles,
        int coalTiles,
        int titaniumTiles,
        int thoriumTiles
    ) {
        String compact() {
            return "water=" + waterTiles
                + ", tar=" + tarTiles
                + ", copper=" + copperTiles
                + ", lead=" + leadTiles
                + ", coal=" + coalTiles
                + ", titanium=" + titaniumTiles
                + ", thorium=" + thoriumTiles;
        }
    }

    private record NoisePreset(
        Block block,
        int seedOffset,
        float scale,
        float threshold,
        float octaves,
        float falloff,
        float tilt
    ) {
    }

    private static final class MutableSummary {
        private int waterTiles;
        private int tarTiles;
        private int copperTiles;
        private int leadTiles;
        private int coalTiles;
        private int titaniumTiles;
        private int thoriumTiles;

        private void addOre(Block overlay) {
            if (overlay == Blocks.oreCopper) {
                copperTiles++;
            } else if (overlay == Blocks.oreLead) {
                leadTiles++;
            } else if (overlay == Blocks.oreCoal) {
                coalTiles++;
            } else if (overlay == Blocks.oreTitanium) {
                titaniumTiles++;
            } else if (overlay == Blocks.oreThorium) {
                thoriumTiles++;
            }
        }

        private Summary freeze() {
            return new Summary(
                waterTiles,
                tarTiles,
                copperTiles,
                leadTiles,
                coalTiles,
                titaniumTiles,
                thoriumTiles
            );
        }
    }
}
