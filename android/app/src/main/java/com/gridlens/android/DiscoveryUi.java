package com.gridlens.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Native discovery presentation only; selection never starts network work by itself. */
final class DiscoveryUi {
    static final int GREEN = Color.rgb(16, 232, 12);
    static final int LINK = Color.rgb(138, 180, 248);
    static final int TEXT = Color.rgb(242, 242, 242);
    static final int SECONDARY = Color.rgb(211, 211, 211);
    private final Activity activity;

    DiscoveryUi(Activity activity) { this.activity = activity; }

    AlertDialog.Builder builder(String title) {
        TextView heading = new TextView(activity);
        heading.setText(title);
        heading.setTextColor(GREEN);
        heading.setTextSize(22);
        heading.setPadding(dp(20), dp(18), dp(20), dp(10));
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        return new AlertDialog.Builder(activity) {
            @Override public AlertDialog show() {
                AlertDialog dialog = super.show();
                style(dialog);
                return dialog;
            }
        }.setCustomTitle(heading);
    }

    AlertDialog show(AlertDialog.Builder builder) {
        AlertDialog dialog = builder.create();
        dialog.show();
        style(dialog);
        return dialog;
    }

    /** Call after showing an existing dialog; its listeners and button behavior remain intact. */
    void style(AlertDialog dialog) {
        int titleId = activity.getResources().getIdentifier("alertTitle", "id", "android");
        View title = titleId == 0 ? null : dialog.findViewById(titleId);
        if (title instanceof TextView) ((TextView) title).setTextColor(GREEN);
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
            Button button = dialog.getButton(which);
            if (button != null) outline(button);
        }
    }

    LinearLayout form() {
        LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(16), dp(4), dp(16), dp(12));
        return form;
    }

    TextView label(LinearLayout form, String text) {
        TextView label = new TextView(activity);
        label.setText(text);
        label.setTextColor(SECONDARY);
        label.setTextSize(15);
        label.setPadding(0, dp(6), 0, dp(6));
        form.addView(label);
        return label;
    }

    EditText field(LinearLayout form, String name, String hint, String text, boolean secret) {
        TextView label = label(form, name);
        label.setTextColor(TEXT);
        EditText field = new EditText(activity);
        field.setId(View.generateViewId());
        label.setLabelFor(field.getId());
        field.setHint(hint);
        field.setText(text);
        field.setSingleLine(true);
        field.setSaveEnabled(false);
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        field.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        field.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        field.setMinHeight(dp(48));
        form.addView(field);
        return field;
    }

    static final class Action {
        final String label, description;
        final Runnable action;
        Action(String label, String description, Runnable action) {
            this.label = label;
            this.description = description;
            this.action = action;
        }
    }

    View action(String title, String description, Runnable action) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(4), dp(10), dp(4), dp(10));
        row.setMinimumHeight(dp(56));
        row.setBackground(ripple(null));
        TextView heading = new TextView(activity);
        heading.setText(title);
        heading.setTextColor(LINK);
        heading.setTextSize(18);
        heading.setPaintFlags(heading.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        row.addView(heading);
        if (description != null && !description.isEmpty()) {
            TextView detail = label(row, description);
            detail.setPadding(0, dp(4), 0, 0);
        }
        row.setFocusable(true);
        row.setClickable(true);
        row.setContentDescription(title + (description == null || description.isEmpty() ? "" : ". " + description));
        row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        for (int i = 0; i < row.getChildCount(); i++) row.getChildAt(i).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.setOnClickListener(view -> { if (action != null) action.run(); });
        return row;
    }

    AlertDialog menu(String title, String description, List<Action> actions, String closeLabel, Runnable onClose) {
        LinearLayout body = form();
        if (description != null && !description.isEmpty()) label(body, description);
        AlertDialog dialog = builder(title).setView(scroll(body))
                .setNegativeButton(closeLabel, (closed, which) -> run(onClose))
                .setOnCancelListener(closed -> run(onClose)).create();
        for (Action item : actions) body.addView(action(item.label, item.description, () -> {
            dialog.dismiss();
            run(item.action);
        }));
        dialog.show();
        style(dialog);
        return dialog;
    }

    static final class Row {
        final String id, title, description, group;
        final boolean selectable;
        Row(String id, String title, String description) { this(id, title, description, "", true); }
        Row(String id, String title, String description, String group, boolean selectable) {
            this.id = id;
            this.title = title;
            this.description = description == null ? "" : description;
            this.group = group == null ? "" : group;
            this.selectable = selectable;
        }
    }

    static final class Filter {
        final String label;
        final Predicate<Row> matches;
        Filter(String label, Predicate<Row> matches) { this.label = label; this.matches = matches; }
        @Override public String toString() { return label; }
    }

    AlertDialog select(String title, String explanation, List<Row> rows, List<Filter> filters,
            Set<String> initiallySelected, int maxSelection, String buttonLabel,
            Consumer<Set<String>> onSelected, Runnable onCancel) {
        if (maxSelection < 1) throw new IllegalArgumentException("Selection limit must be positive");
        Selection selection = new Selection(rows, initiallySelected);
        LinearLayout body = form();
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.VERTICAL);
        if (explanation != null && !explanation.isEmpty()) label(header, explanation);
        EditText search = new EditText(activity);
        search.setTag("discovery-search");
        search.setHint("Search name or address");
        search.setContentDescription("Search discovered computers and hosts");
        search.setSingleLine(true);
        search.setSaveEnabled(false);
        search.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        search.setMinHeight(dp(48));
        header.addView(search);

        List<Filter> available = filters == null || filters.isEmpty()
                ? Collections.singletonList(new Filter("All", row -> true)) : new ArrayList<>(filters);
        Spinner filter = new Spinner(activity);
        filter.setTag("discovery-filter");
        filter.setContentDescription("Filter discovery results");
        filter.setMinimumHeight(dp(48));
        ArrayAdapter<Filter> choices = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item, available);
        choices.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        filter.setAdapter(choices);
        if (available.size() > 1) header.addView(filter);

        LinearLayout controls = new LinearLayout(activity);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        Button all = new Button(activity);
        all.setText("Select all shown");
        all.setTag("discovery-select-all");
        all.setAllCaps(false);
        all.setSingleLine(false);
        outline(all);
        controls.addView(all, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button clear = new Button(activity);
        clear.setText("Clear selection");
        clear.setTag("discovery-clear");
        clear.setAllCaps(false);
        clear.setSingleLine(false);
        outline(clear);
        LinearLayout.LayoutParams clearLayout = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        clearLayout.setMarginStart(dp(6));
        controls.addView(clear, clearLayout);
        header.addView(controls);

        ListView list = new ListView(activity);
        list.setTag("discovery-list");
        list.setDividerHeight(dp(1));
        list.setItemsCanFocus(true);
        list.addHeaderView(header, null, false);
        // All explanatory controls scroll with the virtualized list at large font sizes.
        // This leaves the selection total and dialog actions reachable on small screens.
        int listHeight = Math.max(dp(160), Math.min(dp(360), activity.getResources().getDisplayMetrics().heightPixels * 2 / 5));
        body.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, listHeight));
        TextView count = label(body, "");
        count.setTag("discovery-count");
        count.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        AlertDialog dialog = builder(title).setView(body)
                .setPositiveButton(buttonLabel, null)
                .setNegativeButton("Cancel", (closed, which) -> run(onCancel))
                .setOnCancelListener(closed -> run(onCancel)).create();
        SelectionAdapter adapter = new SelectionAdapter(selection);
        Runnable update = () -> {
            int shownSelectable = 0;
            for (Row row : selection.visible) if (row.selectable) shownSelectable++;
            int selected = selection.selected.size();
            String text = selected + " selected · " + selection.visible.size() + " shown";
            if (selected > maxSelection) text += "\nChoose at most " + maxSelection + " to continue.";
            else if (selected == 0) text += "\nSelect at least one to continue.";
            count.setText(text);
            all.setEnabled(shownSelectable > 0);
            clear.setEnabled(selected > 0);
            Button proceed = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (proceed != null) proceed.setEnabled(selected > 0 && selected <= maxSelection);
        };
        adapter.changed = update;
        list.setAdapter(adapter);
        Runnable refilter = () -> {
            int index = filter.getSelectedItemPosition();
            selection.filter(search.getText().toString(), available.get(Math.max(0, index)).matches);
            adapter.notifyDataSetChanged();
            update.run();
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { refilter.run(); }
            @Override public void afterTextChanged(Editable text) { }
        });
        filter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { refilter.run(); }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        all.setOnClickListener(view -> {
            for (Row row : selection.visible) if (row.selectable) selection.selected.add(row.id);
            adapter.notifyDataSetChanged();
            update.run();
        });
        clear.setOnClickListener(view -> {
            selection.selected.clear();
            adapter.notifyDataSetChanged();
            update.run();
        });
        dialog.show();
        style(dialog);
        if (dialog.getWindow() != null) dialog.getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (selection.selected.isEmpty() || selection.selected.size() > maxSelection) return;
            LinkedHashSet<String> ordered = new LinkedHashSet<>();
            for (Row row : selection.all) if (selection.selected.contains(row.id)) ordered.add(row.id);
            dialog.dismiss();
            onSelected.accept(Collections.unmodifiableSet(ordered));
        });
        refilter.run();
        return dialog;
    }

    AlertDialog result(String title, String summary, String details, String doneLabel, Runnable onDone) {
        LinearLayout body = form();
        TextView overview = label(body, summary);
        overview.setTextColor(TEXT);
        if (details != null && !details.isEmpty()) {
            TextView detail = new TextView(activity);
            detail.setTag("discovery-details");
            detail.setText(details);
            detail.setTextColor(SECONDARY);
            detail.setTextSize(14);
            detail.setPadding(0, dp(8), 0, dp(8));
            detail.setVisibility(View.GONE);
            Button toggle = new Button(activity);
            toggle.setTag("discovery-details-toggle");
            toggle.setAllCaps(false);
            toggle.setText("View inspection details");
            toggle.setTextColor(LINK);
            toggle.setPaintFlags(toggle.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
            toggle.setMinHeight(dp(48));
            toggle.setOnClickListener(view -> {
                boolean expanded = detail.getVisibility() != View.VISIBLE;
                detail.setVisibility(expanded ? View.VISIBLE : View.GONE);
                toggle.setText(expanded ? "Hide inspection details" : "View inspection details");
                toggle.setContentDescription(expanded ? "Hide inspection details, expanded" : "View inspection details, collapsed");
            });
            toggle.setContentDescription("View inspection details, collapsed");
            body.addView(toggle);
            body.addView(detail);
        }
        return show(builder(title).setView(scroll(body)).setPositiveButton(doneLabel, (dialog, which) -> run(onDone)));
    }

    /** Pure selection state, kept separate from the virtualized Android views. */
    static final class Selection {
        final List<Row> all, visible = new ArrayList<>();
        final Set<String> selected = new LinkedHashSet<>();
        Selection(List<Row> rows, Set<String> initiallySelected) {
            all = new ArrayList<>(rows);
            Set<String> ids = new LinkedHashSet<>();
            for (Row row : all) {
                if (row.id == null || !ids.add(row.id)) throw new IllegalArgumentException("Selection rows need unique IDs");
                if (row.selectable && initiallySelected != null && initiallySelected.contains(row.id)) selected.add(row.id);
            }
            visible.addAll(all);
        }
        void filter(String query, Predicate<Row> filter) {
            visible.clear();
            String match = query.trim().toLowerCase(Locale.ROOT);
            for (Row row : all) if (filter.test(row)
                    && (match.isEmpty() || (row.title + "\n" + row.description).toLowerCase(Locale.ROOT).contains(match))) visible.add(row);
        }
    }

    private final class SelectionAdapter extends BaseAdapter {
        private final Selection selection;
        private Runnable changed;
        SelectionAdapter(Selection selection) { this.selection = selection; }
        @Override public int getCount() { return selection.visible.size(); }
        @Override public Row getItem(int position) { return selection.visible.get(position); }
        @Override public long getItemId(int position) { return selection.all.indexOf(getItem(position)); }
        @Override public boolean hasStableIds() { return true; }
        @Override public boolean areAllItemsEnabled() { return false; }
        @Override public boolean isEnabled(int position) { return getItem(position).selectable; }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            CheckBox checkbox;
            TextView detail;
            if (convertView instanceof LinearLayout) {
                row = (LinearLayout) convertView;
                checkbox = (CheckBox) row.getChildAt(0);
                detail = (TextView) row.getChildAt(1);
            } else {
                row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(0, dp(7), 0, dp(7));
                row.setMinimumHeight(dp(56));
                row.setBackground(ripple(null));
                checkbox = new CheckBox(activity);
                checkbox.setTextSize(17);
                checkbox.setMinHeight(dp(48));
                checkbox.setSingleLine(false);
                checkbox.setFocusable(true);
                row.addView(checkbox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                detail = new TextView(activity);
                detail.setTextSize(14);
                detail.setPadding(dp(8), 0, dp(8), dp(5));
                row.addView(detail);
            }
            Row value = getItem(position);
            row.setTag("discovery-row:" + value.id);
            checkbox.setTag("discovery-check:" + value.id);
            checkbox.setOnCheckedChangeListener(null);
            checkbox.setText(value.title);
            checkbox.setTextColor(value.selectable ? LINK : SECONDARY);
            checkbox.setPaintFlags(value.selectable ? checkbox.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG : checkbox.getPaintFlags() & ~Paint.UNDERLINE_TEXT_FLAG);
            checkbox.setEnabled(value.selectable);
            checkbox.setChecked(selection.selected.contains(value.id));
            checkbox.setContentDescription(value.title + (value.description.isEmpty() ? "" : ". " + value.description)
                    + (value.selectable ? "" : ". Unavailable for selection"));
            detail.setText(value.description);
            detail.setTextColor(SECONDARY);
            detail.setVisibility(value.description.isEmpty() ? View.GONE : View.VISIBLE);
            detail.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            checkbox.setOnCheckedChangeListener((button, checked) -> {
                if (checked) selection.selected.add(value.id); else selection.selected.remove(value.id);
                run(changed);
            });
            CheckBox target = checkbox;
            row.setOnClickListener(value.selectable ? view -> target.toggle() : null);
            return row;
        }
    }

    private ScrollView scroll(View content) {
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content);
        return scroll;
    }
    private void outline(Button button) {
        GradientDrawable border = new GradientDrawable();
        border.setColor(Color.TRANSPARENT);
        border.setCornerRadius(dp(5));
        border.setStroke(dp(1), Color.rgb(112, 142, 110));
        button.setBackground(ripple(border));
        button.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{Color.rgb(155, 155, 155), GREEN}));
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setPadding(dp(10), dp(8), dp(10), dp(8));
        button.setSingleLine(false);
    }
    private RippleDrawable ripple(GradientDrawable content) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        return new RippleDrawable(ColorStateList.valueOf(Color.argb(65, 138, 180, 248)), content, mask);
    }
    private int dp(int size) { return Math.round(size * activity.getResources().getDisplayMetrics().density); }
    private static void run(Runnable action) { if (action != null) action.run(); }
}
