package com.enzo.podsguard;

import android.content.Context;
import android.content.SharedPreferences;

final class Prefs {
    static final String FILE = "podsguard_settings";
    static final String ENABLED = "enabled";
    static final String ONLY_BLUETOOTH = "only_bluetooth";
    static final String DELAY_MS = "delay_ms";
    static final String BYPASS_UNTIL = "bypass_until";
    static final String RESUME_COUNT = "resume_count";
    static final String LAST_RESUME_AT = "last_resume_at";

    private Prefs() {}

    static SharedPreferences get(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }
}
