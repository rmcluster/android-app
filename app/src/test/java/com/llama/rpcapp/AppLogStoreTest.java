package com.llama.rpcapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.util.Log;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

public class AppLogStoreTest {
    private AppLogStore store;

    @Before
    public void setUp() throws Exception {
        store = AppLogStore.getInstance();
        clearStore();
    }

    @Test
    public void getInstance_returnsSingleton() {
        assertSame(store, AppLogStore.getInstance());
    }

    @Test
    public void append_notifiesListenersWithSnapshot() {
        AtomicReference<String> latest = new AtomicReference<>();
        AppLogStore.Listener listener = latest::set;
        store.addListener(listener);

        store.append(Log.INFO, "RpcTag", "hello", "rpc", 1_700_000_000_000L);

        assertTrue(latest.get().contains("hello"));
        assertTrue(latest.get().contains("[RPC]"));
        store.removeListener(listener);
    }

    @Test
    public void append_ignoresNullListener() {
        long now = System.currentTimeMillis();
        store.addListener(null);
        store.removeListener(null);
        store.append(Log.INFO, "tag", "still works", null, now);
        assertTrue(store.snapshotText().contains("still works"));
    }

    @Test
    public void append_splitsMultilineMessages() {
        long now = System.currentTimeMillis();
        store.append(Log.DEBUG, "tag", "line-one\nline-two", "general", now);

        String snapshot = store.snapshotText();
        assertTrue(snapshot.contains("line-one"));
        assertTrue(snapshot.contains("line-two"));
    }

    @Test
    public void append_handlesNullAndEmptyMessage() {
        long now = System.currentTimeMillis();
        store.append(Log.WARN, "tag", null, " ", now);
        store.append(Log.ERROR, "tag", "", "storage", now + 1);

        assertFalse(store.snapshotText().isEmpty());
    }

    @Test
    public void append_prunesLinesOlderThanMaxAge() throws Exception {
        long now = System.currentTimeMillis();
        store.append(Log.INFO, "tag", "stale", "general", now - AppLogStore.MAX_AGE_MS - 1_000L);
        store.append(Log.INFO, "tag", "fresh", "general", now);

        assertFalse(store.snapshotText().contains("stale"));
        assertTrue(store.snapshotText().contains("fresh"));
    }

    @Test
    public void append_trimsToMaxLines() {
        long now = System.currentTimeMillis();
        for (int i = 0; i < AppLogStore.MAX_LINES + 5; i++) {
            store.append(Log.INFO, "tag", "line-" + i, "general", now + i);
        }

        String[] lines = store.snapshotText().split("\\r?\\n");
        assertEquals(AppLogStore.MAX_LINES, lines.length);
        assertTrue(store.snapshotText().contains("line-" + (AppLogStore.MAX_LINES + 4)));
        assertFalse(store.snapshotText().contains("line-0"));
    }

    @Test
    public void formatLine_coversCategoriesAndTags() {
        assertTrue(AppLogStore.formatLine(0L, null, "INFO", null, "msg").contains("[GENERAL]"));
        assertTrue(AppLogStore.formatLine(0L, " rpc ", "INFO", "", "msg").contains("[RPC]"));
        assertTrue(AppLogStore.formatLine(0L, "storage", "INFO", "StorageServer", "msg").contains("StorageServer:"));
        assertTrue(AppLogStore.formatLine(0L, "general", "INFO", "tag", null).endsWith(" "));
    }

    @Test
    public void priorityLevels_areRenderedInSnapshot() throws Exception {
        clearStore();
        long now = System.currentTimeMillis();
        store.append(2, "tag", "v", "general", now);
        store.append(3, "tag", "d", "general", now + 1);
        store.append(4, "tag", "i", "general", now + 2);
        store.append(5, "tag", "w", "general", now + 3);
        store.append(6, "tag", "e", "general", now + 4);
        store.append(7, "tag", "a", "general", now + 5);
        store.append(99, "tag", "u", "general", now + 6);

        String snapshot = store.snapshotText();
        assertTrue(snapshot.contains("[VERBOSE]"));
        assertTrue(snapshot.contains("[DEBUG]"));
        assertTrue(snapshot.contains("[INFO]"));
        assertTrue(snapshot.contains("[WARN]"));
        assertTrue(snapshot.contains("[ERROR]"));
        assertTrue(snapshot.contains("[ASSERT]"));
        assertTrue(snapshot.contains("[UNKNOWN]"));
    }

    @Test
    public void snapshotText_trimsExcessLinesViaPruneLocked() throws Exception {
        clearStore();
        long now = System.currentTimeMillis();
        Field linesField = AppLogStore.class.getDeclaredField("lines");
        linesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        ArrayDeque<Object> lines = (ArrayDeque<Object>) linesField.get(store);
        Class<?> logLineClass = Class.forName("com.llama.rpcapp.AppLogStore$LogLine");
        Constructor<?> logLineCtor = logLineClass.getDeclaredConstructor(long.class, String.class);
        logLineCtor.setAccessible(true);
        for (int i = 0; i < AppLogStore.MAX_LINES + 3; i++) {
            lines.addLast(logLineCtor.newInstance(now, "line-" + i));
        }

        store.snapshotText();

        assertEquals(AppLogStore.MAX_LINES, store.snapshotText().split("\\r?\\n").length);
    }

    @Test
    public void snapshotText_prunesBeforeReturning() throws Exception {
        long now = System.currentTimeMillis();
        store.append(Log.INFO, "tag", "old", "general", now - AppLogStore.MAX_AGE_MS - 5_000L);

        String snapshot = store.snapshotText();

        assertNotNull(snapshot);
        assertFalse(snapshot.contains("old"));
    }

    private void clearStore() throws Exception {
        Field lines = AppLogStore.class.getDeclaredField("lines");
        lines.setAccessible(true);
        ((ArrayDeque<?>) lines.get(store)).clear();

        Field listeners = AppLogStore.class.getDeclaredField("listeners");
        listeners.setAccessible(true);
        ((CopyOnWriteArrayList<?>) listeners.get(store)).clear();
    }
}
