package com.gridlens.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.AbsListView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
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

    /** Emphasize an explicitly chosen primary action without changing its authority or label. */
    void highlightPrimary(AlertDialog dialog) {
        Button button = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (button == null) return;
        GradientDrawable enabled = new GradientDrawable();
        enabled.setColor(GREEN);
        enabled.setCornerRadius(dp(5));
        enabled.setStroke(dp(1), GREEN);
        GradientDrawable disabled = new GradientDrawable();
        disabled.setColor(Color.rgb(65, 73, 65));
        disabled.setCornerRadius(dp(5));
        disabled.setStroke(dp(1), Color.rgb(105, 115, 105));
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled}, disabled);
        states.addState(new int[]{}, enabled);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(5));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(45, 0, 0, 0)), states, mask));
        button.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{Color.rgb(175, 175, 175), Color.rgb(11, 25, 10)}));
        button.setTypeface(button.getTypeface(), Typeface.BOLD);
        button.setTag("discovery-highlighted-primary");
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
        return select(title, explanation, null, rows, filters, initiallySelected, maxSelection,
                buttonLabel, onSelected, onCancel);
    }

    AlertDialog select(String title, String explanation, String searchHelp, List<Row> rows, List<Filter> filters,
            Set<String> initiallySelected, int maxSelection, String buttonLabel,
            Consumer<Set<String>> onSelected, Runnable onCancel) {
        if (maxSelection < 1) throw new IllegalArgumentException("Selection limit must be positive");
        Selection selection = new Selection(rows, initiallySelected);
        SelectionLayout body = new SelectionLayout();
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.VERTICAL);
        if (searchHelp != null && !searchHelp.isEmpty()) {
            TextView help = label(header, searchHelp);
            help.setTag("discovery-search-help");
        }
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
        // A ListView header is not attached during AlertDialog's text-editor check.
        // Keep the editor in the actual custom-view hierarchy before creating it.
        body.addView(search);

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
        all.setText("Select all results");
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
        ScrollView options = scroll(header);
        options.setTag("discovery-options");
        body.options = options;
        body.addView(options, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ListView list = new ListView(activity);
        list.setTag("discovery-list");
        list.setDividerHeight(dp(1));
        list.setItemsCanFocus(true);
        list.setVerticalScrollBarEnabled(true);
        list.setScrollbarFadingEnabled(false);
        body.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        TextView count = label(body, "");
        count.setTag("discovery-count");
        count.setTextColor(GREEN);
        count.setTextSize(18);
        count.setTypeface(count.getTypeface(), Typeface.BOLD);
        count.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        TextView scrollHint = label(body, "");
        scrollHint.setTag("discovery-scroll-hint");
        scrollHint.setTextSize(14);
        scrollHint.setPadding(0, 0, 0, 0);
        scrollHint.setMinHeight(dp(20));
        Runnable updateScrollHint = () -> {
            String hint = selection.visible.isEmpty() ? "No results match your search."
                    : list.canScrollVertically(1) ? "Scroll for more results"
                    : list.canScrollVertically(-1) ? "Scroll up for earlier results" : "";
            if (!hint.contentEquals(scrollHint.getText())) scrollHint.setText(hint);
        };
        AlertDialog dialog = builder(title).setView(body)
                .setPositiveButton(buttonLabel, null)
                .setNegativeButton("Cancel", (closed, which) -> run(onCancel))
                .setOnCancelListener(closed -> run(onCancel)).create();
        SelectionAdapter adapter = new SelectionAdapter(selection);
        Runnable update = () -> {
            int shownSelectable = 0;
            for (Row row : selection.visible) if (row.selectable) shownSelectable++;
            int selected = selection.selected.size();
            String text = selected + " selected · " + selection.visible.size() + " results";
            if (selected > maxSelection) text += "\nChoose at most " + maxSelection + " to continue.";
            count.setText(text);
            all.setEnabled(shownSelectable > 0);
            clear.setEnabled(selected > 0);
            Button proceed = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (proceed != null) proceed.setEnabled(selected > 0 && selected <= maxSelection);
            list.post(updateScrollHint);
        };
        adapter.changed = update;
        list.setAdapter(adapter);
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int state) { updateScrollHint.run(); }
            @Override public void onScroll(AbsListView view, int first, int visible, int total) { updateScrollHint.run(); }
        });
        list.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> updateScrollHint.run());
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
        reserveSelectionFooter(dialog);
        if (dialog.getWindow() != null) {
            dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
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

    AlertDialog confirmRows(String title, String explanation, List<Row> rows, String positiveLabel,
            Runnable onConfirm, String backLabel, Runnable onBack, Runnable onCancel) {
        SelectionLayout body = new SelectionLayout();
        LinearLayout summary = new LinearLayout(activity);
        summary.setOrientation(LinearLayout.VERTICAL);
        if (explanation != null && !explanation.isEmpty()) label(summary, explanation);
        ScrollView options = scroll(summary);
        options.setTag("discovery-review-explanation");
        body.options = options;
        body.addView(options, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ListView list = new ListView(activity);
        list.setTag("discovery-review-list");
        list.setDividerHeight(dp(1));
        list.setVerticalScrollBarEnabled(true);
        list.setScrollbarFadingEnabled(false);
        list.setAdapter(new ReviewAdapter(rows));
        body.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        AlertDialog.Builder review = builder(title).setView(body)
                .setNegativeButton("Cancel", (closed, which) -> run(onCancel))
                .setOnCancelListener(closed -> run(onCancel));
        if (positiveLabel != null) review.setPositiveButton(positiveLabel, (closed, which) -> run(onConfirm));
        if (backLabel != null) review.setNeutralButton(backLabel, (closed, which) -> run(onBack));
        AlertDialog dialog = show(review);
        reserveSelectionFooter(dialog);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        return dialog;
    }

    private void reserveSelectionFooter(AlertDialog dialog) {
        int footerId = activity.getResources().getIdentifier("buttonPanel", "id", "android");
        View footer = footerId == 0 ? null : dialog.findViewById(footerId);
        if (footer == null || !(footer.getParent() instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) footer.getParent();
        int index = parent.indexOfChild(footer);
        ViewGroup.LayoutParams layout = footer.getLayoutParams();
        parent.removeView(footer);
        FooterReservation reservation = new FooterReservation();
        reservation.setId(footerId);
        reservation.setTag("discovery-footer");
        footer.setId(View.NO_ID);
        reservation.addView(footer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        parent.addView(reservation, index, layout);
    }

    /** The platform's stacked bar minimum includes only a peek of its second button. */
    private final class FooterReservation extends FrameLayout {
        private int fullHeight;
        FooterReservation() { super(activity); }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            // AlertDialogLayout asks for the footer's minimum before measuring its
            // expanding middle panel. Reserve the actual native bar, including every
            // wrapped/stacked button. Recompute for width and font changes in both
            // directions; no previously larger minimum may keep the bar expanded.
            fullHeight = 0;
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            fullHeight = getMeasuredHeight();
            super.onMeasure(widthSpec, heightSpec);
        }
        @Override public int getMinimumHeight() { return Math.max(super.getMinimumHeight(), fullHeight); }
    }

    /** Reserve the remaining dialog space for results, including when the IME resizes it. */
    private final class SelectionLayout extends LinearLayout {
        ScrollView options;
        SelectionLayout() {
            super(activity);
            setOrientation(VERTICAL);
            setPadding(dp(16), dp(4), dp(16), dp(12));
        }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int height = MeasureSpec.getSize(heightSpec);
            if (options != null && height > 0 && MeasureSpec.getMode(heightSpec) != MeasureSpec.UNSPECIFIED) {
                int width = Math.max(0, MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight());
                int available = Math.max(0, height - getPaddingTop() - getPaddingBottom());
                int optionsLimit = Math.min(available / 3, Math.max(0, available - dp(190)));
                options.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(optionsLimit, MeasureSpec.AT_MOST));
                options.getLayoutParams().height = Math.min(optionsLimit, options.getMeasuredHeight());
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
            } else super.onMeasure(widthSpec, heightSpec);
        }
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

    private final class ReviewAdapter extends BaseAdapter {
        private final List<Row> rows;
        ReviewAdapter(List<Row> rows) { this.rows = new ArrayList<>(rows); }
        @Override public int getCount() { return rows.size(); }
        @Override public Row getItem(int position) { return rows.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public boolean hasStableIds() { return true; }
        @Override public boolean areAllItemsEnabled() { return false; }
        @Override public boolean isEnabled(int position) { return false; }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            if (convertView instanceof LinearLayout) row = (LinearLayout) convertView;
            else {
                row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(4), dp(8), dp(4), dp(8));
                TextView heading = label(row, "");
                heading.setTextColor(TEXT);
                heading.setTextSize(17);
                heading.setTypeface(heading.getTypeface(), Typeface.BOLD);
                label(row, "").setTextSize(14);
            }
            Row value = getItem(position);
            row.setTag("discovery-review-row:" + value.id);
            ((TextView) row.getChildAt(0)).setText(value.title);
            TextView detail = (TextView) row.getChildAt(1);
            detail.setText(value.description);
            detail.setVisibility(value.description.isEmpty() ? View.GONE : View.VISIBLE);
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
