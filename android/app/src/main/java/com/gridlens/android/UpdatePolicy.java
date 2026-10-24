package com.gridlens.android;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.MessageDigest;

/** Pure validation shared by the downloader and its JVM tests. */
public final class UpdatePolicy {
    public static final String REPOSITORY = "MachoDrone/gridlens-android-releases";
    public static final String MANIFEST_URL = "https://github.com/" + REPOSITORY
            + "/releases/latest/download/update.json";
    public static final long MAX_APK_BYTES = 256L * 1024 * 1024;
    public static final int MAX_MANIFEST_BYTES = 16 * 1024;
    private static final String RELEASE_PREFIX = "/" + REPOSITORY + "/releases/download/";

    private UpdatePolicy() { }

    public static final class Release {
        public final int versionCode;
        public final String versionName;
        public final URI apkUri;
        public final String sha256;
        public final long size;
        public final int minSdk;

        public Release(int schemaVersion, long versionCode, String versionName, String apkUrl,
                       String sha256, long size, int minSdk) {
            require(schemaVersion == 1, "Unsupported update manifest.");
            require(versionCode > 0 && versionCode <= Integer.MAX_VALUE, "Invalid update version.");
            require(versionName != null && versionName.length() <= 64
                    && versionName.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9]+(?:[.-][A-Za-z0-9]+)*)?"),
                    "Invalid update name.");
            require(sha256 != null && sha256.matches("[0-9a-f]{64}"), "Invalid update checksum.");
            require(size > 0 && size <= MAX_APK_BYTES, "Invalid update size.");
            require(minSdk >= 26 && minSdk <= 100, "Invalid Android requirement.");
            URI uri = parse(apkUrl);
            require(isHttps(uri) && "github.com".equals(uri.getHost())
                    && uri.getRawQuery() == null
                    && (RELEASE_PREFIX + "v" + versionName + "/gridlens-android.apk").equals(uri.getRawPath()),
                    "The update is outside the GridLens release repository.");
            this.versionCode = (int) versionCode;
            this.versionName = versionName;
            this.apkUri = uri;
            this.sha256 = sha256;
            this.size = size;
            this.minSdk = minSdk;
        }

        public boolean isNewerThan(long installedVersionCode) {
            return versionCode > installedVersionCode;
        }
    }

    /** GitHub's first-party release redirect; no arbitrary host or protocol redirect. */
    public static boolean allowedDownloadUri(URI uri, String artifactName) {
        if (!isHttps(uri)) return false;
        if ("release-assets.githubusercontent.com".equals(uri.getHost())) {
            return uri.getRawPath() != null && uri.getRawPath().startsWith("/github-production-release-asset/");
        }
        if (!"github.com".equals(uri.getHost()) || uri.getRawQuery() != null) return false;
        String path = uri.getRawPath();
        if ("update.json".equals(artifactName)
                && ("/" + REPOSITORY + "/releases/latest/download/update.json").equals(path)) return true;
        return path != null && path.startsWith(RELEASE_PREFIX)
                && path.substring(RELEASE_PREFIX.length()).matches("v[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9]+(?:[.-][A-Za-z0-9]+)*)?/"
                + ("update.json".equals(artifactName) ? "update\\.json" : "gridlens-android\\.apk"));
    }

    public static void verifyDownloaded(Release release, long actualSize, byte[] actualSha256) {
        require(actualSize == release.size, "The update download is incomplete.");
        require(actualSha256 != null && actualSha256.length == 32
                && MessageDigest.isEqual(decodeHex(release.sha256), actualSha256),
                "The update checksum did not match.");
    }

    public static void verifyPackage(Release release, String installedPackage, long installedVersion,
                                     byte[][] installedSigners, String candidatePackage,
                                     long candidateVersion, String candidateName, int candidateMinSdk,
                                     byte[][] candidateSigners, int deviceSdk) {
        require(installedPackage != null && installedPackage.equals(candidatePackage),
                "The download is not a GridLens update.");
        require(release.isNewerThan(installedVersion) && candidateVersion == release.versionCode,
                "The update version did not match or is not newer.");
        require(release.versionName.equals(candidateName), "The update name did not match.");
        require(release.minSdk == candidateMinSdk && candidateMinSdk <= deviceSdk,
                "This update requires a newer Android version.");
        require(sameSigners(installedSigners, candidateSigners),
                "The update was not signed with the installed GridLens signing key.");
    }

    static boolean sameSigners(byte[][] first, byte[][] second) {
        if (first == null || second == null || first.length == 0 || first.length != second.length) return false;
        // Strict current-certificate equality deliberately does not accept signing-key rotation.
        boolean[] used = new boolean[second.length];
        for (byte[] signer : first) {
            if (signer == null || signer.length == 0) return false;
            boolean found = false;
            for (int i = 0; i < second.length; i++) {
                if (!used[i] && second[i] != null && MessageDigest.isEqual(signer, second[i])) {
                    used[i] = true;
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    static URI parse(String value) {
        try {
            require(value != null && value.length() <= 8192, "Invalid download address.");
            return new URI(value);
        } catch (URISyntaxException error) {
            throw new ValidationFailure("Invalid download address.");
        }
    }

    private static boolean isHttps(URI uri) {
        return uri != null && "https".equals(uri.getScheme()) && uri.getHost() != null
                && uri.getRawUserInfo() == null && uri.getRawFragment() == null
                && uri.getPort() == -1;
    }

    private static byte[] decodeHex(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new ValidationFailure(message);
    }

    public static final class ValidationFailure extends IllegalArgumentException {
        ValidationFailure(String message) { super(message); }
    }
}
