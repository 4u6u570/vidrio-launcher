package com.augusto.vidrio;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The launcher's settings. Each one is a list of choices kept by position; the first choice is
 * the default, so a fresh install behaves as the launcher always did.
 */
final class Config {
    static final class Setting {
        final String key, label;
        final String[] choices;

        Setting(String key, String label, String... choices) {
            this.key = key;
            this.label = label;
            this.choices = choices;
        }
    }

    static final Setting WALLPAPER = new Setting("fondo", "Fondo", "Fotos de Salta", "Degradado");
    static final Setting PERIOD = new Setting("periodo", "Cambiar la foto", "Cada 20 minutos", "Cada hora", "Cada día");
    static final int[] PERIOD_MINUTES = {20, 60, 24 * 60};
    static final Setting CREDIT = new Setting("credito", "Crédito de la foto", "Visible", "Oculto");
    static final Setting FOOTBALL = new Setting("futbol", "Fútbol en vivo", "Sí", "No");
    static final Setting TEAM = new Setting("equipo", "Equipo favorito",
            "Boca", "River", "Racing", "Independiente", "San Lorenzo", "Ninguno");
    /** As ESPN writes them; null for none. */
    static final String[] TEAM_NAMES = {"Boca Juniors", "River Plate", "Racing", "Independiente", "San Lorenzo", null};
    static final Setting WEATHER = new Setting("clima", "Clima", "Sí", "No");
    static final Setting CITY = new Setting("ciudad", "Ciudad del clima",
            "Salta", "San Salvador de Jujuy", "San Miguel de Tucumán", "Córdoba", "Buenos Aires");
    static final double[][] CITY_COORDS = {
            {-24.7859, -65.4117}, {-24.1858, -65.2995}, {-26.8083, -65.2176},
            {-31.4201, -64.1888}, {-34.6037, -58.3816}};
    static final Setting MUSIC = new Setting("musica", "Reproduciendo ahora", "Sí", "No");
    static final Setting LINGER = new Setting("permanencia", "Último tema en pantalla",
            "1 hora", "2 horas", "30 minutos", "No");
    static final int[] LINGER_MINUTES = {60, 120, 30, 0};
    static final Setting CLOCK = new Setting("hora", "Formato de la hora", "24 horas", "12 horas");
    static final Setting AUTO_FAVORITES = new Setting("autofav", "Apps nuevas a favoritos", "Sí", "No");

    static final Setting[] ALL = {WALLPAPER, PERIOD, CREDIT, FOOTBALL, TEAM, WEATHER, CITY, MUSIC, LINGER,
            CLOCK, AUTO_FAVORITES};

    private final SharedPreferences prefs;

    Config(Context context) {
        prefs = context.getSharedPreferences("config", Context.MODE_PRIVATE);
    }

    int get(Setting s) {
        int i = prefs.getInt(s.key, 0);
        return i >= 0 && i < s.choices.length ? i : 0;
    }

    /** For yes/no settings, whose first choice is the "on" one. */
    boolean on(Setting s) {
        return get(s) == 0;
    }

    /** Moves to the next choice, back to the first after the last; returns it. */
    int next(Setting s) {
        int i = (get(s) + 1) % s.choices.length;
        prefs.edit().putInt(s.key, i).apply();
        return i;
    }

    String shown(Setting s) {
        return s.choices[get(s)];
    }
}
