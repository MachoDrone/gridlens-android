package com.gridlens.android;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public final class UpdateDownloadEngineTest {
    private UpdatePolicy.Release release(byte[] bytes) throws Exception {
        StringBuilder hash = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) hash.append(String.format("%02x", b & 255));
        return new UpdatePolicy.Release(1, 999, "0.0.999", "https://github.com/MachoDrone/gridlens-android-releases/releases/download/v0.0.999/gridlens-android.apk", hash.toString(), bytes.length, 26);
    }
    private static class Response implements UpdateDownloadEngine.Response {
        final int status; final String location; final long length; final InputStream bytes;
        boolean closed; int openedBody;
        Response(int status, String location, long length, byte[] bytes) { this(status, location, length, new ByteArrayInputStream(bytes)); }
        Response(int status, String location, long length, InputStream bytes) { this.status = status; this.location = location; this.length = length; this.bytes = bytes; }
        public int status() { return status; }
        public String location() { return location; }
        public long contentLength() { return length; }
        public InputStream body() { openedBody++; return bytes; }
        public void close() { closed = true; try { bytes.close(); } catch (IOException ignored) {} }
    }

    @Test public void exactManifestBytesAreVerifiedAndProgressUsesTheExpectedTotal() throws Exception {
        byte[] bytes = new byte[]{1, 2, 3, 4, 5}; UpdatePolicy.Release release = release(bytes);
        Path file = Files.createTempFile("gridlens-transfer-", ".part");
        Response response = new Response(200, null, bytes.length, bytes);
        List<Long> progress = new ArrayList<>();
        try {
            UpdateDownloadEngine.transfer(release, file.toFile(), uri -> { assertEquals(release.apkUri, uri); return response; }, () -> {}, progress::add, () -> 0);
            assertArrayEquals(bytes, Files.readAllBytes(file)); assertEquals(List.of(5L), progress); assertTrue(response.closed);
        } finally { Files.deleteIfExists(file); }
    }

    @Test public void redirectsRemainInsideTheExistingHttpsReleaseAllowlist() throws Exception {
        byte[] bytes = new byte[]{1, 2}; UpdatePolicy.Release release = release(bytes);
        Path file = Files.createTempFile("gridlens-transfer-", ".part");
        try {
            String githubAsset = "https://release-assets.githubusercontent.com/github-production-release-asset/123/example?sig=fixture";
            List<URI> requests = new ArrayList<>();
            UpdateDownloadEngine.transfer(release, file.toFile(), uri -> {
                requests.add(uri); return requests.size() == 1 ? new Response(302, githubAsset, 0, new byte[0]) : new Response(200, null, bytes.length, bytes);
            }, () -> {}, ignored -> {}, () -> 0);
            assertEquals(List.of(release.apkUri, URI.create(githubAsset)), requests);
            for (String target : new String[]{"https://untrusted.example/update.apk", "http://github.com/MachoDrone/gridlens-android-releases/releases/download/v0.0.999/gridlens-android.apk",
                    "https://user:pass@release-assets.githubusercontent.com/github-production-release-asset/x", "file:///tmp/update.apk"}) {
                int[] calls = {0};
                assertThrows(IOException.class, () -> UpdateDownloadEngine.transfer(release, file.toFile(), uri -> {
                    calls[0]++; return new Response(302, target, 0, new byte[0]);
                }, () -> {}, ignored -> {}, () -> 0));
                assertEquals("A forbidden redirect must never be opened", 1, calls[0]);
            }
        } finally { Files.deleteIfExists(file); }
    }

    @Test public void advertisedMismatchAndOversizedStreamingBodiesFailBeforeExtraBytesAreWritten() throws Exception {
        byte[] expected = new byte[]{1, 2, 3}; UpdatePolicy.Release release = release(expected);
        Path file = Files.createTempFile("gridlens-transfer-", ".part");
        try {
            Response mismatch = new Response(200, null, 100, new byte[100]);
            assertThrows(IOException.class, () -> UpdateDownloadEngine.transfer(release, file.toFile(), uri -> mismatch, () -> {}, ignored -> {}, () -> 0));
            assertEquals(0, mismatch.openedBody); assertTrue(mismatch.closed);
            Response oversized = new Response(200, null, -1, new byte[4]);
            assertThrows(IOException.class, () -> UpdateDownloadEngine.transfer(release, file.toFile(), uri -> oversized, () -> {}, ignored -> {}, () -> 0));
            assertTrue(Files.size(file) <= expected.length); assertTrue(oversized.closed);
        } finally { Files.deleteIfExists(file); }
    }

    @Test public void truncatedAndTamperedBodiesCannotComplete() throws Exception {
        UpdatePolicy.Release release = release(new byte[]{1, 2, 3});
        Path file = Files.createTempFile("gridlens-transfer-", ".part");
        try {
            for (byte[] body : new byte[][]{new byte[]{1, 2}, new byte[]{1, 2, 4}}) {
                Response response = new Response(200, null, -1, body);
                assertThrows(UpdatePolicy.ValidationFailure.class, () -> UpdateDownloadEngine.transfer(release, file.toFile(), uri -> response, () -> {}, ignored -> {}, () -> 0));
                assertTrue(response.closed);
            }
        } finally { Files.deleteIfExists(file); }
    }

    @Test public void onlyCancellationOrBoundedFailureStopsAStartedTransfer() throws Exception {
        byte[] body = new byte[30]; UpdatePolicy.Release release = release(body);
        Path file = Files.createTempFile("gridlens-transfer-", ".part");
        AtomicBoolean cancelled = new AtomicBoolean();
        InputStream chunks = new ByteArrayInputStream(body) { @Override public synchronized int read(byte[] output, int offset, int length) { return super.read(output, offset, Math.min(length, 3)); } };
        Response response = new Response(200, null, body.length, chunks);
        try {
            assertThrows(IOException.class, () -> UpdateDownloadEngine.transfer(release, file.toFile(), uri -> response,
                    () -> { if (cancelled.get()) throw new IOException("Explicit cancellation"); }, downloaded -> cancelled.set(true), () -> 0));
            assertEquals(3, Files.size(file)); assertTrue(response.closed);
            AtomicLong elapsed = new AtomicLong();
            Response tooSlow = new Response(200, null, body.length, new ByteArrayInputStream(body) {
                @Override public synchronized int read(byte[] output, int offset, int length) { elapsed.set(UpdateDownloadEngine.MAX_TRANSFER_MS + 1); return super.read(output, offset, length); }
            });
            assertThrows(IOException.class, () -> UpdateDownloadEngine.transfer(release, file.toFile(), uri -> tooSlow, () -> {}, ignored -> {}, elapsed::get));
            assertTrue(tooSlow.closed);
        } finally { Files.deleteIfExists(file); }
    }

    @Test public void redirectLoopsAndHttpFailuresAreBounded() throws Exception {
        UpdatePolicy.Release release = release(new byte[]{1}); Path file = Files.createTempFile("gridlens-transfer-", ".part");
        try {
            int[] calls = {0};
            assertThrows(IOException.class, () -> UpdateDownloadEngine.transfer(release, file.toFile(), uri -> {
                calls[0]++; return new Response(302, release.apkUri.toString(), 0, new byte[0]);
            }, () -> {}, ignored -> {}, () -> 0));
            assertEquals(5, calls[0]);
            Response unavailable = new Response(503, null, 0, new byte[0]);
            assertThrows(IOException.class, () -> UpdateDownloadEngine.transfer(release, file.toFile(), uri -> unavailable, () -> {}, ignored -> {}, () -> 0));
            assertEquals(0, unavailable.openedBody); assertTrue(unavailable.closed);
        } finally { Files.deleteIfExists(file); }
    }

    @Test public void displayReportsDecimalMegabytesTotalAndPercentageWithoutPretendingUnknownSizeIsZero() {
        UpdateDownloadState progress = new UpdateDownloadState("fixture", "downloading", "0.0.999", 73_420_000, 197_210_000, "Downloading");
        assertEquals("73.42 / 197.21 MB · 37%", progress.progressText()); assertTrue(progress.active()); assertFalse(progress.ready());
        UpdateDownloadState ready = new UpdateDownloadState("fixture", "ready", "0.0.999", 197_210_000, 197_210_000, "Ready");
        assertEquals("197.21 / 197.21 MB · 100%", ready.progressText()); assertTrue(ready.ready()); assertFalse(ready.active());
        assertEquals("Size unavailable", new UpdateDownloadState(null, "failed", null, 0, 0, "Saved record unavailable").progressText());
        assertThrows(IllegalArgumentException.class, () -> new UpdateDownloadState("fixture", "ready", "0.0.999", 1, 2, "Not finished"));
    }
}
