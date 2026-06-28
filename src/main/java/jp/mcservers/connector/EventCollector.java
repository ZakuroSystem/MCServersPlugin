package jp.mcservers.connector;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class EventCollector implements Listener {
    private final EventBuffer buffer;
    private final AreaClassifier areaClassifier;
    private final String hashSalt;
    private final Set<String> allowedCommands;
    private final Map<UUID, SessionInfo> sessions = new HashMap<UUID, SessionInfo>();

    EventCollector(EventBuffer buffer, AreaClassifier areaClassifier, String hashSalt, Set<String> allowedCommands) {
        this.buffer = buffer;
        this.areaClassifier = areaClassifier;
        this.hashSalt = hashSalt == null ? "" : hashSalt;
        this.allowedCommands = normalizeCommands(allowedCommands);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        long now = System.currentTimeMillis();
        String playerHash = playerHash(player.getUniqueId());
        String sessionId = "sess_" + playerHash.substring(0, 16) + "_" + now;
        sessions.put(player.getUniqueId(), new SessionInfo(sessionId, now));

        Location location = player.getLocation();
        buffer.offer(new PluginEvent(
                "player_session_started",
                "join:" + playerHash + ":" + now,
                playerHash,
                sessionId,
                now,
                areaClassifier.worldBucket(location),
                areaClassifier.locationBucket(location),
                "admin",
                "{\"is_first_seen_local\":false}"
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        long now = System.currentTimeMillis();
        String playerHash = playerHash(player.getUniqueId());
        SessionInfo session = sessions.remove(player.getUniqueId());
        String sessionId = session == null ? "sess_" + playerHash.substring(0, 16) + "_" + now : session.sessionId;
        long durationSeconds = session == null ? 0L : Math.max(0L, (now - session.startedAtMs) / 1000L);

        Location location = player.getLocation();
        buffer.offer(new PluginEvent(
                "player_session_ended",
                "quit:" + playerHash + ":" + now,
                playerHash,
                sessionId,
                now,
                areaClassifier.worldBucket(location),
                areaClassifier.locationBucket(location),
                "admin",
                "{\"duration_seconds\":" + durationSeconds + "}"
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String command = commandName(event.getMessage());
        if (command.length() == 0 || !allowedCommands.contains(command)) return;

        Player player = event.getPlayer();
        long now = System.currentTimeMillis();
        String playerHash = playerHash(player.getUniqueId());
        SessionInfo session = sessions.get(player.getUniqueId());
        String sessionId = session == null ? "sess_" + playerHash.substring(0, 16) + "_" + now : session.sessionId;
        Location location = player.getLocation();

        buffer.offer(new PluginEvent(
                "key_command_used",
                "cmd:" + command + ":" + playerHash + ":" + now,
                playerHash,
                sessionId,
                now,
                areaClassifier.worldBucket(location),
                areaClassifier.locationBucket(location),
                "admin",
                "{\"command\":\"" + PluginEvent.jsonEscape(command) + "\"}"
        ));
    }

    private static Set<String> normalizeCommands(Set<String> commands) {
        Set<String> normalized = new HashSet<String>();
        if (commands == null) return normalized;
        for (String command : commands) {
            String name = normalizeCommand(command);
            if (name.length() > 0) normalized.add(name);
        }
        return normalized;
    }

    private static String commandName(String message) {
        if (message == null) return "";
        String text = message.trim();
        if (text.startsWith("/")) text = text.substring(1);
        int space = text.indexOf(' ');
        if (space >= 0) text = text.substring(0, space);
        return normalizeCommand(text);
    }

    private static String normalizeCommand(String command) {
        if (command == null) return "";
        String text = command.trim().toLowerCase(Locale.ROOT);
        if (text.startsWith("/")) text = text.substring(1);
        if (!text.matches("[a-z0-9:_-]{1,40}")) return "";
        return text;
    }

    private String playerHash(UUID uuid) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((hashSalt + ":" + String.valueOf(uuid)).getBytes("UTF-8"));
            char[] hex = new char[bytes.length * 2];
            char[] alphabet = "0123456789abcdef".toCharArray();
            for (int i = 0; i < bytes.length; i++) {
                int v = bytes[i] & 0xff;
                hex[i * 2] = alphabet[v >>> 4];
                hex[i * 2 + 1] = alphabet[v & 0x0f];
            }
            return new String(hex);
        } catch (Exception ex) {
            return String.valueOf(uuid).replace("-", "");
        }
    }

    private static final class SessionInfo {
        final String sessionId;
        final long startedAtMs;

        SessionInfo(String sessionId, long startedAtMs) {
            this.sessionId = sessionId;
            this.startedAtMs = startedAtMs;
        }
    }
}
