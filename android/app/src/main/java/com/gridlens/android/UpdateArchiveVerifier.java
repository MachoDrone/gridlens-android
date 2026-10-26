package com.gridlens.android;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import java.io.File;
import java.io.IOException;

/** Same installed package/version/certificate checks before durable readiness and every install. */
@SuppressWarnings("deprecation")
final class UpdateArchiveVerifier {
    static final class Failure extends IOException { Failure(String message) { super(message); } }
    static void verify(Context context, File apk, UpdatePolicy.Release release) throws Exception {
        PackageManager manager = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo candidate = manager.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        if (candidate == null || candidate.applicationInfo == null) throw new Failure("Android could not verify the downloaded APK.");
        PackageInfo installed = manager.getPackageInfo(context.getPackageName(), flags);
        UpdatePolicy.verifyPackage(release, installed.packageName, version(installed), signers(installed), candidate.packageName,
                version(candidate), candidate.versionName, candidate.applicationInfo.minSdkVersion, signers(candidate), Build.VERSION.SDK_INT);
    }
    private static long version(PackageInfo info) { return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode; }
    private static byte[][] signers(PackageInfo info) {
        Signature[] signatures = Build.VERSION.SDK_INT >= 28 ? info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners() : info.signatures;
        if (signatures == null) return null;
        byte[][] result = new byte[signatures.length][]; for (int i = 0; i < signatures.length; i++) result[i] = signatures[i].toByteArray(); return result;
    }
    private UpdateArchiveVerifier() {}
}
