package pl.marm.carobd;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.json.JSONObject;

/** Lokalny język interfejsu. Format danych OBD, GPX i NAS pozostaje niezależny od języka. */
public final class UiLanguage extends Application {
    private static final String KEY="ui_language";
    private static final String[] TAGS={"en","de","pl"};
    private static final String[] NAMES={"English","Deutsch","Polski"};
    private static final String[] ADAPTER_PREFIXES={"Łączenie z adapterem ","Łączenie Wi-Fi · ","Adapter "};
    private static volatile Catalog current=new Catalog(0,Collections.emptyMap());

    private static final class Catalog {
        final int index;
        final Map<String,String> values;
        Catalog(int index,Map<String,String> values) { this.index=index; this.values=values; }
    }

    @Override public void onCreate() { super.onCreate(); load(this,preferred(this)); }

    private static int preferred(Context context) {
        String value=context.getSharedPreferences("carobd",MODE_PRIVATE).getString(KEY,"en");
        for (int i=0;i<TAGS.length;i++) if (TAGS[i].equals(value)) return i;
        return 0;
    }

    private static Locale locale(int index) {
        return index==1 ? Locale.GERMANY : index==2 ? Locale.forLanguageTag("pl-PL") : Locale.UK;
    }

    static Locale locale() { return locale(current.index); }
    static int selected() { return current.index; }
    static String name() { return NAMES[selected()]; }
    static String[] options() { return NAMES.clone(); }

    static Context localized(Context base) {
        Configuration config=new Configuration(base.getResources().getConfiguration());
        config.setLocale(locale(preferred(base)));
        return base.createConfigurationContext(config);
    }

    static void set(Context context,int index) {
        if (index==selected()) return;
        context.getSharedPreferences("carobd",MODE_PRIVATE).edit().putString(KEY,TAGS[index]).apply();
        load(context,index);
    }

    private static void load(Context context,int index) {
        Map<String,String> values=new HashMap<>();
        if (index!=2) try (InputStreamReader input=new InputStreamReader(context.getResources()
                .openRawResource(R.raw.ui_translations),StandardCharsets.UTF_8)) {
            StringBuilder json=new StringBuilder();
            char[] buffer=new char[4096];
            for (int count;(count=input.read(buffer))!=-1;) json.append(buffer,0,count);
            JSONObject catalog=new JSONObject(json.toString());
            for (java.util.Iterator<String> keys=catalog.keys();keys.hasNext();) {
                String source=keys.next();
                JSONObject translations=catalog.getJSONObject(source);
                values.put(source,translations.optString(TAGS[index],translations.optString("en",source)));
            }
        } catch (java.io.IOException | org.json.JSONException e) { android.util.Log.w("CarOBD","Cannot load UI translations",e); }
        current=new Catalog(index,Collections.unmodifiableMap(values));
    }

    static String tr(String source) { return current.values.getOrDefault(source,source); }

    static String duration(long seconds) {
        if (selected()==2) return EngineHours.duration(seconds);
        if (seconds<0) return "—";
        long minutes=seconds/60, days=minutes/1440, years=days/365;
        StringBuilder text=new StringBuilder();
        if (years>0) text.append(years).append(selected()==1 ? " J. " : " yr ");
        if (days%365>0) text.append(days%365).append(selected()==1 ? " T. " : " d ");
        if (minutes/60%24>0) text.append(minutes/60%24).append(" h ");
        if (minutes%60>0 || text.length()==0) text.append(minutes%60).append(" min");
        return text.toString().trim();
    }

    static String adapterStatus(String source) {
        String translation=tr(source);
        if (!translation.equals(source)) return translation;
        for (String prefix:ADAPTER_PREFIXES) if (source.startsWith(prefix)) return tr(prefix)+source.substring(prefix.length());
        return source;
    }

    static String proxyStatus(String source) {
        return tr(source).replace("Klient Wi-Fi · ",tr("Klient Wi-Fi · "))
            .replace("Proxy lokalne niedostępne",tr("Proxy lokalne niedostępne"))
            .replace(" · klienci: ",tr(" · klienci: "))
            .replace("\nProxy Wi-Fi nie może otworzyć portu ",tr("\nProxy Wi-Fi nie może otworzyć portu "));
    }

    static String dtc(String source) { return withPrefix(source,"Wypadanie zapłonu na cylindrze "); }
    static String roadDistance(String source) { return withPrefix(source,"Za "); }
    private static String withPrefix(String source,String prefix) {
        return source.startsWith(prefix) ? tr(prefix)+source.substring(prefix.length()) : tr(source);
    }
}
