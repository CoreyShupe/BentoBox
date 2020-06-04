package world.bentobox.bentobox.managers.island;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.concurrent.CompletableFuture;

/**
 * Determines the locations for new islands
 *
 * @author tastybento, leonardochaia
 * @since 1.8.0
 */
public interface NewIslandLocationStrategy {
    Location getNextLocation(World world);

    CompletableFuture<Location> getNextLocationAsync(World world);
}
