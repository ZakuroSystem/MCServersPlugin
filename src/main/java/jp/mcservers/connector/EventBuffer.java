package jp.mcservers.connector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

final class EventBuffer {
    private final ArrayDeque<PluginEvent> queue = new ArrayDeque<PluginEvent>();
    private final int maxSize;
    private int droppedCount;

    EventBuffer(int maxSize) {
        this.maxSize = Math.max(1, Math.min(maxSize, 5000));
    }

    synchronized void offer(PluginEvent event) {
        if (event == null) return;
        while (queue.size() >= maxSize) {
            queue.pollFirst();
            droppedCount++;
        }
        queue.offerLast(event);
    }

    synchronized List<PluginEvent> drain(int limit) {
        int count = Math.max(1, Math.min(limit, queue.size()));
        List<PluginEvent> events = new ArrayList<PluginEvent>(count);
        for (int i = 0; i < count; i++) {
            PluginEvent event = queue.pollFirst();
            if (event == null) break;
            events.add(event);
        }
        return events;
    }

    synchronized void prepend(List<PluginEvent> events) {
        if (events == null || events.isEmpty()) return;
        for (int i = events.size() - 1; i >= 0; i--) {
            PluginEvent event = events.get(i);
            if (event == null) continue;
            while (queue.size() >= maxSize) {
                queue.pollLast();
                droppedCount++;
            }
            queue.offerFirst(event);
        }
    }

    synchronized int size() {
        return queue.size();
    }

    synchronized int getAndResetDroppedCount() {
        int value = droppedCount;
        droppedCount = 0;
        return value;
    }

    static String toBatchJson(String instanceId, List<PluginEvent> events, int droppedCount) {
        StringBuilder out = new StringBuilder(256 + Math.max(0, events == null ? 0 : events.size()) * 192);
        out.append('{');
        out.append("\"instance_id\":\"").append(PluginEvent.jsonEscape(instanceId)).append("\",");
        out.append("\"timestamp_ms\":").append(System.currentTimeMillis()).append(',');
        out.append("\"dropped_count\":").append(Math.max(0, droppedCount)).append(',');
        out.append("\"events\":[");
        if (events != null) {
            for (int i = 0; i < events.size(); i++) {
                if (i > 0) out.append(',');
                out.append(events.get(i).toJson());
            }
        }
        out.append("]}");
        return out.toString();
    }
}
