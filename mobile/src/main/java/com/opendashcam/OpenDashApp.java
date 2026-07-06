package com.opendashcam;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;

import java.util.Locale;

public class OpenDashApp extends Application {

    private static OpenDashApp sApp;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(applyRussianLocale(base));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        applyRussianLocale(this);
        if (sApp == null) {
            sApp = this;
        }
    }

    public static Context getAppContext() {
        return sApp.getApplicationContext();
    }

    private static Context applyRussianLocale(Context context) {
        Locale locale = Locale.forLanguageTag("ru");
        Locale.setDefault(locale);
        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.setLocale(locale);
        return context.createConfigurationContext(config);
    }
}
