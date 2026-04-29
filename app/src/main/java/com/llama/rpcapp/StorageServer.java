package com.llama.rpcapp;

import android.util.Log;
import fi.iki.elonen.NanoHTTPD;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class StorageServer extends NanoHTTPD {
    private static final String TAG = "StorageServer";
    private final File storageDir;
    //validate hashes as SHA256 string
    private static final Pattern SHA256_PATTERN = Pattern.compile("^[a-fA-F0-9]{64}$");

    private static class StorageHealth {
        long timestamp;
        String status;
        List<String> badChunks;
    }

    private volatile StorageHealth storageHealth;

    //new custom status
    private static final Response.IStatus INSUFFICIENT_STORAGE = new Response.IStatus() {
        @Override public String getDescription() { return "507 Insufficient Storage"; }
        @Override public int getRequestStatus() { return 507; }
    };

    //init
    public StorageServer(int port, File storageDir) {
        super(port);
        this.storageDir = storageDir;
    }

    /* HTTP request handler 
     *
     * GET /chunk/{id} - Retrieve chunk by ID (returns 404 if not found or corrupted)
     * PUT /chunk/{id} - Upload chunk (body is raw data, must match ID hash, returns 400 if checksum fails or insufficient storage)
     * DELETE /chunk/{id} - Delete chunk by ID (returns 404 if not found)
     * GET /chunks/list - List all chunk IDs
     * GET /chunks/healthcheck?max_age={seconds} - Check storage health, use cached result if not older than max_age
     * GET /storage_info - Get total, used, and available storage space
    */
    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();
        Log.d(TAG, "Request: " + method + " " + uri);

        try {
            if (uri.startsWith("/chunk/")) {
                String chunkId = uri.substring("/chunk/".length());

                //check validity of chunkId as SHA256 hash string
                if (!SHA256_PATTERN.matcher(chunkId).matches()) {
                    //match return format: 400 + either plaintext or JSON
                    if (Method.PUT.equals(method)) {
                        return jsonResponse(Response.Status.BAD_REQUEST, new JSONObject().put("error", "bad_sha256"));
                    }
                    return newFixedLengthResponse(Response.Status.BAD_REQUEST, NanoHTTPD.MIME_PLAINTEXT, "Invalid chunk ID format");
                }
                if (Method.GET.equals(method)) {
                    return handleGetChunk(chunkId);
                } else if (Method.PUT.equals(method)) {
                    return handlePutChunk(chunkId, session);
                } else if (Method.DELETE.equals(method)) {
                    return handleDeleteChunk(chunkId);
                }
            } else if ("/chunks/list".equals(uri) && Method.GET.equals(method)) {
                return handleListChunks();
            } else if ("/chunks/healthcheck".equals(uri) && Method.GET.equals(method)) {
                return handleHealthCheck(session);
            } else if ("/storage_info".equals(uri) && Method.GET.equals(method)) {
                return handleStorageInfo();
            }
        } catch (Exception e) {
            Log.e(TAG, "Unhandled exception", e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, e.getMessage());
        }
        return newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "Not Found");
    }

    private Response jsonResponse(Response.IStatus status, Object json) {
        return newFixedLengthResponse(status, "application/json", json.toString());
    }

    private Response handleGetChunk(String chunkId) throws Exception {
        File file = new File(storageDir, chunkId);
        if (!file.exists() || !file.isFile()) {
            return jsonResponse(Response.Status.NOT_FOUND, new JSONObject().put("error", "not_found"));
        }

        String actualHash = computeSHA256(file);
        if (!actualHash.equalsIgnoreCase(chunkId)) {
            return jsonResponse(Response.Status.NOT_FOUND, new JSONObject().put("error", "corrupted_chunk"));
        }

        return newChunkedResponse(Response.Status.OK, "application/octet-stream", new FileInputStream(file));
    }

    private Response handlePutChunk(String chunkId, IHTTPSession session) throws Exception {
        long contentLength = 0;
        String clStr = session.getHeaders().get("content-length");
        if (clStr != null) {
            contentLength = Long.parseLong(clStr);
        }

        if (storageDir.getUsableSpace() - contentLength < 50 * 1024 * 1024) {
            return jsonResponse(INSUFFICIENT_STORAGE, new JSONObject().put("error", "insufficient_storage"));
        }

        Map<String, String> files = new HashMap<>();
        session.parseBody(files);

        String tempFilePath = files.get("content");
        File tempFile;
        if (tempFilePath != null) {
            tempFile = new File(tempFilePath);
        } else {
            String postData = files.get("postData");
            if (postData == null) {
                return jsonResponse(Response.Status.BAD_REQUEST, new JSONObject().put("error", "missing_content"));
            }
            tempFile = File.createTempFile("chunk", ".tmp", storageDir);
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                fos.write(postData.getBytes("ISO-8859-1"));
            }
        }
        String actualHash = computeSHA256(tempFile);

        if (!actualHash.equalsIgnoreCase(chunkId)) {
            tempFile.delete();
            return jsonResponse(Response.Status.BAD_REQUEST, new JSONObject().put("error", "checksum_incorrect"));
        }

        File targetFile = new File(storageDir, chunkId);
        if (!tempFile.renameTo(targetFile)) {
            copyFile(tempFile, targetFile);
            tempFile.delete();
        }

        storageHealth = null;

        return newFixedLengthResponse(Response.Status.OK, NanoHTTPD.MIME_PLAINTEXT, "OK");
    }

    private Response handleDeleteChunk(String chunkId) throws Exception {
        File file = new File(storageDir, chunkId);
        if (!file.exists() || !file.isFile()) {
            return jsonResponse(Response.Status.NOT_FOUND, new JSONObject().put("error", "not_found"));
        }

        if (file.delete()) {
            storageHealth = null;
            return newFixedLengthResponse(Response.Status.OK, NanoHTTPD.MIME_PLAINTEXT, "OK");
        }
        return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, "Delete failed");
    }

    private Response handleListChunks() throws Exception {
        JSONArray array = new JSONArray();
        File[] files = storageDir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && SHA256_PATTERN.matcher(f.getName()).matches()) {
                    array.put(f.getName());
                }
            }
        }
        return jsonResponse(Response.Status.OK, array);
    }

    private synchronized Response handleHealthCheck(IHTTPSession session) throws Exception {
        Map<String, String> params = session.getParms();
        String maxAgeStr = params.get("max_age");
        long maxAge = maxAgeStr != null ? Long.parseLong(maxAgeStr) : 300;

        long now = System.currentTimeMillis();
        if (storageHealth != null && (now - storageHealth.timestamp) < (maxAge * 1000L)) {
            JSONObject json = new JSONObject()
                    .put("status", storageHealth.status)
                    .put("bad_chunks", new JSONArray(storageHealth.badChunks));
            return jsonResponse(Response.Status.OK, json);
        }

        StorageHealth newHealth = new StorageHealth();
        newHealth.timestamp = now;
        newHealth.badChunks = new ArrayList<>();

        File[] files = storageDir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && SHA256_PATTERN.matcher(f.getName()).matches()) {
                    String actual = computeSHA256(f);
                    if (!actual.equalsIgnoreCase(f.getName())) {
                        newHealth.badChunks.add(f.getName());
                    }
                }
            }
        }

        newHealth.status = newHealth.badChunks.isEmpty() ? "healthy" : "degraded";
        storageHealth = newHealth;

        JSONObject json = new JSONObject()
                .put("status", storageHealth.status)
                .put("bad_chunks", new JSONArray(storageHealth.badChunks));
        return jsonResponse(Response.Status.OK, json);
    }

    private Response handleStorageInfo() throws Exception {
        long total = storageDir.getTotalSpace();
        long available = storageDir.getUsableSpace();
        long used = 0;

        File[] files = storageDir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile()) {
                    used += f.length();
                }
            }
        }

        JSONObject json = new JSONObject()
                .put("total_space", total)
                .put("used_space", used)
                .put("available_space", available);
        return jsonResponse(Response.Status.OK, json);
    }

    private String computeSHA256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream is = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        byte[] hash = digest.digest();
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }

    private void copyFile(File source, File dest) throws IOException {
        try (InputStream is = new FileInputStream(source);
             FileOutputStream os = new FileOutputStream(dest)) {
            byte[] buffer = new byte[65536];
            int length;
            while ((length = is.read(buffer)) > 0) {
                os.write(buffer, 0, length);
            }
        }
    }
}
