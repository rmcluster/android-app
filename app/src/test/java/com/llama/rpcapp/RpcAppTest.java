package com.llama.rpcapp;

import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import timber.log.Timber;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = RpcApp.class)
public class RpcAppTest {
    @Test
    public void onCreate_plantsAppLogTree() {
        assertTrue(org.robolectric.RuntimeEnvironment.getApplication() instanceof RpcApp);

        boolean hasAppLogTree = false;
        for (Timber.Tree tree : Timber.forest()) {
            if (tree instanceof AppLogTree) {
                hasAppLogTree = true;
                break;
            }
        }
        assertTrue(hasAppLogTree);
    }
}
