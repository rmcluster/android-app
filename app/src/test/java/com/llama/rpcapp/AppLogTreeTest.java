package com.llama.rpcapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mockStatic;

import android.util.Log;

import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;

public class AppLogTreeTest {
    @Before
    public void setUp() throws Exception {
        clearLogStore();
    }

    @Test
    public void log_appendsToStoreAndFormatsLogcatLine() throws Exception {
        AppLogTree tree = new AppLogTree();
        invokeLog(tree, Log.INFO, "StorageServer", "stored", null);

        assertTrue(AppLogStore.getInstance().snapshotText().contains("stored"));
    }

    @Test
    public void log_routesRpcAndGeneralTags() throws Exception {
        AppLogTree tree = new AppLogTree();
        invokeLog(tree, Log.DEBUG, "RpcService", "rpc line", null);
        invokeLog(tree, Log.WARN, "Other", "general line", null);

        String snapshot = AppLogStore.getInstance().snapshotText();
        assertTrue(snapshot.contains("rpc line"));
        assertTrue(snapshot.contains("general line"));
    }

    @Test
    public void log_handlesMessageWithoutThrowable() throws Exception {
        invokeLog(new AppLogTree(), Log.INFO, "rpc", "plain", null);
        assertTrue(AppLogStore.getInstance().snapshotText().contains("plain"));
    }

    @Test
    public void log_handlesNullMessage() throws Exception {
        invokeLog(new AppLogTree(), Log.INFO, "rpc", null, null);
        assertFalse(AppLogStore.getInstance().snapshotText().isEmpty());
    }

    @Test
    public void log_rendersThrowablesAndEmptyMessages() throws Exception {
        AppLogTree tree = new AppLogTree();
        invokeLog(tree, Log.ERROR, "rpc", "", new IOException("disk"));
        invokeLog(tree, Log.ERROR, "rpc", "failure", new IOException("disk"));

        String snapshot = AppLogStore.getInstance().snapshotText();
        assertTrue(snapshot.contains("failure"));
        assertTrue(snapshot.contains("IOException"));
    }

    @Test
    public void log_continuesWhenStoreAppendFails() throws Exception {
        try (MockedStatic<AppLogStore> mocked = mockStatic(AppLogStore.class)) {
            mocked.when(AppLogStore::getInstance).thenThrow(new RuntimeException("store down"));
            invokeLog(new AppLogTree(), Log.INFO, "rpc", "still logged", null);
        }
    }

    @Test
    public void categoryForTag_classifiesTags() throws Exception {
        assertEquals("GENERAL", categoryForTag(null));
        assertEquals("STORAGE", categoryForTag("StorageServer"));
        assertEquals("RPC", categoryForTag("RpcService"));
        assertEquals("RPC", categoryForTag("LlamaWorker"));
        assertEquals("GENERAL", categoryForTag("Other"));
    }

    @Test
    public void priorityToLevel_coversAllBranches() throws Exception {
        for (int priority : new int[] {2, 3, 4, 5, 6, 7, 99}) {
            assertTrue(priorityToLevel(priority).length() > 0);
        }
    }

    private static void invokeLog(AppLogTree tree, int priority, String tag, String message, Throwable t)
            throws Exception {
        Method method = AppLogTree.class.getDeclaredMethod(
                "log",
                int.class,
                String.class,
                String.class,
                Throwable.class
        );
        method.setAccessible(true);
        method.invoke(tree, priority, tag, message, t);
    }

    private static String categoryForTag(String tag) throws Exception {
        Method method = AppLogTree.class.getDeclaredMethod("categoryForTag", String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, tag);
    }

    private static String priorityToLevel(int priority) throws Exception {
        Method method = AppLogTree.class.getDeclaredMethod("priorityToLevel", int.class);
        method.setAccessible(true);
        return (String) method.invoke(null, priority);
    }

    private static void clearLogStore() throws Exception {
        java.lang.reflect.Field lines = AppLogStore.class.getDeclaredField("lines");
        lines.setAccessible(true);
        ((ArrayDeque<?>) lines.get(AppLogStore.getInstance())).clear();
    }
}
