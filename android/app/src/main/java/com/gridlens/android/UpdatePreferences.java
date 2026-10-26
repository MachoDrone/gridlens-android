package com.gridlens.android;

import android.content.Context;
import android.content.SharedPreferences;

/** Uses the same synchronized provider deadline authority from Activity and transfer service. */
final class UpdatePreferences {
    static UpdatePersistence open(Context context) {
        SharedPreferences preferences = context.getSharedPreferences("updates", Context.MODE_PRIVATE);
        return new UpdatePersistence(new UpdatePersistence.Store() {
            public long readLong(String key) { try { return preferences.getLong(key, 0); } catch (ClassCastException invalid) { return 0; } }
            public UpdatePersistence.Editor edit() { return new Editor(preferences.edit()); }
        });
    }
    private static final class Editor implements UpdatePersistence.Editor {
        private final SharedPreferences.Editor editor;
        Editor(SharedPreferences.Editor editor) { this.editor = editor; }
        public UpdatePersistence.Editor putLong(String key, long value) { editor.putLong(key, value); return this; }
        public UpdatePersistence.Editor putString(String key, String value) { editor.putString(key, value); return this; }
        public UpdatePersistence.Editor remove(String key) { editor.remove(key); return this; }
        public void apply() { editor.apply(); }
        public boolean commit() { return editor.commit(); }
    }
    private UpdatePreferences() {}
}
