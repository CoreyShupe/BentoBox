package world.bentobox.bentobox.managers.island;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.util.Vector;
import world.bentobox.bentobox.BStats;
import world.bentobox.bentobox.BentoBox;
import world.bentobox.bentobox.api.addons.GameModeAddon;
import world.bentobox.bentobox.api.events.IslandBaseEvent;
import world.bentobox.bentobox.api.events.island.IslandEvent;
import world.bentobox.bentobox.api.events.island.IslandEvent.Reason;
import world.bentobox.bentobox.api.user.User;
import world.bentobox.bentobox.database.objects.Island;
import world.bentobox.bentobox.managers.BlueprintsManager;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Create and paste a new island
 *
 * @author tastybento
 */
public class NewIsland {
    private final static Set<UUID> IN_PROCESS = new HashSet<>();
    private BentoBox plugin;
    private Island island;
    private final User user;
    private final Reason reason;
    private final World world;
    private String name;
    private final boolean noPaste;
    private GameModeAddon addon;
    private final CompletableFuture<IOException> exceptionCompletableFuture;

    private NewIslandLocationStrategy locationStrategy;

    public NewIsland(Builder builder) {
        plugin = BentoBox.getInstance();
        this.user = builder.user2;
        this.reason = builder.reason2;
        this.world = builder.world2;
        this.name = builder.name2;
        this.noPaste = builder.noPaste2;
        this.addon = builder.addon2;
        this.locationStrategy = builder.locationStrategy2;

        if (this.locationStrategy == null) {
            this.locationStrategy = new DefaultNewIslandLocationStrategy();
        }

        exceptionCompletableFuture = newIsland(builder.oldIsland2);
    }

    public CompletableFuture<IOException> getExceptionCompletableFuture() {
        return exceptionCompletableFuture;
    }

    /**
     * @return the island that was created
     */
    public Island getIsland() {
        return island;
    }

    /**
     * Start building a new island
     *
     * @return New island builder object
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Build a new island for a player
     *
     * @author tastybento
     */
    public static class Builder {
        private Island oldIsland2;
        private User user2;
        private Reason reason2;
        private World world2;
        private String name2 = BlueprintsManager.DEFAULT_BUNDLE_NAME;
        private boolean noPaste2;
        private GameModeAddon addon2;
        private NewIslandLocationStrategy locationStrategy2;

        public Builder oldIsland(Island oldIsland) {
            this.oldIsland2 = oldIsland;
            this.world2 = oldIsland.getWorld();
            return this;
        }


        public Builder player(User player) {
            this.user2 = player;
            return this;
        }

        /**
         * Sets the reason
         *
         * @param reason reason, can only be {@link Reason#CREATE} or {@link Reason#RESET}.
         */
        public Builder reason(Reason reason) {
            if (!reason.equals(Reason.CREATE) && !reason.equals(Reason.RESET)) {
                throw new IllegalArgumentException("Reason must be CREATE or RESET.");
            }
            this.reason2 = reason;
            return this;
        }

        /**
         * Set the addon
         *
         * @param addon a game mode addon
         */
        public Builder addon(GameModeAddon addon) {
            this.addon2 = addon;
            this.world2 = addon.getOverWorld();
            return this;
        }

        /**
         * No blocks will be pasted
         */
        public Builder noPaste() {
            this.noPaste2 = true;
            return this;
        }

        /**
         * @param name - name of Blueprint bundle
         */
        public Builder name(String name) {
            this.name2 = name;
            return this;
        }

        /**
         * @param strategy - the location strategy to use
         * @since 1.8.0
         */
        public Builder locationStrategy(NewIslandLocationStrategy strategy) {
            this.locationStrategy2 = strategy;
            return this;
        }

        /**
         * @return Completable future of Island with possible exceptions.
         */
        public CompletableFuture<Island> build() {
            CompletableFuture<Island> islandCompletableFuture = new CompletableFuture<>();
            if (user2 != null) {
                NewIsland newIsland = new NewIsland(this);
                newIsland.getExceptionCompletableFuture().exceptionally(IOException::new)
                        .thenAccept(exception -> {
                            if (exception == null) {
                                islandCompletableFuture.complete(newIsland.getIsland());
                            } else {
                                islandCompletableFuture.completeExceptionally(exception);
                            }
                        });
            } else {
                islandCompletableFuture.completeExceptionally(new IOException("Insufficient parameters. Must have a user!"));
            }
            return islandCompletableFuture;
        }
    }

    /**
     * Makes an island.
     * <p>
     * This must be synchronous since it has a data race check. Only one thread should be able to access this.
     * This maintains synchronization with the basic accessing thread.
     *
     * @param oldIsland old island that is being replaced, if any
     * @return CompletableFuture with exception - if an island cannot be made. Message is the tag to show the user.
     */
    public synchronized CompletableFuture<IOException> newIsland(final Island oldIsland) {
        // this is an immediate error before even looking
        if (IN_PROCESS.contains(user.getUniqueId())) {
            plugin.logError("Failed to make island - data race found.");
            plugin.logError("User attempted to make an island whilst already creating an island.");
            return CompletableFuture.supplyAsync(() -> new IOException("commands.island.create.cannot-create-island"));
        }
        // this NEEDS to be synchronized to stop future islands from being made promptly
        IN_PROCESS.add(user.getUniqueId());
        CompletableFuture<IOException> exceptionFuture = new CompletableFuture<>(); // this is just so we can bubble up the exception
        Location next = null;
        if (plugin.getIslands().hasIsland(world, user)) {
            // Island exists, it just needs pasting
            island = plugin.getIslands().getIsland(world, user);
            if (island != null && island.isReserved()) {
                next = island.getCenter();
                // Clear the reservation
                island.setReserved(false);
            } else {
                // This should never happen unless we allow another way to paste over islands without reserving
                plugin.logError("New island for user " + user.getName() + " was not reserved!");
            }
        }
        // If the reservation fails, then we need to make a new island anyway
        if (next == null) {
            this.locationStrategy.getNextLocationAsync(world).whenComplete((location, throwable) -> { // when complete is sync
                if (throwable != null) {
                    exceptionFuture.completeExceptionally(throwable);
                    return;
                }
                if (location == null) {
                    plugin.logError("Failed to make island - no unoccupied spot found.");
                    plugin.logError("If the world was imported, try multiple times until all unowned islands are known.");
                    exceptionFuture.complete(new IOException("commands.island.create.cannot-create-island"));
                    return;
                }
                // Add to the grid
                island = plugin.getIslands().createIsland(location, user.getUniqueId());
                if (island == null) {
                    plugin.logError("Failed to make island! Island could not be added to the grid.");
                    exceptionFuture.complete(new IOException("commands.island.create.unable-create-island"));
                    return;
                }
                completeNewIsland(oldIsland, location);
                exceptionFuture.complete(null); // this is necessary before remove since it sets up cool-down
                // final step
                // this is the only remove call, and it should be synced up, and even if it fails to be read correctly
                // it will still act properly since we only care about the `add` functionality
                IN_PROCESS.remove(user.getUniqueId()); // this should stop them from being in progress
            });
        } else {
            completeNewIsland(oldIsland, next);
            exceptionFuture.complete(null);
        }
        return exceptionFuture;
    }

    private void completeNewIsland(Island oldIsland, Location next) {
        // Clear any old home locations (they should be clear, but just in case)
        plugin.getPlayers().clearHomeLocations(world, user.getUniqueId());
        // Set home location
        plugin.getPlayers().setHomeLocation(user, new Location(next.getWorld(), next.getX() + 0.5D, next.getY(), next.getZ() + 0.5D), 1);
        // Reset deaths
        if (plugin.getIWM().isDeathsResetOnNewIsland(world)) {
            plugin.getPlayers().setDeaths(world, user.getUniqueId(), 0);
        }
        // Check if owner has a different range permission than the island size
        island.setProtectionRange(user.getPermissionValue(plugin.getIWM().getAddon(island.getWorld())
                .map(GameModeAddon::getPermissionPrefix).orElse("") + "island.range", island.getProtectionRange()));
        // Save the player so that if the server crashes weird things won't happen
        plugin.getPlayers().save(user.getUniqueId());
        // Fire event
        IslandBaseEvent event = IslandEvent.builder()
                .involvedPlayer(user.getUniqueId())
                .reason(reason)
                .island(island)
                .location(island.getCenter())
                .blueprintBundle(plugin.getBlueprintsManager().getBlueprintBundles(addon).get(name))
                .oldIsland(oldIsland)
                .build();
        if (event.isCancelled()) {
            return;
        }
        // Get the new BlueprintBundle if it was changed
        switch (reason) {
            case CREATE:
                name = ((IslandEvent.IslandCreateEvent) event).getBlueprintBundle().getUniqueId();
                break;
            case RESET:
                name = ((IslandEvent.IslandResetEvent) event).getBlueprintBundle().getUniqueId();
                break;
            default:
                break;
        }

        // Task to run after creating the island
        Runnable task = () -> {
            // Set initial spawn point if one exists
            if (island.getSpawnPoint(Environment.NORMAL) != null) {
                plugin.getPlayers().setHomeLocation(user, island.getSpawnPoint(Environment.NORMAL), 1);
            }
            // Stop the player from falling or moving if they are
            if (user.isOnline()) {
                if (reason.equals(Reason.RESET) || (reason.equals(Reason.CREATE) && plugin.getIWM().isTeleportPlayerToIslandUponIslandCreation(world))) {
                    user.getPlayer().setVelocity(new Vector(0, 0, 0));
                    user.getPlayer().setFallDistance(0F);
                    // Teleport player after this island is built
                    plugin.getIslands().homeTeleportAsync(world, user.getPlayer(), true).thenRun(() -> tidyUp(oldIsland));
                    return;
                } else {
                    // let's send him a message so that he knows he can teleport to his island!
                    user.sendMessage("commands.island.create.you-can-teleport-to-your-island");
                }
            } else {
                // Remove the player again to completely clear the data
                User.removePlayer(user.getPlayer());
            }
            tidyUp(oldIsland);
        };
        if (noPaste) {
            Bukkit.getScheduler().runTask(plugin, task);
        } else {
            // Create islands
            plugin.getBlueprintsManager().paste(addon, island, name, task);
        }
        // Set default settings
        island.setFlagsDefaults();
        plugin.getMetrics().ifPresent(BStats::increaseIslandsCreatedCount);
        // Save island
        plugin.getIslands().save(island);
    }

    private void tidyUp(Island oldIsland) {
        // Delete old island
        if (oldIsland != null && !plugin.getSettings().isKeepPreviousIslandOnReset()) {
            // Delete the old island
            plugin.getIslands().deleteIsland(oldIsland, true, user.getUniqueId());
        }

        // Fire exit event
        Reason reasonDone = Reason.CREATED;
        switch (reason) {
            case CREATE:
                reasonDone = Reason.CREATED;
                break;
            case RESET:
                reasonDone = Reason.RESETTED;
                break;
            default:
                break;
        }
        IslandEvent.builder()
                .involvedPlayer(user.getUniqueId())
                .reason(reasonDone)
                .island(island)
                .location(island.getCenter())
                .oldIsland(oldIsland)
                .build();
        // this tidys up the strategy to remove this as a valid location
    }
}
