package com.llama.rpcapp;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import fi.iki.elonen.NanoHTTPD;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class StorageServerTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private File storageDir;
    private TestStorageServer server;

    @Before
    public void setUp() throws Exception {
        storageDir = temporaryFolder.newFolder("storage");
        server = new TestStorageServer(8080, storageDir);
    }

    @Test
    public void insufficientStorageStatus_exposesCustomDescription() throws Exception {
        Field field = StorageServer.class.getDeclaredField("INSUFFICIENT_STORAGE");
        field.setAccessible(true);
        NanoHTTPD.Response.IStatus status = (NanoHTTPD.Response.IStatus) field.get(null);

        assertEquals("507 Insufficient Storage", status.getDescription());
        assertEquals(507, status.getRequestStatus());
    }

    @Test
    public void getUsableSpace_delegatesToStorageDirectory() {
        StorageServer baseServer = new StorageServer(8080, storageDir);

        assertTrue(baseServer.getUsableSpace() > 0);
    }

    @Test
    public void serve_returnsBadRequestForInvalidChunkIdOnGet() throws Exception {
        NanoHTTPD.Response response = server.serve(session("/chunk/not-a-hash", NanoHTTPD.Method.GET));

        assertEquals(400, response.getStatus().getRequestStatus());
        assertEquals("Invalid chunk ID format", responseBody(response));
    }

    @Test
    public void serve_returnsJsonBadIdForInvalidChunkIdOnPut() throws Exception {
        NanoHTTPD.Response response = server.serve(putSession("/chunk/not-a-hash", bytes("hello")));

        assertEquals(400, response.getStatus().getRequestStatus());
        assertEquals("bad_id", new JSONObject(responseBody(response)).getString("error"));
    }

    @Test
    public void serve_returnsNotFoundForUnknownRoute() throws Exception {
        NanoHTTPD.Response response = server.serve(session("/unknown", NanoHTTPD.Method.GET));

        assertEquals(404, response.getStatus().getRequestStatus());
        assertEquals("Not Found", responseBody(response));
    }

    @Test
    public void getChunk_returnsNotFoundWhenFileDoesNotExist() throws Exception {
        String chunkId = sha256(bytes("missing"));

        NanoHTTPD.Response response = server.serve(session("/chunk/" + chunkId, NanoHTTPD.Method.GET));

        assertEquals(404, response.getStatus().getRequestStatus());
        assertEquals("not_found", new JSONObject(responseBody(response)).getString("error"));
    }

    @Test
    public void chunkRoutes_returnNotFoundForUnsupportedMethod() throws Exception {
        String chunkId = sha256(bytes("missing"));

        NanoHTTPD.Response response = server.serve(session("/chunk/" + chunkId, NanoHTTPD.Method.POST));

        assertEquals(404, response.getStatus().getRequestStatus());
        assertEquals("Not Found", responseBody(response));
    }

    @Test
    public void collectionRoutes_returnNotFoundForUnsupportedMethods() throws Exception {
        assertEquals(404, server.serve(session("/chunks/list", NanoHTTPD.Method.POST)).getStatus().getRequestStatus());
        assertEquals(404, server.serve(session("/chunks/healthcheck", NanoHTTPD.Method.POST)).getStatus().getRequestStatus());
        assertEquals(404, server.serve(session("/storage_info", NanoHTTPD.Method.POST)).getStatus().getRequestStatus());
    }

    @Test
    public void getChunk_returnsNotFoundWhenChunkPathIsDirectory() throws Exception {
        String chunkId = sha256(bytes("dir"));
        assertTrue(new File(storageDir, chunkId).mkdirs());

        NanoHTTPD.Response response = server.serve(session("/chunk/" + chunkId, NanoHTTPD.Method.GET));

        assertEquals(404, response.getStatus().getRequestStatus());
        assertEquals("not_found", new JSONObject(responseBody(response)).getString("error"));
    }

    @Test
    public void putChunk_returnsMissingContentWhenBodyParseProvidesNothing() throws Exception {
        NanoHTTPD.IHTTPSession session = session("/chunk/" + sha256(bytes("abc")), NanoHTTPD.Method.PUT);
        Map<String, String> headers = new HashMap<>();
        headers.put("content-length", "3");
        when(session.getHeaders()).thenReturn(headers);
        doAnswer(invocation -> null).when(session).parseBody(anyMap());

        NanoHTTPD.Response response = server.serve(session);

        assertEquals(400, response.getStatus().getRequestStatus());
        assertEquals("missing_content", new JSONObject(responseBody(response)).getString("error"));
    }

    @Test
    public void putChunk_returnsChecksumIncorrectForMismatchedBody() throws Exception {
        String chunkId = sha256(bytes("expected"));

        NanoHTTPD.Response response = server.serve(putSessionWithPostData("/chunk/" + chunkId, bytes("actual")));

        assertEquals(400, response.getStatus().getRequestStatus());
        assertEquals("checksum_incorrect", new JSONObject(responseBody(response)).getString("error"));
    }

    @Test
    public void putGetListInfoAndDeleteChunk_coverHappyPath() throws Exception {
        byte[] body = bytes("payload");
        String chunkId = sha256(body);

        NanoHTTPD.Response putResponse = server.serve(putSession("/chunk/" + chunkId, body));
        assertEquals(200, putResponse.getStatus().getRequestStatus());
        assertEquals("OK", responseBody(putResponse));

        File storedFile = new File(storageDir, chunkId);
        assertTrue(storedFile.exists());

        NanoHTTPD.Response getResponse = server.serve(session("/chunk/" + chunkId, NanoHTTPD.Method.GET));
        assertEquals(200, getResponse.getStatus().getRequestStatus());
        assertArrayEquals(body, responseBytes(getResponse));

        NanoHTTPD.Response listResponse = server.serve(session("/chunks/list", NanoHTTPD.Method.GET));
        JSONArray listJson = new JSONArray(responseBody(listResponse));
        assertEquals(1, listJson.length());
        assertEquals(chunkId, listJson.getString(0));

        NanoHTTPD.Response infoResponse = server.serve(session("/storage_info", NanoHTTPD.Method.GET));
        JSONObject infoJson = new JSONObject(responseBody(infoResponse));
        assertEquals(body.length, infoJson.getLong("used_space"));
        assertTrue(infoJson.getLong("total_space") >= infoJson.getLong("used_space"));

        NanoHTTPD.Response deleteResponse = server.serve(session("/chunk/" + chunkId, NanoHTTPD.Method.DELETE));
        assertEquals(200, deleteResponse.getStatus().getRequestStatus());
        assertEquals("OK", responseBody(deleteResponse));

        NanoHTTPD.Response missingDeleteResponse = server.serve(session("/chunk/" + chunkId, NanoHTTPD.Method.DELETE));
        assertEquals(404, missingDeleteResponse.getStatus().getRequestStatus());
        assertEquals("not_found", new JSONObject(responseBody(missingDeleteResponse)).getString("error"));
    }

    @Test
    public void listChunks_ignoresInvalidFilenamesAndDirectories() throws Exception {
        byte[] body = bytes("payload");
        String chunkId = sha256(body);
        Files.write(new File(storageDir, chunkId).toPath(), body);
        assertTrue(new File(storageDir, "not-a-hash").createNewFile());
        assertTrue(new File(storageDir, "subdir").mkdirs());

        NanoHTTPD.Response listResponse = server.serve(session("/chunks/list", NanoHTTPD.Method.GET));

        JSONArray listJson = new JSONArray(responseBody(listResponse));
        assertEquals(1, listJson.length());
        assertEquals(chunkId, listJson.getString(0));
    }

    @Test
    public void putChunk_acceptsEmptyContentLength() throws Exception {
        byte[] empty = new byte[0];
        String chunkId = sha256(empty);

        NanoHTTPD.Response response = server.serve(emptyPutSession("/chunk/" + chunkId));

        assertEquals(200, response.getStatus().getRequestStatus());
        assertTrue(new File(storageDir, chunkId).exists());
    }

    @Test
    public void putChunk_defaultsMissingContentLengthToEmptyBody() throws Exception {
        String chunkId = sha256(new byte[0]);

        NanoHTTPD.Response response = server.serve(session("/chunk/" + chunkId, NanoHTTPD.Method.PUT));

        assertEquals(200, response.getStatus().getRequestStatus());
        assertTrue(new File(storageDir, chunkId).exists());
    }

    @Test
    public void getChunk_returnsCorruptedChunkWhenStoredFileHashDoesNotMatchName() throws Exception {
        String chunkId = sha256(bytes("expected"));
        Files.write(new File(storageDir, chunkId).toPath(), bytes("different"));

        NanoHTTPD.Response response = server.serve(session("/chunk/" + chunkId, NanoHTTPD.Method.GET));

        assertEquals(404, response.getStatus().getRequestStatus());
        assertEquals("corrupted_chunk", new JSONObject(responseBody(response)).getString("error"));
    }

    @Test
    public void healthCheck_reportsBadChunksAndUsesCacheWithinMaxAge() throws Exception {
        String badChunkId = sha256(bytes("expected"));
        File badChunk = new File(storageDir, badChunkId);
        Files.write(badChunk.toPath(), bytes("different"));

        NanoHTTPD.Response first = server.serve(getWithParams("/chunks/healthcheck", Collections.singletonMap("max_age", "300")));
        JSONObject firstJson = new JSONObject(responseBody(first));
        assertEquals("degraded", firstJson.getString("status"));
        assertEquals(badChunkId, firstJson.getJSONArray("bad_chunks").getString(0));

        assertTrue(badChunk.delete());

        NanoHTTPD.Response second = server.serve(getWithParams("/chunks/healthcheck", Collections.singletonMap("max_age", "300")));
        JSONObject secondJson = new JSONObject(responseBody(second));
        assertEquals("degraded", secondJson.getString("status"));
        assertEquals(badChunkId, secondJson.getJSONArray("bad_chunks").getString(0));
    }

    @Test
    public void healthCheck_returnsHealthyWhenStorageIsClean() throws Exception {
        NanoHTTPD.Response response = server.serve(getWithParams("/chunks/healthcheck", Collections.singletonMap("max_age", "0")));

        JSONObject json = new JSONObject(responseBody(response));
        assertEquals("healthy", json.getString("status"));
        assertEquals(0, json.getJSONArray("bad_chunks").length());
    }

    @Test
    public void healthCheck_withoutMaxAgeUsesDefaultAndIgnoresDirectoriesAndInvalidNames() throws Exception {
        assertTrue(new File(storageDir, "not-a-hash").createNewFile());
        assertTrue(new File(storageDir, "subdir").mkdirs());

        NanoHTTPD.Response response = server.serve(session("/chunks/healthcheck", NanoHTTPD.Method.GET));

        JSONObject json = new JSONObject(responseBody(response));
        assertEquals("healthy", json.getString("status"));
        assertEquals(0, json.getJSONArray("bad_chunks").length());
    }

    @Test
    public void healthCheck_treatsValidChunksAsHealthy() throws Exception {
        byte[] body = bytes("payload");
        String chunkId = sha256(body);
        Files.write(new File(storageDir, chunkId).toPath(), body);

        NanoHTTPD.Response response = server.serve(getWithParams("/chunks/healthcheck", Collections.singletonMap("max_age", "0")));

        JSONObject json = new JSONObject(responseBody(response));
        assertEquals("healthy", json.getString("status"));
        assertEquals(0, json.getJSONArray("bad_chunks").length());
    }

    @Test
    public void healthCheck_recomputesWhenCacheExpired() throws Exception {
        String badChunkId = sha256(bytes("expected"));
        File badChunk = new File(storageDir, badChunkId);
        Files.write(badChunk.toPath(), bytes("different"));
        server.serve(getWithParams("/chunks/healthcheck", Collections.singletonMap("max_age", "300")));

        assertTrue(badChunk.delete());

        NanoHTTPD.Response response = server.serve(getWithParams("/chunks/healthcheck", Collections.singletonMap("max_age", "0")));

        JSONObject json = new JSONObject(responseBody(response));
        assertEquals("healthy", json.getString("status"));
    }

    @Test
    public void serve_returnsInternalErrorWhenHealthCheckParameterIsInvalid() throws Exception {
        NanoHTTPD.Response response = server.serve(getWithParams("/chunks/healthcheck", Collections.singletonMap("max_age", "abc")));

        assertEquals(500, response.getStatus().getRequestStatus());
        assertTrue(responseBody(response).contains("For input string"));
    }

    @Test
    public void putChunk_returnsInsufficientStorageWhenDiskBudgetIsTooLow() throws Exception {
        server.usableSpace = 49L * 1024 * 1024;
        String chunkId = sha256(bytes("payload"));

        NanoHTTPD.Response response = server.serve(putSession("/chunk/" + chunkId, bytes("payload")));

        assertEquals(507, response.getStatus().getRequestStatus());
        assertEquals("insufficient_storage", new JSONObject(responseBody(response)).getString("error"));
    }

    @Test
    public void deleteChunk_returnsInternalErrorWhenFileDeletionFails() throws Exception {
        byte[] body = bytes("payload");
        String chunkId = sha256(body);
        Files.write(new File(storageDir, chunkId).toPath(), body);
        server.failDeletes = true;

        NanoHTTPD.Response response = server.serve(session("/chunk/" + chunkId, NanoHTTPD.Method.DELETE));

        assertEquals(500, response.getStatus().getRequestStatus());
        assertEquals("Delete failed", responseBody(response));
    }

    @Test
    public void deleteChunk_returnsNotFoundWhenChunkPathIsDirectory() throws Exception {
        String chunkId = sha256(bytes("dir"));
        assertTrue(new File(storageDir, chunkId).mkdirs());

        NanoHTTPD.Response response = server.serve(session("/chunk/" + chunkId, NanoHTTPD.Method.DELETE));

        assertEquals(404, response.getStatus().getRequestStatus());
        assertEquals("not_found", new JSONObject(responseBody(response)).getString("error"));
    }

    @Test
    public void moveChunkIntoPlace_fallsBackToCopyWhenRenameFails() throws Exception {
        File tempFile = temporaryFolder.newFile("temp.bin");
        byte[] data = bytes("copy-me");
        Files.write(tempFile.toPath(), data);
        File targetFile = new File(storageDir, "copied.bin");
        server.failRename = true;

        server.moveChunkIntoPlace(tempFile, targetFile);

        assertArrayEquals(data, Files.readAllBytes(targetFile.toPath()));
        assertFalse(tempFile.exists());
    }

    @Test
    public void listAndStorageInfoHandleNullDirectoryListing() throws Exception {
        server.returnNullListings = true;

        NanoHTTPD.Response listResponse = server.serve(session("/chunks/list", NanoHTTPD.Method.GET));
        NanoHTTPD.Response infoResponse = server.serve(session("/storage_info", NanoHTTPD.Method.GET));

        assertEquals(0, new JSONArray(responseBody(listResponse)).length());
        assertEquals(0, new JSONObject(responseBody(infoResponse)).getLong("used_space"));
    }

    @Test
    public void healthCheck_handlesNullDirectoryListing() throws Exception {
        server.returnNullListings = true;

        NanoHTTPD.Response response = server.serve(getWithParams("/chunks/healthcheck", Collections.singletonMap("max_age", "0")));

        JSONObject json = new JSONObject(responseBody(response));
        assertEquals("healthy", json.getString("status"));
        assertEquals(0, json.getJSONArray("bad_chunks").length());
    }

    @Test
    public void storageInfo_ignoresDirectoriesWhenSummingUsedSpace() throws Exception {
        Files.write(new File(storageDir, "plain-file").toPath(), bytes("abc"));
        assertTrue(new File(storageDir, "subdir").mkdirs());

        NanoHTTPD.Response response = server.serve(session("/storage_info", NanoHTTPD.Method.GET));

        JSONObject json = new JSONObject(responseBody(response));
        assertEquals(3, json.getLong("used_space"));
    }

    private static NanoHTTPD.IHTTPSession session(String uri, NanoHTTPD.Method method) {
        NanoHTTPD.IHTTPSession session = mock(NanoHTTPD.IHTTPSession.class);
        when(session.getUri()).thenReturn(uri);
        when(session.getMethod()).thenReturn(method);
        when(session.getHeaders()).thenReturn(new HashMap<>());
        when(session.getParms()).thenReturn(new HashMap<>());
        return session;
    }

    private static NanoHTTPD.IHTTPSession getWithParams(String uri, Map<String, String> params) {
        NanoHTTPD.IHTTPSession session = session(uri, NanoHTTPD.Method.GET);
        when(session.getParms()).thenReturn(params);
        return session;
    }

    private static NanoHTTPD.IHTTPSession putSession(String uri, byte[] body) throws Exception {
        NanoHTTPD.IHTTPSession session = session(uri, NanoHTTPD.Method.PUT);
        Map<String, String> headers = new HashMap<>();
        headers.put("content-length", String.valueOf(body.length));
        when(session.getHeaders()).thenReturn(headers);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<String, String> files = invocation.getArgument(0);
            File tempFile = File.createTempFile("body", ".tmp");
            Files.write(tempFile.toPath(), body);
            files.put("content", tempFile.getAbsolutePath());
            return null;
        }).when(session).parseBody(anyMap());
        return session;
    }

    private static NanoHTTPD.IHTTPSession putSessionWithPostData(String uri, byte[] body) throws Exception {
        NanoHTTPD.IHTTPSession session = session(uri, NanoHTTPD.Method.PUT);
        Map<String, String> headers = new HashMap<>();
        headers.put("content-length", String.valueOf(body.length));
        when(session.getHeaders()).thenReturn(headers);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<String, String> files = invocation.getArgument(0);
            files.put("postData", new String(body, StandardCharsets.ISO_8859_1));
            return null;
        }).when(session).parseBody(anyMap());
        return session;
    }

    private NanoHTTPD.IHTTPSession emptyPutSession(String uri) throws Exception {
        NanoHTTPD.IHTTPSession session = session(uri, NanoHTTPD.Method.PUT);
        Map<String, String> headers = new HashMap<>();
        headers.put("content-length", "0");
        when(session.getHeaders()).thenReturn(headers);
        File tempFile = server.createTempFile();
        Files.write(tempFile.toPath(), new byte[0]);
        return session;
    }

    private static byte[] responseBytes(NanoHTTPD.Response response) throws Exception {
        try (InputStream inputStream = response.getData();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
            return outputStream.toByteArray();
        }
    }

    private static String responseBody(NanoHTTPD.Response response) throws Exception {
        return new String(responseBytes(response), StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(data);
        StringBuilder builder = new StringBuilder();
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                builder.append('0');
            }
            builder.append(hex);
        }
        return builder.toString();
    }

    private static class TestStorageServer extends StorageServer {
        long usableSpace = Long.MAX_VALUE;
        boolean failDeletes;
        boolean failRename;
        boolean returnNullListings;

        TestStorageServer(int port, File storageDir) {
            super(port, storageDir);
        }

        @Override
        long getUsableSpace() {
            return usableSpace;
        }

        @Override
        boolean deleteFile(File file) {
            return !failDeletes && super.deleteFile(file);
        }

        @Override
        boolean renameFile(File source, File target) {
            return !failRename && super.renameFile(source, target);
        }

        @Override
        File[] listStorageFiles() {
            return returnNullListings ? null : super.listStorageFiles();
        }
    }
}
