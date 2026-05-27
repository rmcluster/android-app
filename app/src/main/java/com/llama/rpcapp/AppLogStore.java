package com.llama.rpcapp;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

public final class AppLogStore {
    public static final long MAX_AGE_MS = 5 * 60 * 1000L;
    public static final int MAX_LINES = 100;
    private static final ThreadLocal<SimpleDateFormat> TIMESTAMP_FORMAT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US));

    private static final AppLogStore INSTANCE = new AppLogStore();
    private final ArrayDeque<LogLine> lines = new ArrayDeque<>();

    private AppLogStore() {
    }

    public static AppLogStore getInstance() {
        return INSTANCE;
    }

    public synchronized void append(int priority, String tag, String message, long timestampMs) {
        pruneLocked(timestampMs);

        if (message == null || message.isEmpty()) {
            message = "";
        }

        String[] splitLines = message.split("\\r?\\n", -1);
        for (String line : splitLines) {
            lines.addLast(new LogLine(timestampMs, formatLine(timestampMs, priorityToLevel(priority), tag, line)));
        }

        while (lines.size() > MAX_LINES) {
            lines.removeFirst();
        }
    }

    public synchronized String snapshotText() {
        long now = System.currentTimeMillis();
        pruneLocked(now);

        StringBuilder out = new StringBuilder();
        boolean first = true;
        for (LogLine line : lines) {
            if (!first) {
                out.append('\n');
            }
            out.append(line.text);
            first = false;
        }
        return out.toString();
    }

    private void pruneLocked(long nowMs) {
        long cutoff = nowMs - MAX_AGE_MS;
        while (!lines.isEmpty() && lines.peekFirst().timestampMs < cutoff) {
            lines.removeFirst();
        }

        while (lines.size() > MAX_LINES) {
            lines.removeFirst();
        }
    }

    public static String formatLine(long timestampMs, String level, String tag, String message) {
        StringBuilder out = new StringBuilder();
        out.append(TIMESTAMP_FORMAT.get().format(new Date(timestampMs)));
        out.append(" [").append(level).append("]");
        if (tag != null && !tag.isEmpty()) {
            out.append(" ").append(tag).append(":");
        }
        out.append(" ").append(message == null ? "" : message);
        return out.toString();
    }

    private static String priorityToLevel(int priority) {
        switch (priority) {
            case 2:
                return "VERBOSE";
            case 3:
                return "DEBUG";
            case 4:
                return "INFO";
            case 5:
                return "WARN";
            case 6:
                return "ERROR";
            case 7:
                return "ASSERT";
            default:
                return "UNKNOWN";
        }
    }

    private static final class LogLine {
        private final long timestampMs;
        private final String text;

        private LogLine(long timestampMs, String text) {
            this.timestampMs = timestampMs;
            this.text = text;
        }
    }
}
