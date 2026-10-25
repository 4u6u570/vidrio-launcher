package com.augusto.vidrio;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The weather in a city, Salta unless set otherwise, from Open-Meteo: free, no key. Now and
 * the next seven days, in one request. Call it off the main thread.
 */
final class Weather {
    private static final String URL = "https://api.open-meteo.com/v1/forecast"
            + "?latitude=%s&longitude=%s"
            + "&current=temperature_2m,weather_code,is_day"
            + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"
            + "&timezone=auto&forecast_days=7";

    static final class Day {
        /** "2026-10-25" */
        final String date;
        final int max, min, rain;
        final String icon;

        Day(String date, int max, int min, int rain, String icon) {
            this.date = date;
            this.max = max;
            this.min = min;
            this.rain = rain;
            this.icon = icon;
        }
    }

    final int now;
    final String icon;
    final Day[] days;

    private Weather(int now, String icon, Day[] days) {
        this.now = now;
        this.icon = icon;
        this.days = days;
    }

    static Weather fetch(double lat, double lon) throws Exception {
        JSONObject r = new JSONObject(Net.text(String.format(java.util.Locale.US, URL, lat, lon)));
        JSONObject current = r.getJSONObject("current");
        JSONObject daily = r.getJSONObject("daily");
        JSONArray dates = daily.getJSONArray("time");
        Day[] days = new Day[dates.length()];
        for (int i = 0; i < days.length; i++) {
            days[i] = new Day(dates.getString(i),
                    round(daily.getJSONArray("temperature_2m_max"), i),
                    round(daily.getJSONArray("temperature_2m_min"), i),
                    daily.getJSONArray("precipitation_probability_max").optInt(i, 0),
                    icon(daily.getJSONArray("weather_code").optInt(i, 3), true));
        }
        return new Weather((int) Math.round(current.getDouble("temperature_2m")),
                icon(current.getInt("weather_code"), current.optInt("is_day", 1) == 1), days);
    }

    private static int round(JSONArray a, int i) {
        return (int) Math.round(a.optDouble(i, 0));
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
