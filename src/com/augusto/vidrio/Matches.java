package com.augusto.vidrio;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/**
 * Football scores straight from ESPN's public scoreboard, the same source Salta News uses for
 * its live module. Asking ESPN directly costs Salta News nothing.
 *
 * Shown: everything being played right now in the leagues below, plus every Boca and Inter
 * Miami match of the day, whatever its state. Boca and Inter Miami go first.
 */
final class Matches {
    /** ESPN code and the name shown. The MLS only for Inter Miami, as on Salta News. */
    private static final String[][] LEAGUES = {
            {"arg.1", "Liga Profesional"}, {"arg.copa_lpf", "Copa de la Liga"}, {"arg.copa", "Copa Argentina"},
            {"conmebol.libertadores", "Libertadores"}, {"conmebol.sudamericana", "Sudamericana"},
            {"uefa.champions", "Champions"}, {"esp.1", "LaLiga"}, {"eng.1", "Premier League"},
            {"usa.1", "MLS"}};
    private static final String[] FAVORITE_TEAMS = {"Boca Juniors", "Inter Miami"};
    /** A finished match stays a while: who turns the TV on after the whistle wants the result. */
    private static final long FINISHED_VISIBLE_MS = 3L * 60 * 60 * 1000;
    static final int MAX = 5;

    static final class Side {
        final String name, logo;
        final String goals;

        Side(String name, String logo, String goals) {
            this.name = name;
            this.logo = logo;
            this.goals = goals;
        }
    }

    static final class Match {
        final String id, league, status;
        final Side home, away;
        final long start;
        final boolean live, finished, favorite;

        Match(String id, String league, Side home, Side away, long start, boolean live, boolean finished,
              boolean favorite, String status) {
            this.id = id;
            this.league = league;
            this.home = home;
            this.away = away;
            this.start = start;
            this.live = live;
            this.finished = finished;
            this.favorite = favorite;
            this.status = status;
        }
    }

    static final class Board {
        final List<Match> matches;
        /** The next kickoff among everything fetched, or 0: lets the widget sleep until then. */
        final long nextStart;

        Board(List<Match> matches, long nextStart) {
            this.matches = matches;
            this.nextStart = nextStart;
        }

        boolean anyLive() {
            for (Match m : matches) if (m.live) return true;
            return false;
        }
    }

    private Matches() {}

    /** Asks every league; one that fails is skipped. Call it off the main thread. */
    static Board fetch() {
        long now = System.currentTimeMillis();
        List<Match> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        long next = 0;
        for (String[] league : LEAGUES) {
            JSONArray events;
            try {
                events = new JSONObject(Net.text("https://site.api.espn.com/apis/site/v2/sports/soccer/"
                        + league[0] + "/scoreboard")).optJSONArray("events");
            } catch (Exception e) {
                continue;
            }
            if (events == null) continue;
            for (int i = 0; i < events.length(); i++) {
                Match m = read(events.optJSONObject(i), league[1]);
                if (m == null || !seen.add(m.id)) continue;
                if (league[0].equals("usa.1") && !m.favorite) continue;
                if (!m.live && !m.finished && m.start > now && (next == 0 || m.start < next)) next = m.start;
                boolean recent = m.finished && now - m.start < FINISHED_VISIBLE_MS;
                if (m.live || (m.favorite && (recent || isToday(m.start)))) all.add(m);
            }
        }
        all.sort((a, b) -> {
            if (a.favorite != b.favorite) return a.favorite ? -1 : 1;
            if (a.live != b.live) return a.live ? -1 : 1;
            return Long.compare(a.start, b.start);
        });
        return new Board(all.size() > MAX ? new ArrayList<>(all.subList(0, MAX)) : all, next);
    }

    private static Match read(JSONObject e, String league) {
        try {
            JSONObject c = e.getJSONArray("competitions").getJSONObject(0);
            JSONObject status = c.getJSONObject("status");
            JSONObject type = status.getJSONObject("type");
            String name = type.optString("name"), state = type.optString("state");
            if (name.contains("POSTPONED") || name.contains("CANCELED")) return null;

            Side home = null, away = null;
            boolean favorite = false;
            boolean started = !"pre".equals(state);
            JSONArray teams = c.getJSONArray("competitors");
            for (int i = 0; i < teams.length(); i++) {
                JSONObject t = teams.getJSONObject(i);
                JSONObject team = t.getJSONObject("team");
                String full = team.optString("displayName");
                for (String f : FAVORITE_TEAMS) if (full.contains(f)) favorite = true;
                Side s = new Side(team.optString("shortDisplayName", full).trim(), small(team.optString("logo")),
                        started ? t.optString("score", "") : "");
                if ("home".equals(t.optString("homeAway"))) home = s;
                else away = s;
            }
            if (home == null || away == null) return null;

            long start = parseDate(e.getString("date"));
            boolean live = "in".equals(state);
            boolean finished = "post".equals(state);
            String label;
            if (name.equals("STATUS_HALFTIME")) label = "Entretiempo";
            else if (live) label = status.optString("displayClock", "En vivo");
            else if (finished) label = "Final";
            else label = new SimpleDateFormat("HH:mm", Locale.US).format(new Date(start));
            return new Match(e.getString("id"), league, home, away, start, live, finished, favorite, label);
        } catch (Exception ex) {
            return null;
        }
    }

    /** ESPN's logos come at 500 px; its resizer hands out small ones. */
    private static String small(String logo) {
        String prefix = "https://a.espncdn.com/";
        if (!logo.startsWith(prefix)) return logo.isEmpty() ? null : logo;
        return prefix + "combiner/i?img=/" + logo.substring(prefix.length()) + "&h=72&w=72";
    }

    /** "2026-10-25T20:00Z" */
    private static long parseDate(String s) throws Exception {
        SimpleDateFormat f = new SimpleDateFormat(s.length() > 17 ? "yyyy-MM-dd'T'HH:mm:ss'Z'" : "yyyy-MM-dd'T'HH:mm'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.parse(s).getTime();
    }

    private static boolean isToday(long t) {
        Calendar a = Calendar.getInstance(), b = Calendar.getInstance();
        b.setTimeInMillis(t);
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }
}
