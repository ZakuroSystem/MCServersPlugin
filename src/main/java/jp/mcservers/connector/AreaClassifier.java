package jp.mcservers.connector;

import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class AreaClassifier {
    private final List<AreaRule> rules = new ArrayList<AreaRule>();

    AreaClassifier(JavaPlugin plugin) {
        load(plugin);
    }

    String worldBucket(Location location) {
        AreaRule rule = firstMatchingRule(location);
        return rule == null ? "unknown" : rule.bucket;
    }

    String locationBucket(Location location) {
        AreaRule rule = firstMatchingRule(location);
        return rule == null ? "unknown" : rule.locationBucket;
    }

    private AreaRule firstMatchingRule(Location location) {
        if (location == null || location.getWorld() == null) return null;
        for (AreaRule rule : rules) {
            if (rule.matches(location)) return rule;
        }
        return null;
    }

    private void load(JavaPlugin plugin) {
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("analytics-areas");
        if (root == null) return;
        Set<String> buckets = root.getKeys(false);
        for (String bucket : buckets) {
            ConfigurationSection section = root.getConfigurationSection(bucket);
            if (section != null) {
                addRule(bucket, bucket, section.getString("world", ""), section.getList("center"), section.getDouble("radius", 0.0D));
                continue;
            }
            List<Map<?, ?>> mapList = root.getMapList(bucket);
            for (Map<?, ?> item : mapList) {
                addRule(bucket, bucket, stringValue(item.get("world")), listValue(item.get("center")), doubleValue(item.get("radius")));
            }
        }
    }

    private void addRule(String bucket, String locationBucket, String worldRaw, List<?> center, double radiusRaw) {
        String world = worldRaw == null ? "" : worldRaw.trim();
        if (world.length() == 0) return;
        double radius = Math.max(0.0D, radiusRaw);
        double x = 0.0D;
        double y = 0.0D;
        double z = 0.0D;
        boolean hasCenter = false;
        if (center != null && center.size() >= 3) {
            x = asDouble(center.get(0));
            y = asDouble(center.get(1));
            z = asDouble(center.get(2));
            hasCenter = true;
        }
        rules.add(new AreaRule(safeBucket(bucket), safeBucket(locationBucket), world, hasCenter, x, y, z, radius));
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static double doubleValue(Object value) {
        return asDouble(value);
    }

    private static List<?> listValue(Object value) {
        return value instanceof List ? (List<?>) value : null;
    }

    private static double asDouble(Object value) {
        if (value instanceof Number) return ((Number) value).doubleValue();
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception ignored) {
            return 0.0D;
        }
    }

    private static String safeBucket(String value) {
        String text = value == null ? "unknown" : value.trim().toLowerCase(java.util.Locale.ROOT);
        if (!text.matches("[a-z0-9._:-]{1,40}")) return "unknown";
        return text;
    }

    private static final class AreaRule {
        final String bucket;
        final String locationBucket;
        final String world;
        final boolean hasCenter;
        final double x;
        final double y;
        final double z;
        final double radius;

        AreaRule(String bucket, String locationBucket, String world, boolean hasCenter, double x, double y, double z, double radius) {
            this.bucket = bucket;
            this.locationBucket = locationBucket;
            this.world = world;
            this.hasCenter = hasCenter;
            this.x = x;
            this.y = y;
            this.z = z;
            this.radius = radius;
        }

        boolean matches(Location location) {
            if (location == null || location.getWorld() == null) return false;
            if (!world.equalsIgnoreCase(location.getWorld().getName())) return false;
            if (!hasCenter || radius <= 0.0D) return true;
            double dx = location.getX() - x;
            double dy = location.getY() - y;
            double dz = location.getZ() - z;
            return (dx * dx + dy * dy + dz * dz) <= radius * radius;
        }
    }
}
