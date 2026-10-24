package com.gridlens.android;

import android.content.Context;
import android.content.res.AssetManager;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** One embedded Node instance per Android process; Activity recreation never restarts it. */
final class NodeRuntime {
    private static boolean started;
    private static volatile boolean prepared;
    private static volatile String failure;
    static native int startNative(String[] arguments, String workingDirectory, String cacheDirectory);

    static synchronized void start(Context context) {
        if (started) return;
        started = true;
        Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                File data = new File(app.getFilesDir(), "private-data");
                if (!data.isDirectory() && !data.mkdirs()) throw new IOException("Cannot create private data directory");
                File ready = new File(data, "runtime.json");
                if (ready.exists() && !ready.delete()) throw new IOException("Cannot clear prior runtime state");
                File runtime = new File(app.getFilesDir(), "runtime");
                copyAssets(app.getAssets(), "runtime", runtime);
                int port = app.getSharedPreferences("runtime", Context.MODE_PRIVATE).getInt("port", 0);
                System.loadLibrary("node");
                System.loadLibrary("gridlens-runtime");
                prepared = true;
                int result = startNative(new String[]{"node", "--max-old-space-size=256",
                    new File(runtime, "server.mjs").getAbsolutePath(), data.getAbsolutePath(), String.valueOf(port)},
                    runtime.getAbsolutePath(), app.getCacheDir().getAbsolutePath());
                failure = "The on-phone runtime stopped (" + result + "). Force stop GridLens in Android Settings, then reopen it.";
            } catch (Exception | LinkageError error) {
                failure = "The on-phone runtime could not start. Force stop GridLens in Android Settings, then reopen it.";
            }
        }, "GridLens-Node").start();
    }

    static String failure() { return failure; }

    static JSONObject ready(Context context) throws Exception {
        if (!prepared) return null;
        File file = new File(context.getFilesDir(), "private-data/runtime.json");
        if (!file.isFile() || file.length() > 4096) return null;
        try (InputStream input = new FileInputStream(file)) {
            JSONObject value = new JSONObject(new String(readBounded(input, 4096), StandardCharsets.UTF_8));
            int port = value.getInt("port");
            String token = value.getString("token");
            if (port < 1 || port > 65535 || !token.matches("[A-Za-z0-9_-]{32,256}")) throw new IOException("Invalid runtime state");
            return value;
        }
    }

    static byte[] readBounded(InputStream input, int maximum) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > maximum) throw new IOException("File is too large");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static void copyAssets(AssetManager assets, String source, File target) throws IOException {
        String[] children = assets.list(source);
        if (children != null && children.length > 0) {
            if (!target.isDirectory() && !target.mkdirs()) throw new IOException("Cannot unpack runtime");
            for (String child : children) copyAssets(assets, source + "/" + child, new File(target, child));
        } else {
            try (InputStream input = assets.open(source); FileOutputStream output = new FileOutputStream(target)) {
                byte[] buffer = new byte[32768];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            }
        }
    }
}
