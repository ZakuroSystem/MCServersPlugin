package jp.mcservers.connector;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

final class AnalyticsRuntime {
    interface EventPoster {
        void postEventsBatch(String body) throws Exception;
    }

    private final JavaPlugin plugin;
    private final String instanceId;
    private final EventPoster poster;
    private final EventBuffer buffer;
    private final AtomicBoolean flushInFlight = new AtomicBoolean(false);
    private BukkitTask flushTask;

    AnalyticsRuntime(JavaPlugin plugin, String instanceId, String hashSalt, EventPoster poster) {
        this.plugin = plugin;
        this.instanceId = instanceId;
        this.poster = poster;
        this.buffer = new EventBuffer(plugin.getConfig().getInt("analytics-max-buffer-size", 500));

        AreaClassifier areaClassifier = new AreaClassifier(plugin);
        Set<String> allowedCommands = new HashSet<String>(plugin.getConfig().getStringList("analytics-allowed-commands"));
        plugin.getServer().getPluginManager().registerEvents(
                new EventCollector(buffer, areaClassifier, hashSalt, allowedCommands),
                plugin
        );
    }

    void start() {
        if (flushTask != null) return;
        long intervalTicks = Math.max(10, plugin.getConfig().getInt("analytics-flush-interval-seconds", 30)) * 20L;
        flushTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, new Runnable() {
            public void run() {
                flushSafely(false);
            }
        }, intervalTicks, intervalTicks);
    }

    void stop() {
        if (flushTask != null) {
            flushTask.cancel();
            flushTask = null;
        }
        flushSafely(true);
    }

    int pendingEvents() {
        return buffer.size();
    }

    void recordServerSnapshot(int currentPlayers, int maxPlayers, String serverVersion) {
        long now = System.currentTimeMillis();
        String metadata = "{"
                + "\"current_players\":" + Math.max(0, currentPlayers) + ","
                + "\"max_players\":" + Math.max(0, maxPlayers) + ","
                + "\"server_version\":\"" + PluginEvent.jsonEscape(serverVersion) + "\""
                + "}";
        buffer.offer(new PluginEvent(
                "server_snapshot",
                "snapshot:" + now,
                null,
                null,
                now,
                null,
                null,
                "admin",
                metadata
        ));
    }

    private void flushSafely(boolean finalFlush) {
        if (!flushInFlight.compareAndSet(false, true)) return;
        List<PluginEvent> events = null;
        try {
            int batchSize = Math.max(1, Math.min(plugin.getConfig().getInt("analytics-batch-size", 100), 100));
            events = buffer.drain(batchSize);
            if (events.isEmpty()) return;
            String body = EventBuffer.toBatchJson(instanceId, events, buffer.getAndResetDroppedCount());
            poster.postEventsBatch(body);
        } catch (Exception ex) {
            if (events != null && !finalFlush) {
                buffer.prepend(events);
            }
            plugin.getLogger().log(Level.WARNING, "Failed to flush MCServers analytics events: " + ex.getMessage());
        } finally {
            flushInFlight.set(false);
        }
    }
}
