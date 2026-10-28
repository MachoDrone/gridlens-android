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
import java.util.Set;
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
    private final MonitoringController monitoring;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile DiscoveryNetwork network;
    private AlertDialog progress;
    private TextView progressText;
    private JSONObject profile;
    private String fleetId;
    private volatile int generation;
    private volatile boolean destroyed;
    private boolean working;
    private CredentialForm keyForm;
    private final List<SshDiscovery.Credential> openCredentials = new ArrayList<>();
    private final java.util.Map<String, String> credentialIds = new java.util.HashMap<>();
    private final java.util.Map<String, byte[]> monitoringSudo = new java.util.HashMap<>();
    private List<SshDiscovery.BatchRequest> lastRequests = new ArrayList<>();
    private boolean enableMonitoringForBatch, monitoringIntent, foreground;
    private volatile boolean recoveringConnections;

    DiscoveryController(Activity activity, String origin, String token, Navigation navigation) throws Exception {
        this.activity = activity; this.origin = origin; this.token = token; this.navigation = navigation;
        DiscoveryStore opened;
        try { opened = new DiscoveryStore(activity); } catch (Exception unavailable) { opened = null; }
        store = opened;
        monitoring = store == null ? null : new MonitoringController(activity, store, origin, token);
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
        monitoringIntent = false;
        if (monitoring != null) monitoring.pause();
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
            if (prior == null && fleetId != null) {
                JSONArray targets = store.monitoringTargets();
                for (int i = 0; i < targets.length(); i++) {
                    JSONObject target = targets.getJSONObject(i);
                    if (fleetId.equals(target.getString("fleetId")) && host.identity.keyBase64.equals(target.getString("keyBase64"))
                        && host.candidate.address.equals(target.getString("address")) && host.candidate.port == target.getInt("port")) {
                        prior = store.credential(target.optString("credentialId", ""));
                    }
                }
            }
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
                        if (value.has("id")) credentialIds.put(host.candidate.endpointKey(), value.getString("id"));
                        else credentialIds.remove(host.candidate.endpointKey());
                        old.credential.close(); openCredentials.remove(old.credential);
                        reviewRequests(hosts, requests);
                    } catch (Exception error) { credentialError(); cancel(); }
                });
            }).setPositiveButton(monitoringIntent ? "Enable monitoring" : "Inspect selected PCs", (d, w) -> {
                if (monitoringIntent) approveGpuRecording(hosts, requests); else collect(requests, false);
            })
            .setNeutralButton(monitoringIntent ? "Inspect only" : "Enable monitoring…", (d, w) -> {
                if (monitoringIntent) collect(requests, false); else approveGpuRecording(hosts, requests);
            })
            .setNegativeButton("Cancel", (d, w) -> cancel()).setOnCancelListener(d -> cancel()).show();
    }
    private void approveGpuRecording(List<Trusted> hosts, List<SshDiscovery.BatchRequest> requests) {
        new AlertDialog.Builder(activity).setTitle("Enable monitoring on " + hosts.size() + " trusted PCs?")
            .setMessage("GridLens will remember the selected SSH credentials encrypted on this phone and reconnect only to these verified PCs over your Wi-Fi or VPN while this fleet is open. No further network searches are needed.\n\n"
                + "Rewards: verify each node inside its existing container, then use its existing node identity there for the fixed Nosana rewards GET. Private keys stay inside that container. Its authorization is sent only to Nosana; the signature has broader authority, while this helper permits only the rewards GET and exposes no claim or transfer action. Only the public address, amount, time and status return to your phone. Reads run at most every five minutes, honoring provider delays; denied authorization pauses that node.\n\n"
                + "GPU: create labeled GridLens Docker recorders and named volumes using a pinned image. They keep bounded history while the phone is closed. Read recordings every 30 seconds while viewing the fleet. GridLens may repair its own recorder connection after a node restarts. No host installation, host networking changes or node workload restarts. Docker/NVIDIA support must already exist.\n\n"
                + "IP addresses: read the PC’s interfaces and check public egress from one verified node using ipify. This sends no keys or credentials to that service. Checks are shared across this PC’s hosts and cached for 30 minutes.\n\n"
                + "Changed identities or credentials pause the affected PC. Missing data remains unknown; public Fleet data continues independently.")
            .setPositiveButton("Enable monitoring", (d, w) -> collect(requests, true))
            .setNegativeButton("Back", (d, w) -> reviewRequests(hosts, requests))
            .setOnCancelListener(d -> cancel()).show();
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
            if (value.has("id")) credentialIds.put(host.candidate.endpointKey(), value.getString("id"));
            byte[] sudo = null;
            if (monitoringIntent && fleetId != null) {
                JSONArray targets = store.monitoringTargets();
                for (int i = 0; i < targets.length(); i++) {
                    JSONObject target = targets.getJSONObject(i);
                    if (fleetId.equals(target.getString("fleetId")) && host.identity.keyBase64.equals(target.getString("keyBase64"))
                            && host.candidate.address.equals(target.getString("address")) && host.candidate.port == target.getInt("port") && target.optBoolean("sudoApproved")) {
                        sudo = target.optString("sudoPassword", "").getBytes(StandardCharsets.UTF_8);
                        monitoringSudo.put(host.candidate.endpointKey(), sudo);
                    }
                }
            }
            requests.add(new SshDiscovery.BatchRequest(host.candidate, host.identity, credential, sudo));
            credentials(hosts, index + 1, requests, shared);
        } catch (Exception error) { credentialError(); cancel(); }
    }
    static SshDiscovery.Credential decodeCredential(JSONObject value) throws Exception {
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
    private void collect(List<SshDiscovery.BatchRequest> selectedRequests, boolean enableGpuRecording) {
        enableMonitoringForBatch = enableGpuRecording;
        String collectorSource;
        try (InputStream input = activity.getAssets().open("gpu-collector/collector.py")) {
            collectorSource = new String(NodeRuntime.readBounded(input, GpuCollector.MAX_SOURCE_BYTES), StandardCharsets.UTF_8);
        } catch (Exception error) {
            message("GPU recorder unavailable", "The app's GPU recorder could not be loaded. No PC changes were started. Retry after updating GridLens.");
            cancel(); return;
        }
        List<SshDiscovery.BatchRequest> requests = new ArrayList<>();
        for (SshDiscovery.BatchRequest selected : selectedRequests) requests.add(new SshDiscovery.BatchRequest(selected.candidate,
                selected.identity, selected.credential, selected.sudoPassword, collectorSource, false));
        lastRequests = requests;
        working = true; int run = generation; busy("Identifying Nosana hosts", enableGpuRecording
                ? "Verifying exact node containers on the selected PCs before enabling approved monitoring…"
                : "Reading bounded container metadata and existing GPU recordings on selected, trusted computers…");
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
            } catch (Exception error) { ui(run, () -> { dismissProgress(); working = false; message("Inspection stopped", enableGpuRecording
                    ? "The connection changed or inspection was cancelled. Some approved GPU recorder operations may have completed. Existing recorders and saved GPU history remain on the selected PCs; rescan to check their status."
                    : "The connection changed or inspection was cancelled. Read-only discovery did not change workloads or host configuration."); cancel(); }); }
        });
    }
    private void sudoNext(List<SshDiscovery.BatchRequest> requests, int index, java.util.Map<String, SshDiscovery.Result> results, int expected) {
        if (index >= requests.size()) { saveResults(results, expected); return; }
        SshDiscovery.BatchRequest request = requests.get(index); LinearLayout form = form();
        label(form, display(request.candidate) + (enableMonitoringForBatch
                ? " needs elevated container access. Approve the fixed inventory, in-container rewards GET and owned GPU recorder operations already selected. This password is remembered encrypted only for this approved PC, so monitoring can reuse it without repeated prompts. No interactive shell or workload changes are allowed."
                : " needs elevated container access. Approve only the fixed, read-only Docker/Podman inventory and existing owned GPU-recording reads. This does not grant an interactive shell, install software, read wallet files, restart workloads or change configuration."));
        EditText password = field(form, "This PC's sudo password (if required)", "", true);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("Approve inventory with sudo?").setView(scroll(form))
            .setPositiveButton("Approve this PC", (d, w) -> {
                byte[] secret = password.getText().toString().getBytes(StandardCharsets.UTF_8); password.setText("");
                if (enableMonitoringForBatch) monitoringSudo.put(request.candidate.endpointKey(), secret.clone());
                int run = generation; working = true; busy("Reading approved inventory", display(request.candidate));
                DiscoveryNetwork selectedNetwork = network;
                io.execute(() -> {
                    try {
                        SshDiscovery.Result result = SshDiscovery.inventory(selectedNetwork, request.candidate, request.identity, request.credential, secret, request.collectorSource, request.enableGpuRecording);
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
                .append("; ").append(result.getValue().addresses.size()).append(" public addresses, ").append(result.getValue().unresolved).append(" unresolved observations")
                .append(result.getValue().gpuCollectorStatus == null ? "" : "; " + result.getValue().gpuCollectorStatus.label);
        java.util.Map<String, PcMetadata> pcMetadata = InspectionResults.metadataByAddress(results.values());
        long pcConflicts = pcMetadata.values().stream().filter(pc -> pc.pcId == null).count();
        String summary = addresses.size() + " verified public host addresses. " + unresolved + " unresolved Nosana instance observations. " + inaccessible + " inaccessible or failed PC inspections."
            + (authFailed ? " The credential batch stopped after an authentication failure; remaining PCs were not tried." : "")
            + (results.size() < expected ? " Some selected PCs were not inspected." : "")
            + (unresolved > 0 ? " Normal and elevated inventory observations may refer to the same unresolved instance." : "")
            + (pcConflicts > 0 ? " " + pcConflicts + " host addresses appeared on conflicting PC identities; their PC name and IP addresses remain unknown." : "") + pcDetails;
        if (addresses.isEmpty() && !allowsEmptyMonitoringMerge(fleetId, enableMonitoringForBatch, lastRequests, results)) {
            message("No verified host identities", summary + " Existing saved fleets remain unchanged."); cancel(); return;
        }
        String savedFleet = fleetId;
        int run = generation;
        JSONObject operation = new JSONObject();
        try { operation.put("op", savedFleet == null ? "create" : "merge").put("addresses", new JSONArray(addresses));
            JSONArray metadata = new JSONArray();
            for (java.util.Map.Entry<String, PcMetadata> entry : pcMetadata.entrySet()) {
                PcMetadata pc = entry.getValue();
                JSONObject reading = new JSONObject().put("address", entry.getKey()).put("pcId", pc.pcId == null ? JSONObject.NULL : pc.pcId)
                        .put("pcName", pc.pcName == null ? JSONObject.NULL : pc.pcName)
                        .put("ipAddresses", new JSONArray(pc.ipAddresses)).put("observedAtMs", pc.observedAtMs);
                if (pc.pcId != null && pc.gpuTelemetryJson != null) reading.put("gpuTelemetry", new JSONObject(pc.gpuTelemetryJson));
                metadata.put(reading);
            }
            operation.put("pcMetadata", metadata);
            if (savedFleet == null) operation.put("name", profile.getString("name")); else operation.put("id", savedFleet);
        } catch (Exception error) { storageError(); cancel(); return; }
        working = true; busy("Saving discovered fleet", summary);
        io.execute(() -> {
            try {
                JSONObject response = api(operation);
                String id = response.getString("fleetId");
                List<JSONObject> setup;
                String setupFailure = null;
                try { setup = monitoring == null ? new ArrayList<>() : monitoring.register(id, profile.getString("id"), lastRequests,
                        results, credentialIds, monitoringSudo, enableMonitoringForBatch); }
                catch (Exception error) { setup = new ArrayList<>(); setupFailure = "The public fleet was saved, but monitoring setup could not be saved. Each PC supports at most 64 exact node bindings and needs remembered SSH credentials. Open Fleets → Enable / repair monitoring to retry."; }
                List<JSONObject> setupTargets = setup;
                String setupIssue = setupFailure;
                boolean enabled = enableMonitoringForBatch;
                long pcOnly = setupTargets.stream().filter(target -> "binding-changed".equals(target.optString("status"))
                        && !target.optBoolean("setupPending")).count();
                ui(run, () -> {
                    dismissProgress(); working = false; cancel(); navigation.open("fleet:" + id);
                    if (monitoring != null && enabled) monitoring.startSetup(setupTargets);
                    message("Fleet saved", summary + (setupIssue != null ? " " + setupIssue : enabled ? setupTargets.isEmpty()
                            ? " No approved PC connection could be registered for this fleet. Existing public Fleet data remains available."
                            : pcOnly > 0 ? " PC IP checks are enabled. Node bindings remain unverified on " + pcOnly
                                + " PCs; rewards and GPU setup are not started on those PCs. Keep this fleet open for independent IP checks."
                            : " PC IP checks are enabled, and monitoring setup is verifying rewards and GPU access. Keep this fleet open; unavailable targets show their own status."
                            : " Public Fleet reads need no SSH. Choose Enable monitoring to connect rewards and GPU recordings using these saved PCs."));
                });
            } catch (Exception error) { ui(run, () -> { dismissProgress(); working = false; message("Fleet could not be saved", "The save could not be confirmed. Check Saved fleets before retrying; the request may have completed."); cancel(); }); }
        });
    }
    /** An explicitly approved saved-PC repair may keep the roster when node discovery is unavailable. */
    static boolean allowsEmptyMonitoringMerge(String savedFleet, boolean approved, List<SshDiscovery.BatchRequest> requests,
            java.util.Map<String, SshDiscovery.Result> results) {
        if (!approved || savedFleet == null || !savedFleet.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) return false;
        for (SshDiscovery.BatchRequest request : requests) {
            SshDiscovery.Result result = results.get(request.candidate.endpointKey());
            if (result == null || result.pcMetadata == null || result.status == SshDiscovery.Status.AUTH_FAILED
                    || result.status == SshDiscovery.Status.IDENTITY_CHANGED || result.status == SshDiscovery.Status.CANCELLED
                    || result.status == SshDiscovery.Status.UNREACHABLE) continue;
            try { if (MonitoringController.pcId(request.identity).equals(result.pcMetadata.pcId)) return true; }
            catch (Exception invalidIdentity) { /* Only an authenticated, matching PC can preserve this repair flow. */ }
        }
        return false;
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
            .setItems(new String[]{"Open fleet", "Check saved connections", "Enable / repair monitoring", "Monitoring status", "Rescan and merge", "Rename", "Remove selected hosts", "Delete fleet"}, (d, which) -> {
                if (which == 0) mutate("select", null, "scenario", "fleet:" + id, true);
                else if (which == 1) checkSavedConnections(id);
                else if (which == 2) enableMonitoring(id);
                else if (which == 3) monitoringStatus(id);
                else if (which == 4) { fleetId = id; prepare(); }
                else if (which == 5) { LinearLayout form = form(); EditText name = field(form, "Fleet name", fleet.optString("name"), false);
                    new AlertDialog.Builder(activity).setTitle("Rename fleet").setView(form).setPositiveButton("Save", (dd, w) -> mutate("rename", id, "name", name.getText().toString(), false)).setNegativeButton("Cancel", null).show();
                } else if (which == 6) removeHosts(fleet);
                else new AlertDialog.Builder(activity).setTitle("Delete saved fleet?").setMessage("This removes only this public-address fleet. Credentials and network profiles remain.")
                    .setPositiveButton("Delete", (dd, w) -> mutate("delete", id, null, null, true)).setNegativeButton("Cancel", null).show();
            }).setNegativeButton("Close", null).show();
    }
    void enableMonitoring(String id) {
        if (working) { if (progress != null) progress.show(); return; }
        if (store == null || monitoring == null || id == null || !id.matches("[a-f0-9-]{36}")) { storageError(); return; }
        int run = generation; working = true; busy("Opening monitoring setup", "Checking the saved fleet…");
        io.execute(() -> {
            try {
                JSONArray fleets = api(null).getJSONArray("fleets"); boolean known = false;
                for (int i = 0; i < fleets.length(); i++) if (id.equals(fleets.getJSONObject(i).getString("id"))) known = true;
                if (!known) throw new IllegalArgumentException("Unknown fleet");
                ui(run, () -> { dismissProgress(); working = false; beginMonitoring(id); });
            } catch (Exception error) { ui(run, () -> { dismissProgress(); working = false; storageError(); }); }
        });
    }
    void checkSavedConnections(String id) {
        if (destroyed) return;
        if (working) { if (progress != null) progress.show(); return; }
        if (store == null || monitoring == null || id == null || !id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) { storageError(); return; }
        if (monitoring.storageNeedsRecovery()) {
            message("Monitoring storage needs attention", "Local permission storage could not save a previous restriction. Check saved connections cannot clear that block. Restore available storage and use Enable / repair monitoring.");
            return;
        }
        cancel(); monitoring.pause();
        recoveringConnections = true; working = true;
        int run = generation;
        busy("Checking saved connections", "Verifying saved SSH identities and nodes. No network scan or rewards/GPU read is started by this check. Cancel leaves unchecked connections paused.");
        io.execute(() -> {
            DiscoveryNetwork captured = null;
            try {
                JSONArray fleets = api(null).getJSONArray("fleets"); boolean known = false;
                for (int i = 0; i < fleets.length(); i++) if (id.equals(fleets.getJSONObject(i).getString("id"))) known = true;
                if (!known) throw new IllegalArgumentException("Unknown saved fleet");
                if (run != generation || destroyed) return;
                captured = DiscoveryNetwork.capture(activity); network = captured;
                if (run != generation || destroyed) { captured.cancel(); return; }
                MonitoringController.RecoveryReport result = monitoring.checkSavedConnections(id, captured,
                        () -> run == generation && !destroyed && recoveringConnections,
                        (done, total, verified, skipped) -> ui(run, () -> progressText.setText("PC connections: " + done + " / " + total
                                + " processed; " + verified + " verified; " + skipped + " skipped after a shared credential rejection.")));
                ui(run, () -> {
                    dismissProgress(); working = false; recoveringConnections = false; cancel();
                    if (!result.cancelled && result.resumed > 0) selectScenario("fleet:" + id);
                    String detail = result.eligible == 0 ? "No previously approved credential-paused connections need this check. Use Enable / repair monitoring for missing or changed connections."
                            : result.checked + " PC connections checked; " + result.resumed + " verified connections resumed. "
                                + (result.eligible - result.resumed) + " remain paused. Existing reward denials and provider retry times are preserved."
                                + result.failures
                                + (result.resumed < result.eligible ? " Use Enable / repair monitoring if saved credentials or node bindings need to change." : "")
                                + (result.resumed > 0 ? " Resumed connections refresh when this saved fleet is open." : "");
                    message(result.cancelled ? "Connection check stopped" : "Saved connections checked", detail);
                });
            } catch (Exception unavailable) { ui(run, () -> {
                dismissProgress(); working = false; recoveringConnections = false; cancel();
                message("Saved connections remain paused", monitoring.storageNeedsRecovery()
                        ? "Local permission storage could not save a restriction. Restore available storage and use Enable / repair monitoring. This connection check cannot clear the block."
                        : "The check could not finish. Check the Wi-Fi or VPN connection and saved credentials; use Enable / repair monitoring if saved permissions need attention. No new monitoring permissions were granted.");
            }); }
            finally { if (captured != null) { captured.close(); if (network == captured) network = null; } }
        });
    }
    private void beginMonitoring(String id) {
        cancel(); fleetId = id; monitoringIntent = true; monitoring.pause();
        try {
            network = DiscoveryNetwork.capture(activity);
            JSONArray known = store.knownEndpoints();
            java.util.LinkedHashMap<String, List<JSONObject>> byProfile = new java.util.LinkedHashMap<>();
            JSONArray saved = store.monitoringTargets();
            Set<String> selectedEndpoints = new java.util.HashSet<>();
            Set<String> selectedKeys = new java.util.HashSet<>();
            for (int i = 0; i < saved.length(); i++) {
                JSONObject target = saved.getJSONObject(i);
                if (id.equals(target.getString("fleetId"))) {
                    selectedEndpoints.add(target.getString("profileId") + "|" + target.getString("address") + "|" + target.getInt("port"));
                    selectedKeys.add(target.getString("keyBase64"));
                }
            }
            for (int i = 0; i < known.length(); i++) {
                JSONObject endpoint = known.getJSONObject(i);
                String key = endpoint.getString("profileId");
                if (!selectedEndpoints.isEmpty() && !selectedEndpoints.contains(key + "|" + endpoint.getString("address") + "|" + endpoint.getInt("port"))
                        && !selectedKeys.contains(endpoint.getString("keyBase64"))) continue;
                byProfile.computeIfAbsent(key, ignored -> new ArrayList<>()).add(endpoint);
            }
            if (byProfile.isEmpty()) {
                message("Discover this fleet once", "No trusted SSH endpoints are saved for this fleet. Use Rescan and select your PCs once; Enable monitoring will then reuse them without future network searches."); cancel(); return;
            }
            List<List<JSONObject>> groups = new ArrayList<>(byProfile.values());
            if (groups.size() == 1) selectKnownMonitoring(groups.get(0));
            else {
                String[] labels = new String[groups.size()];
                for (int i = 0; i < groups.size(); i++) labels[i] = groups.get(i).get(0).optString("profileName") + " (" + groups.get(i).size() + " known PCs)";
                new AlertDialog.Builder(activity).setTitle("Saved SSH connections").setItems(labels, (dialog, which) -> selectKnownMonitoring(groups.get(which)))
                        .setNegativeButton("Cancel", (dialog, which) -> cancel()).setOnCancelListener(dialog -> cancel()).show();
            }
        } catch (Exception error) { message("Monitoring setup unavailable", "Connect to the Wi-Fi or VPN that reaches your saved PCs. If saved credentials are unavailable, use Fleets to enter them again. Public Fleet data remains available."); cancel(); }
    }
    private void selectKnownMonitoring(List<JSONObject> endpoints) {
        try {
            profile = new JSONObject().put("id", endpoints.get(0).getString("profileId")).put("name", endpoints.get(0).getString("profileName"));
            String[] labels = new String[endpoints.size()]; boolean[] checked = new boolean[endpoints.size()];
            for (int i = 0; i < endpoints.size(); i++) { labels[i] = endpoints.get(i).getString("address") + ":" + endpoints.get(i).getInt("port"); checked[i] = true; }
            new AlertDialog.Builder(activity).setTitle("Enable monitoring on saved PCs")
                    .setMultiChoiceItems(labels, checked, (dialog, index, selected) -> checked[index] = selected)
                    .setPositiveButton("Continue", (dialog, which) -> {
                        try {
                            List<Trusted> trusted = new ArrayList<>();
                            for (int i = 0; i < endpoints.size(); i++) if (checked[i]) {
                                JSONObject endpoint = endpoints.get(i);
                                trusted.add(new Trusted(new DiscoveryPolicy.Candidate(endpoint.getString("address"), endpoint.getInt("port"), ""),
                                        new SshDiscovery.Identity(endpoint.getString("keyBase64"), endpoint.getString("algorithm"))));
                            }
                            if (trusted.isEmpty()) { cancel(); return; }
                            credentials(trusted, 0, new ArrayList<>(), null);
                        } catch (Exception error) { credentialError(); cancel(); }
                    }).setNegativeButton("Cancel", (dialog, which) -> cancel()).setOnCancelListener(dialog -> cancel()).show();
        } catch (Exception error) { storageError(); cancel(); }
    }
    private void monitoringStatus(String id) {
        try {
            JSONArray targets = store.monitoringTargets(); StringBuilder text = new StringBuilder();
            if (monitoring.storageNeedsRecovery()) text.append("Monitoring stopped because an authorization restriction could not be saved. Repair storage, then explicitly Enable / repair monitoring. No further SSH requests are started.\n\n");
            for (int i = 0; i < targets.length(); i++) {
                JSONObject target = targets.getJSONObject(i);
                if (!id.equals(target.getString("fleetId"))) continue;
                text.append(target.getString("address")).append(":").append(target.getInt("port")).append(" — ")
                        .append(target.optString("status", "authorization-required").replace('-', ' '))
                        .append(target.optBoolean("rewardsAuthorized") ? "" : "; rewards need verification")
                        .append(target.has("lastConnectionCheckStatus") ? "; last connection check: " + target.optString("lastConnectionCheckStatus").replace('-', ' ') : "").append("\n");
            }
            if (text.length() == 0) text.append("Monitoring is not enabled for this fleet. Use your saved SSH connections to set it up once.");
            new AlertDialog.Builder(activity).setTitle("Monitoring status").setMessage(text.toString())
                    .setPositiveButton("Enable / repair", (dialog, which) -> enableMonitoring(id))
                    .setNeutralButton("Pause monitoring", (dialog, which) -> {
                        try { monitoring.pauseFleet(id); message("Monitoring paused", "Phone SSH reads are paused for this fleet. Existing owned GPU recorders retain their data and continue recording on the PCs. Public Fleet reads continue."); }
                        catch (Exception error) { storageError(); }
                    }).setNegativeButton("Close", null).show();
        } catch (Exception error) { credentialError(); }
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
        boolean changesMonitoringScope = "delete".equals(op) || "remove-hosts".equals(op);
        if (changesMonitoringScope && monitoring != null) monitoring.pause();
        io.execute(() -> {
            try {
                JSONObject request = new JSONObject().put("op", op); if (id != null) request.put("id", id); if (field != null) request.put(field, value);
                if ("delete".equals(op) && id != null && store != null) store.removeMonitoringFleet(id);
                JSONObject response = api(request); activity.runOnUiThread(() -> { if (!destroyed) {
                    if (open) navigation.open(response.optString("selectedScenario", "network-discovery")); else fleets();
                    if (changesMonitoringScope && foreground && monitoring != null) monitoring.resume();
                } });
            } catch (Exception error) { activity.runOnUiThread(() -> {
                storageError(); if (changesMonitoringScope && foreground && monitoring != null && !destroyed) monitoring.resume();
            }); }
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
            .setPositiveButton("Forget credentials", (d, w) -> { try { if (monitoring != null) monitoring.pause(); store.forgetCredentials(); Toast.makeText(activity, "Credentials and monitoring permissions forgotten. Saved fleets remain.", Toast.LENGTH_LONG).show(); } catch (Exception error) { credentialError(); } })
            .setNegativeButton("Cancel", null).show();
    }
    void selectedScenario(String scenario) { if (monitoring != null) monitoring.selectedScenario(scenario); }
    void resumed() { foreground = true; if (monitoring != null && !working && network == null) monitoring.resume(); }
    void paused() { foreground = false; if (monitoring != null) monitoring.pause(); if (recoveringConnections) cancel(); }
    void stopped() { paused(); if (working) cancel(); if (store != null) store.lock(); }
    void destroy() { destroyed = true; if (monitoring != null) monitoring.destroy(); cancel(); if (keyForm != null) keyForm.clear(); io.shutdownNow(); if (store != null) store.lock(); }
    private void cancel() {
        generation++; working = false; recoveringConnections = false; if (network != null) { network.cancel(); try { network.close(); } catch (Exception ignored) {} network = null; }
        dismissProgress(); for (SshDiscovery.Credential credential : openCredentials) credential.close(); openCredentials.clear(); if (store != null) store.lock();
        credentialIds.clear(); lastRequests = new ArrayList<>();
        for (byte[] password : monitoringSudo.values()) Arrays.fill(password, (byte) 0);
        monitoringSudo.clear();
        if (!destroyed && foreground && monitoring != null) monitoring.resume();
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
