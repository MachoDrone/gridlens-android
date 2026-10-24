package com.gridlens.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Every operation with network/SSH authority begins in this native, user-operated controller. */
final class DiscoveryController {
    static final int IMPORT_KEY = 42;
    interface Navigation { void open(String scenario); }
    private final Activity activity;
    private final String origin;
    private final String token;
    private final Navigation navigation;
    private final DiscoveryStore store;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private DiscoveryNetwork network;
    private AlertDialog progress;
    private TextView progressText;
    private JSONObject profile;
    private String fleetId;
    private volatile int generation;
    private volatile boolean destroyed;
    private boolean working;
    private CredentialForm keyForm;
    private final List<SshDiscovery.Credential> openCredentials = new ArrayList<>();

    DiscoveryController(Activity activity, String origin, String token, Navigation navigation) throws Exception {
        this.activity = activity; this.origin = origin; this.token = token; this.navigation = navigation;
        DiscoveryStore opened;
        try { opened = new DiscoveryStore(activity); } catch (Exception unavailable) { opened = null; }
        store = opened;
    }
    void show(String rescanFleet) {
        if (working) { if (progress != null) progress.show(); return; }
        fleetId = rescanFleet;
        new AlertDialog.Builder(activity).setTitle(rescanFleet == null ? "Network Discovery" : "Rescan saved fleet")
            .setItems(new String[]{rescanFleet == null ? "Search network" : "Rescan network", "Saved fleets", "Network profiles", "Forget credentials"}, (dialog, choice) -> {
                if (choice == 0) prepare();
                else if (choice == 1) fleets();
                else if (choice == 2) profiles(false);
                else forget();
            }).setNegativeButton("Close", null).show();
    }
    private void prepare() {
        if (store == null) { storageError(); return; }
        cancel();
        try { network = DiscoveryNetwork.capture(activity); profiles(true); }
        catch (Exception error) { message("Network unavailable", "Connect to Wi-Fi or your existing VPN, then search again. GridLens does not configure VPNs."); }
    }
    private void profiles(boolean searching) {
        try {
            JSONArray list = store.profiles();
            String[] names = new String[list.length() + 1]; names[0] = searching ? "Learn / enter a network range" : "New network profile";
            for (int i = 0; i < list.length(); i++) names[i + 1] = list.getJSONObject(i).getString("name");
            new AlertDialog.Builder(activity).setTitle("Network profiles").setItems(names, (dialog, which) -> {
                try {
                    if (which == 0) editProfile(null, searching);
                    else {
                        JSONObject chosen = list.getJSONObject(which - 1);
                        if (searching) { profile = chosen; confirmSearch(); }
                        else new AlertDialog.Builder(activity).setTitle(chosen.getString("name"))
                            .setItems(new String[]{"Edit name, ranges and SSH ports", "Delete profile"}, (d, action) -> {
                                if (action == 0) editProfile(chosen, false);
                                else new AlertDialog.Builder(activity).setTitle("Delete network profile?")
                                    .setMessage("Saved fleets and credentials remain. Trusted endpoint keys in this profile will be removed.")
                                    .setPositiveButton("Delete", (dd, w) -> { try { store.deleteProfile(chosen.getString("id")); } catch (Exception e) { storageError(); } })
                                    .setNegativeButton("Cancel", null).show();
                            }).show();
                    }
                } catch (Exception error) { if (searching) cancel(); storageError(); }
            }).setNegativeButton("Cancel", (dialog, which) -> { if (searching) cancel(); }).setOnCancelListener(d -> { if (searching) cancel(); }).show();
        } catch (Exception error) { if (searching) cancel(); storageError(); }
    }
    private void editProfile(JSONObject existing, boolean searching) {
        LinearLayout form = form();
        EditText name = field(form, "Network profile name", existing == null ? "My network" : existing.optString("name"), false);
        List<String> suggestions = network == null ? new ArrayList<>() : network.suggestedRanges;
        String rangesText = existing == null ? String.join("\n", suggestions) : join(existing.optJSONArray("ranges"), "\n");
        label(form, "Destination ranges, one per line. IPv4 /16–/32; IPv6 host /128 only. These describe computers, not your phone's VPN address. Leave empty only to search existing SSH advertisements.");
        EditText ranges = field(form, "Network ranges", rangesText, false); ranges.setSingleLine(false); ranges.setMinLines(2);
        if (rangesText.isEmpty()) label(form, "The active connection does not reveal a usable destination range. Learn it once while on-site Wi-Fi, or enter a destination network range. A full-tunnel VPN cannot reveal a hidden remote network.");
        EditText ports = field(form, "SSH ports (comma separated)", existing == null ? "22" : join(existing.optJSONArray("ports"), ","), false);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("Network profile").setView(scroll(form))
            .setPositiveButton(searching ? "Save and search" : "Save", null).setNegativeButton("Cancel", (d, w) -> { if (searching) cancel(); }).setOnCancelListener(d -> { if (searching) cancel(); }).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                JSONArray rangeArray = new JSONArray();
                for (String value : ranges.getText().toString().trim().split("[\\s,]+")) if (!value.isEmpty()) rangeArray.put(value);
                JSONArray portArray = new JSONArray(); List<Integer> requestedPorts = new ArrayList<>();
                for (String value : ports.getText().toString().trim().split("[\\s,]+")) if (!value.isEmpty()) requestedPorts.add(Integer.parseInt(value));
                for (int port : DiscoveryPolicy.ports(requestedPorts)) portArray.put(port);
                // Validate complete ranges before any connection is attempted; the core enforces /16 and host-route limits.
                for (int i = 0; i < rangeArray.length(); i++) validateRange(rangeArray.getString(i));
                String id = store.saveProfile(existing == null ? null : existing.getString("id"), name.getText().toString(), rangeArray, portArray);
                profile = new JSONObject().put("id", id).put("name", name.getText().toString().trim()).put("ranges", rangeArray).put("ports", portArray);
                dialog.dismiss(); if (searching) scan();
            } catch (Exception error) { message("Check this profile", "Enter a name, IPv4 ranges no broader than /16 (or explicit IPv6 /128 routes), and one to eight valid SSH ports."); }
        })); dialog.show();
    }
    private static void validateRange(String range) {
        String[] parts = range.split("/", -1); if (parts.length != 2) throw new IllegalArgumentException();
        int prefix = Integer.parseInt(parts[1]);
        java.net.InetAddress address = DiscoveryPolicy.numericAddress(parts[0]);
        if (address instanceof java.net.Inet6Address ? prefix != 128 : prefix < 16 || prefix > 32) throw new IllegalArgumentException();
    }
    private void confirmSearch() {
        new AlertDialog.Builder(activity).setTitle("Search " + profile.optString("name"))
            .setMessage("Search " + (profile.optJSONArray("ranges").length() == 0 ? "existing SSH advertisements only" : join(profile.optJSONArray("ranges"), ", ")) + " on SSH ports " + join(profile.optJSONArray("ports"), ", ") + ". Connections stay on the current " + (network.vpn ? "VPN" : "Wi-Fi network") + "; network changes cancel the search. No login happens until you select computers and trust their SSH identities.")
            .setPositiveButton("Search", (d, w) -> scan()).setNeutralButton("Edit profile", (d, w) -> editProfile(profile, true))
            .setNegativeButton("Cancel", (d, w) -> cancel()).setOnCancelListener(d -> cancel()).show();
    }
    private void scan() {
        int run = ++generation; working = true; busy("Searching network", "Finding reachable SSH candidates…");
        DiscoveryNetwork selected = network;
        io.execute(() -> {
            try {
                List<String> ranges = strings(profile.getJSONArray("ranges")); List<Integer> ports = new ArrayList<>();
                JSONArray array = profile.getJSONArray("ports"); for (int i = 0; i < array.length(); i++) ports.add(array.getInt(i));
                List<DiscoveryPolicy.Candidate> found = selected.scan(ranges, ports, (done, total, count) -> ui(run, () -> progressText.setText(activity.getString(R.string.discovery_probe_progress, done, total, count))));
                ui(run, () -> { dismissProgress(); working = false; selectCandidates(found); });
            } catch (Exception error) { ui(run, () -> { dismissProgress(); working = false; message("Search stopped", "The network changed, permission was denied, the search was cancelled, or the profile could not be searched. No failed connection establishes that a PC is offline."); cancel(); }); }
        });
    }
    private void selectCandidates(List<DiscoveryPolicy.Candidate> candidates) {
        if (candidates.isEmpty()) { message("No SSH candidates found", "No reachable SSH services answered this search. Computers may be inaccessible, use other ports, or require a different network profile."); cancel(); return; }
        boolean[] checked = new boolean[candidates.size()]; String[] labels = new String[candidates.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = display(candidates.get(i));
        new AlertDialog.Builder(activity).setTitle("Select computers to inspect")
            .setMultiChoiceItems(labels, checked, (d, index, selected) -> checked[index] = selected)
            .setPositiveButton("Review SSH identities", (d, w) -> {
                List<DiscoveryPolicy.Candidate> selected = new ArrayList<>();
                for (int i = 0; i < checked.length; i++) if (checked[i]) selected.add(candidates.get(i));
                if (selected.isEmpty()) { cancel(); return; }
                if (selected.size() > 512) { message("Choose fewer computers", "Select at most 512 computers per inspection. Split larger fleets into searches; saved rescans merge verified discoveries."); cancel(); return; }
                reviewIdentity(selected, 0, new ArrayList<>());
            }).setNegativeButton("Cancel", (d, w) -> cancel()).setOnCancelListener(d -> cancel()).show();
    }
    private static final class Trusted {
        final DiscoveryPolicy.Candidate candidate; final SshDiscovery.Identity identity;
        Trusted(DiscoveryPolicy.Candidate candidate, SshDiscovery.Identity identity) { this.candidate = candidate; this.identity = identity; }
    }
    private void reviewIdentity(List<DiscoveryPolicy.Candidate> selected, int index, List<Trusted> trusted) {
        if (index >= selected.size()) { if (trusted.isEmpty()) { cancel(); return; } credentials(trusted, 0, new ArrayList<>(), null); return; }
        int run = generation; DiscoveryPolicy.Candidate candidate = selected.get(index); working = true;
        busy("Reading SSH identity", display(candidate));
        DiscoveryNetwork selectedNetwork = network;
        io.execute(() -> {
            try {
                SshDiscovery.Identity identity = SshDiscovery.inspectIdentity(selectedNetwork, candidate);
                String known = store.trustedKey(profile.getString("id"), candidate.endpointKey());
                ui(run, () -> {
                    dismissProgress(); working = false;
                    if (known != null && !known.equals(identity.keyBase64)) {
                        new AlertDialog.Builder(activity).setTitle("SSH identity changed — blocked")
                            .setMessage(display(candidate) + " no longer matches the trusted SSH key in this network profile. No credentials were sent. Verify the computer and use the correct network profile for overlapping address ranges.")
                            .setPositiveButton("Skip computer", (d, w) -> reviewIdentity(selected, index + 1, trusted)).setNegativeButton("Cancel", (d, w) -> cancel()).setOnCancelListener(d -> cancel()).show(); return;
                    }
                    Runnable next = () -> { trusted.add(new Trusted(candidate, identity)); reviewIdentity(selected, index + 1, trusted); };
                    if (known != null) { next.run(); return; }
                    new AlertDialog.Builder(activity).setTitle("Trust this SSH identity?")
                        .setMessage(display(candidate) + "\n\n" + identity.algorithm + "\n" + identity.fingerprint + "\n\nCompare this fingerprint with the computer's SSH host key using a trusted source. An address or advertised name alone does not identify a computer.")
                        .setPositiveButton("Trust this key", (d, w) -> { try { store.trust(profile.getString("id"), candidate.endpointKey(), identity.keyBase64); next.run(); } catch (Exception error) { storageError(); cancel(); } })
                        .setNeutralButton("Skip", (d, w) -> reviewIdentity(selected, index + 1, trusted)).setNegativeButton("Cancel", (d, w) -> cancel()).setOnCancelListener(d -> cancel()).show();
                });
            } catch (Exception error) { ui(run, () -> { dismissProgress(); working = false;
                new AlertDialog.Builder(activity).setTitle("SSH identity unavailable").setMessage(display(candidate) + " could not complete SSH identity exchange. This does not establish that it is offline.")
                    .setPositiveButton("Skip", (d, w) -> reviewIdentity(selected, index + 1, trusted)).setNegativeButton("Cancel", (d, w) -> cancel()).setOnCancelListener(d -> cancel()).show(); }); }
        });
    }
    private void credentials(List<Trusted> hosts, int index, List<SshDiscovery.BatchRequest> requests, JSONObject shared) {
        if (index == hosts.size()) { reviewRequests(hosts, requests); return; }
        Trusted host = hosts.get(index);
        try {
            String override = store.override(host.identity.keyBase64);
            JSONObject prior = override == null ? shared : store.credential(override);
            if (prior != null) { addRequest(hosts, index, requests, shared, prior); return; }
        } catch (Exception error) { credentialError(); cancel(); return; }
        try {
            JSONArray saved = store.credentialProfiles(); String[] choices = new String[saved.length() + 1]; choices[0] = "Enter / import SSH credentials";
            for (int i = 0; i < saved.length(); i++) { JSONObject value = saved.getJSONObject(i); choices[i + 1] = value.getString("name") + " (" + value.getString("username") + ")"; }
            new AlertDialog.Builder(activity).setTitle("Credentials for " + display(host.candidate)).setItems(choices, (d, which) -> {
                if (which == 0) newCredential(host, true, (value, forRemaining) -> addRequest(hosts, index, requests, forRemaining ? value : shared, value));
                else {
                    try {
                        JSONObject value = store.credential(saved.getJSONObject(which - 1).getString("id"));
                        credentialScope(host, value, (forRemaining) -> addRequest(hosts, index, requests, forRemaining ? value : shared, value));
                    } catch (Exception error) { credentialError(); cancel(); }
                }
            }).setNegativeButton("Cancel", (d, w) -> cancel()).setOnCancelListener(d -> cancel()).show();
        } catch (Exception error) { credentialError(); cancel(); }
    }
    private void reviewRequests(List<Trusted> hosts, List<SshDiscovery.BatchRequest> requests) {
        String[] choices = new String[hosts.size()];
        for (int i = 0; i < hosts.size(); i++) choices[i] = "Change credentials: " + display(hosts.get(i).candidate);
        new AlertDialog.Builder(activity).setTitle("Review " + hosts.size() + " selected, trusted PCs")
            .setItems(choices, (d, index) -> {
                Trusted host = hosts.get(index);
                newCredential(host, false, (value, ignored) -> {
                    try {
                        SshDiscovery.Credential replacement = decodeCredential(value); openCredentials.add(replacement);
                        SshDiscovery.BatchRequest old = requests.set(index, new SshDiscovery.BatchRequest(host.candidate, host.identity, replacement, null));
                        old.credential.close(); openCredentials.remove(old.credential);
                        reviewRequests(hosts, requests);
                    } catch (Exception error) { credentialError(); cancel(); }
                });
            }).setPositiveButton("Inspect selected PCs", (d, w) -> collect(requests))
            .setNegativeButton("Cancel", (d, w) -> cancel()).setOnCancelListener(d -> cancel()).show();
    }
    interface CredentialReady { void ready(JSONObject value, boolean shared); }
    interface ScopeReady { void ready(boolean shared); }
    private void credentialScope(Trusted host, JSONObject value, ScopeReady callback) {
        new AlertDialog.Builder(activity).setTitle("Apply credential profile")
            .setMessage("Use this credential only for this selected, trusted computer, or share it with the remaining selected, trusted computers? Saved per-PC overrides take priority.")
            .setPositiveButton("Share with remaining", (d, w) -> callback.ready(true))
            .setNeutralButton("Only this PC", (d, w) -> { try { store.setOverride(host.identity.keyBase64, value.getString("id")); callback.ready(false); } catch (Exception e) { credentialError(); cancel(); } })
            .setNegativeButton("Cancel", (d, w) -> cancel()).setOnCancelListener(d -> cancel()).show();
    }
    private void addRequest(List<Trusted> hosts, int index, List<SshDiscovery.BatchRequest> requests, JSONObject shared, JSONObject value) {
        try {
            Trusted host = hosts.get(index); SshDiscovery.Credential credential = decodeCredential(value); openCredentials.add(credential);
            requests.add(new SshDiscovery.BatchRequest(host.candidate, host.identity, credential, null));
            credentials(hosts, index + 1, requests, shared);
        } catch (Exception error) { credentialError(); cancel(); }
    }
    private static SshDiscovery.Credential decodeCredential(JSONObject value) throws Exception {
        String encodedKey = value.optString("key", "");
        byte[] password = value.optString("password", "").getBytes(StandardCharsets.UTF_8);
        byte[] privateKey = encodedKey.isEmpty() ? null : android.util.Base64.decode(encodedKey, android.util.Base64.NO_WRAP);
        byte[] passphrase = value.optString("passphrase", "").getBytes(StandardCharsets.UTF_8);
        try { return new SshDiscovery.Credential(value.getString("username"), password, privateKey, passphrase); }
        finally { Arrays.fill(password, (byte)0); if (privateKey != null) Arrays.fill(privateKey, (byte)0); Arrays.fill(passphrase, (byte)0); }
    }
    private final class CredentialForm {
        EditText name, username, password, passphrase; CheckBox remember, shared; TextView keyLabel; byte[] key; AlertDialog dialog;
        void clear() { password.setText(""); passphrase.setText(""); if (key != null) Arrays.fill(key, (byte)0); key = null; if (keyForm == this) keyForm = null; }
    }
    private void newCredential(Trusted host, boolean allowShared, CredentialReady callback) {
        CredentialForm fields = new CredentialForm(); LinearLayout form = form();
        label(form, "SSH access only. Never import a Nosana or Solana wallet key. Credentials stay in native Android encrypted storage when remembered.");
        fields.name = field(form, "Credential profile name", "SSH credential", false);
        fields.username = field(form, "SSH username", "", false);
        fields.password = field(form, "SSH password (leave blank for key login)", "", true);
        fields.keyLabel = label(form, "No SSH private key selected");
        android.widget.Button importKey = new android.widget.Button(activity); importKey.setText(R.string.import_ssh_key); form.addView(importKey);
        importKey.setOnClickListener(v -> {
            keyForm = fields;
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            try { activity.startActivityForResult(intent, IMPORT_KEY); }
            catch (android.content.ActivityNotFoundException error) { message("No document picker", "This phone has no document picker available."); }
        });
        fields.passphrase = field(form, "Private-key passphrase (if encrypted)", "", true);
        fields.remember = check(form, "Remember encrypted credentials", true);
        fields.shared = check(form, "Use for remaining selected, trusted PCs", allowShared);
        if (!allowShared) fields.shared.setVisibility(View.GONE);
        fields.dialog = new AlertDialog.Builder(activity).setTitle("SSH credentials").setView(scroll(form)).setPositiveButton("Continue", null)
            .setNegativeButton("Cancel", (d, w) -> cancel()).setOnCancelListener(d -> cancel()).create();
        fields.dialog.setOnDismissListener(d -> fields.clear()); fields.dialog.setOnCancelListener(d -> cancel());
        fields.dialog.setOnShowListener(d -> {
            fields.dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            fields.dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                try {
                    String username = fields.username.getText().toString().trim();
                    if (username.isEmpty() || username.length() > 64 || username.contains("\n") || (fields.name.getText().toString().trim().isEmpty() || fields.name.getText().length() > 80)) throw new IllegalArgumentException();
                    if (fields.key == null && fields.password.getText().length() == 0) throw new IllegalArgumentException();
                    JSONObject value = new JSONObject().put("name", fields.name.getText().toString().trim()).put("username", username)
                        .put("password", fields.password.getText().toString()).put("passphrase", fields.passphrase.getText().toString())
                        .put("key", fields.key == null ? "" : android.util.Base64.encodeToString(fields.key, android.util.Base64.NO_WRAP));
                    boolean shared = fields.shared.isChecked(), remember = fields.remember.isChecked();
                    int run = generation;
                    fields.dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                    fields.keyLabel.setText(R.string.credential_validating);
                    io.execute(() -> {
                        try {
                            try (SshDiscovery.Credential validated = decodeCredential(value)) { SshDiscovery.validateCredential(validated); }
                            if (run != generation || destroyed) return;
                            if (remember) value.put("id", store.saveCredential(value, shared ? null : host.identity.keyBase64));
                            ui(run, () -> { if (fields.dialog.isShowing()) { fields.dialog.dismiss(); callback.ready(value, shared); } });
                        } catch (Exception error) { ui(run, () -> {
                            fields.dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                            fields.keyLabel.setText(R.string.credential_validation_failed);
                        }); }
                    });
                } catch (Exception error) { message("Check credentials", "Enter a profile name, SSH username and password or SSH key. Credentials must be available in encrypted storage before they can be remembered."); }
            });
        }); fields.dialog.show();
    }
    boolean activityResult(int request, int result, Intent data) {
        if (request != IMPORT_KEY) return false;
        CredentialForm target = keyForm;
        if (target == null || result != Activity.RESULT_OK || data == null || data.getData() == null) return true;
        Uri document = data.getData();
        io.execute(() -> {
            byte[] bytes = null;
            try (InputStream input = activity.getContentResolver().openInputStream(document)) {
                if (input == null) throw new java.io.IOException(); bytes = NodeRuntime.readBounded(input, 64 * 1024);
                int headerLength = 0; while (headerLength < bytes.length && headerLength < 80 && bytes[headerLength] != '\n') headerLength++;
                String header = new String(bytes, 0, headerLength, StandardCharsets.UTF_8);
                if (!header.startsWith("-----BEGIN ") || !header.contains("PRIVATE KEY-----")) throw new java.io.IOException();
                byte[] imported = bytes; bytes = null;
                activity.runOnUiThread(() -> {
                    if (destroyed || keyForm != target || !target.dialog.isShowing()) { Arrays.fill(imported, (byte)0); return; }
                    if (target.key != null) Arrays.fill(target.key, (byte)0); target.key = imported;
                    target.password.setText(""); target.keyLabel.setText(R.string.ssh_key_imported);
                });
            } catch (Exception error) { activity.runOnUiThread(() -> message("Could not import SSH key", "Select a supported PEM/OpenSSH RSA, ECDSA or Ed25519 SSH private-key file, no larger than 64 KB. Never select a wallet file.")); }
            finally { if (bytes != null) Arrays.fill(bytes, (byte)0); }
        }); return true;
    }
    private void collect(List<SshDiscovery.BatchRequest> requests) {
        working = true; int run = generation; busy("Identifying Nosana hosts", "Reading bounded container metadata on selected, trusted computers…");
        java.util.Map<String, SshDiscovery.Result> results = java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>());
        List<SshDiscovery.BatchRequest> needsSudo = java.util.Collections.synchronizedList(new ArrayList<>());
        DiscoveryNetwork selectedNetwork = network;
        io.execute(() -> {
            try {
                SshDiscovery.runBatch(selectedNetwork, requests, (candidate, result) -> {
                    results.put(candidate.endpointKey(), result);
                    if (result.status == SshDiscovery.Status.CONTAINER_ACCESS_REQUIRED) {
                        for (SshDiscovery.BatchRequest request : requests) {
                            if (request.candidate == candidate) needsSudo.add(request);
                        }
                    }
                    ui(run, () -> progressText.setText(activity.getString(R.string.discovery_inspection_progress, results.size(), requests.size(), display(candidate), status(result.status))));
                });
                ui(run, () -> { dismissProgress(); working = false;
                    boolean authFailure = results.values().stream().anyMatch(r -> r.status == SshDiscovery.Status.AUTH_FAILED);
                    if (authFailure) saveResults(results, requests.size()); else sudoNext(needsSudo, 0, results, requests.size());
                });
            } catch (Exception error) { ui(run, () -> { dismissProgress(); working = false; message("Inspection stopped", "The connection changed or inspection was cancelled. No workload or host configuration was changed."); cancel(); }); }
        });
    }
    private void sudoNext(List<SshDiscovery.BatchRequest> requests, int index, java.util.Map<String, SshDiscovery.Result> results, int expected) {
        if (index >= requests.size()) { saveResults(results, expected); return; }
        SshDiscovery.BatchRequest request = requests.get(index); LinearLayout form = form();
        label(form, display(request.candidate) + " needs elevated container access. Approve only the fixed, read-only Docker/Podman inventory commands. This does not grant an interactive shell, install software, read wallet files, restart workloads or change configuration.");
        EditText password = field(form, "This PC's sudo password (if required)", "", true);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("Approve inventory with sudo?").setView(scroll(form))
            .setPositiveButton("Approve this PC", (d, w) -> {
                byte[] secret = password.getText().toString().getBytes(StandardCharsets.UTF_8); password.setText("");
                int run = generation; working = true; busy("Reading approved inventory", display(request.candidate));
                DiscoveryNetwork selectedNetwork = network;
                io.execute(() -> {
                    try {
                        SshDiscovery.Result result = SshDiscovery.inventory(selectedNetwork, request.candidate, request.identity, request.credential, secret);
                        SshDiscovery.Result original = results.get(request.candidate.endpointKey());
                        results.put(request.candidate.endpointKey(), InspectionResults.merge(original, result));
                        ui(run, () -> { dismissProgress(); working = false; if (result.status == SshDiscovery.Status.AUTH_FAILED) saveResults(results, expected); else sudoNext(requests, index + 1, results, expected); });
                    } finally { Arrays.fill(secret, (byte)0); }
                });
            }).setNeutralButton("Skip this PC", (d, w) -> sudoNext(requests, index + 1, results, expected))
            .setNegativeButton("Cancel", (d, w) -> cancel()).setOnCancelListener(d -> cancel()).create();
        dialog.setOnShowListener(d -> dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE));
        dialog.setOnDismissListener(d -> password.setText("")); dialog.show();
    }
    private void saveResults(java.util.Map<String, SshDiscovery.Result> results, int expected) {
        LinkedHashSet<String> addresses = new LinkedHashSet<>(); int unresolved = 0, inaccessible = 0; boolean authFailed = false;
        for (SshDiscovery.Result result : results.values()) {
            addresses.addAll(result.addresses); unresolved += result.unresolved;
            if (result.status != SshDiscovery.Status.SUCCESS) inaccessible++;
            if (result.status == SshDiscovery.Status.AUTH_FAILED) authFailed = true;
        }
        StringBuilder pcDetails = new StringBuilder();
        for (java.util.Map.Entry<String, SshDiscovery.Result> result : results.entrySet())
            pcDetails.append("\n").append(result.getKey()).append(": ").append(status(result.getValue().status))
                .append("; ").append(result.getValue().addresses.size()).append(" public addresses, ").append(result.getValue().unresolved).append(" unresolved observations");
        String summary = addresses.size() + " verified public host addresses. " + unresolved + " unresolved Nosana instance observations. " + inaccessible + " inaccessible or failed PC inspections."
            + (authFailed ? " The credential batch stopped after an authentication failure; remaining PCs were not tried." : "")
            + (results.size() < expected ? " Some selected PCs were not inspected." : "")
            + (unresolved > 0 ? " Normal and elevated inventory observations may refer to the same unresolved instance." : "") + pcDetails;
        if (addresses.isEmpty()) { message("No verified host identities", summary + " Existing saved fleets remain unchanged."); cancel(); return; }
        String savedFleet = fleetId;
        int run = generation;
        JSONObject operation = new JSONObject();
        try { operation.put("op", savedFleet == null ? "create" : "merge").put("addresses", new JSONArray(addresses));
            if (savedFleet == null) operation.put("name", profile.getString("name")); else operation.put("id", savedFleet);
        } catch (Exception error) { storageError(); cancel(); return; }
        working = true; busy("Saving discovered fleet", summary);
        io.execute(() -> {
            try {
                JSONObject response = api(operation);
                String id = response.getString("fleetId");
                ui(run, () -> { dismissProgress(); working = false; navigation.open("fleet:" + id); message("Fleet saved", summary + " Reopening this fleet refreshes public data without SSH or another network search."); cancel(); });
            } catch (Exception error) { ui(run, () -> { dismissProgress(); working = false; message("Fleet could not be saved", "The save could not be confirmed. Check Saved fleets before retrying; the request may have completed."); cancel(); }); }
        });
    }
    private void fleets() {
        io.execute(() -> {
            try { JSONObject response = api(null); JSONArray fleets = response.getJSONArray("fleets"); activity.runOnUiThread(() -> {
                if (destroyed) return;
                warnRecovery(response);
                if (fleets.length() == 0) { message("No saved fleets", "Choose Search network to discover a fleet. Supplied Host Addresses remains available through Hosts."); return; }
                String[] labels = new String[fleets.length()];
                for (int i = 0; i < fleets.length(); i++) { JSONObject fleet = fleets.optJSONObject(i); labels[i] = fleet.optString("name") + " (" + fleet.optJSONArray("hosts").length() + " hosts)"; }
                new AlertDialog.Builder(activity).setTitle("Saved fleets").setItems(labels, (d, w) -> fleetActions(fleets.optJSONObject(w))).setNegativeButton("Close", null).show();
            }); } catch (Exception error) { activity.runOnUiThread(this::storageError); }
        });
    }
    private void fleetActions(JSONObject fleet) {
        String id = fleet.optString("id");
        new AlertDialog.Builder(activity).setTitle(fleet.optString("name"))
            .setItems(new String[]{"Open fleet", "Rescan and merge", "Rename", "Remove selected hosts", "Delete fleet"}, (d, which) -> {
                if (which == 0) mutate("select", null, "scenario", "fleet:" + id, true);
                else if (which == 1) { fleetId = id; prepare(); }
                else if (which == 2) { LinearLayout form = form(); EditText name = field(form, "Fleet name", fleet.optString("name"), false);
                    new AlertDialog.Builder(activity).setTitle("Rename fleet").setView(form).setPositiveButton("Save", (dd, w) -> mutate("rename", id, "name", name.getText().toString(), false)).setNegativeButton("Cancel", null).show();
                } else if (which == 3) removeHosts(fleet);
                else new AlertDialog.Builder(activity).setTitle("Delete saved fleet?").setMessage("This removes only this public-address fleet. Credentials and network profiles remain.")
                    .setPositiveButton("Delete", (dd, w) -> mutate("delete", id, null, null, true)).setNegativeButton("Cancel", null).show();
            }).setNegativeButton("Close", null).show();
    }
    private void removeHosts(JSONObject fleet) {
        JSONArray hosts = fleet.optJSONArray("hosts"); String[] labels = new String[hosts.length()]; boolean[] selected = new boolean[hosts.length()];
        for (int i = 0; i < hosts.length(); i++) labels[i] = hosts.optJSONObject(i).optString("address");
        new AlertDialog.Builder(activity).setTitle("Explicitly remove hosts").setMultiChoiceItems(labels, selected, (d, i, value) -> selected[i] = value)
            .setPositiveButton("Remove selected", (d, w) -> { JSONArray addresses = new JSONArray(); for (int i = 0; i < selected.length; i++) if (selected[i]) addresses.put(labels[i]); mutate("remove-hosts", fleet.optString("id"), "addresses", addresses, true); })
            .setNegativeButton("Cancel", null).show();
    }
    void selectScenario(String scenario) { mutate("select", null, "scenario", scenario, true); }
    private void mutate(String op, String id, String field, Object value, boolean open) {
        io.execute(() -> {
            try {
                JSONObject request = new JSONObject().put("op", op); if (id != null) request.put("id", id); if (field != null) request.put(field, value);
                JSONObject response = api(request); activity.runOnUiThread(() -> { if (!destroyed) { if (open) navigation.open(response.optString("selectedScenario", "network-discovery")); else fleets(); } });
            } catch (Exception error) { activity.runOnUiThread(this::storageError); }
        });
    }
    void restoreSelection(String fallback) {
        io.execute(() -> { String scenario = fallback; JSONObject response = null; try { response = api(null); scenario = response.getString("selectedScenario"); } catch (Exception ignored) {}
            String chosen = scenario; JSONObject state = response;
            activity.runOnUiThread(() -> { if (!destroyed) { navigation.open(chosen); if (state != null) warnRecovery(state); } }); });
    }
    private void warnRecovery(JSONObject response) {
        if (!response.isNull("error")) message("Saved fleet recovery", "The public fleet store reported a recovery or read error. Check the fleet list before changing it. Existing saved evidence was not refreshed by recovery.");
    }
    private JSONObject api(JSONObject operation) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(origin + "/mobile/fleets").openConnection();
        try {
            connection.setRequestProperty("X-GridLens-Token", token); connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(10000); connection.setReadTimeout(10000);
            if (operation != null) {
                byte[] body = operation.toString().getBytes(StandardCharsets.UTF_8); connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json"); connection.setDoOutput(true); connection.setFixedLengthStreamingMode(body.length);
                try (java.io.OutputStream output = connection.getOutputStream()) { output.write(body); }
            }
            if (connection.getResponseCode() != 200) throw new java.io.IOException("Saved fleet operation rejected");
            try (InputStream input = connection.getInputStream()) { return new JSONObject(new String(NodeRuntime.readBounded(input, 4 * 1024 * 1024), StandardCharsets.UTF_8)); }
        } finally { connection.disconnect(); }
    }
    private void forget() {
        new AlertDialog.Builder(activity).setTitle("Forget all SSH credentials?")
            .setMessage("Delete encrypted SSH passwords, private keys, passphrases and per-PC credential overrides. Your saved fleets, network profiles and trusted SSH identities remain.")
            .setPositiveButton("Forget credentials", (d, w) -> { try { store.forgetCredentials(); Toast.makeText(activity, "Credentials forgotten. Saved fleets remain.", Toast.LENGTH_LONG).show(); } catch (Exception error) { credentialError(); } })
            .setNegativeButton("Cancel", null).show();
    }
    void stopped() { if (working) cancel(); if (store != null) store.lock(); }
    void destroy() { destroyed = true; cancel(); if (keyForm != null) keyForm.clear(); io.shutdownNow(); if (store != null) store.lock(); }
    private void cancel() {
        generation++; working = false; if (network != null) { network.cancel(); try { network.close(); } catch (Exception ignored) {} network = null; }
        dismissProgress(); for (SshDiscovery.Credential credential : openCredentials) credential.close(); openCredentials.clear(); if (store != null) store.lock();
    }
    private void ui(int run, Runnable action) { activity.runOnUiThread(() -> { if (!destroyed && run == generation) action.run(); }); }
    private void busy(String title, String text) {
        dismissProgress(); LinearLayout form = form(); progressText = label(form, text);
        progress = new AlertDialog.Builder(activity).setTitle(title).setView(form).setNegativeButton("Cancel", (d, w) -> cancel()).setOnCancelListener(d -> cancel()).show();
    }
    private void dismissProgress() { if (progress != null) { progress.dismiss(); progress = null; } }
    private LinearLayout form() { LinearLayout view = new LinearLayout(activity); view.setOrientation(LinearLayout.VERTICAL); int p = (int) (16 * activity.getResources().getDisplayMetrics().density); view.setPadding(p,p,p,p); return view; }
    private ScrollView scroll(LinearLayout form) { ScrollView scroll = new ScrollView(activity); scroll.addView(form); return scroll; }
    private TextView label(LinearLayout form, String text) { TextView label = new TextView(activity); label.setText(text); label.setPadding(0,8,0,8); form.addView(label); return label; }
    private EditText field(LinearLayout form, String hint, String text, boolean secret) {
        label(form, hint); EditText field = new EditText(activity); field.setHint(hint); field.setText(text); field.setSingleLine(true); field.setSaveEnabled(false);
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        field.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        field.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI | android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        form.addView(field); return field;
    }
    private CheckBox check(LinearLayout form, String text, boolean checked) { CheckBox value = new CheckBox(activity); value.setText(text); value.setChecked(checked); form.addView(value); return value; }
    private void message(String title, String text) { if (!destroyed) new AlertDialog.Builder(activity).setTitle(title).setMessage(text).setPositiveButton("OK", null).show(); }
    private void storageError() { message("Saved data unavailable", "GridLens could not confirm this operation. Check saved records before retrying. A native discovery-storage error does not delete your public fleets."); }
    private void credentialError() { message("Saved credentials unavailable", "Android could not decrypt saved credentials. If the Keystore key was invalidated, use Forget credentials and enter them again. Saved fleets remain available."); }
    private static String display(DiscoveryPolicy.Candidate candidate) { return (candidate.name == null || candidate.name.isEmpty() ? "" : candidate.name + " — ") + candidate.address + ":" + candidate.port; }
    private static String status(SshDiscovery.Status status) { return status.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' '); }
    private static String join(JSONArray values, String separator) { List<String> strings = new ArrayList<>(); if (values != null) for (int i = 0; i < values.length(); i++) strings.add(values.optString(i)); return String.join(separator, strings); }
    private static List<String> strings(JSONArray values) throws Exception { List<String> result = new ArrayList<>(); for (int i = 0; i < values.length(); i++) result.add(values.getString(i)); return result; }
}
