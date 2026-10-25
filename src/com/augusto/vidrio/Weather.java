package com.augusto.vidrio;

import org.json.JSONObject;

/** The weather in Salta from Open-Meteo: free, no key. Call it off the main thread. */
final class Weather {
    private static final String URL = "https://api.open-meteo.com/v1/forecast"
            + "?latitude=-24.7859&longitude=-65.4117"
            + "&current=temperature_2m,weather_code,is_day"
            + "&daily=temperature_2m_max,temperature_2m_min"
            + "&timezone=America%2FArgentina%2FSalta&forecast_days=1";

    final int now, max, min;
    final String icon;

    private Weather(int now, int max, int min, String icon) {
        this.now = now;
        this.max = max;
        this.min = min;
        this.icon = icon;
    }

    static Weather fetch() throws Exception {
        JSONObject r = new JSONObject(Net.text(URL));
        JSONObject current = r.getJSONObject("current");
        JSONObject daily = r.getJSONObject("daily");
        return new Weather(
                (int) Math.round(current.getDouble("temperature_2m")),
                (int) Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0)),
                (int) Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0)),
                icon(current.getInt("weather_code"), current.optInt("is_day", 1) == 1));
    }

    /** WMO weather codes, as Open-Meteo sends them. */
    private static String icon(int code, boolean day) {
        if (code == 0) return day ? "☀️" : "🌙";
        if (code <= 2) return day ? "🌤️" : "☁️";
        if (code == 3) return "☁️";
        if (code <= 48) return "🌫️";
        if (code <= 67) return "🌧️";
        if (code <= 77) return "🌨️";
        if (code <= 82) return "🌦️";
        return "⛈️";
    }
}
