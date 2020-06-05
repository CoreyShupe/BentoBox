package world.bentobox.bentobox.managers.island;

import io.papermc.lib.PaperLib;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import world.bentobox.bentobox.BentoBox;
import world.bentobox.bentobox.util.Util;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * The default strategy for generating locations for island
 *
 * @author tastybento, leonardochaia
 * @since 1.8.0
 */
public class DefaultNewIslandLocationStrategy implements NewIslandLocationStrategy {

    /**
     * The amount times to tolerate island check returning blocks without known
     * island.
     */
    protected static final Integer MAX_UNOWNED_ISLANDS = 20;

    private final static Set<LocationHash> LOCATION_HASHES = new HashSet<>();
    protected BentoBox plugin = BentoBox.getInstance();

    protected enum Result {
        ISLAND_FOUND, BLOCKS_IN_AREA, FREE
    }

    @Override
    public Location getNextLocation(World world) {
        Location last = plugin.getIslands().getLast(world);
        if (last == null) {
            last = new Location(world,
                    (double) plugin.getIWM().getIslandXOffset(world) + plugin.getIWM().getIslandStartX(world),
                    plugin.getIWM().getIslandHeight(world),
                    (double) plugin.getIWM().getIslandZOffset(world) + plugin.getIWM().getIslandStartZ(world));
        }

        try {
            return getLocation(last).get(); // BLOCKING CALL, shouldn't generally use
        } catch (InterruptedException | ExecutionException e) {
            e.printStackTrace();
            return null;
        }
    }

    @Override public CompletableFuture<Location> getNextLocationAsync(World world) {
        Location last = plugin.getIslands().getLast(world);
        if (last == null) {
            last = new Location(world,
                    (double) plugin.getIWM().getIslandXOffset(world) + plugin.getIWM().getIslandStartX(world),
                    plugin.getIWM().getIslandHeight(world),
                    (double) plugin.getIWM().getIslandZOffset(world) + plugin.getIWM().getIslandStartZ(world));
        }

        return getLocation(last);
    }

    private CompletableFuture<Location> getLocation(Location location) {
        return CompletableFuture.supplyAsync(() -> location)
                .thenCompose(this::isIsland)
                .thenCompose(res -> getLocationRecur(res, location, 0, 0));
    }

    private CompletableFuture<Location> getLocationRecur(Result result, Location location, int nest, int found) {
        Location next;
        switch (result) {
            case ISLAND_FOUND:
                next = nextGridLocation(location.clone());
                return CompletableFuture.supplyAsync(() -> next).thenCompose(this::isIsland).thenCompose(res -> getLocationRecur(res, next, nest, found + 1));
            case BLOCKS_IN_AREA:
                if (nest >= MAX_UNOWNED_ISLANDS) {
                    // We could not find a free spot within the limit required. It's likely this
                    // world is not empty
                    plugin.logError("Could not find a free spot for islands! Is this world empty?");
                    plugin.logError("Blocks around center locations: " + nest + " max " + MAX_UNOWNED_ISLANDS);
                    plugin.logError("Known islands: " + found + " max unlimited.");
                    return CompletableFuture.supplyAsync(() -> null);
                }
                next = nextGridLocation(location.clone());
                return CompletableFuture.supplyAsync(() -> next).thenCompose(this::isIsland).thenCompose(res -> getLocationRecur(res, next, nest + 1, found));
            case FREE:
                return CompletableFuture.supplyAsync(() -> location);
            default:
                return null;
        }
    }

    /**
     * Checks if there is an island or blocks at this location
     *
     * @param location - the location
     * @return Result enum if island found, null if blocks found, false if nothing found
     */
    protected CompletableFuture<Result> isIsland(Location location) {
        // Quick check
        if (plugin.getIslands().getIslandAt(location).isPresent()) {
            return CompletableFuture.supplyAsync(() -> Result.ISLAND_FOUND);
        }

        World world = location.getWorld();

        // Check 4 corners
        int dist = plugin.getIWM().getIslandDistance(Objects.requireNonNull(location.getWorld()));
        Set<Location> locs = new HashSet<>();
        locs.add(location);
        locs.add(new Location(world, location.getX() - dist, 0, location.getZ() - dist));
        locs.add(new Location(world, location.getX() - dist, 0, location.getZ() + dist - 1));
        locs.add(new Location(world, location.getX() + dist - 1, 0, location.getZ() - dist));
        locs.add(new Location(world, location.getX() + dist - 1, 0, location.getZ() + dist - 1));

        boolean generated = false;
        for (Location l : locs) {
            if (plugin.getIslands().getIslandAt(l).isPresent() || plugin.getIslandDeletionManager().inDeletion(l)) {
                return CompletableFuture.supplyAsync(() -> Result.ISLAND_FOUND);
            }
            if (Util.isChunkGenerated(l)) generated = true;
        }
        // If chunk has not been generated yet, then it's not occupied
        if (!generated) {
            return CompletableFuture.supplyAsync(() -> getFinalResult(location, false));
        }
        // Block check
        CompletableFuture<Result> resultFuture = new CompletableFuture<>();
        PaperLib.getChunkAtAsync(location, true)
                .exceptionally(throwable -> {
                    resultFuture.completeExceptionally(throwable);
                    return null;
                })
                .thenAccept(chunk -> {
                    if (resultFuture.isCompletedExceptionally()) return;
                    Block block = chunk.getBlock(location.getBlockX(), location.getBlockY(), location.getBlockZ());
                    if (!plugin.getIWM().isUseOwnGenerator(chunk.getWorld()) && Arrays.stream(BlockFace.values()).anyMatch(bf ->
                            !block.getRelative(bf).isEmpty() && !block.getRelative(bf).getType().equals(Material.WATER))
                    ) {
                        // Block found
                        getFinalResult(location, true);
                        plugin.getIslands().createIsland(location);
                        resultFuture.complete(Result.BLOCKS_IN_AREA);
                        return;
                    }
                    resultFuture.complete(getFinalResult(location, false));
                });
        return resultFuture;
    }

    @Override public void cleanLocation(Location location) {
        getFinalResult(location, true);
    }

    // this NEEDS to be synchronized, only one thread at a time to synchronize the data race
    private synchronized Result getFinalResult(Location location, boolean remove) {
        LocationHash hash = new LocationHash(location);
        if (remove) {
            LOCATION_HASHES.remove(hash);
            return Result.BLOCKS_IN_AREA;
        }
        // this line prevents the data race of 2 islands with 1 location
        if (LOCATION_HASHES.contains(hash)) return Result.ISLAND_FOUND;
        LOCATION_HASHES.add(hash);
        return Result.FREE;
    }

    /**
     * Finds the next free island spot based off the last known island Uses
     * island_distance setting from the config file Builds up in a grid fashion
     *
     * @param lastIsland - last island location
     * @return Location of next free island
     */
    private Location nextGridLocation(final Location lastIsland) {
        int x = lastIsland.getBlockX();
        int z = lastIsland.getBlockZ();
        int d = plugin.getIWM().getIslandDistance(lastIsland.getWorld()) * 2;
        if (x < z) {
            if (-1 * x < z) {
                lastIsland.setX(lastIsland.getX() + d);
                return lastIsland;
            }
            lastIsland.setZ(lastIsland.getZ() + d);
            return lastIsland;
        }
        if (x > z) {
            if (-1 * x >= z) {
                lastIsland.setX(lastIsland.getX() - d);
                return lastIsland;
            }
            lastIsland.setZ(lastIsland.getZ() - d);
            return lastIsland;
        }
        if (x <= 0) {
            lastIsland.setZ(lastIsland.getZ() + d);
            return lastIsland;
        }
        lastIsland.setZ(lastIsland.getZ() - d);
        return lastIsland;
    }

    private static class LocationHash {
        private final UUID id;
        private final int x;
        private final int y;
        private final int z;

        private LocationHash(Location location) {
            this.id = Objects.requireNonNull(location.getWorld()).getUID();
            this.x = location.getBlockX();
            this.y = location.getBlockY();
            this.z = location.getBlockZ();
        }

        @Override public boolean equals(Object object) {
            if (this == object) return true;
            if (object == null || getClass() != object.getClass()) return false;
            LocationHash that = (LocationHash) object;
            return x == that.x &&
                    y == that.y &&
                    z == that.z &&
                    Objects.equals(id, that.id);
        }

        @Override public int hashCode() {
            return Objects.hash(id, x, y, z);
        }
    }
}
