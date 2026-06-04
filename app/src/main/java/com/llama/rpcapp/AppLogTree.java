package com.llama.rpcapp;

import java.io.PrintWriter;
import java.io.StringWriter;

import timber.log.Timber;

public final class AppLogTree extends Timber.DebugTree {
    private static final String CATEGORY_RPC = "RPC";
    private static final String CATEGORY_STORAGE = "STORAGE";
    private static final String CATEGORY_GENERAL = "GENERAL";

    @Override
    protected void log(int priority, String tag, String message, Throwable t) {
        long now = System.currentTimeMillis();
        String renderedMessage = renderMessage(message, t);
        String category = categoryForTag(tag);
        String lineForLogcat = renderedMessage;
        try {
            AppLogStore.getInstance().append(priority, tag, renderedMessage, category, now);
            lineForLogcat = AppLogStore.formatLine(now, category, priorityToLevel(priority), tag, renderedMessage);
        } catch (Throwable storeFailure) {
            lineForLogcat = renderedMessage;
        }
        super.log(priority, tag, lineForLogcat, null);
    }

    private static String categoryForTag(String tag) {
        String normalizedTag = tag == null ? "" : tag.toLowerCase();
        if (normalizedTag.contains("storage")) {
            return CATEGORY_STORAGE;
        }
        if (normalizedTag.contains("rpc") || normalizedTag.contains("llama") || normalizedTag.contains("ggml")) {
            return CATEGORY_RPC;
        }
        return CATEGORY_GENERAL;
    }

    private static String renderMessage(String message, Throwable throwable) {
        String safeMessage = message == null ? "" : message;
        if (throwable == null) {
            return safeMessage;
        }

        StringWriter sw = new StringWriter();
        throwable.printStackTrace(new PrintWriter(sw));
        if (safeMessage.isEmpty()) {
            return sw.toString();
        }
        return safeMessage + "\n" + sw;
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
}
