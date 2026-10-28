package com.gridlens.android;

import java.util.Arrays;

/** Narrow consent rules for PC-only access during the native discovery save. */
final class PcIpEnrollment {
    static boolean authenticatedPc(SshDiscovery.BatchRequest request, SshDiscovery.Result result) throws Exception {
        return result != null && result.pcMetadata != null
                && result.status != SshDiscovery.Status.AUTH_FAILED && result.status != SshDiscovery.Status.IDENTITY_CHANGED
                && result.status != SshDiscovery.Status.CANCELLED && result.status != SshDiscovery.Status.UNREACHABLE
                && MonitoringController.pcId(request.identity).equals(result.pcMetadata.pcId);
    }
    static boolean sameCredential(SshDiscovery.Credential authenticated, SshDiscovery.Credential remembered) {
        return authenticated.username.equals(remembered.username)
                && sameBytes(authenticated.password, remembered.password)
                && sameBytes(authenticated.privateKey, remembered.privateKey)
                && sameBytes(authenticated.passphrase, remembered.passphrase);
    }
    private static boolean sameBytes(byte[] left, byte[] right) {
        return (left == null || left.length == 0) && (right == null || right.length == 0) || Arrays.equals(left, right);
    }
    static boolean unusedInspection(boolean approved, boolean paused, String status, boolean setupPending,
            boolean aliasReplaced, boolean sourcePolicyClear) {
        return !approved && !paused && "authorization-required".equals(status) && !setupPending
                && !aliasReplaced && sourcePolicyClear;
    }
    private PcIpEnrollment() {}
}
