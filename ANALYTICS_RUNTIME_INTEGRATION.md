# AnalyticsRuntime integration notes

This document describes the minimal changes needed to connect the added analytics classes to `MCServersConnectorPlugin.java`.

`MCServersConnectorPlugin.java` already contains security-sensitive signing and HTTP code. Do not rewrite the file wholesale. Apply a small patch only.

## Added classes

Already implemented:

```text
PluginEvent.java
EventBuffer.java
AreaClassifier.java
EventCollector.java
AnalyticsRuntime.java
```

## 1. Add fields

Near existing task fields:

```java
private AnalyticsRuntime analyticsRuntime;
private boolean analyticsEnabled;
```

## 2. Load config

In `reloadLocalConfig()`, after the existing timeout / interval settings are loaded:

```java
analyticsEnabled = getConfig().getBoolean("analytics-enabled", false);
```

Analytics must stay disabled by default.

## 3. Start runtime

In `onEnable()` after `reloadLocalConfig()` and before logging enabled state:

```java
if (hasServerCredentials() && analyticsEnabled) {
    startAnalyticsRuntime();
}
```

Also call it after ownership verification succeeds and credentials are received.

## 4. Stop runtime

In `stopTasks()` add:

```java
if (analyticsRuntime != null) {
    analyticsRuntime.stop();
    analyticsRuntime = null;
}
```

## 5. Add helper methods

Add methods inside `MCServersConnectorPlugin`:

```java
private void startAnalyticsRuntime() {
    if (analyticsRuntime != null) return;
    if (!hasServerCredentials()) return;
    if (!analyticsEnabled) return;
    String hashSalt = getConfig().getString("analytics-hash-salt", "");
    if (hashSalt == null || hashSalt.trim().length() < 16) {
        getLogger().warning("MCServers analytics is enabled, but analytics-hash-salt is too short. Analytics will not start.");
        return;
    }
    analyticsRuntime = new AnalyticsRuntime(this, instanceId, hashSalt, new AnalyticsRuntime.EventPoster() {
        public void postEventsBatch(String body) throws Exception {
            postJson("/api/plugin/v1/events/batch", body);
        }
    });
    analyticsRuntime.start();
    getLogger().info("MCServers analytics runtime started.");
}

private void recordAnalyticsSnapshot(ServerSnapshot snapshot) {
    if (analyticsRuntime == null || snapshot == null) return;
    analyticsRuntime.recordServerSnapshot(snapshot.currentPlayers, snapshot.maxPlayers, snapshot.serverVersion);
}
```

## 6. Feed heartbeat snapshot into analytics

In `sendHeartbeatSafely()`, after `ServerSnapshot snapshot = captureServerSnapshot();`, add:

```java
recordAnalyticsSnapshot(snapshot);
```

This keeps heartbeat and analytics consistent without recapturing server state.

## 7. Config keys

Copy the relevant values from `ANALYTICS_CONFIG_EXAMPLE.yml` into `config.yml` when ready.

Minimum required keys:

```yaml
analytics-enabled: false
analytics-flush-interval-seconds: 30
analytics-batch-size: 100
analytics-max-buffer-size: 500
analytics-hash-salt: "CHANGE_ME_TO_A_LONG_RANDOM_VALUE"
analytics-allowed-commands:
  - help
  - menu
  - spawn
  - warp
analytics-areas:
  lobby:
    - world: world
      center: [0, 64, 0]
      radius: 80
  survival:
    world: survival
```

## 8. Behavior after integration

When analytics is enabled:

- `PlayerJoinEvent` creates `player_session_started`
- `PlayerQuitEvent` creates `player_session_ended`
- allowed commands create `key_command_used`
- heartbeat snapshot also creates `server_snapshot`
- events are buffered in memory
- every `analytics-flush-interval-seconds`, events are sent to `/api/plugin/v1/events/batch`
- failed batches are returned to the front of the memory buffer unless the plugin is shutting down

## 9. Important constraints

- Do not record command arguments.
- Do not record raw chat text in this phase.
- Do not record exact coordinates.
- Keep `analytics-enabled: false` by default.
- Do not reuse `server-secret` as `analytics-hash-salt`.
