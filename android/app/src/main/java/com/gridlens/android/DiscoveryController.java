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
import android.widget.RadioButton;
import android.widget.RadioGroup;
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
    private final DiscoveryUi presentation;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile DiscoveryNetwork network;
    private AlertDialog progress;
    private AlertDialog selectionDialog;
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
    private boolean enableMonitoringForBatch, monitoringIntent;
    private volatile boolean enableAutomaticIpForBatch, foreground;
    private volatile boolean recoveringConnections;
    private volatile boolean directPcChecking;
    private boolean directPcCredentialEntry;
    private AlertDialog directPcDialog;
    private String directPcFleet;

    DiscoveryController(Activity activity, String origin, String token, Navigation navigation) throws Exception {
        this.activity = activity; this.origin = origin; this.token = token; this.navigation = navigation;
        presentation = new DiscoveryUi(activity);
        DiscoveryStore opened;
        try { opened = new DiscoveryStore(activity); } catch (Exception unavailable) { opened = null; }
        store = opened;
        monitoring = store == null ? null : new MonitoringController(activity, store, origin, token);
    }
    void show(String rescanFleet) {
        if (working) { if (progress != null) progress.show(); return; }
        fleetId = rescanFleet;
        presentation.menu(rescanFleet == null ? "Network Discovery" : "Rescan saved fleet", "Choose what you want to do.", Arrays.asList(
                new DiscoveryUi.Action(rescanFleet == null ? "Search network" : "Rescan network", "Find computers on your Wi-Fi or existing VPN.", this::prepare),
                new DiscoveryUi.Action("Saved fleets", "Open or manage hosts you already saved.", this::fleets),
                new DiscoveryUi.Action("Network profiles", "Manage networks and connection settings.", () -> profiles(false)),
                new DiscoveryUi.Action("Forget credentials", "Remove saved SSH credentials and monitoring permissions. Your fleets stay saved.", this::forget)), "Close", null);
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
            if (store == null) { storageError(); return; }
            JSONArray list = store.profiles();
            if (list.length() == 0) { editProfile(null, searching); return; }
            List<DiscoveryUi.Action> actions = new ArrayList<>();
            actions.add(new DiscoveryUi.Action("New network profile", "Save the network settings for another location.", () -> editProfile(null, searching)));
            for (int i = 0; i < list.length(); i++) {
                JSONObject chosen = list.getJSONObject(i);
                actions.add(new DiscoveryUi.Action(chosen.getString("name"), join(chosen.optJSONArray("ranges"), ", "), () -> {
                try {
                        if (searching) { profile = chosen; confirmSearch(); }
                        else presentation.menu(chosen.getString("name"), "Manage this saved network.", Arrays.asList(
                            new DiscoveryUi.Action("Edit profile", "Change the name, network ranges or SSH ports.", () -> editProfile(chosen, false)),
                            new DiscoveryUi.Action("Delete profile", "Remove this network and its saved SSH identities. Fleets and credentials remain.", () -> presentation.show(presentation.builder("Delete network profile?")
                                    .setMessage("Saved fleets and credentials remain. Trusted endpoint keys in this profile will be removed.")
                                    .setPositiveButton("Delete", (dd, w) -> { try { store.deleteProfile(chosen.getString("id")); } catch (Exception e) { storageError(); } })
                                    .setNegativeButton("Cancel", null)))), "Close", null);
                } catch (Exception error) { if (searching) cancel(); storageError(); }
                }));
            }
            presentation.menu("Network profiles", "Choose a saved network or add another location.", actions, "Cancel", searching ? this::cancel : null);
        } catch (Exception error) { if (searching) cancel(); storageError(); }
    }
    private void editProfile(JSONObject existing, boolean searching) {
        LinearLayout form = form();
        EditText name = field(form, "Network profile name", existing == null ? "My network" : existing.optString("name"), false);
        List<String> suggestions = network == null ? new ArrayList<>() : network.suggestedRanges;
        String rangesText = existing == null ? String.join("\n", suggestions) : join(existing.optJSONArray("ranges"), "\n");
        if (existing == null && !suggestions.isEmpty()) label(form, network.vpn
                ? "These ranges were suggested by your VPN routes. Check that they describe the computers you want to find. SSH normally uses port 22."
                : "These suggested settings usually work on your current Wi-Fi. You can normally leave them unchanged. SSH normally uses port 22.");
        else if (existing != null) label(form, "These are your saved settings. Keep them if the computers are still on this network. SSH normally uses port 22.");
        label(form, "The network containing your computers. This is not your phone's VPN address.");
        EditText ranges = field(form, "Network ranges", rangesText, false); ranges.setSingleLine(false); ranges.setMinLines(2);
        if (rangesText.isEmpty()) label(form, "The active connection does not reveal a usable destination range. Learn it once while on-site Wi-Fi, or enter a destination network range. A full-tunnel VPN cannot reveal a hidden remote network.");
        LinearLayout advanced = new LinearLayout(activity); advanced.setOrientation(LinearLayout.VERTICAL);
        label(advanced, "Enter one range per line: IPv4 /16–/32, or individual IPv6 /128 addresses. An empty range searches existing SSH advertisements only.");
        EditText ports = field(advanced, "SSH ports", existing == null ? "22" : join(existing.optJSONArray("ports"), ","), false);
        label(advanced, "Separate additional ports with commas. The standard SSH port 22 is always included.");
        form.addView(presentation.action("Advanced settings", "Network range help and custom SSH ports.", () -> advanced.setVisibility(advanced.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE)));
        form.addView(advanced); advanced.setVisibility(rangesText.isEmpty() ? View.VISIBLE : View.GONE);
        AlertDialog dialog = presentation.builder("Network profile").setView(scroll(form))
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
        })); dialog.show(); presentation.style(dialog);
    }
    private static void validateRange(String range) {
        String[] parts = range.split("/", -1); if (parts.length != 2) throw new IllegalArgumentException();
        int prefix = Integer.parseInt(parts[1]);
        java.net.InetAddress address = DiscoveryPolicy.numericAddress(parts[0]);
        if (address instanceof java.net.Inet6Address ? prefix != 128 : prefix < 16 || prefix > 32) throw new IllegalArgumentException();
    }
    private void confirmSearch() {
        presentation.show(presentation.builder("Search " + profile.optString("name"))
            .setMessage("Find SSH computers in " + (profile.optJSONArray("ranges").length() == 0 ? "existing SSH advertisements" : join(profile.optJSONArray("ranges"), ", ")) + ".\nSSH ports: " + join(profile.optJSONArray("ports"), ", ") + "\n\nYou will choose computers and approve their identities before signing in. Network changes cancel this search.")
            .setPositiveButton("Search", (d, w) -> scan()).setNeutralButton("Edit profile", (d, w) -> editProfile(profile, true))
            .setNegativeButton("Cancel", (d, w) -> cancel()).setOnCancelListener(d -> cancel()));
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
        List<DiscoveryUi.Row> rows = new ArrayList<>();
        try {
            for (DiscoveryPolicy.Candidate candidate : candidates) {
                boolean saved = store.trustedKey(profile.getString("id"), candidate.endpointKey()) != null;
                rows.add(new DiscoveryUi.Row(candidate.endpointKey(), display(candidate),
                        saved ? "Saved SSH identity — checked again before sign-in" : "Unfamiliar computer — identity approval needed", saved ? "saved" : "new", true));
            }
        } catch (Exception error) { storageError(); cancel(); return; }
        int run = generation;
        selectionDialog = presentation.select("Select computers to inspect",
                "These computers answered SSH. Sign-in and inspection are needed to confirm Nosana. Select the computers you want to check.",
                rows, Arrays.asList(new DiscoveryUi.Filter("All computers", row -> true),
                        new DiscoveryUi.Filter("Saved identities", row -> "saved".equals(row.group)),
                        new DiscoveryUi.Filter("Unfamiliar", row -> "new".equals(row.group))),
                java.util.Collections.emptySet(), 512, "Review SSH identities", ids -> {
                    if (run != generation || destroyed) return;
                    List<DiscoveryPolicy.Candidate> selected = new ArrayList<>();
                    for (DiscoveryPolicy.Candidate candidate : candidates) if (ids.contains(candidate.endpointKey())) selected.add(candidate);
                    reviewIdentities(selected);
                }, this::cancel);
    }
    private static final class Trusted {
        final DiscoveryPolicy.Candidate candidate; final SshDiscovery.Identity identity;
        Trusted(DiscoveryPolicy.Candidate candidate, SshDiscovery.Identity identity) { this.candidate = candidate; this.identity = identity; }
    }
    private static final class IdentityReview {
        final DiscoveryPolicy.Candidate candidate; final SshDiscovery.Identity identity; final String state;
        IdentityReview(DiscoveryPolicy.Candidate candidate, SshDiscovery.Identity identity, String state) {
            this.candidate = candidate; this.identity = identity; this.state = state;
        }
    }
    private void reviewIdentities(List<DiscoveryPolicy.Candidate> selected) {
        int run = generation; working = true; busy("Reading SSH identities", "Checking computer identities before sending any sign-in credentials…");
        DiscoveryNetwork selectedNetwork = network; String selectedProfile = profile.optString("id");
        io.execute(() -> {
            java.util.concurrent.ExecutorService workers = Executors.newFixedThreadPool(DiscoveryPolicy.SSH_CONCURRENCY);
            List<java.util.concurrent.Future<IdentityReview>> tasks = new ArrayList<>();
            java.util.concurrent.atomic.AtomicInteger completed = new java.util.concurrent.atomic.AtomicInteger();
            try {
                for (DiscoveryPolicy.Candidate candidate : selected) tasks.add(workers.submit(() -> {
                    try {
                        selectedNetwork.ensureActive();
                        String known = store.trustedKey(selectedProfile, candidate.endpointKey());
                        SshDiscovery.Identity identity = SshDiscovery.inspectIdentity(selectedNetwork, candidate, known);
                        return new IdentityReview(candidate, identity, known == null ? "new" : known.equals(identity.keyBase64) ? "saved" : "changed");
                    } catch (Exception error) { return new IdentityReview(candidate, null, "unavailable"); }
                    finally {
                        int done = completed.incrementAndGet();
                        ui(run, () -> { if (progressText != null) progressText.setText("Checked " + done + " of " + selected.size() + " computer identities"); });
                    }
                }));
                List<IdentityReview> reviews = new ArrayList<>();
                for (java.util.concurrent.Future<IdentityReview> task : tasks) reviews.add(task.get());
                selectedNetwork.ensureActive();
                ui(run, () -> { dismissProgress(); working = false; showIdentities(reviews, run, selectedProfile); });
            } catch (Exception error) {
                ui(run, () -> { cancel(); message("Identity check stopped", "The network changed or the check was cancelled. No sign-in credentials were sent."); });
            } finally { workers.shutdownNow(); }
        });
    }
    private void showIdentities(List<IdentityReview> reviews, int run, String selectedProfile) {
        List<DiscoveryUi.Row> rows = new ArrayList<>(); Set<String> selected = new LinkedHashSet<>(); boolean hasNew = false;
        for (IdentityReview review : reviews) {
            boolean saved = "saved".equals(review.state), unknown = "new".equals(review.state);
            if (saved) selected.add(review.candidate.endpointKey());
            hasNew |= unknown;
            String description = saved ? "Matches saved identity" : unknown ? "New identity — compare this fingerprint with a trusted source" :
                    "changed".equals(review.state) ? "Identity changed — blocked. Verify the computer or network profile. No credentials sent." : "Could not verify identity — not checked for Nosana";
            if (review.identity != null) description += "\n" + review.identity.algorithm + "\n" + review.identity.fingerprint;
            rows.add(new DiscoveryUi.Row(review.candidate.endpointKey(), display(review.candidate), description, review.state, saved || unknown));
        }
        selectionDialog = presentation.select("Review SSH identities",
                "Saved identities are selected when they still match. Select new computers only after comparing their fingerprints with a trusted source. Names and IP addresses alone are not identity proof.",
                rows, Arrays.asList(new DiscoveryUi.Filter("All identities", row -> true),
                        new DiscoveryUi.Filter("New identities", row -> "new".equals(row.group)),
                        new DiscoveryUi.Filter("Needs attention", row -> !row.selectable)),
                selected, 512, hasNew ? "Trust selected computers" : "Continue", ids -> {
                    if (run != generation || destroyed) return;
                    try {
                        network.ensureActive(); List<Trusted> trusted = new ArrayList<>();
                        for (IdentityReview review : reviews) if (ids.contains(review.candidate.endpointKey())) {
                            if (!"saved".equals(review.state) && !"new".equals(review.state)) continue;
                            if ("new".equals(review.state)) store.trust(selectedProfile, review.candidate.endpointKey(), review.identity.keyBase64);
                            trusted.add(new Trusted(review.candidate, review.identity));
                        }
                        credentials(trusted, 0, new ArrayList<>(), null);
                    } catch (Exception error) { cancel(); message("Identity approval stopped", "The current network or saved identities could not be confirmed. Search again before signing in."); }
                }, this::cancel);
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
            JSONArray saved = store.credentialProfiles();
            Runnable enter = () -> newCredential(host, true, (value, forRemaining) -> addRequest(hosts, index, requests, forRemaining ? value : shared, value));
            if (saved.length() == 0) { enter.run(); return; }
            List<DiscoveryUi.Action> actions = new ArrayList<>();
            actions.add(new DiscoveryUi.Action("New SSH sign-in", "Choose an existing SSH key or use a password.", enter));
            for (int i = 0; i < saved.length(); i++) {
                JSONObject savedProfile = saved.getJSONObject(i);
                actions.add(new DiscoveryUi.Action(savedProfile.getString("name"), "Username: " + savedProfile.getString("username"), () -> {
                    try {
                        JSONObject value = store.credential(savedProfile.getString("id"));
                        credentialScope(host, value, (forRemaining) -> addRequest(hosts, index, requests, forRemaining ? value : shared, value));
                    } catch (Exception error) { credentialError(); cancel(); }
                }));
            }
            presentation.menu("Credentials for " + display(host.candidate), "Choose a saved sign-in or add another.", actions, "Cancel", this::cancel);
        } catch (Exception error) { credentialError(); cancel(); }
    }
    private void reviewRequests(List<Trusted> hosts, List<SshDiscovery.BatchRequest> requests) {
        LinearLayout explanation = form();
        AlertDialog[] review = new AlertDialog[1];
        if (!monitoringIntent) {
            explanation.addView(presentation.action("→ Enable full monitoring…", "Set up rewards, GPU history and automatic IP checks for the hosts you save.", () -> { review[0].dismiss(); approveGpuRecording(hosts, requests); }));
            label(explanation, "Inspect + automatic IP keeps IP addresses current while this saved fleet is open. It uses sign-ins you chose to remember, reads PC interfaces and checks public IP with installed curl using ipify. Successful checks are cached for 30 minutes.");
            label(explanation, "Sign-ins not remembered are used once. Existing pauses and restrictions remain. Rewards, GPU setup and sudo need separate approval. Inspect only adds no automatic access.");
        }
        label(explanation, "Sign-ins for selected computers");
        for (int i = 0; i < hosts.size(); i++) {
            final int requestIndex = i; Trusted host = hosts.get(i);
            explanation.addView(presentation.action("Change credentials: " + display(host.candidate), "Username: " + requests.get(i).credential.username, () -> {
                review[0].dismiss(); newCredential(host, false, (value, ignored) -> {
                    try {
                        SshDiscovery.Credential replacement = decodeCredential(value); openCredentials.add(replacement);
                        SshDiscovery.BatchRequest old = requests.set(requestIndex, new SshDiscovery.BatchRequest(host.candidate, host.identity, replacement, null));
                        if (value.has("id")) credentialIds.put(host.candidate.endpointKey(), value.getString("id"));
                        else credentialIds.remove(host.candidate.endpointKey());
                        old.credential.close(); openCredentials.remove(old.credential);
                        reviewRequests(hosts, requests);
                    } catch (Exception error) { credentialError(); cancel(); }
                });
            }));
        }
        review[0] = presentation.show(presentation.builder("Review " + hosts.size() + " selected, trusted PCs")
            .setView(scroll(explanation)).setPositiveButton(monitoringIntent ? "Enable monitoring" : "Inspect + automatic IP", (d, w) -> {
                if (monitoringIntent) approveGpuRecording(hosts, requests); else collect(requests, false, true);
            })
            .setNeutralButton("Inspect only", (d, w) -> collect(requests, false))
            .setNegativeButton("Cancel", (d, w) -> cancel()).setOnCancelListener(d -> cancel()));
    }
    private void approveGpuRecording(List<Trusted> hosts, List<SshDiscovery.BatchRequest> requests) {
        LinearLayout form = form();
        label(form, monitoringIntent ? "Enable monitoring for these saved computers." : "After inspection, choose which Nosana hosts to save. New monitoring applies only to those hosts and their computers.");
        label(form, "Saved sign-in\nSelected SSH credentials are remembered encrypted on this phone. Connections run over your Wi-Fi or VPN while the fleet is open, without another network search.");
        label(form, "Rewards\nRead each verified node's claimable rewards using its existing identity inside its container. Private keys stay there. Authorization goes only to Nosana. The signature has broader authority, but this helper allows only the fixed rewards GET, never claims or transfers. Only public address, amount, time and status return. Reads run at most every five minutes and honor provider delays; denied authorization pauses that node.");
        label(form, "GPU history\nCreate labeled GridLens Docker recorders and named volumes using a pinned image. Recording continues while the phone is closed; readings refresh every 30 seconds while viewing. GridLens may repair its own recorder connection after a node restarts. Docker/NVIDIA support must already exist. No host installation, networking changes or workload restarts.");
        label(form, "IP addresses\nRead PC interfaces and check public IP with installed curl using ipify. No keys or credentials go to that service. Checks are shared across the PC's hosts and cached for 30 minutes.");
        label(form, "Changed identities or rejected credentials pause access. Missing readings stay unknown. Existing PCs that cannot be safely updated for only the selected hosts keep their monitoring settings; use Enable / repair monitoring separately.");
        presentation.show(presentation.builder("Enable monitoring on " + hosts.size() + " trusted PCs?")
            .setView(scroll(form))
            .setPositiveButton("Enable monitoring", (d, w) -> collect(requests, true))
            .setNegativeButton("Back", (d, w) -> reviewRequests(hosts, requests))
            .setOnCancelListener(d -> cancel()));
    }
    interface CredentialReady { void ready(JSONObject value, boolean shared); }
    interface ScopeReady { void ready(boolean shared); }
    private void credentialScope(Trusted host, JSONObject value, ScopeReady callback) {
        presentation.builder("Apply credential profile")
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
        EditText name, username, password, passphrase; CheckBox remember, shared; TextView keyLabel, validation;
        RadioButton keyMethod, passwordMethod; LinearLayout keyFields, passwordFields; byte[] key; AlertDialog dialog;
        boolean keyMode = true;
        void clear() { password.setText(""); passphrase.setText(""); if (key != null) Arrays.fill(key, (byte)0); key = null; if (keyForm == this) keyForm = null; }
    }
    private void newCredential(Trusted host, boolean allowShared, CredentialReady callback) {
        CredentialForm fields = new CredentialForm(); LinearLayout form = form();
        label(form, "Choose how you already sign in to these computers. Both methods need the computer's username.");
        fields.name = presentation.field(form, "Credential profile name", "Profile name", "SSH credential", false);
        fields.username = field(form, "Username", "", false);
        label(form, "The account you use to sign in to these computers.");
        RadioGroup methods = new RadioGroup(activity);
        fields.keyMethod = new RadioButton(activity); fields.keyMethod.setId(View.generateViewId());
        fields.keyMethod.setText("SSH key — recommended if already set up"); fields.keyMethod.setTag("credential-key-method"); methods.addView(fields.keyMethod);
        fields.passwordMethod = new RadioButton(activity); fields.passwordMethod.setId(View.generateViewId());
        fields.passwordMethod.setText("Password"); fields.passwordMethod.setTag("credential-password-method"); methods.addView(fields.passwordMethod); form.addView(methods);
        fields.keyFields = new LinearLayout(activity); fields.keyFields.setOrientation(LinearLayout.VERTICAL); fields.keyFields.setTag("credential-key-fields"); form.addView(fields.keyFields);
        label(fields.keyFields, "Choose the SSH private-key file already authorized for this username on these computers. Never choose a Nosana or Solana wallet key.");
        fields.keyLabel = label(fields.keyFields, "No SSH private key selected");
        android.widget.Button importKey = new android.widget.Button(activity); importKey.setText("Choose SSH key file"); fields.keyFields.addView(importKey);
        importKey.setOnClickListener(v -> {
            keyForm = fields;
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            try { activity.startActivityForResult(intent, IMPORT_KEY); }
            catch (android.content.ActivityNotFoundException error) { message("No document picker", "This phone has no document picker available."); }
        });
        fields.passphrase = field(fields.keyFields, "Key passphrase", "", true);
        label(fields.keyFields, "Only needed if this SSH key is protected by a passphrase.");
        fields.passwordFields = new LinearLayout(activity); fields.passwordFields.setOrientation(LinearLayout.VERTICAL); fields.passwordFields.setTag("credential-password-fields"); form.addView(fields.passwordFields);
        fields.password = field(fields.passwordFields, "SSH password", "", true);
        label(fields.passwordFields, "The password for this username on the selected computers.");
        fields.passwordFields.setVisibility(View.GONE); methods.check(fields.keyMethod.getId());
        methods.setOnCheckedChangeListener((group, id) -> {
            fields.keyMode = id == fields.keyMethod.getId();
            fields.password.setText(""); fields.passphrase.setText("");
            if (fields.key != null) Arrays.fill(fields.key, (byte)0);
            fields.key = null; if (keyForm == fields) keyForm = null;
            fields.keyLabel.setText("No SSH private key selected");
            fields.keyFields.setVisibility(fields.keyMode ? View.VISIBLE : View.GONE);
            fields.passwordFields.setVisibility(fields.keyMode ? View.GONE : View.VISIBLE);
            if (fields.validation != null) fields.validation.setText("");
        });
        fields.remember = check(form, "Remember encrypted credentials", true);
        fields.remember.setTag("credential-remember");
        label(form, "Save securely on this phone for future connections. Uncheck to use this sign-in only for this inspection.");
        fields.shared = check(form, "Use for remaining selected, trusted PCs", allowShared);
        if (!allowShared) fields.shared.setVisibility(View.GONE);
        fields.validation = label(form, "");
        fields.dialog = presentation.builder("SSH credentials").setView(scroll(form)).setPositiveButton("Continue", null)
            .setNegativeButton("Cancel", (d, w) -> cancel()).setOnCancelListener(d -> cancel()).create();
        fields.dialog.setOnDismissListener(d -> fields.clear()); fields.dialog.setOnCancelListener(d -> cancel());
        fields.dialog.setOnShowListener(d -> {
            fields.dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            fields.dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                try {
                    String username = fields.username.getText().toString().trim();
                    if (username.isEmpty() || username.length() > 64 || username.contains("\n") || (fields.name.getText().toString().trim().isEmpty() || fields.name.getText().length() > 80)) throw new IllegalArgumentException();
                    if (fields.keyMode ? fields.key == null : fields.password.getText().length() == 0) throw new IllegalArgumentException();
                    JSONObject value = new JSONObject().put("name", fields.name.getText().toString().trim()).put("username", username)
                        .put("password", fields.keyMode ? "" : fields.password.getText().toString()).put("passphrase", fields.keyMode ? fields.passphrase.getText().toString() : "")
                        .put("key", fields.keyMode ? android.util.Base64.encodeToString(fields.key, android.util.Base64.NO_WRAP) : "");
                    boolean shared = fields.shared.isChecked(), remember = fields.remember.isChecked();
                    int run = generation;
                    fields.dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                    fields.keyMethod.setEnabled(false); fields.passwordMethod.setEnabled(false); importKey.setEnabled(false);
                    fields.validation.setText(R.string.credential_validating);
                    io.execute(() -> {
                        try {
                            try (SshDiscovery.Credential validated = decodeCredential(value)) { SshDiscovery.validateCredential(validated); }
                            if (run != generation || destroyed) return;
                            if (remember) value.put("id", store.saveCredential(value, shared ? null : host.identity.keyBase64));
                            ui(run, () -> { if (fields.dialog.isShowing()) { fields.dialog.dismiss(); callback.ready(value, shared); } });
                        } catch (Exception error) { ui(run, () -> {
                            fields.dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                            fields.keyMethod.setEnabled(true); fields.passwordMethod.setEnabled(true); importKey.setEnabled(true);
                            fields.validation.setText(R.string.credential_validation_failed);
                        }); }
                    });
                } catch (Exception error) { message("Check credentials", "Enter a profile name, SSH username and password or SSH key. Credentials must be available in encrypted storage before they can be remembered."); }
            });
        }); fields.dialog.show(); presentation.style(fields.dialog);
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
                    if (destroyed || keyForm != target || !target.keyMode || !target.dialog.isShowing()) { Arrays.fill(imported, (byte)0); return; }
                    if (target.key != null) Arrays.fill(target.key, (byte)0); target.key = imported;
                    target.password.setText(""); target.keyLabel.setText(R.string.ssh_key_imported);
                });
            } catch (Exception error) { activity.runOnUiThread(() -> message("Could not import SSH key", "Select a supported PEM/OpenSSH RSA, ECDSA or Ed25519 SSH private-key file, no larger than 64 KB. Never select a wallet file.")); }
            finally { if (bytes != null) Arrays.fill(bytes, (byte)0); }
        }); return true;
    }
    private void collect(List<SshDiscovery.BatchRequest> selectedRequests, boolean enableGpuRecording) {
        collect(selectedRequests, enableGpuRecording, false);
    }
    private void collect(List<SshDiscovery.BatchRequest> selectedRequests, boolean enableGpuRecording, boolean automaticIp) {
        enableMonitoringForBatch = enableGpuRecording;
        enableAutomaticIpForBatch = automaticIp;
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
        MonitoringController.InspectionCredentialEvidence credentialEvidence;
        try { credentialEvidence = monitoring == null ? null : monitoring.captureInspectionCredentials(requests, new java.util.HashMap<>(credentialIds)); }
        catch (Exception failure) { cancel(); credentialError(); return; }
        working = true; int run = generation; busy("Identifying Nosana hosts", enableGpuRecording
                ? "Verifying exact node containers on the selected PCs before enabling approved monitoring…"
                : "Reading bounded container metadata and existing GPU recordings on selected, trusted computers…");
        java.util.Map<String, SshDiscovery.Result> results = java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>());
        List<SshDiscovery.BatchRequest> needsSudo = java.util.Collections.synchronizedList(new ArrayList<>());
        DiscoveryNetwork selectedNetwork = network;
        java.util.concurrent.atomic.AtomicBoolean denialStorageFailed = new java.util.concurrent.atomic.AtomicBoolean();
        io.execute(() -> {
            try {
                SshDiscovery.runBatch(selectedNetwork, requests, (candidate, result) -> {
                    results.put(candidate.endpointKey(), result);
                    try { if (monitoring != null) monitoring.recordInspectionFailure(credentialEvidence, candidate.endpointKey(), result.status); }
                    catch (Exception failure) { denialStorageFailed.set(true); selectedNetwork.cancel(); }
                    if (result.status == SshDiscovery.Status.CONTAINER_ACCESS_REQUIRED) {
                        for (SshDiscovery.BatchRequest request : requests) {
                            if (request.candidate == candidate) needsSudo.add(request);
                        }
                    }
                    ui(run, () -> progressText.setText(activity.getString(R.string.discovery_inspection_progress, results.size(), requests.size(), display(candidate), status(result.status))));
                });
                if (denialStorageFailed.get()) {
                    ui(run, () -> { cancel(); message("Sign-in protection unavailable", "GridLens could not save a rejected sign-in restriction. Automatic monitoring is paused until credential storage is recovered."); });
                    return;
                }
                ui(run, () -> { dismissProgress(); working = false;
                    boolean authFailure = results.values().stream().anyMatch(r -> r.status == SshDiscovery.Status.AUTH_FAILED);
                    if (authFailure) saveResults(results, requests.size()); else sudoNext(needsSudo, 0, results, requests.size(), credentialEvidence);
                });
            } catch (Exception error) { ui(run, () -> { dismissProgress(); working = false; message("Inspection stopped", enableGpuRecording
                    ? "The connection changed or inspection was cancelled. Read-only inspection did not change workloads or start new monitoring setup."
                    : "The connection changed or inspection was cancelled. Read-only discovery did not change workloads or host configuration."); cancel(); }); }
        });
    }
    private void sudoNext(List<SshDiscovery.BatchRequest> requests, int index, java.util.Map<String, SshDiscovery.Result> results, int expected,
            MonitoringController.InspectionCredentialEvidence credentialEvidence) {
        if (index >= requests.size()) { saveResults(results, expected); return; }
        SshDiscovery.BatchRequest request = requests.get(index); LinearLayout form = form();
        label(form, display(request.candidate) + (enableMonitoringForBatch
                ? " needs elevated container access. Approve the fixed inventory, in-container rewards GET and owned GPU recorder operations already selected. This password is remembered encrypted only for this approved PC, so monitoring can reuse it without repeated prompts. No interactive shell or workload changes are allowed."
                : " needs elevated container access. Approve only the fixed, read-only Docker/Podman inventory and existing owned GPU-recording reads. This does not grant an interactive shell, install software, read wallet files, restart workloads or change configuration."));
        EditText password = field(form, "Sudo password", "", true);
        label(form, "Only needed if this computer requires a password for elevated container access.");
        AlertDialog dialog = presentation.builder("Approve inventory with sudo?").setView(scroll(form))
            .setPositiveButton("Approve this PC", (d, w) -> {
                byte[] secret = password.getText().toString().getBytes(StandardCharsets.UTF_8); password.setText("");
                if (enableMonitoringForBatch) monitoringSudo.put(request.candidate.endpointKey(), secret.clone());
                int run = generation; working = true; busy("Reading approved inventory", display(request.candidate));
                DiscoveryNetwork selectedNetwork = network;
                io.execute(() -> {
                    try {
                        SshDiscovery.Result result = SshDiscovery.inventory(selectedNetwork, request.candidate, request.identity, request.credential, secret, request.collectorSource, request.enableGpuRecording);
                        if (monitoring != null) monitoring.recordInspectionFailure(credentialEvidence, request.candidate.endpointKey(), result.status);
                        SshDiscovery.Result original = results.get(request.candidate.endpointKey());
                        results.put(request.candidate.endpointKey(), InspectionResults.merge(original, result));
                        ui(run, () -> { dismissProgress(); working = false; if (result.status == SshDiscovery.Status.AUTH_FAILED) saveResults(results, expected); else sudoNext(requests, index + 1, results, expected, credentialEvidence); });
                    } catch (Exception failure) {
                        ui(run, () -> { cancel(); message("Sign-in protection unavailable", "GridLens could not save a rejected sign-in restriction. Automatic monitoring is paused until credential storage is recovered."); });
                    } finally { Arrays.fill(secret, (byte)0); }
                });
            }).setNeutralButton("Skip this PC", (d, w) -> sudoNext(requests, index + 1, results, expected, credentialEvidence))
            .setNegativeButton("Cancel", (d, w) -> cancel()).setOnCancelListener(d -> cancel()).create();
        dialog.setOnShowListener(d -> dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE));
        dialog.setOnDismissListener(d -> password.setText("")); dialog.show(); presentation.style(dialog);
    }
    private void saveResults(java.util.Map<String, SshDiscovery.Result> results, int expected) {
        java.util.Map<String, SshDiscovery.Result> complete = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(results));
        LinkedHashSet<String> found = new LinkedHashSet<>();
        for (SshDiscovery.Result result : complete.values()) found.addAll(result.addresses);
        // Explicit saved-PC recovery retains its existing zero-host merge semantics.
        // An empty user selection below can never enter that repair path.
        if (monitoringIntent || found.isEmpty()) { saveSelectedResults(complete, expected, null); return; }
        java.util.Map<String, PcMetadata> metadata = InspectionResults.metadataByAddress(complete.values());
        List<DiscoveryUi.Row> rows = new ArrayList<>();
        for (String address : found) {
            PcMetadata pc = metadata.get(address);
            String name = pc == null || pc.pcName == null ? "Computer name unavailable" : pc.pcName;
            String details = "Host: " + address + (pc == null || pc.ipAddresses.isEmpty() ? "" : "\nPC addresses: " + String.join(", ", pc.ipAddresses))
                    + (pc == null || pc.pcId == null ? "\nPC association is unknown; this host cannot authorize PC monitoring." : "");
            rows.add(new DiscoveryUi.Row(address, name, details, "hosts", true));
        }
        rows.sort(java.util.Comparator.comparing((DiscoveryUi.Row row) -> row.title).thenComparing(row -> row.id));
        int attention = 0;
        for (java.util.Map.Entry<String, SshDiscovery.Result> entry : complete.entrySet()) {
            SshDiscovery.Result result = entry.getValue();
            if (result.status != SshDiscovery.Status.SUCCESS || result.unresolved > 0) attention++;
            if (result.status != SshDiscovery.Status.SUCCESS || result.unresolved > 0 || result.addresses.isEmpty()) {
                rows.add(new DiscoveryUi.Row("inspection:" + entry.getKey(), entry.getKey(), inspectionStatus(result)
                        + (result.unresolved == 0 ? "" : "\n" + result.unresolved + " unresolved Nosana observations"), "details", false));
            }
        }
        int run = generation;
        selectionDialog = presentation.select("Choose Nosana hosts to save", found.size() + " verified Nosana hosts found. "
                + attention + " computers need attention; use Inspection details to review incomplete checks. "
                + (fleetId == null ? "Select the hosts to save." : "Select hosts to add or update. Existing saved hosts are kept, including those you leave unselected."),
                rows, Arrays.asList(new DiscoveryUi.Filter("Verified Nosana hosts", row -> row.selectable),
                        new DiscoveryUi.Filter("Inspection details", row -> !row.selectable), new DiscoveryUi.Filter("All results", row -> true)),
                java.util.Collections.emptySet(), Integer.MAX_VALUE, fleetId == null ? "Save selected hosts" : "Add / update selected hosts", ids -> {
                    if (run != generation || destroyed || ids.isEmpty()) return;
                    try { network.ensureActive(); saveSelectedResults(complete, expected, InspectionSelection.from(complete, ids)); }
                    catch (Exception error) { cancel(); storageError(); }
                }, this::cancel);
    }
    private static String inspectionStatus(SshDiscovery.Result result) {
        switch (result.status) {
            case AUTH_FAILED: return "Sign-in rejected — correct the saved sign-in before retrying";
            case CANCELLED: return "Not checked — inspection was stopped";
            case IDENTITY_CHANGED: return "SSH identity changed — blocked";
            case CONTAINER_ACCESS_REQUIRED: return "Container access needs approval — Nosana inventory may be incomplete";
            case UNREACHABLE: return "Could not connect — this does not prove the PC is offline";
            case SUCCESS: return result.addresses.isEmpty() ? result.unresolved > 0 ? "Nosana observations could not be verified" : "No running Nosana hosts found in the inspected inventory" : "Verified " + result.addresses.size() + " Nosana hosts";
            default: return status(result.status) + " — inspection incomplete";
        }
    }
    private void saveSelectedResults(java.util.Map<String, SshDiscovery.Result> results, int expected, InspectionSelection selection) {
        LinkedHashSet<String> addresses = new LinkedHashSet<>(); int unresolved = 0, inaccessible = 0; boolean authFailed = false;
        for (SshDiscovery.Result result : results.values()) {
            addresses.addAll(result.addresses); unresolved += result.unresolved;
            if (result.status != SshDiscovery.Status.SUCCESS) inaccessible++;
            if (result.status == SshDiscovery.Status.AUTH_FAILED) authFailed = true;
        }
        int foundCount = addresses.size();
        if (selection != null) { addresses.clear(); addresses.addAll(selection.addresses); }
        StringBuilder pcDetails = new StringBuilder();
        for (java.util.Map.Entry<String, SshDiscovery.Result> result : results.entrySet())
            pcDetails.append("\n").append(result.getKey()).append(": ").append(inspectionStatus(result.getValue()))
                .append("; ").append(result.getValue().addresses.size()).append(" public addresses, ").append(result.getValue().unresolved).append(" unresolved observations")
                .append(result.getValue().gpuCollectorStatus == null ? "" : "; " + result.getValue().gpuCollectorStatus.label);
        java.util.Map<String, PcMetadata> pcMetadata = selection == null ? InspectionResults.metadataByAddress(results.values()) : selection.metadata;
        long pcConflicts = pcMetadata.values().stream().filter(pc -> pc.pcId == null).count();
        String summary = addresses.size() + " of " + foundCount + " verified Nosana hosts selected. " + unresolved + " unresolved Nosana observations. " + inaccessible + " incomplete or unattempted PC inspections."
            + (authFailed ? " The credential batch stopped after an authentication failure; remaining PCs were not tried." : "")
            + (results.size() < expected ? " Some selected PCs were not inspected." : "")
            + (unresolved > 0 ? " Normal and elevated inventory observations may refer to the same unresolved instance." : "")
            + (pcConflicts > 0 ? " " + pcConflicts + " host addresses appeared on conflicting PC identities; their PC name and IP addresses remain unknown." : "") + pcDetails;
        if (addresses.isEmpty() && (selection != null || !allowsEmptyMonitoringMerge(fleetId, enableMonitoringForBatch || enableAutomaticIpForBatch, lastRequests, results))) {
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
        boolean automaticIpApproved = enableAutomaticIpForBatch, fullMonitoringApproved = enableMonitoringForBatch;
        List<SshDiscovery.BatchRequest> approvedRequests = new ArrayList<>(lastRequests);
        java.util.Map<String, String> approvedCredentialIds = new java.util.HashMap<>(credentialIds);
        String approvedProfileId = profile.optString("id");
        DiscoveryNetwork approvedNetwork = network;
        final int selectedCount = addresses.size(), issues = inaccessible, unresolvedCount = unresolved;
        int selectedPcs = (int) pcMetadata.values().stream().filter(pc -> pc.pcId != null).map(pc -> pc.pcId).distinct().count();
        working = true; busy("Saving discovered fleet", "Saving " + selectedCount + " selected hosts…");
        io.execute(() -> {
            try {
                JSONObject response = api(operation);
                String id = response.getString("fleetId");
                List<JSONObject> setup;
                String setupFailure = null;
                String automaticIpSummary = null;
                String automaticIpDetails = null;
                String monitoringDeferred = null;
                String monitoringDetails = null;
                try {
                    if (automaticIpApproved && monitoring != null) {
                        setup = new ArrayList<>();
                        MonitoringController.PcIpEnrollmentReport enrollment = monitoring.registerAutomaticPcIp(id, approvedProfileId, approvedRequests,
                                results, approvedCredentialIds, () -> run == generation && foreground && !destroyed && enableAutomaticIpForBatch
                                        && approvedNetwork != null && !approvedNetwork.isCancelled(), selection);
                        automaticIpSummary = "Automatic IP: " + enrollment.enabled + " computers newly approved while this fleet is open."
                                + (enrollment.unchanged == 0 ? "" : "\n" + enrollment.unchanged + " existing approvals kept.")
                                + (enrollment.excluded.isEmpty() ? "" : "\n" + enrollment.excluded.size() + " computers excluded — view inspection details.")
                                + "\nNo new rewards or GPU permissions were added.";
                        automaticIpDetails = enrollment.summary();
                    } else if (monitoring == null || !fullMonitoringApproved) setup = new ArrayList<>();
                    else {
                        MonitoringController.MonitoringRegistrationReport registration = monitoring.register(id, approvedProfileId, approvedRequests,
                                results, approvedCredentialIds, monitoringSudo, true, selection,
                                () -> run == generation && foreground && !destroyed && enableMonitoringForBatch
                                        && approvedNetwork != null && !approvedNetwork.isCancelled());
                        setup = registration.targets;
                        monitoringDeferred = registration.summary();
                        StringBuilder deferredDetails = new StringBuilder();
                        for (MonitoringController.RegistrationDeferral deferred : registration.deferred)
                            deferredDetails.append("\n").append(deferred.endpoint).append(": ").append(deferred.reason);
                        monitoringDetails = deferredDetails.toString();
                    }
                }
                catch (Exception error) { setup = new ArrayList<>(); setupFailure = automaticIpApproved
                        ? "The public fleet was saved, but automatic IP approval could not finish. Previously saved approvals remain. Reopen native discovery to review the selected PCs again."
                        : "The public fleet was saved, but monitoring setup could not be saved. Each PC supports at most 64 exact node bindings and needs remembered SSH credentials. Open Fleets → Enable / repair monitoring to retry."; }
                List<JSONObject> setupTargets = setup;
                String setupIssue = setupFailure;
                String ipSummary = automaticIpSummary;
                String deferredSummary = monitoringDeferred;
                String enrollmentDetails = automaticIpDetails, registrationDetails = monitoringDetails;
                boolean enabled = fullMonitoringApproved;
                long pcOnly = setupTargets.stream().filter(target -> "binding-changed".equals(target.optString("status"))
                        && !target.optBoolean("setupPending")).count();
                ui(run, () -> {
                    dismissProgress(); working = false; cancel(); navigation.open("fleet:" + id);
                    if (monitoring != null && enabled) monitoring.startSetup(setupTargets);
                    String monitoringStatus = setupIssue != null ? setupIssue : ipSummary != null ? ipSummary : enabled ? setupTargets.isEmpty()
                            ? "No new monitoring setup was started. Existing monitoring settings remain."
                            : "Automatic IP: approved for " + setupTargets.size() + " computers while this fleet is open.\nFull monitoring: setup in progress; rewards and GPU access are not yet confirmed."
                                + (pcOnly > 0 ? "\n" + pcOnly + " computers have unverified node bindings; only PC IP access was approved for them." : "")
                            : "No new automatic access was approved. Existing monitoring settings remain. Public Fleet readings need no SSH.";
                    if (deferredSummary != null && !deferredSummary.isEmpty()) monitoringStatus += "\n" + deferredSummary;
                    String completion = selectedCount + " Nosana hosts " + (savedFleet == null ? "saved" : "added / updated") + " from " + selectedPcs + " verified computers."
                            + (pcConflicts > 0 ? "\n" + pcConflicts + " selected hosts have conflicting PC associations." : "")
                            + "\n\n" + monitoringStatus
                            + (issues == 0 && unresolvedCount == 0 ? "" : "\n\n" + issues + " computer inspections incomplete or unattempted; " + unresolvedCount + " unresolved Nosana observations. View inspection details.");
                    presentation.result("Fleet saved", completion, summary
                            + (enrollmentDetails == null ? "" : "\n\n" + enrollmentDetails)
                            + (registrationDetails == null ? "" : "\n\n" + registrationDetails), "View fleet", null);
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
                presentation.builder("Saved fleets").setItems(labels, (d, w) -> fleetActions(fleets.optJSONObject(w))).setNegativeButton("Close", null).show();
            }); } catch (Exception error) { activity.runOnUiThread(this::storageError); }
        });
    }
    private void fleetActions(JSONObject fleet) {
        String id = fleet.optString("id");
        presentation.builder(fleet.optString("name"))
            .setItems(new String[]{"Open fleet", "Check saved connections", "Enable / repair monitoring", "Monitoring status", "Rescan and merge", "Rename", "Remove selected hosts", "Delete fleet"}, (d, which) -> {
                if (which == 0) mutate("select", null, "scenario", "fleet:" + id, true);
                else if (which == 1) checkSavedConnections(id);
                else if (which == 2) enableMonitoring(id);
                else if (which == 3) monitoringStatus(id);
                else if (which == 4) { fleetId = id; prepare(); }
                else if (which == 5) { LinearLayout form = form(); EditText name = field(form, "Fleet name", fleet.optString("name"), false);
                    presentation.builder("Rename fleet").setView(form).setPositiveButton("Save", (dd, w) -> mutate("rename", id, "name", name.getText().toString(), false)).setNegativeButton("Cancel", null).show();
                } else if (which == 6) removeHosts(fleet);
                else presentation.builder("Delete saved fleet?").setMessage("This removes only this public-address fleet. Credentials and network profiles remain.")
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
                presentation.builder("Saved SSH connections").setItems(labels, (dialog, which) -> selectKnownMonitoring(groups.get(which)))
                        .setNegativeButton("Cancel", (dialog, which) -> cancel()).setOnCancelListener(dialog -> cancel()).show();
            }
        } catch (Exception error) { message("Monitoring setup unavailable", "Connect to the Wi-Fi or VPN that reaches your saved PCs. If saved credentials are unavailable, use Fleets to enter them again. Public Fleet data remains available."); cancel(); }
    }
    private void selectKnownMonitoring(List<JSONObject> endpoints) {
        try {
            profile = new JSONObject().put("id", endpoints.get(0).getString("profileId")).put("name", endpoints.get(0).getString("profileName"));
            String[] labels = new String[endpoints.size()]; boolean[] checked = new boolean[endpoints.size()];
            for (int i = 0; i < endpoints.size(); i++) { labels[i] = endpoints.get(i).getString("address") + ":" + endpoints.get(i).getInt("port"); checked[i] = true; }
            presentation.builder("Enable monitoring on saved PCs")
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
    /** Fixed native action for one saved PC. It never enters node discovery/setup. */
    void checkPcIp(String id, String pcId) {
        if (working) { if (progress != null) progress.show(); return; }
        if (store == null || monitoring == null || !PcIpRepair.validScope(id, pcId)) { storageError(); return; }
        cancel(); monitoring.pause(); directPcChecking = true; working = true; directPcFleet = id;
        int run = generation;
        PcIpRepair repair = new PcIpRepair(activity, store, monitoring, origin, token);
        busy("Checking saved PC", "Reading the saved PC association, trusted SSH connection and IP policy.");
        io.execute(() -> {
            try {
                PcIpRepair.Plan plan = repair.inspect(id, pcId);
                ui(run, () -> { dismissProgress(); choosePcIpEndpoint(run, repair, plan); });
            } catch (PcIpRepair.Failure failure) { ui(run, () -> finishPcIpFailure(failure.status)); }
            catch (Exception failure) { ui(run, () -> finishPcIpFailure("saved-connection-unavailable")); }
        });
    }
    private void choosePcIpEndpoint(int run, PcIpRepair repair, PcIpRepair.Plan plan) {
        if (!currentPcIp(run)) return;
        if (plan.endpoints.size() == 1) { choosePcIpCredential(run, repair, plan, plan.endpoints.get(0)); return; }
        String[] labels = new String[plan.endpoints.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = plan.endpoints.get(i).label();
        showPcIpDialog(presentation.builder("Saved connection for " + plan.pcName)
                .setItems(labels, (dialog, index) -> choosePcIpCredential(run, repair, plan, plan.endpoints.get(index)))
                .setNegativeButton("Cancel", (dialog, which) -> cancel()).setOnCancelListener(dialog -> cancel()));
    }
    private void choosePcIpCredential(int run, PcIpRepair repair, PcIpRepair.Plan plan, PcIpRepair.Endpoint endpoint) {
        if (!currentPcIp(run)) return;
        if (endpoint.credentialId != null) { confirmPcIp(run, repair, plan, endpoint, endpoint.credentialId); return; }
        try {
            JSONArray profiles = store.credentialProfiles(); String[] choices = new String[profiles.length() + 1]; choices[0] = "Enter / import SSH credentials";
            for (int i = 0; i < profiles.length(); i++) choices[i + 1] = profiles.getJSONObject(i).getString("name") + " (" + profiles.getJSONObject(i).getString("username") + ")";
            showPcIpDialog(presentation.builder("SSH credentials for " + plan.pcName).setItems(choices, (dialog, index) -> {
                if (!currentPcIp(run)) return;
                if (index > 0) {
                    try { confirmPcIp(run, repair, plan, endpoint, profiles.getJSONObject(index - 1).getString("id")); }
                    catch (Exception unavailable) { finishPcIpFailure("credentials-required"); }
                } else {
                    try {
                        Trusted trusted = new Trusted(new DiscoveryPolicy.Candidate(endpoint.endpoint.getString("address"), endpoint.endpoint.getInt("port"), ""),
                                new SshDiscovery.Identity(endpoint.endpoint.getString("keyBase64"), endpoint.endpoint.getString("algorithm")));
                        // No connection is active while Android's protected credential picker is open.
                        directPcChecking = false; directPcCredentialEntry = true; working = false;
                        newCredential(trusted, false, (value, shared) -> {
                            directPcCredentialEntry = false;
                            if (destroyed || !foreground) { cancel(); return; }
                            if (!value.has("id")) { finishPcIpFailure("remembered-credentials-required"); return; }
                            monitoring.pause(); directPcChecking = true; working = true; directPcFleet = plan.fleetId;
                            confirmPcIp(generation, repair, plan, endpoint, value.optString("id"));
                        });
                    } catch (Exception unavailable) { finishPcIpFailure("credentials-required"); }
                }
            }).setNegativeButton("Cancel", (dialog, which) -> cancel()).setOnCancelListener(dialog -> cancel()));
        } catch (Exception unavailable) { finishPcIpFailure("credentials-required"); }
    }
    private void confirmPcIp(int run, PcIpRepair repair, PcIpRepair.Plan plan, PcIpRepair.Endpoint endpoint, String credentialId) {
        if (!currentPcIp(run)) return;
        boolean approval = endpoint.needsApproval || !java.util.Objects.equals(endpoint.credentialId, credentialId);
        if (!approval) { runPcIp(run, repair, plan, endpoint, credentialId, false); return; }
        boolean oneTime = PcIpRepair.oneTimeConnection(endpoint, credentialId);
        showPcIpDialog(presentation.builder(oneTime ? "Check this saved connection once?" : "Allow IP checks for " + plan.pcName + "?")
                .setMessage("Verify the saved SSH identity at " + endpoint.label() + " using the selected encrypted credential. Then read this PC's interfaces and public IP with installed curl.\n\n"
                        + (oneTime ? "This is one IP check through the selected connection. It will not replace the saved automatic connection or grant node permissions. "
                            : "This restores IP checks for this PC only while its saved fleet is open. Node monitoring stays paused. ")
                        + "No Docker, rewards, GPU, sudo or network-configuration commands run. Existing provider waits are honored.\n\n"
                        + "A changed SSH identity stays blocked. A real rejection of shared SSH credentials pauses the affected connections. Cancel preserves the current permissions.")
                .setPositiveButton(oneTime ? "Verify and check IP" : "Verify and allow IP", (dialog, which) -> runPcIp(run, repair, plan, endpoint, credentialId, true))
                .setNegativeButton("Cancel", (dialog, which) -> cancel()).setOnCancelListener(dialog -> cancel()));
    }
    private boolean currentPcIp(int run) { return run == generation && !destroyed && foreground && directPcChecking; }
    private void showPcIpDialog(AlertDialog.Builder builder) {
        if (directPcDialog != null) directPcDialog.dismiss();
        AlertDialog dialog = builder.create(); directPcDialog = dialog;
        dialog.setOnDismissListener(ignored -> { if (directPcDialog == dialog) directPcDialog = null; });
        dialog.show();
    }
    private void runPcIp(int run, PcIpRepair repair, PcIpRepair.Plan plan, PcIpRepair.Endpoint endpoint, String credentialId, boolean approval) {
        if (!currentPcIp(run)) return;
        busy("Checking " + plan.pcName + " IP", "Checking the saved PC connection and IP cache. Node containers are not queried.");
        monitoring.enqueueDirectPcIp(() -> {
            DiscoveryNetwork captured = null;
            try {
                if (run != generation || destroyed || !foreground) return;
                captured = DiscoveryNetwork.capture(activity); network = captured;
                if (run != generation || destroyed || !foreground) { captured.cancel(); return; }
                PcIpRepair.Result result = repair.execute(plan, endpoint, credentialId, approval, captured,
                        () -> run == generation && !destroyed && foreground && directPcChecking);
                ui(run, () -> {
                    dismissProgress(); working = false; directPcChecking = false; cancel();
                    if (result.delivered) navigation.open("fleet:" + plan.fleetId);
                    message("IP check: " + plan.pcName, pcIpResult(result) + (PcIpRepair.oneTimeConnection(endpoint, credentialId)
                            ? "\nThis check did not replace the saved automatic monitoring connection or grant node permissions." : ""));
                });
            } catch (Exception failure) { ui(run, () -> finishPcIpFailure("network-unavailable")); }
            finally { if (captured != null) { captured.close(); if (network == captured) network = null; } }
        });
    }
    private void finishPcIpFailure(String status) {
        dismissProgress(); working = false; directPcChecking = false; cancel();
        message("PC IP check", pcIpFailure(status));
    }
    private static String pcIpResult(PcIpRepair.Result result) {
        PublicIpReader.Observation value = result.observation;
        String text;
        if ("measured".equals(result.status)) text = "Public IP: " + value.publicIp + ". The result was delivered to this PC's saved host rows.";
        else if ("cached".equals(result.status)) text = "Cached public IP: " + value.publicIp + (result.measured
                ? ". A public lookup completed, but the dashboard retained its newer existing reading."
                : ". The original reading was delivered without another public lookup.");
        else if ("lookup-failed".equals(result.status)) text = (result.connectionVerified
                ? "The PC connection was verified, but the public IP lookup failed: " : "The previously saved IP attempt failed: ")
                + pcIpLookupFailure(value.publicFailure) + (value.httpStatus == null ? "." : " (HTTP " + value.httpStatus + ").")
                + (result.measured ? "" : " No new public lookup was made.");
        else text = pcIpFailure(result.status);
        if (result.connectionVerified) text += "\nSaved SSH identity and login verified in this check.";
        if (value != null) {
            if (value.publicObservedAtMs != null) text += "\nPublic IP last measured: " + java.time.Instant.ofEpochMilli(value.publicObservedAtMs) + ".";
            else text += "\nOriginal IP attempt: " + java.time.Instant.ofEpochMilli(value.observedAtMs) + ".";
            if (value.privateIp != null) text += "\nPrivate IP: " + value.privateIp + ".";
        }
        if (result.retryAtMs > System.currentTimeMillis()) text += "\nNext eligible IP attempt: " + java.time.Instant.ofEpochMilli(result.retryAtMs) + ". This is not a recovery forecast.";
        return text;
    }
    private static String pcIpLookupFailure(String failure) {
        if (failure == null) return "no public address was returned";
        switch (failure) {
            case "missing-curl": return "curl is not installed on this PC";
            case "dns": return "DNS lookup failed";
            case "connection": return "connection failed";
            case "timeout": return "request timed out";
            case "tls": return "TLS connection or verification failed";
            case "http-refusal": return "the provider refused the request";
            default: return "the provider returned an invalid response";
        }
    }
    private static String pcIpFailure(String status) {
        switch (status) {
            case "confirmation-required": return "IP checks need native approval for this PC. No connection was started.";
            case "unknown-pc": case "invalid-scope": return "This PC is no longer associated with the selected saved fleet. No connection was started.";
            case "saved-endpoint-missing": return "No saved trusted SSH endpoint matches this PC. Its identity must be verified through native discovery before IP checks can run.";
            case "credentials-required": return "The saved SSH credential is missing or was rejected. Select or enter the correct SSH credential for this PC; rejected shared credentials remain paused.";
            case "remembered-credentials-required": return "IP repair needs a remembered SSH connection. Save the credential with Remember encrypted credentials selected; no credential was saved by this check.";
            case "identity-changed": return "The PC did not present its saved SSH identity. The check stopped; review this PC's SSH identity in Fleets before changing trust.";
            case "provider-wait": return "The public IP provider requested a wait. No public lookup was repeated.";
            case "retry-wait": return "A previous IP attempt is still within its retry allowance. No public lookup was repeated.";
            case "delivery-wait": return "An IP observation is saved on this phone and waiting for its local delivery retry. No public lookup was repeated.";
            case "cancelled": return "The IP check was cancelled. Existing observations and permissions were preserved.";
            case "connection-changed": return "Saved connection or permission changed during this check. No new approval was granted; open the IP check again.";
            case "network-unavailable": case "unreachable": return "This phone could not connect to the saved PC over the current Wi-Fi or VPN. This does not establish that the PC is offline.";
            case "clock-untrusted": return "The saved observation is ahead of the phone clock. Its original time and retry allowance were preserved.";
            case "permission-storage-failed": return "The phone could not save a required access restriction. Monitoring remains stopped until native storage is repaired.";
            case "saved-fleet-unavailable": return "The phone could not confirm this PC's current saved-fleet association. Existing readings were preserved.";
            case "local-delivery-rejected": return "The dashboard rejected local IP delivery. The observation remains cached with its original time.";
            case "delivery-unconfirmed": return "The IP was saved on this phone, but the dashboard did not return the matching reading. Its original observation is retained for delivery retry.";
            default: return "The phone could not complete saved-connection access or local IP delivery. Existing observations were preserved; no raw command output or credentials are exposed.";
        }
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
            presentation.builder("Monitoring status").setMessage(text.toString())
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
        presentation.builder("Explicitly remove hosts").setMultiChoiceItems(labels, selected, (d, i, value) -> selected[i] = value)
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
        presentation.builder("Forget all SSH credentials?")
            .setMessage("Delete encrypted SSH passwords, private keys, passphrases and per-PC credential overrides. Your saved fleets, network profiles and trusted SSH identities remain.")
            .setPositiveButton("Forget credentials", (d, w) -> { try { if (monitoring != null) monitoring.pause(); store.forgetCredentials(); Toast.makeText(activity, "Credentials and monitoring permissions forgotten. Saved fleets remain.", Toast.LENGTH_LONG).show(); } catch (Exception error) { credentialError(); } })
            .setNegativeButton("Cancel", null).show();
    }
    void selectedScenario(String scenario) {
        if (directPcChecking && !("fleet:" + directPcFleet).equals(scenario)) cancel();
        if (monitoring != null) monitoring.selectedScenario(scenario);
    }
    void resumed() { foreground = true; if (monitoring != null && !working && network == null && !directPcCredentialEntry) monitoring.resume(); }
    void paused() { foreground = false; if (monitoring != null) monitoring.pause(); if (recoveringConnections || directPcChecking || enableAutomaticIpForBatch || enableMonitoringForBatch) cancel(); }
    void stopped() { paused(); if (working) cancel(); if (store != null) store.lock(); }
    void destroy() { destroyed = true; if (monitoring != null) monitoring.destroy(); cancel(); if (keyForm != null) keyForm.clear(); io.shutdownNow(); if (store != null) store.lock(); }
    private void cancel() {
        generation++; working = false; recoveringConnections = false; directPcChecking = false; directPcCredentialEntry = false; directPcFleet = null;
        if (directPcDialog != null) { directPcDialog.dismiss(); directPcDialog = null; }
        if (selectionDialog != null) { selectionDialog.dismiss(); selectionDialog = null; }
        if (network != null) { network.cancel(); try { network.close(); } catch (Exception ignored) {} network = null; }
        dismissProgress(); for (SshDiscovery.Credential credential : openCredentials) credential.close(); openCredentials.clear(); if (store != null) store.lock();
        credentialIds.clear(); lastRequests = new ArrayList<>();
        enableAutomaticIpForBatch = false; enableMonitoringForBatch = false;
        for (byte[] password : monitoringSudo.values()) Arrays.fill(password, (byte) 0);
        monitoringSudo.clear();
        if (!destroyed && foreground && monitoring != null) monitoring.resume();
    }
    private void ui(int run, Runnable action) { activity.runOnUiThread(() -> { if (!destroyed && run == generation) action.run(); }); }
    private void busy(String title, String text) {
        dismissProgress(); LinearLayout form = form(); progressText = label(form, text);
        progress = presentation.builder(title).setView(form).setNegativeButton("Cancel", (d, w) -> cancel()).setOnCancelListener(d -> cancel()).show();
    }
    private void dismissProgress() { if (progress != null) { progress.dismiss(); progress = null; } }
    private LinearLayout form() { return presentation.form(); }
    private ScrollView scroll(LinearLayout form) { ScrollView scroll = new ScrollView(activity); scroll.addView(form); return scroll; }
    private TextView label(LinearLayout form, String text) { return presentation.label(form, text); }
    private EditText field(LinearLayout form, String hint, String text, boolean secret) {
        return presentation.field(form, hint, hint, text, secret);
    }
    private CheckBox check(LinearLayout form, String text, boolean checked) { CheckBox value = new CheckBox(activity); value.setText(text); value.setChecked(checked); form.addView(value); return value; }
    private void message(String title, String text) { if (!destroyed) presentation.show(presentation.builder(title).setMessage(text).setPositiveButton("OK", null)); }
    private void storageError() { message("Saved data unavailable", "GridLens could not confirm this operation. Check saved records before retrying. A native discovery-storage error does not delete your public fleets."); }
    private void credentialError() { message("Saved credentials unavailable", "Android could not decrypt saved credentials. If the Keystore key was invalidated, use Forget credentials and enter them again. Saved fleets remain available."); }
    private static String display(DiscoveryPolicy.Candidate candidate) { return (candidate.name == null || candidate.name.isEmpty() ? "" : candidate.name + " — ") + candidate.address + ":" + candidate.port; }
    private static String status(SshDiscovery.Status status) { return status.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' '); }
    private static String join(JSONArray values, String separator) { List<String> strings = new ArrayList<>(); if (values != null) for (int i = 0; i < values.length(); i++) strings.add(values.optString(i)); return String.join(separator, strings); }
    private static List<String> strings(JSONArray values) throws Exception { List<String> result = new ArrayList<>(); for (int i = 0; i < values.length(); i++) result.add(values.getString(i)); return result; }
}
