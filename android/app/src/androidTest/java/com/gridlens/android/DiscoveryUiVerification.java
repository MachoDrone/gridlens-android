package com.gridlens.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** Native discovery UI only: synthetic rows, generated identities, and an empty disposable app. */
final class DiscoveryUiVerification {
    static void verify(Instrumentation driver) {
        Bundle result = new Bundle();
        int outcome = Activity.RESULT_CANCELED;
        MainActivity activity = null;
        try {
            require(BuildConfig.DEBUG, "Discovery UI verification requires a debug fixture app");
            require(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.HARDWARE.contains("ranchu"),
                    "Discovery UI verification requires a disposable emulator");
            require(!new java.io.File(driver.getTargetContext().getFilesDir(), "private-data/hosts.json").exists()
                    && !new java.io.File(driver.getTargetContext().getFilesDir(), "private-data/real200.json").exists(),
                    "Discovery UI verification never opens an operator roster");
            DiscoveryStore untouched = new DiscoveryStore(driver.getTargetContext());
            require(untouched.profiles().length() == 0 && untouched.credentialProfiles().length() == 0
                    && untouched.knownEndpoints().length() == 0 && untouched.monitoringTargets().length() == 0,
                    "Discovery UI verification requires an empty native vault");
            activity = (MainActivity) driver.startActivitySync(new Intent(driver.getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("scenario", "fresh"));
            MainActivity screen = activity;
            DiscoveryController controller = awaitController(activity);
            long searches = DiscoveryNetwork.startedSearchesForTesting(), connections = DiscoveryNetwork.startedConnectionsForTesting();
            verifySelection(driver, screen);
            verifyLimitsAndTrust(driver, screen);
            verifyResult(driver, screen);
            verifyEmptyProfileShortcut(driver, controller);
            verifyCredentialMethods(driver, screen, controller);
            DiscoveryStore reopened = new DiscoveryStore(driver.getTargetContext());
            require(reopened.profiles().length() == 0 && reopened.credentialProfiles().length() == 0
                    && reopened.knownEndpoints().length() == 0 && reopened.monitoringTargets().length() == 0,
                    "UI-only checks persisted discovery credentials or monitoring authority");
            require(DiscoveryNetwork.startedSearchesForTesting() == searches && DiscoveryNetwork.startedConnectionsForTesting() == connections,
                    "UI-only actions started discovery or SSH connections");
            result.putString("stream", "PASS (discovery UI): 200 virtualized candidates, search/filter preservation and explicit selection of 50 hosts; "
                    + "512-PC limit, blocked identity rows, empty-profile and empty-credential shortcuts, key/password visibility and secret clearing, "
                    + "cancelled document picker, secure credential dialog, compact 320dp layout with enlarged text, and expandable Fleet saved details. "
                    + "No network discovery, SSH connection, operator roster, persistent credential, or monitoring grant. Physical-device acceptance remains separate.\n");
            outcome = Activity.RESULT_OK;
        } catch (Exception | AssertionError error) {
            result.putString("stream", "FAIL (discovery UI): " + error.getClass().getSimpleName() + ": " + error.getMessage() + "\n");
        } finally {
            if (activity != null) {
                MainActivity closing = activity;
                ui(driver, closing::finish);
            }
        }
        driver.finish(outcome, result);
    }

    private static void verifySelection(Instrumentation driver, Activity activity) {
        List<DiscoveryUi.Row> candidates = new ArrayList<>();
        for (int i = 0; i < 200; i++) candidates.add(new DiscoveryUi.Row("pc-" + i,
                (i < 50 ? "Rack A " : "Rack B ") + String.format(java.util.Locale.ROOT, "%03d", i),
                "192.0.2." + (i + 1) + ":22 · SSH computer; Nosana not checked", i < 100 ? "saved" : "new", true));
        List<DiscoveryUi.Filter> filters = Arrays.asList(new DiscoveryUi.Filter("All computers", row -> true),
                new DiscoveryUi.Filter("Saved identities", row -> row.group.equals("saved")),
                new DiscoveryUi.Filter("Unfamiliar computers", row -> row.group.equals("new")));
        AtomicReference<Set<String>> chosen = new AtomicReference<>();
        AlertDialog dialog = showSelection(driver, activity, "Select computers to inspect", candidates, filters, Collections.emptySet(), 512, chosen);
        try {
            ui(driver, () -> {
                require(!dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled(), "An empty candidate selection can continue");
                ListView list = tag(dialog, "discovery-list");
                require(list.getAdapter().getCount() == 201 && list.getChildCount() < 200,
                        "Large candidate list was truncated or is not virtualized");
                ((Spinner) tag(dialog, "discovery-filter")).setSelection(1);
            });
            driver.waitForIdleSync();
            ui(driver, () -> {
                ((EditText) tag(dialog, "discovery-search")).setText("rack a");
                tag(dialog, "discovery-select-all").performClick();
                require(count(dialog).startsWith("50 selected · 50 shown"), "Search and identity filter did not select the matching 50 PCs");
                ((EditText) tag(dialog, "discovery-search")).setText("");
                require(count(dialog).startsWith("50 selected · 100 shown"), "Clearing search lost selections");
                ((Spinner) tag(dialog, "discovery-filter")).setSelection(2);
            });
            driver.waitForIdleSync();
            ui(driver, () -> {
                require(count(dialog).startsWith("50 selected · 100 shown"), "Changing filters discarded hidden selections");
                ((Spinner) tag(dialog, "discovery-filter")).setSelection(0);
            });
            driver.waitForIdleSync();
            ui(driver, () -> {
                require(count(dialog).startsWith("50 selected · 200 shown"), "Restoring all candidates changed the selection");
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            });
            driver.waitForIdleSync();
            require(chosen.get() != null && chosen.get().size() == 50 && chosen.get().contains("pc-0") && chosen.get().contains("pc-49")
                    && !chosen.get().contains("pc-50"), "Candidate callback included unselected PCs");
        } finally { ui(driver, dialog::dismiss); }

        List<DiscoveryUi.Row> hosts = new ArrayList<>();
        for (int i = 0; i < 50; i++) hosts.add(new DiscoveryUi.Row("host-" + i, "Verified Nosana host " + i,
                "Computer " + (i / 2) + " · 192.0.2." + (i / 2 + 1) + " · public identity " + i));
        chosen.set(null);
        AlertDialog found = showSelection(driver, activity, "Choose Nosana hosts to save", hosts, Collections.emptyList(), Collections.emptySet(), 512, chosen);
        try {
            ui(driver, () -> {
                tag(found, "discovery-select-all").performClick();
                require(count(found).startsWith("50 selected · 50 shown"), "Verified host select-all lost hosts on shared PCs");
                tag(found, "discovery-clear").performClick();
                require(count(found).startsWith("0 selected") && !found.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled(),
                        "Clearing verified hosts still permits an empty save");
                tag(found, "discovery-select-all").performClick();
                found.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            });
            driver.waitForIdleSync();
            require(chosen.get() != null && chosen.get().size() == 50, "Verified-host selection collapsed hosts sharing a PC");
        } finally { ui(driver, found::dismiss); }
    }

    private static void verifyLimitsAndTrust(Instrumentation driver, Activity activity) {
        List<DiscoveryUi.Row> candidates = new ArrayList<>();
        for (int i = 0; i < 600; i++) candidates.add(new DiscoveryUi.Row("bounded-" + i, "Computer " + i, "Unfamiliar SSH computer"));
        AtomicReference<Set<String>> selected = new AtomicReference<>();
        AlertDialog limitDialog = showSelection(driver, activity, "Select computers to inspect", candidates, Collections.emptyList(), Collections.emptySet(), 512, selected);
        try {
            ui(driver, () -> {
                tag(limitDialog, "discovery-select-all").performClick();
                require(count(limitDialog).contains("600 selected") && count(limitDialog).contains("512"), "Over-limit selection was silently truncated");
                require(!limitDialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled(), "More than 512 PCs can enter one inspection");
                tag(limitDialog, "discovery-clear").performClick();
                require(count(limitDialog).startsWith("0 selected"), "Clear did not remove the whole selection");
            });
        } finally { ui(driver, limitDialog::dismiss); }
        List<DiscoveryUi.Row> trust = Arrays.asList(new DiscoveryUi.Row("new", "192.0.2.1:22", "New identity · SHA256:fixture-first-use", "new", true),
                new DiscoveryUi.Row("changed", "192.0.2.2:22", "Changed identity — blocked. No credentials will be sent.", "blocked", false));
        AlertDialog trustDialog = showSelection(driver, activity, "Review SSH identities", trust, Collections.emptyList(),
                new LinkedHashSet<>(Collections.singletonList("changed")), 512, selected);
        try {
            ui(driver, () -> {
                require(count(trustDialog).startsWith("0 selected"), "A blocked identity was preselected");
                tag(trustDialog, "discovery-select-all").performClick();
                require(count(trustDialog).startsWith("1 selected"), "Select-all included a blocked identity");
                trustDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            });
            driver.waitForIdleSync();
            require(selected.get().equals(Collections.singleton("new")), "Blocked identity entered approved trust selection");
        } finally { ui(driver, trustDialog::dismiss); }
    }

    private static void verifyResult(Instrumentation driver, Activity activity) {
        AtomicReference<AlertDialog> shown = new AtomicReference<>();
        boolean[] opened = {false};
        ui(driver, () -> shown.set(new DiscoveryUi(activity).result("Fleet saved",
                "50 Nosana hosts saved from 25 computers.\nAutomatic IP: 24 computers enabled.\n1 computer needs attention.",
                "192.0.2.25: remembered credentials required. Other inspections succeeded.", "View fleet", () -> opened[0] = true)));
        AlertDialog dialog = shown.get();
        try {
            ui(driver, () -> {
                require(tag(dialog, "discovery-details").getVisibility() == View.GONE, "Completion exposes its diagnostic wall of text by default");
                tag(dialog, "discovery-details-toggle").performClick();
                require(tag(dialog, "discovery-details").getVisibility() == View.VISIBLE, "Inspection details cannot be expanded");
                tag(dialog, "discovery-details-toggle").performClick();
                require(tag(dialog, "discovery-details").getVisibility() == View.GONE, "Inspection details cannot be collapsed");
            });
            verifyCompactLayout(driver, activity, dialog);
            ui(driver, () -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
            driver.waitForIdleSync();
            require(opened[0], "View fleet did not complete the result action");
        } finally { ui(driver, dialog::dismiss); }
    }

    private static void verifyEmptyProfileShortcut(Instrumentation driver, DiscoveryController controller) throws Exception {
        ui(driver, () -> invoke(controller, "profiles", new Class<?>[]{boolean.class}, false));
        AutomaticPcIpVerification.awaitText(driver, "Network profile name");
        require(!AutomaticPcIpVerification.hasText(driver, "New network profile"), "Empty profiles still require a one-option menu");
        require(AutomaticPcIpVerification.clickText(driver, "Cancel"), "Could not cancel the empty-profile editor");
    }

    private static void verifyCredentialMethods(Instrumentation driver, Activity activity, DiscoveryController controller) throws Exception {
        com.jcraft.jsch.KeyPair generated = com.jcraft.jsch.KeyPair.genKeyPair(new com.jcraft.jsch.JSch(), com.jcraft.jsch.KeyPair.RSA, 2048);
        Object trusted;
        try {
            SshDiscovery.Identity identity = new SshDiscovery.Identity(java.util.Base64.getEncoder().encodeToString(generated.getPublicKeyBlob()), "ssh-rsa");
            Class<?> type = Class.forName("com.gridlens.android.DiscoveryController$Trusted");
            java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor(DiscoveryPolicy.Candidate.class, SshDiscovery.Identity.class);
            constructor.setAccessible(true);
            trusted = constructor.newInstance(new DiscoveryPolicy.Candidate("192.0.2.1", 22, "UI fixture"), identity);
        } finally { generated.dispose(); }
        ui(driver, () -> invoke(controller, "credentials", new Class<?>[]{List.class, int.class, List.class, org.json.JSONObject.class},
                Collections.singletonList(trusted), 0, new ArrayList<SshDiscovery.BatchRequest>(), null));
        AutomaticPcIpVerification.awaitText(driver, "SSH credentials");
        require(!AutomaticPcIpVerification.hasText(driver, "Enter / import SSH credentials"), "Empty credentials still require a one-option chooser");
        IntentFilter pickerFilter = new IntentFilter(Intent.ACTION_OPEN_DOCUMENT);
        pickerFilter.addCategory(Intent.CATEGORY_OPENABLE);
        pickerFilter.addDataType("*/*");
        Instrumentation.ActivityMonitor picker = driver.addMonitor(pickerFilter,
                new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true);
        try {
            require(AutomaticPcIpVerification.clickText(driver, "Choose SSH key file"), "Key mode does not offer the native file picker");
            long pickerDeadline = android.os.SystemClock.elapsedRealtime() + 5000;
            while (picker.getHits() == 0 && android.os.SystemClock.elapsedRealtime() < pickerDeadline) Thread.sleep(50);
            driver.waitForIdleSync();
            require(picker.getHits() == 1, "Choosing an SSH key did not use the Android document picker");
        } finally { driver.removeMonitor(picker); }
        Object form = field(controller, "keyForm");
        require(form != null, "Cancelled key import lost the current credential form");
        AlertDialog dialog = (AlertDialog) field(form, "dialog");
        EditText username = (EditText) field(form, "username"), password = (EditText) field(form, "password"), passphrase = (EditText) field(form, "passphrase");
        byte[] transientKey = "only-a-generated-in-memory-key-fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try {
            ui(driver, () -> {
                require(dialog.isShowing(), "Cancelled file selection dismissed SSH credentials");
                require((dialog.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0,
                        "Credential fields lost their secure-window protection");
                RadioButton key = tag(dialog, "credential-key-method"), pw = tag(dialog, "credential-password-method");
                require(key.isChecked() && !pw.isChecked(), "New credentials do not default to the existing-key method");
                require(tag(dialog, "credential-key-fields").getVisibility() == View.VISIBLE
                        && tag(dialog, "credential-password-fields").getVisibility() == View.GONE, "Both credential methods are exposed together");
                require("Username".contentEquals(username.getHint()) && "SSH password".contentEquals(password.getHint())
                        && "Key passphrase".contentEquals(passphrase.getHint()), "Credential hints are not short persistent field names");
                username.setText("fixture"); passphrase.setText("ephemeral-key-passphrase");
                setField(form, "key", transientKey);
                pw.performClick();
                require(passphrase.length() == 0 && field(form, "key") == null && zeroed(transientKey),
                        "Switching to password retained the imported key or its passphrase");
                require(tag(dialog, "credential-key-fields").getVisibility() == View.GONE
                        && tag(dialog, "credential-password-fields").getVisibility() == View.VISIBLE, "Password mode did not hide key controls");
                password.setText("ephemeral-ssh-password");
                key.performClick();
                require(password.length() == 0 && "fixture".contentEquals(username.getText()), "Switching methods did not clear only discarded secrets");
                CheckBox remember = tag(dialog, "credential-remember");
                require(remember.isChecked(), "Remember default unexpectedly changed");
                remember.setChecked(false);
            });
            verifyCompactLayout(driver, activity, dialog);
            ui(driver, () -> dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
            driver.waitForIdleSync();
            require(password.length() == 0 && passphrase.length() == 0, "Closing credentials retained text secrets");
        } finally { Arrays.fill(transientKey, (byte) 0); ui(driver, dialog::dismiss); }
    }

    private static AlertDialog showSelection(Instrumentation driver, Activity activity, String title, List<DiscoveryUi.Row> rows,
            List<DiscoveryUi.Filter> filters, Set<String> initiallySelected, int limit, AtomicReference<Set<String>> chosen) {
        AtomicReference<AlertDialog> shown = new AtomicReference<>();
        ui(driver, () -> shown.set(new DiscoveryUi(activity).select(title, "Synthetic fixture; no computers are contacted.", rows, filters,
                initiallySelected, limit, title.startsWith("Choose") ? "Save selected hosts" : "Continue", chosen::set, () -> {})));
        driver.waitForIdleSync();
        return shown.get();
    }
    private static void verifyCompactLayout(Instrumentation driver, Activity activity, AlertDialog dialog) {
        ui(driver, () -> {
            dialog.getWindow().setLayout(Math.round(320 * activity.getResources().getDisplayMetrics().density), WindowManager.LayoutParams.WRAP_CONTENT);
            scaleText(dialog.getWindow().getDecorView(), 1.6f);
        });
        driver.waitForIdleSync();
        ui(driver, () -> checkText(dialog.getWindow().getDecorView()));
    }
    private static void scaleText(View view, float factor) {
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, text.getTextSize() * factor);
        }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) scaleText(((ViewGroup) view).getChildAt(i), factor);
    }
    private static void checkText(View view) {
        if (view.getVisibility() == View.GONE) return;
        if (view instanceof TextView && view.getWidth() > 0) {
            TextView text = (TextView) view;
            android.text.Layout layout = text.getLayout();
            if (layout != null) for (int line = 0; line < layout.getLineCount(); line++)
                require(layout.getEllipsisCount(line) == 0, "Discovery text is ellipsized at 320dp with enlarged text");
            if (view instanceof EditText && text.getHint() != null)
                require(text.getPaint().measureText(text.getHint().toString()) <= text.getWidth() - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight(),
                        "Credential hint is too long at 320dp with enlarged text: " + text.getHint());
        }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) checkText(((ViewGroup) view).getChildAt(i));
    }
    /** Surface assertions on the instrumentation thread instead of crashing the app main thread. */
    private static void ui(Instrumentation driver, Runnable action) {
        AtomicReference<Throwable> failed = new AtomicReference<>();
        driver.runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failed.set(error); } });
        Throwable error = failed.get();
        if (error instanceof AssertionError) throw (AssertionError) error;
        if (error != null) throw new AssertionError("Native UI fixture failed: " + error.getMessage(), error);
    }
    private static String count(AlertDialog dialog) { return ((TextView) tag(dialog, "discovery-count")).getText().toString(); }
    @SuppressWarnings("unchecked") private static <T extends View> T tag(AlertDialog dialog, String name) {
        View view = dialog.getWindow().getDecorView().findViewWithTag(name);
        require(view != null, "Missing discovery view: " + name); return (T) view;
    }
    private static DiscoveryController awaitController(MainActivity activity) throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + 60000;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            DiscoveryController controller = (DiscoveryController) field(activity, "discovery");
            if (controller != null) return controller;
            Thread.sleep(100);
        }
        throw new AssertionError("Discovery controller did not start");
    }
    private static Object invoke(Object owner, String name, Class<?>[] parameters, Object... values) {
        try { java.lang.reflect.Method method = owner.getClass().getDeclaredMethod(name, parameters); method.setAccessible(true); return method.invoke(owner, values); }
        catch (ReflectiveOperationException error) { throw new AssertionError("Could not exercise native " + name, error); }
    }
    private static Object field(Object owner, String name) {
        try { java.lang.reflect.Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
        catch (ReflectiveOperationException error) { throw new AssertionError("Missing native fixture field " + name, error); }
    }
    private static void setField(Object owner, String name, Object value) {
        try { java.lang.reflect.Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner, value); }
        catch (ReflectiveOperationException error) { throw new AssertionError("Could not set native fixture field " + name, error); }
    }
    private static boolean zeroed(byte[] bytes) { for (byte value : bytes) if (value != 0) return false; return true; }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private DiscoveryUiVerification() { }
}
