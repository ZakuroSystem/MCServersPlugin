package jp.mcservers.connector;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MCServersConnectorPlugin extends JavaPlugin {
    private static final Pattern OWNERSHIP_SERVER_ID_PATTERN = Pattern.compile("\"server_id\"\\s*:\\s*(\\d+)");
    private static final Pattern OWNERSHIP_SECRET_PATTERN = Pattern.compile("\"server_secret\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern REWARD_PATTERN = Pattern.compile("\\{[^{}]*\"id\"\\s*:\\s*(\\d+)[^{}]*\"player\"\\s*:\\s*\"([^\"]+)\"[^{}]*\\}");
    private static final Pattern PLAYER_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]{1,16}$");
    private static final char[] HEX_ALPHABET = "0123456789abcdef".toCharArray();

    private String baseUrl;
    private int serverId;
    private String serverSecret;
    private String ownershipToken;
    private String instanceId;
    private String registrationKey;
    private boolean autoRegister;
    private int publicPort;
    private String configuredServerName;
    private String configuredShortDescription;
    private String configuredTags;
    private int claimBatchSize;
    private int connectTimeoutMs;
    private int readTimeoutMs;
    private int ownershipVerifyIntervalSeconds;
    private List<String> voteCommands;
    private BukkitTask heartbeatTask;
    private BukkitTask voteTask;
    private BukkitTask ownershipTask;
    private final AtomicBoolean heartbeatInFlight = new AtomicBoolean(false);
    private final AtomicBoolean votePollInFlight = new AtomicBoolean(false);
    private final AtomicBoolean ownershipVerifyInFlight = new AtomicBoolean(false);

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadLocalConfig();

        if (hasServerCredentials()) {
            startPluginTasks();
        }
        if (shouldRunOwnershipTask()) {
            startOwnershipTask();
        }
        if (hasServerCredentials() && hasOwnershipToken()) {
            getLogger().info("MCServersConnector is using configured server credentials. ownership-token is ignored until credentials are cleared.");
        }
        if (!hasServerCredentials() && !hasOwnershipToken()) {
            getLogger().warning("MCServersConnector is not configured. Set ownership-token, or server-id and server-secret in config.yml.");
        }
        getLogger().info("MCServersConnector enabled.");
    }

    @Override
    public void onDisable() {
        stopTasks();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("mcservers")) return false;

        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            sender.sendMessage("[MCServers] server-id=" + serverId
                    + ", credentials=" + (hasServerCredentials() ? "configured" : "not-configured")
                    + ", ownership-token=" + (hasOwnershipToken() ? "configured" : "empty")
                    + ", instance-id=" + instanceId);
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("mcservers.admin")) {
                sender.sendMessage("[MCServers] You do not have permission.");
                return true;
            }
            stopTasks();
            reloadConfig();
            reloadLocalConfig();
            if (hasServerCredentials()) {
                startPluginTasks();
            }
            if (shouldRunOwnershipTask()) {
                startOwnershipTask();
                Bukkit.getScheduler().runTaskAsynchronously(this, new Runnable() {
                    public void run() { verifyOwnershipSafely(); }
                });
            }
            sender.sendMessage("[MCServers] Reloaded config.yml. credentials="
                    + (hasServerCredentials() ? "configured" : "not-configured")
                    + ", ownership-token=" + (hasOwnershipToken() ? "configured" : "empty"));
            return true;
        }

        sender.sendMessage("[MCServers] Usage: /" + label + " reload");
        sender.sendMessage("[MCServers] Usage: /" + label + " status");
        return true;
    }

    private void stopTasks() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel();
            heartbeatTask = null;
        }
        if (voteTask != null) {
            voteTask.cancel();
            voteTask = null;
        }
        if (ownershipTask != null) {
            ownershipTask.cancel();
            ownershipTask = null;
        }
    }

    private void reloadLocalConfig() {
        baseUrl = getConfig().getString("base-url", "https://mcservers.jp");
        if (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        validateBaseUrl(baseUrl);
        serverId = getConfig().getInt("server-id", 0);
        serverSecret = getConfig().getString("server-secret", "CHANGE_ME");
        ownershipToken = getConfig().getString("ownership-token", "");
        instanceId = getConfig().getString("instance-id", "");
        if (instanceId == null || instanceId.trim().isEmpty()) {
            instanceId = UUID.randomUUID().toString();
            getConfig().set("instance-id", instanceId);
            saveConfig();
        }
        claimBatchSize = Math.max(1, Math.min(getConfig().getInt("claim-batch-size", 20), 50));
        connectTimeoutMs = Math.max(1, getConfig().getInt("connect-timeout-seconds", 10)) * 1000;
        readTimeoutMs = Math.max(1, getConfig().getInt("request-timeout-seconds", 15)) * 1000;
        ownershipVerifyIntervalSeconds = Math.max(15, getConfig().getInt("ownership-verify-interval-seconds", 60));
        voteCommands = new ArrayList<String>(getConfig().getStringList("vote-commands"));
    }

    private boolean hasServerCredentials() {
        return serverId > 0 && serverSecret != null && !serverSecret.trim().isEmpty() && !"CHANGE_ME".equals(serverSecret);
    }

    private boolean hasOwnershipToken() {
        return ownershipToken != null && ownershipToken.trim().length() >= 24;
    }

    private boolean shouldRunOwnershipTask() {
        return !hasServerCredentials() && (hasOwnershipToken() || autoRegister);
    }

    private void startPluginTasks() {
        if (heartbeatTask != null || voteTask != null) return;
        long heartbeatTicks = Math.max(10, getConfig().getInt("heartbeat-interval-seconds", 60)) * 20L;
        long voteTicks = Math.max(10, getConfig().getInt("vote-poll-interval-seconds", 30)) * 20L;
        heartbeatTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, new Runnable() {
            public void run() { sendHeartbeatSafely(); }
        }, 20L, heartbeatTicks);
        voteTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, new Runnable() {
            public void run() { pollVoteRewardsSafely(); }
        }, 60L, voteTicks);
    }

    private void startOwnershipTask() {
        if (ownershipTask != null) return;
        long ownershipTicks = ownershipVerifyIntervalSeconds * 20L;
        ownershipTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, new Runnable() {
            public void run() { verifyOwnershipSafely(); }
        }, 20L, ownershipTicks);
    }

    private void verifyOwnershipSafely() {
        if (!ownershipVerifyInFlight.compareAndSet(false, true)) return;
        try {
            ServerSnapshot snapshot = captureServerSnapshot();
            String body = "{"
                    + "\"ownership_token\":\"" + jsonEscape(ownershipToken) + "\","
                    + "\"current_players\":" + snapshot.currentPlayers + ","
                    + "\"max_players\":" + snapshot.maxPlayers + ","
                    + "\"server_version\":\"" + jsonEscape(snapshot.serverVersion) + "\","
                    + "\"instance_id\":\"" + jsonEscape(instanceId) + "\","
                    + "\"timestamp_ms\":" + System.currentTimeMillis()
                    + "}";
            String response = postUnsignedJson("/api/plugin/v1/ownership/verify", body);
            Integer issuedServerId = matchInt(response, OWNERSHIP_SERVER_ID_PATTERN);
            String issuedSecret = matchString(response, OWNERSHIP_SECRET_PATTERN);
            if (issuedServerId != null && issuedSecret != null && issuedSecret.length() > 16) {
                getConfig().set("server-id", issuedServerId);
                getConfig().set("server-secret", issuedSecret);
                getConfig().set("ownership-token", "");
                saveConfig();
                reloadLocalConfig();
                getLogger().info("MCServers ownership verified and server credentials received. server-id=" + issuedServerId);
                if (ownershipTask != null) {
                    ownershipTask.cancel();
                    ownershipTask = null;
                }
                startPluginTasks();
            } else {
                getLogger().info("MCServers ownership verification signal sent. Waiting for approval.");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            getLogger().log(Level.WARNING, "MCServers ownership verification was interrupted.");
        } catch (Exception ex) {
            getLogger().log(Level.WARNING, "Failed to verify MCServers ownership: " + ex.getMessage());
        } finally {
            ownershipVerifyInFlight.set(false);
        }
    }

    private void sendHeartbeatSafely() {
        if (!heartbeatInFlight.compareAndSet(false, true)) return;
        try {
            ServerSnapshot snapshot = captureServerSnapshot();
            String body = "{"
                    + "\"online\":true,"
                    + "\"current_players\":" + snapshot.currentPlayers + ","
                    + "\"max_players\":" + snapshot.maxPlayers + ","
                    + "\"server_version\":\"" + jsonEscape(snapshot.serverVersion) + "\","
                    + "\"instance_id\":\"" + jsonEscape(instanceId) + "\","
                    + "\"timestamp_ms\":" + System.currentTimeMillis()
                    + "}";
            postJson("/api/plugin/v1/heartbeat", body);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            getLogger().log(Level.WARNING, "MCServers heartbeat was interrupted.");
        } catch (Exception ex) {
            getLogger().log(Level.WARNING, "Failed to send MCServers heartbeat: " + ex.getMessage(), ex);
        } finally {
            heartbeatInFlight.set(false);
        }
    }

    private void pollVoteRewardsSafely() {
        if (!votePollInFlight.compareAndSet(false, true)) return;
        try {
            String body = "{\"timestamp_ms\":" + System.currentTimeMillis() + ",\"limit\":" + claimBatchSize + "}";
            String response = postJson("/api/plugin/v1/rewards/fetch", body);
            List<Reward> rewards = parseRewards(response);
            if (rewards.isEmpty()) return;

            StringBuilder results = new StringBuilder();
            results.append("{\"results\":[");
            for (int i = 0; i < rewards.size(); i++) {
                Reward reward = rewards.get(i);
                boolean success = true;
                String message = "executed";
                try {
                    executeVoteCommands(reward.player);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    success = false;
                    message = "interrupted";
                    getLogger().log(Level.WARNING, "Vote reward execution was interrupted for " + reward.player, ex);
                } catch (Exception ex) {
                    success = false;
                    message = ex.getMessage();
                    getLogger().log(Level.WARNING, "Failed to execute vote reward for " + reward.player, ex);
                }
                if (i > 0) results.append(',');
                results.append("{\"id\":").append(reward.id)
                        .append(",\"success\":").append(success)
                        .append(",\"message\":\"").append(jsonEscape(message)).append("\"}");
            }
            results.append("],\"timestamp_ms\":").append(System.currentTimeMillis()).append("}");
            postJson("/api/plugin/v1/rewards/ack", results.toString());
        } catch (Exception ex) {
            getLogger().log(Level.WARNING, "Failed to fetch MCServers vote rewards: " + ex.getMessage(), ex);
        } finally {
            votePollInFlight.set(false);
        }
    }

    private List<Reward> parseRewards(String json) {
        List<Reward> rewards = new ArrayList<Reward>();
        Matcher matcher = REWARD_PATTERN.matcher(json);
        while (matcher.find()) {
            String playerName = unescapeJson(matcher.group(2));
            if (!isSafePlayerName(playerName)) {
                getLogger().warning("Ignoring vote reward with invalid player name payload.");
                continue;
            }
            rewards.add(new Reward(Integer.parseInt(matcher.group(1)), playerName));
        }
        return rewards;
    }

    private void executeVoteCommands(final String playerName) throws ExecutionException, InterruptedException {
        if (voteCommands.isEmpty()) throw new IllegalStateException("vote-commands is empty");
        if (!isSafePlayerName(playerName)) throw new IllegalStateException("invalid player name");
        Bukkit.getScheduler().callSyncMethod(this, new java.util.concurrent.Callable<Boolean>() {
            public Boolean call() {
                CommandSender console = Bukkit.getConsoleSender();
                for (String template : voteCommands) {
                    String command = template.replace("%PLAYER%", playerName).trim();
                    if (command.startsWith("/")) command = command.substring(1);
                    if (!command.trim().isEmpty()) {
                        boolean ok = Bukkit.dispatchCommand(console, command);
                        if (!ok) throw new IllegalStateException("command failed: " + command);
                    }
                }
                return true;
            }
        }).get();
    }

    private String postJson(String path, String body) throws IOException {
        long timestamp = System.currentTimeMillis();
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String signature = sign("POST", path, String.valueOf(serverId), String.valueOf(timestamp), nonce, body);
        return post(path, body, timestamp, nonce, signature);
    }

    private String postUnsignedJson(String path, String body) throws IOException {
        return post(path, body, System.currentTimeMillis(), "", "");
    }

    private String post(String path, String body, long timestamp, String nonce, String signature) throws IOException {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setDoOutput(true);
            conn.setUseCaches(false);
            conn.setInstanceFollowRedirects(false);
            conn.setFixedLengthStreamingMode(bodyBytes.length);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("User-Agent", "MCServersConnector/1.1");
            if (nonce != null && nonce.length() > 0) {
                conn.setRequestProperty("X-MCServers-Server-Id", String.valueOf(serverId));
                conn.setRequestProperty("X-MCServers-Timestamp", String.valueOf(timestamp));
                conn.setRequestProperty("X-MCServers-Nonce", nonce);
                conn.setRequestProperty("X-MCServers-Signature", signature);
            }

            try (OutputStream out = conn.getOutputStream()) {
                out.write(bodyBytes);
            }

            int code = conn.getResponseCode();
            if (code >= 300 && code < 400) throw new IOException("HTTP redirect refused");
            String response = readResponseBody(conn, code);
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code + ": " + response);
            return response;
        } finally {
            conn.disconnect();
        }
    }

    private void validateBaseUrl(String configuredBaseUrl) {
        try {
            URI uri = URI.create(configuredBaseUrl);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            boolean localhost = "localhost".equals(host) || "127.0.0.1".equals(host) || "::1".equals(host);
            if (!"https".equals(scheme) && !(localhost && "http".equals(scheme))) {
                throw new IllegalArgumentException("base-url must use HTTPS unless it targets localhost");
            }
            if (uri.getHost() == null || uri.getRawPath() != null && uri.getRawPath().length() > 0) {
                throw new IllegalArgumentException("base-url must be an origin without a path");
            }
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("invalid base-url", ex);
        }
    }

    private String readResponseBody(HttpURLConnection conn, int code) throws IOException {
        InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        if (stream == null) return "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder res = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) res.append(line);
            return res.toString();
        }
    }

    private String sign(String method, String path, String serverId, String timestamp, String nonce, String body) {
        try {
            String canonical = method.toUpperCase() + "\n" + path + "\n" + serverId + "\n" + timestamp + "\n" + nonce + "\n" + sha256Hex(body.getBytes(StandardCharsets.UTF_8));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(serverSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return hex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("failed to sign request", ex);
        }
    }

    private String sha256Hex(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return hex(digest.digest(data));
    }

    private String hex(byte[] bytes) {
        char[] chars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xff;
            chars[i * 2] = HEX_ALPHABET[v >>> 4];
            chars[i * 2 + 1] = HEX_ALPHABET[v & 0x0f];
        }
        return new String(chars);
    }

    private Integer matchInt(String text, Pattern pattern) {
        Matcher m = pattern.matcher(text);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    private String matchString(String text, Pattern pattern) {
        Matcher m = pattern.matcher(text);
        return m.find() ? unescapeJson(m.group(1)) : null;
    }

    private String jsonEscape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    private String unescapeJson(String value) {
        return value.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private boolean isSafePlayerName(String value) {
        return value != null && PLAYER_NAME_PATTERN.matcher(value).matches();
    }

    private ServerSnapshot captureServerSnapshot() throws ExecutionException, InterruptedException {
        return Bukkit.getScheduler().callSyncMethod(this, new java.util.concurrent.Callable<ServerSnapshot>() {
            public ServerSnapshot call() {
                return new ServerSnapshot(Bukkit.getOnlinePlayers().size(), Bukkit.getMaxPlayers(), Bukkit.getVersion());
            }
        }).get();
    }

    private static final class ServerSnapshot {
        final int currentPlayers;
        final int maxPlayers;
        final String serverVersion;

        ServerSnapshot(int currentPlayers, int maxPlayers, String serverVersion) {
            this.currentPlayers = currentPlayers;
            this.maxPlayers = maxPlayers;
            this.serverVersion = serverVersion;
        }
    }

    private static final class Reward {
        final int id;
        final String player;
        Reward(int id, String player) {
            this.id = id;
            this.player = player;
        }
    }
}
