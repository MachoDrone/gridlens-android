package com.gridlens.android;

import org.json.JSONObject;

/** Exact public release metadata shared by the manifest reader and durable download record. */
final class UpdateReleaseJson {
    static UpdatePolicy.Release parse(JSONObject json) throws Exception {
        if (json.length() != 7) throw new IllegalArgumentException("The release manifest format is not supported.");
        return new UpdatePolicy.Release((int) integer(json, "schemaVersion", 1, 1), integer(json, "versionCode", 1, Integer.MAX_VALUE),
                string(json, "versionName"), string(json, "apkUrl"), string(json, "sha256"), integer(json, "size", 1, UpdatePolicy.MAX_APK_BYTES),
                (int) integer(json, "minSdk", 26, 100));
    }
    static JSONObject encode(UpdatePolicy.Release release) throws Exception {
        return new JSONObject().put("schemaVersion", 1).put("versionCode", release.versionCode).put("versionName", release.versionName)
                .put("apkUrl", release.apkUri.toString()).put("sha256", release.sha256).put("size", release.size).put("minSdk", release.minSdk);
    }
    static long integer(JSONObject json, String key, long min, long max) throws Exception {
        Object value = json.get(key);
        if (!(value instanceof Integer) && !(value instanceof Long)) throw new IllegalArgumentException("Invalid update number");
        long number = ((Number) value).longValue(); if (number < min || number > max) throw new IllegalArgumentException("Invalid update number"); return number;
    }
    static String string(JSONObject json, String key) throws Exception {
        Object value = json.get(key); if (!(value instanceof String)) throw new IllegalArgumentException("Invalid update value"); return (String) value;
    }
    private UpdateReleaseJson() {}
}
