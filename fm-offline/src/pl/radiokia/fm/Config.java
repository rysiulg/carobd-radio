package pl.radiokia.fm;

import java.util.Locale;

final class Config {
    static final String FACTORY_FM = "com.tw.radio", XT_PACKAGE = "com.tw.service.xt";
    static final String XT_SERVICE = XT_PACKAGE + ".CommandService";
    static final String XT_INTERFACE = XT_PACKAGE + ".aidl.ITWCommandAidl";
    static final int XT_SEEK_UP = 13, XT_SEEK_DOWN = 14, XT_TUNE = 15, XT_TIMEOUT_MS = 5000;
    static final int FACTORY_START_MS = 1500;
    static final int FM_MIN = 875, FM_MAX = 1080, MAX_PRESETS = 8;
    static final String PREFS = "fm", PRESETS = "presets";
    static final int BACKGROUND = 0xFF0D1822, PANEL = 0xFF142630, LINE = 0xFF2B4752;
    static final int TEXT = 0xFFF2F8FA, MUTED = 0xFFA0B6BD, ACCENT = 0xFF78DDD0;
    static final int INDICATOR = 0xFFFFD28E, PRESET_ACTIVE = 0xFF21504F;
    private static final Locale POLISH = new Locale("pl", "PL");

    static String formatChannel(int frequency) { return String.format(POLISH, "%.1f", frequency / 10.0); }
    private Config() {}
}
