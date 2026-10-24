package com.gridlens.android;

import org.junit.Test;

import java.net.URI;
import java.security.MessageDigest;

import static org.junit.Assert.*;

public final class UpdatePolicyTest {
    private static final String URL = "https://github.com/MachoDrone/gridlens-android-releases/releases/download/v0.2.0/gridlens-android.apk";
    private static final String HASH = "a".repeat(64);
    private static final byte[][] SIGNER = {{1, 2, 3}};

    private UpdatePolicy.Release release() {
        return new UpdatePolicy.Release(1, 2, "0.2.0", URL, HASH, 123, 26);
    }

    private void rejects(Runnable action) {
        assertThrows(IllegalArgumentException.class, action::run);
    }

    @Test public void acceptsPinnedRepositoryAndNewerBuild() {
        UpdatePolicy.Release release = release();
        assertTrue(release.isNewerThan(1));
        assertFalse(release.isNewerThan(2));
        assertFalse(release.isNewerThan(3));
        assertEquals(URI.create(URL), release.apkUri);
    }

    @Test public void rejectsWrongRepositoryOrUnpinnedApk() {
        for (String url : new String[] {
                URL.replace("MachoDrone", "someone"), URL.replace("gridlens-android-releases", "gridlens-android"),
                URL.replace("v0.2.0", "v0.3.0"), URL.replace("/download/v0.2.0/", "/latest/download/"),
                URL + "?token=secret", URL + "#fragment", URL.replace("https:", "http:"),
                URL.replace("github.com", "github.com.evil.test"), URL.replace("github.com", "github.com:443"),
                URL.replace("github.com", "person:password@github.com"), URL.replace("v0.2.0", "%76%30.2.0"),
                URL.replace("releases/download", "releases/../releases/download") }) {
            rejects(() -> new UpdatePolicy.Release(1, 2, "0.2.0", url, HASH, 123, 26));
        }
    }

    @Test public void rejectsInvalidManifestTypesAndBounds() {
        rejects(() -> new UpdatePolicy.Release(2, 2, "0.2.0", URL, HASH, 123, 26));
        rejects(() -> new UpdatePolicy.Release(1, 0, "0.2.0", URL, HASH, 123, 26));
        rejects(() -> new UpdatePolicy.Release(1, (long) Integer.MAX_VALUE + 1, "0.2.0", URL, HASH, 123, 26));
        rejects(() -> new UpdatePolicy.Release(1, 2, "../../escape", URL, HASH, 123, 26));
        rejects(() -> new UpdatePolicy.Release(1, 2, "0.2.0", URL, "missing", 123, 26));
        rejects(() -> new UpdatePolicy.Release(1, 2, "0.2.0", URL, HASH.toUpperCase(), 123, 26));
        rejects(() -> new UpdatePolicy.Release(1, 2, "0.2.0", URL, HASH, 0, 26));
        rejects(() -> new UpdatePolicy.Release(1, 2, "0.2.0", URL, HASH, UpdatePolicy.MAX_APK_BYTES + 1, 26));
        rejects(() -> new UpdatePolicy.Release(1, 2, "0.2.0", URL, HASH, 123, 25));
    }

    @Test public void permitsOnlyGitHubReleaseRedirects() {
        assertTrue(UpdatePolicy.allowedDownloadUri(URI.create(UpdatePolicy.MANIFEST_URL), "update.json"));
        assertTrue(UpdatePolicy.allowedDownloadUri(URI.create(URL.replace("gridlens-android.apk", "update.json")), "update.json"));
        assertTrue(UpdatePolicy.allowedDownloadUri(URI.create(URL), "gridlens-android.apk"));
        assertTrue(UpdatePolicy.allowedDownloadUri(URI.create("https://release-assets.githubusercontent.com/github-production-release-asset/123/a?sig=abc"), "gridlens-android.apk"));
        for (String url : new String[] {
                "https://example.com/update.apk", "http://github.com/MachoDrone/gridlens-android-releases/releases/download/v0.2.0/gridlens-android.apk",
                "https://raw.githubusercontent.com/MachoDrone/gridlens-android-releases/main/update.json",
                "https://release-assets.githubusercontent.com/anything", "https://user:pass@release-assets.githubusercontent.com/github-production-release-asset/a",
                "https://release-assets.githubusercontent.com.evil.test/github-production-release-asset/a",
                URL.replace("MachoDrone", "untrusted"), URL + "?redirect=1", URL.replace("v0.2.0", "%2e%2e") }) {
            assertFalse(url, UpdatePolicy.allowedDownloadUri(URI.create(url), "gridlens-android.apk"));
        }
        assertFalse(UpdatePolicy.allowedDownloadUri(URI.create(URL), "update.json"));
        assertFalse(UpdatePolicy.allowedDownloadUri(URI.create(UpdatePolicy.MANIFEST_URL), "gridlens-android.apk"));
    }

    @Test public void rejectsTamperedAndTruncatedDownload() throws Exception {
        byte[] bytes = {10, 20, 30};
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder hex = new StringBuilder();
        for (byte item : hash) hex.append(String.format("%02x", item & 255));
        UpdatePolicy.Release release = new UpdatePolicy.Release(1, 2, "0.2.0", URL, hex.toString(), bytes.length, 26);
        UpdatePolicy.verifyDownloaded(release, bytes.length, hash);
        rejects(() -> UpdatePolicy.verifyDownloaded(release, 2, hash));
        byte[] wrong = hash.clone();
        wrong[0] ^= 1;
        rejects(() -> UpdatePolicy.verifyDownloaded(release, 3, wrong));
        rejects(() -> UpdatePolicy.verifyDownloaded(release, 3, null));
    }

    @Test public void acceptsOnlySamePackageVersionAndCertificate() {
        UpdatePolicy.verifyPackage(release(), "com.gridlens.android", 1, SIGNER,
                "com.gridlens.android", 2, "0.2.0", 26, SIGNER, 35);
        rejects(() -> UpdatePolicy.verifyPackage(release(), "com.gridlens.android", 1, SIGNER,
                "com.evil.app", 2, "0.2.0", 26, SIGNER, 35));
        rejects(() -> UpdatePolicy.verifyPackage(release(), "com.gridlens.android", 1, SIGNER,
                "com.gridlens.android", 3, "0.2.0", 26, SIGNER, 35));
        rejects(() -> UpdatePolicy.verifyPackage(release(), "com.gridlens.android", 1, SIGNER,
                "com.gridlens.android", 2, "0.2.0", 26, new byte[][] {{4, 5, 6}}, 35));
        rejects(() -> UpdatePolicy.verifyPackage(release(), "com.gridlens.android", 1, SIGNER,
                "com.gridlens.android", 2, "0.2.0", 26, null, 35));
        rejects(() -> UpdatePolicy.verifyPackage(release(), "com.gridlens.android", 1, SIGNER,
                "com.gridlens.android", 2, "0.3.0", 26, SIGNER, 35));
    }

    @Test public void rejectsReinstallAndDowngrade() {
        for (long installed : new long[] {2, 3, Integer.MAX_VALUE}) {
            rejects(() -> UpdatePolicy.verifyPackage(release(), "com.gridlens.android", installed, SIGNER,
                    "com.gridlens.android", 2, "0.2.0", 26, SIGNER, 35));
        }
    }

    @Test public void rejectsIncompatibleAndroidAndManifestMismatch() {
        rejects(() -> UpdatePolicy.verifyPackage(release(), "com.gridlens.android", 1, SIGNER,
                "com.gridlens.android", 2, "0.2.0", 26, SIGNER, 25));
        rejects(() -> UpdatePolicy.verifyPackage(release(), "com.gridlens.android", 1, SIGNER,
                "com.gridlens.android", 2, "0.2.0", 27, SIGNER, 35));
    }

    @Test public void signerSetComparisonHasNoDuplicateBypass() {
        assertTrue(UpdatePolicy.sameSigners(new byte[][] {{1}, {2}}, new byte[][] {{2}, {1}}));
        assertFalse(UpdatePolicy.sameSigners(new byte[][] {{1}, {2}}, new byte[][] {{1}, {1}}));
        assertFalse(UpdatePolicy.sameSigners(new byte[][] {{1}}, new byte[][] {{1}, {2}}));
        assertFalse(UpdatePolicy.sameSigners(new byte[0][], new byte[0][]));
        assertFalse(UpdatePolicy.sameSigners(new byte[][] {{1}}, new byte[][] {{}}));
    }
}
