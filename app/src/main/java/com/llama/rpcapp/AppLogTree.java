package com.llama.rpcapp;

import java.io.PrintWriter;
import java.io.StringWriter;

import timber.log.Timber;

public final class AppLogTree extends Timber.DebugTree {
    @Override
    protected void log(int priority, String tag, String message, Throwable t) {
        long now = System.currentTimeMillis();
        String renderedMessage = renderMessage(message, t);
        AppLogStore.getInstance().append(priority, tag, renderedMessage, now);
        super.log(priority, tag, AppLogStore.formatLine(now, priorityToLevel(priority), tag, renderedMessage), null);
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
