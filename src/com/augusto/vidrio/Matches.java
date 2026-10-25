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
    /** The team that, when it plays, is the only match shown; set from the settings. */
    static volatile String favoriteTeam = "Boca Juniors";
    /** Argentine clubs, to spot them in the continental cups, where the league says nothing. */
    private static final String[] ARGENTINE_CLUBS = {
            "Boca Juniors", "River Plate", "Racing", "Independiente", "San Lorenzo", "Estudiantes",
            "Vélez", "Talleres", "Lanús", "Rosario Central", "Newell", "Argentinos", "Huracán",
            "Defensa y Justicia", "Godoy Cruz", "Belgrano", "Banfield", "Tigre", "Platense", "Unión",
            "Gimnasia", "Instituto", "Central Córdoba", "Atlético Tucumán", "Sarmiento", "Barracas",
            "Riestra", "Independiente Rivadavia", "San Martín", "Aldosivi"};
    /** A finished match stays a while: who turns the TV on after the whistle wants the result. */
    private static final long FINISHED_VISIBLE_MS = 3L * 60 * 60 * 1000;
    private static final long LATE_START_MS = 30L * 60 * 1000;
    static final int MAX = 12;

    static final class Side {
        final String id, name, abbr, logo;
        final String goals;

        Side(String id, String name, String abbr, String logo, String goals) {
            this.id = id;
            this.name = name;
            this.abbr = abbr;
            this.logo = logo;
            this.goals = goals;
        }
    }

    /** A goal or a red card, already on the side it counts for. */
    static final class Event {
        final boolean home, goal, penalty, ownGoal;
        final String player, minute;

        Event(boolean home, boolean goal, boolean penalty, boolean ownGoal, String player, String minute) {
            this.home = home;
            this.goal = goal;
            this.penalty = penalty;
            this.ownGoal = ownGoal;
            this.player = player;
            this.minute = minute;
        }
    }

    static final class Match {
        final String id, league, status;
        final Side home, away;
        final long start;
        final boolean live, finished, favorite;
        /** 0 for Boca, 1 for another Argentine team or Inter Miami, 2 for the rest. */
        int priority = 2;
        List<Event> events = new ArrayList<>();

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
                if (m != null && league[0].startsWith("arg.") && m.priority > 1) m.priority = 1;
                if (m == null || !seen.add(m.id)) continue;
                if (league[0].equals("usa.1") && !m.favorite) continue;
                // A kickoff that already passed without the match starting counts as now: late
                // starts are common, and the widget should keep asking every minute.
                long kickoff = m.start > now ? m.start : now - m.start < LATE_START_MS ? now : 0;
                if (!m.live && !m.finished && kickoff > 0 && (next == 0 || kickoff < next)) next = kickoff;
                boolean recent = m.finished && now - m.start < FINISHED_VISIBLE_MS;
                if (m.live || (m.favorite && (recent || isToday(m.start)))) all.add(m);
            }
        }
        // What is being played first, Boca and then the Argentine teams first among it.
        all.sort((a, b) -> {
            if (a.live != b.live) return a.live ? -1 : 1;
            if (a.priority != b.priority) return Integer.compare(a.priority, b.priority);
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
            int priority = 2;
            boolean started = !"pre".equals(state);
            JSONArray teams = c.getJSONArray("competitors");
            for (int i = 0; i < teams.length(); i++) {
                JSONObject t = teams.getJSONObject(i);
                JSONObject team = t.getJSONObject("team");
                String full = team.optString("displayName");
                for (String f : FAVORITE_TEAMS) if (full.contains(f)) favorite = true;
                String fav = favoriteTeam;
                if (fav != null && full.contains(fav)) priority = 0;
                else if (priority > 1 && (full.contains("Inter Miami") || isArgentine(full))) priority = 1;
                String shortName = team.optString("shortDisplayName", full).trim();
                Side s = new Side(team.optString("id"), shortName, team.optString("abbreviation", shortName), small(team.optString("logo")),
                        started ? t.optString("score", "") : "");
                if ("home".equals(t.optString("homeAway"))) home = s;
                else away = s;
            }
            if (home == null || away == null) return null;

            long start = parseDate(e.getString("date"));
            boolean live = "in".equals(state);
            boolean finished = "post".equals(state);
            String label;
            if (name.equals("STATUS_HALFTIME")) label = "ET";
            else if (live) label = status.optString("displayClock", "En vivo");
            else if (finished) label = "Final";
            else label = new SimpleDateFormat("HH:mm", Locale.US).format(new Date(start));
            Match m = new Match(e.getString("id"), league, home, away, start, live, finished, favorite, label);
            m.priority = priority;
            m.events = events(c.optJSONArray("details"), home.id, away.id);
            return m;
        } catch (Exception ex) {
            return null;
        }
    }

    private static boolean isArgentine(String team) {
        for (String club : ARGENTINE_CLUBS) if (team.contains(club)) return true;
        return false;
    }

    /**
     * Goals and red cards. ESPN files an own goal under the scorer's team; it counts for the
     * other one, so it goes on that side, as on Salta News.
     */
    private static List<Event> events(JSONArray details, String homeId, String awayId) {
        List<Event> list = new ArrayList<>();
        if (details == null) return list;
        for (int i = 0; i < details.length(); i++) {
            JSONObject d = details.optJSONObject(i);
            if (d == null || d.optBoolean("shootout")) continue;
            boolean goal = d.optBoolean("scoringPlay"), red = d.optBoolean("redCard");
            if (!goal && !red) continue;
            String team = d.optJSONObject("team") == null ? "" : d.optJSONObject("team").optString("id");
            boolean own = d.optBoolean("ownGoal");
            boolean home = goal && own ? team.equals(awayId) : team.equals(homeId);
            JSONArray who = d.optJSONArray("athletesInvolved");
            JSONObject a = who == null ? null : who.optJSONObject(0);
            String player = a == null ? "Sin dato" : a.optString("displayName", a.optString("shortName", "Sin dato"));
            String minute = d.optJSONObject("clock") == null ? "" : d.optJSONObject("clock").optString("displayValue");
            list.add(new Event(home, goal, d.optBoolean("penaltyKick"), own, player, minute));
        }
        return list;
    }

    /** ESPN's logos come at 500 px; its resizer hands out small ones. */
    private static String small(String logo) {
        String prefix = "https://a.espncdn.com/";
        if (!logo.startsWith(prefix)) return logo.isEmpty() ? null : logo;
        return prefix + "combiner/i?img=/" + logo.substring(prefix.length()) + "&h=144&w=144";
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
