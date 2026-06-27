package jp.mcservers.connector;

final class PluginEvent {
    private final String eventType;
    private final String eventKey;
    private final String playerHash;
    private final String sessionId;
    private final long occurredAtMs;
    private final String worldBucket;
    private final String locationBucket;
    private final String privacyLevel;
    private final String metadataJson;

    PluginEvent(
            String eventType,
            String eventKey,
            String playerHash,
            String sessionId,
            long occurredAtMs,
            String worldBucket,
            String locationBucket,
            String privacyLevel,
            String metadataJson
    ) {
        this.eventType = safeText(eventType);
        this.eventKey = safeText(eventKey);
        this.playerHash = safeText(playerHash);
        this.sessionId = safeText(sessionId);
        this.occurredAtMs = occurredAtMs;
        this.worldBucket = emptyToNull(worldBucket);
        this.locationBucket = emptyToNull(locationBucket);
        this.privacyLevel = emptyToDefault(privacyLevel, "admin");
        this.metadataJson = normalizeMetadata(metadataJson);
    }

    String getEventKey() {
        return eventKey;
    }

    String toJson() {
        StringBuilder out = new StringBuilder(256);
        out.append('{');
        appendString(out, "event_type", eventType);
        out.append(',');
        appendString(out, "event_key", eventKey);
        if (playerHash != null && playerHash.length() > 0) {
            out.append(',');
            appendString(out, "player_hash", playerHash);
        }
        if (sessionId != null && sessionId.length() > 0) {
            out.append(',');
            appendString(out, "session_id", sessionId);
        }
        out.append(',');
        out.append("\"occurred_at_ms\":").append(occurredAtMs);
        if (worldBucket != null) {
            out.append(',');
            appendString(out, "world_bucket", worldBucket);
        }
        if (locationBucket != null) {
            out.append(',');
            appendString(out, "location_bucket", locationBucket);
        }
        out.append(',');
        appendString(out, "privacy_level", privacyLevel);
        out.append(',');
        out.append("\"metadata\":").append(metadataJson);
        out.append('}');
        return out.toString();
    }

    private static void appendString(StringBuilder out, String key, String value) {
        out.append('"').append(jsonEscape(key)).append("\":\"").append(jsonEscape(value)).append('"');
    }

    static String jsonEscape(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '\\') out.append("\\\\");
            else if (ch == '"') out.append("\\\"");
            else if (ch == '\n') out.append("\\n");
            else if (ch == '\r') out.append("\\r");
            else if (ch == '\t') out.append("\\t");
            else if (ch < 0x20) out.append(' ');
            else out.append(ch);
        }
        return out.toString();
    }

    private static String safeText(String value) {
        return value == null ? "" : value.trim();
    }

    private static String emptyToNull(String value) {
        String text = safeText(value);
        return text.length() == 0 ? null : text;
    }

    private static String emptyToDefault(String value, String fallback) {
        String text = safeText(value);
        return text.length() == 0 ? fallback : text;
    }

    private static String normalizeMetadata(String metadataJson) {
        String text = safeText(metadataJson);
        if (text.length() == 0) return "{}";
        if (!text.startsWith("{") || !text.endsWith("}")) return "{}";
        return text;
    }
}
