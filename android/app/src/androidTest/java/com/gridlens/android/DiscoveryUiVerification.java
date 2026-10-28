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
            verifySearchInput(driver, screen);
            verifySelectionFooterWithKeyboard(driver, screen);
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
            result.putString("stream", "PASS (discovery UI): physical Search tap opens the Android keyboard, injected typing filters eight computers, selection survives keyboard dismissal and clearing search, and all eight rows are reachable by touch scrolling at 360dp; "
                    + "both selection actions fully visible at 320dp with 1.6× text and the keyboard open/closed; "
                    + "200 virtualized candidates, search/filter preservation and explicit selection of 50 hosts; "
                    + "512-PC limit, blocked identity rows, empty-profile and empty-credential shortcuts, key/password visibility and secret clearing, "
                    + "cancelled document picker, secure credential dialog, compact 320dp layout with enlarged text, and expandable Fleet saved details. "
                    + "No network discovery, SSH connection, operator roster, persistent credential, or monitoring grant. Physical-device acceptance remains separate.\n");
            outcome = Activity.RESULT_OK;
        } catch (Exception | AssertionError error) {
            java.io.StringWriter trace = new java.io.StringWriter();
            error.printStackTrace(new java.io.PrintWriter(trace));
            result.putString("stream", "FAIL (discovery UI): " + error.getClass().getSimpleName() + ": " + error.getMessage() + "\n" + trace);
        } finally {
            if (activity != null) {
                MainActivity closing = activity;
                ui(driver, closing::finish);
            }
        }
        driver.finish(outcome, result);
    }

    /** Exercise Android input dispatch, not only programmatic EditText.setText. */
    private static void verifySearchInput(Instrumentation driver, Activity activity) throws Exception {
        require(android.os.Build.VERSION.SDK_INT >= 30, "Discovery input fixture requires Android 11+ to observe actual IME visibility");
        List<DiscoveryUi.Row> rows = new ArrayList<>();
        for (int i = 0; i < 8; i++) rows.add(new DiscoveryUi.Row("input-" + i, "192.0.2." + (101 + i) + ":22",
                "SSH identity not yet saved in this network profile. Approve this computer's identity before signing in.", "new", true));
        AtomicReference<Set<String>> chosen = new AtomicReference<>();
        AtomicReference<AlertDialog> shown = new AtomicReference<>();
        ui(driver, () -> {
            AlertDialog dialog = new DiscoveryUi(activity).select("Select computers to inspect",
                    "These computers answered SSH. Sign-in and inspection are needed to confirm Nosana. Select the computers you want to check.",
                    rows, Arrays.asList(new DiscoveryUi.Filter("All computers", row -> true),
                            new DiscoveryUi.Filter("Identity not saved", row -> "new".equals(row.group))),
                    Collections.emptySet(), 512, "Review SSH identities", chosen::set, () -> {});
            dialog.getWindow().setLayout(Math.round(360 * activity.getResources().getDisplayMetrics().density), WindowManager.LayoutParams.WRAP_CONTENT);
            shown.set(dialog);
        });
        driver.waitForIdleSync();
        AlertDialog dialog = shown.get();
        try {
            awaitStableInputLayout(driver, dialog);
            InputState before = inputState(driver, activity, dialog);
            require(before.matches == 8, "Input fixture did not contain all eight computers");
            Set<String> initiallyVisible = visibleInputRows(driver, dialog);
            Set<String> initiallyFullyVisible = fullyVisibleInputRows(driver, dialog);
            status(driver, "DISCOVERY_INPUT_BEFORE: flags=0x" + Integer.toHexString(before.flags)
                    + ", matchingRows=" + before.matches + ", visibleRows=" + initiallyVisible.size()
                    + ", fullyVisibleRows=" + initiallyFullyVisible.size());
            screenshot(driver, activity, "discovery-input-initial.png");
            tapTag(driver, dialog, "discovery-search");
            InputState opened = awaitInput(driver, activity, dialog, state -> state.imeVisible, 5000);
            require(opened.imeVisible, "Physical Search tap did not open the Android keyboard; beforeFlags=0x"
                    + Integer.toHexString(before.flags) + ", afterFlags=0x" + Integer.toHexString(opened.flags)
                    + ", focused=" + opened.focused + ", inputMethodActive=" + opened.inputMethodActive);
            require((before.flags & WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM) == 0
                    && (opened.flags & WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM) == 0,
                    "Selection dialog excludes its Search field from IME input");
            require(opened.focused && opened.inputMethodActive, "Visible keyboard is not connected to the tapped Search field");
            require(initiallyFullyVisible.size() >= 3,
                    "The 360dp selection viewport initially shows fewer than three complete computer rows; fullyVisibleRows=" + initiallyFullyVisible.size());
            require(before.summary.startsWith("0 selected · 8 results"), "Footer does not distinguish all matching results from the visible viewport");
            awaitStableInputLayout(driver, dialog);
            screenshot(driver, activity, "discovery-input-keyboard.png");

            String query = "192.0.2.103";
            status(driver, "DISCOVERY_INPUT_ACTION: type query with keyboard open");
            typeKeys(driver, query);
            InputState filtered = awaitInput(driver, activity, dialog, state -> query.equals(state.text) && state.matches == 1, 5000);
            require(query.equals(filtered.text) && filtered.matches == 1 && filtered.imeVisible,
                    "Typing with the keyboard open did not filter to the matching computer; input=" + filtered.text + ", matches=" + filtered.matches);
            revealTagBySwiping(driver, dialog, "discovery-check:input-2", true);
            tapTag(driver, dialog, "discovery-check:input-2");
            driver.waitForIdleSync();
            require(inputState(driver, activity, dialog).summary.startsWith("1 selected"), "Tapping the filtered computer did not select it");
            dismissKeyboard(driver, activity, dialog);
            require(dialog.isShowing() && inputState(driver, activity, dialog).summary.startsWith("1 selected"),
                    "Dismissing the keyboard lost the dialog or selected computer");

            revealTagBySwiping(driver, dialog, "discovery-search", false);
            tapTag(driver, dialog, "discovery-search");
            InputState reopened = awaitInput(driver, activity, dialog, state -> state.imeVisible, 5000);
            require(reopened.imeVisible, "Search keyboard could not be reopened after selecting a computer");
            awaitStableInputLayout(driver, dialog);
            status(driver, "DISCOVERY_INPUT_ACTION: clear query with keyboard open");
            key(driver, android.view.KeyEvent.KEYCODE_MOVE_END);
            for (int i = 0; i < query.length(); i++) key(driver, android.view.KeyEvent.KEYCODE_DEL);
            InputState cleared = awaitInput(driver, activity, dialog, state -> state.text.isEmpty() && state.matches == 8, 5000);
            require(cleared.text.isEmpty() && cleared.matches == 8 && cleared.summary.startsWith("1 selected"),
                    "Clearing Search through input events lost rows or the existing selection");
            dismissKeyboard(driver, activity, dialog);

            Set<String> reached = new LinkedHashSet<>();
            ui(driver, () -> {
                ListView list = tag(dialog, "discovery-list");
                TextView hint = tag(dialog, "discovery-scroll-hint");
                require(!list.canScrollVertically(1) || "Scroll for more results".contentEquals(hint.getText()),
                        "The list does not explain that additional results are below the viewport");
            });
            for (int attempt = 0; attempt < 16 && reached.size() < 8; attempt++) {
                reached.addAll(visibleInputRows(driver, dialog));
                if (reached.size() < 8) swipeList(driver, dialog, true);
            }
            require(reached.size() == 8, "Touch scrolling could not reach all eight matching computers; visible at least once=" + reached);
            ui(driver, () -> {
                android.graphics.Rect bounds = new android.graphics.Rect();
                require(tag(dialog, "discovery-count").getGlobalVisibleRect(bounds) && !bounds.isEmpty(), "Selection count is not visible after scrolling");
                require(dialog.getButton(AlertDialog.BUTTON_POSITIVE).getGlobalVisibleRect(bounds) && !bounds.isEmpty(),
                        "Continue action is not visible after scrolling");
            });
            tapView(driver, dialog.getButton(AlertDialog.BUTTON_POSITIVE));
            driver.waitForIdleSync();
            require(Collections.singleton("input-2").equals(chosen.get()), "Keyboard/filter/scroll interaction changed the selected identity");
            status(driver, "PASS_STAGE: physical_search_keyboard_and_eight_scrollable_computers");
        } finally { ui(driver, dialog::dismiss); }
    }

    private static final class InputState {
        int flags, matches;
        boolean imeVisible, focused, inputMethodActive;
        String text, summary;
    }
    private static void verifySelectionFooterWithKeyboard(Instrumentation driver, Activity activity) throws Exception {
        List<DiscoveryUi.Row> rows = new ArrayList<>();
        for (int i = 0; i < 8; i++) rows.add(new DiscoveryUi.Row("compact-" + i, "192.0.2." + (101 + i) + ":22",
                "SSH identity not yet saved. Approve this computer's identity before signing in."));
        AtomicReference<AlertDialog> shown = new AtomicReference<>();
        ui(driver, () -> {
            AlertDialog dialog = new DiscoveryUi(activity).select("Select computers to inspect",
                    "These computers answered SSH. Sign-in and inspection are needed to confirm Nosana.", rows,
                    Collections.emptyList(), Collections.singleton("compact-0"), 512, "Review SSH identities", ignored -> {}, () -> {});
            dialog.getWindow().setLayout(Math.round(320 * activity.getResources().getDisplayMetrics().density), WindowManager.LayoutParams.MATCH_PARENT);
            scaleText(dialog.getWindow().getDecorView(), 1.6f);
            shown.set(dialog);
        });
        driver.waitForIdleSync();
        AlertDialog dialog = shown.get();
        try {
            assertSelectionFooter(driver, dialog, "keyboard closed");
            tapTag(driver, dialog, "discovery-search");
            require(awaitInput(driver, activity, dialog, state -> state.imeVisible, 5000).imeVisible,
                    "Compact selection keyboard did not open from a physical tap");
            awaitStableInputLayout(driver, dialog);
            assertSelectionFooter(driver, dialog, "keyboard open");
            dismissKeyboard(driver, activity, dialog);
            assertSelectionFooter(driver, dialog, "keyboard dismissed");
            status(driver, "PASS_STAGE: selection_footer_320dp_large_text_with_keyboard");
        } finally { ui(driver, dialog::dismiss); }
    }
    private static void assertSelectionFooter(Instrumentation driver, AlertDialog dialog, String phase) {
        ui(driver, () -> {
            android.graphics.Rect bounds = new android.graphics.Rect();
            for (int which : new int[]{AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_POSITIVE}) {
                Button button = dialog.getButton(which);
                require(button != null && button.isShown() && button.getHeight() > 0 && button.getGlobalVisibleRect(bounds)
                                && bounds.height() >= button.getHeight() && bounds.width() >= button.getWidth(),
                        "Selection action is clipped at 320dp with enlarged text (" + phase + "): " + (button == null ? which : button.getText()));
                android.text.Layout layout = button.getLayout();
                require(layout != null && layout.getLineCount() > 0, "Selection action has no readable text layout (" + phase + ")");
                for (int line = 0; line < layout.getLineCount(); line++) require(layout.getEllipsisCount(line) == 0
                                && layout.getLineWidth(line) <= layout.getWidth() + 1,
                        "Selection action text is truncated (" + phase + "): " + button.getText());
                require(layout.getLineBottom(layout.getLineCount() - 1) + button.getCompoundPaddingTop() + button.getCompoundPaddingBottom() <= button.getHeight(),
                        "Selection action text is vertically clipped (" + phase + "): " + button.getText());
            }
            View list = tag(dialog, "discovery-list");
            require(list.isShown() && list.getGlobalVisibleRect(bounds) && bounds.height() > 0,
                    "Selection results have no visible viewport (" + phase + ")");
        });
    }
    private static InputState inputState(Instrumentation driver, Activity activity, AlertDialog dialog) {
        AtomicReference<InputState> captured = new AtomicReference<>();
        ui(driver, () -> {
            InputState state = new InputState();
            EditText search = tag(dialog, "discovery-search");
            ListView list = tag(dialog, "discovery-list");
            state.flags = dialog.getWindow().getAttributes().flags;
            android.view.WindowInsets insets = dialog.getWindow().getDecorView().getRootWindowInsets();
            state.imeVisible = insets != null && insets.isVisible(android.view.WindowInsets.Type.ime());
            state.focused = search.hasFocus();
            android.view.inputmethod.InputMethodManager input = activity.getSystemService(android.view.inputmethod.InputMethodManager.class);
            state.inputMethodActive = input != null && input.isActive(search);
            state.text = search.getText().toString();
            state.matches = list.getAdapter().getCount() - list.getHeaderViewsCount() - list.getFooterViewsCount();
            state.summary = count(dialog);
            captured.set(state);
        });
        return captured.get();
    }
    private static InputState awaitInput(Instrumentation driver, Activity activity, AlertDialog dialog,
            java.util.function.Predicate<InputState> ready, long allowanceMs) throws InterruptedException {
        long deadline = android.os.SystemClock.elapsedRealtime() + allowanceMs;
        InputState state;
        do {
            state = inputState(driver, activity, dialog);
            if (ready.test(state)) return state;
            Thread.sleep(50);
        } while (android.os.SystemClock.elapsedRealtime() < deadline);
        return state;
    }
    private static void dismissKeyboard(Instrumentation driver, Activity activity, AlertDialog dialog) throws InterruptedException {
        if (inputState(driver, activity, dialog).imeVisible) {
            status(driver, "DISCOVERY_INPUT_ACTION: dismiss keyboard with Back");
            key(driver, android.view.KeyEvent.KEYCODE_BACK);
        }
        InputState hidden = awaitInput(driver, activity, dialog, state -> !state.imeVisible, 5000);
        require(!hidden.imeVisible, "Android keyboard did not dismiss with Back");
        awaitStableInputLayout(driver, dialog);
    }
    private static Set<String> visibleInputRows(Instrumentation driver, AlertDialog dialog) {
        Set<String> visible = new LinkedHashSet<>();
        ui(driver, () -> {
            android.graphics.Rect rectangle = new android.graphics.Rect();
            for (int i = 0; i < 8; i++) {
                View row = dialog.getWindow().getDecorView().findViewWithTag("discovery-check:input-" + i);
                if (row != null && row.isShown() && row.getGlobalVisibleRect(rectangle)
                        && rectangle.height() >= Math.min(row.getHeight(), Math.round(24 * row.getResources().getDisplayMetrics().density))) visible.add("input-" + i);
            }
        });
        return visible;
    }
    private static Set<String> fullyVisibleInputRows(Instrumentation driver, AlertDialog dialog) {
        Set<String> visible = new LinkedHashSet<>();
        ui(driver, () -> {
            android.graphics.Rect rectangle = new android.graphics.Rect();
            for (int i = 0; i < 8; i++) {
                View row = dialog.getWindow().getDecorView().findViewWithTag("discovery-row:input-" + i);
                if (row != null && row.isShown() && row.getHeight() > 0 && row.getGlobalVisibleRect(rectangle)
                        && rectangle.height() >= row.getHeight()) visible.add("input-" + i);
            }
        });
        return visible;
    }
    private static void revealTagBySwiping(Instrumentation driver, AlertDialog dialog, String name, boolean forward) throws InterruptedException {
        for (int attempt = 0; attempt < 12; attempt++) {
            boolean[] visible = {false};
            ui(driver, () -> {
                View view = dialog.getWindow().getDecorView().findViewWithTag(name);
                android.graphics.Rect rectangle = new android.graphics.Rect();
                visible[0] = view != null && view.isShown() && view.getGlobalVisibleRect(rectangle)
                        && rectangle.height() >= Math.min(view.getHeight(), Math.round(40 * view.getResources().getDisplayMetrics().density));
            });
            if (visible[0]) return;
            swipeList(driver, dialog, forward);
        }
        throw new AssertionError("Touch scrolling did not reveal " + name);
    }
    private static void tapTag(Instrumentation driver, AlertDialog dialog, String name) {
        AtomicReference<View> found = new AtomicReference<>();
        ui(driver, () -> found.set(tag(dialog, name)));
        tapView(driver, found.get());
    }
    private static void tapView(Instrumentation driver, View view) {
        android.graphics.Rect bounds = new android.graphics.Rect();
        android.graphics.Rect rootBounds = new android.graphics.Rect();
        ui(driver, () -> {
            view.getGlobalVisibleRect(rootBounds);
            require(visibleScreenBounds(view, bounds), "Cannot tap a view outside the visible viewport");
        });
        if ("discovery-search".equals(view.getTag())) status(driver,
                "DISCOVERY_INPUT_TAP: rootBounds=" + rootBounds.toShortString() + ", screenBounds=" + bounds.toShortString());
        long down = android.os.SystemClock.uptimeMillis();
        pointer(driver, down, down, android.view.MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY());
        pointer(driver, down, down + 80, android.view.MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY());
        driver.waitForIdleSync();
    }
    private static void swipeList(Instrumentation driver, AlertDialog dialog, boolean forward) throws InterruptedException {
        android.graphics.Rect bounds = new android.graphics.Rect();
        ui(driver, () -> require(visibleScreenBounds(tag(dialog, "discovery-list"), bounds) && bounds.height() > 80, "Discovery list has no usable scroll viewport"));
        float start = bounds.top + bounds.height() * (forward ? .82f : .18f);
        float end = bounds.top + bounds.height() * (forward ? .18f : .82f);
        long down = android.os.SystemClock.uptimeMillis();
        pointer(driver, down, down, android.view.MotionEvent.ACTION_DOWN, bounds.exactCenterX(), start);
        for (int step = 1; step <= 12; step++) {
            Thread.sleep(20);
            pointer(driver, down, android.os.SystemClock.uptimeMillis(), android.view.MotionEvent.ACTION_MOVE,
                    bounds.exactCenterX(), start + (end - start) * step / 12);
        }
        pointer(driver, down, android.os.SystemClock.uptimeMillis(), android.view.MotionEvent.ACTION_UP, bounds.exactCenterX(), end);
        driver.waitForIdleSync();
        Thread.sleep(150);
    }
    /** Pointer injection uses display coordinates, whereas getGlobalVisibleRect uses the view root. */
    private static boolean visibleScreenBounds(View view, android.graphics.Rect bounds) {
        if (!view.isShown() || !view.getLocalVisibleRect(bounds) || bounds.isEmpty()) return false;
        int[] origin = new int[2];
        view.getLocationOnScreen(origin);
        bounds.offset(origin[0], origin[1]);
        return true;
    }
    private static void pointer(Instrumentation driver, long down, long at, int action, float x, float y) {
        android.view.MotionEvent event = android.view.MotionEvent.obtain(down, at, action, x, y, 0);
        event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        try { require(driver.getUiAutomation().injectInputEvent(event, true), "Android rejected touchscreen action " + action + " at " + x + "," + y); }
        finally { event.recycle(); }
    }
    private static void key(Instrumentation driver, int code) {
        long down = android.os.SystemClock.uptimeMillis();
        for (int action : new int[]{android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP}) {
            android.view.KeyEvent event = new android.view.KeyEvent(down, android.os.SystemClock.uptimeMillis(), action, code, 0, 0,
                    android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, android.view.InputDevice.SOURCE_KEYBOARD);
            require(driver.getUiAutomation().injectInputEvent(event, true), "Android rejected keyboard action " + action + " for key " + code);
        }
    }
    private static void typeKeys(Instrumentation driver, String text) {
        android.view.KeyEvent[] events = android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray());
        require(events != null, "Synthetic query cannot be typed with the virtual keyboard");
        for (android.view.KeyEvent event : events) require(driver.getUiAutomation().injectInputEvent(
                android.view.KeyEvent.changeTimeRepeat(event, android.os.SystemClock.uptimeMillis(), 0), true), "Android rejected a typed query key");
    }
    private static void awaitStableInputLayout(Instrumentation driver, AlertDialog dialog) throws InterruptedException {
        long deadline = android.os.SystemClock.elapsedRealtime() + 3000, stableSince = 0;
        String previous = null;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            AtomicReference<String> captured = new AtomicReference<>();
            ui(driver, () -> {
                android.graphics.Rect list = new android.graphics.Rect(), search = new android.graphics.Rect();
                visibleScreenBounds(tag(dialog, "discovery-list"), list);
                visibleScreenBounds(tag(dialog, "discovery-search"), search);
                View root = dialog.getWindow().getDecorView();
                captured.set(list.toShortString() + search.toShortString() + ":" + root.getWidth() + ":" + root.getHeight() + ":" + root.isLayoutRequested());
            });
            long now = android.os.SystemClock.elapsedRealtime();
            if (!captured.get().equals(previous)) { previous = captured.get(); stableSince = now; }
            else if (now - stableSince >= 250 && !captured.get().endsWith(":true")) { driver.waitForIdleSync(); return; }
            Thread.sleep(50);
        }
        throw new AssertionError("Discovery layout did not settle after an input transition: " + previous);
    }
    private static void screenshot(Instrumentation driver, Activity activity, String name) throws java.io.IOException {
        android.graphics.Bitmap bitmap = driver.getUiAutomation().takeScreenshot();
        require(bitmap != null, "Could not capture the non-secret synthetic discovery screen");
        try (java.io.OutputStream output = new java.io.FileOutputStream(new java.io.File(activity.getCacheDir(), name))) {
            require(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output), "Could not save synthetic discovery screenshot");
        } finally { bitmap.recycle(); }
        status(driver, "DISCOVERY_INPUT_SCREENSHOT: cache/" + name);
    }
    private static void status(Instrumentation driver, String text) {
        Bundle progress = new Bundle(); progress.putString("stream", text + "\n"); driver.sendStatus(0, progress);
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
                require(list.getAdapter().getCount() == 200 + list.getHeaderViewsCount() && list.getChildCount() < 200,
                        "Large candidate list was truncated or is not virtualized");
                ((Spinner) tag(dialog, "discovery-filter")).setSelection(1);
            });
            driver.waitForIdleSync();
            ui(driver, () -> {
                ((EditText) tag(dialog, "discovery-search")).setText("rack a");
                tag(dialog, "discovery-select-all").performClick();
                require(count(dialog).startsWith("50 selected · 50 results"), "Search and identity filter did not select the matching 50 PCs");
                ((EditText) tag(dialog, "discovery-search")).setText("");
                require(count(dialog).startsWith("50 selected · 100 results"), "Clearing search lost selections");
                ((Spinner) tag(dialog, "discovery-filter")).setSelection(2);
            });
            driver.waitForIdleSync();
            ui(driver, () -> {
                require(count(dialog).startsWith("50 selected · 100 results"), "Changing filters discarded hidden selections");
                ((Spinner) tag(dialog, "discovery-filter")).setSelection(0);
            });
            driver.waitForIdleSync();
            ui(driver, () -> {
                require(count(dialog).startsWith("50 selected · 200 results"), "Restoring all candidates changed the selection");
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
                require(count(found).startsWith("50 selected · 50 results"), "Verified host select-all lost hosts on shared PCs");
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
