package com.rightnow.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Saves the routine on the phone and works out what's happening at any moment. */
public final class Store {
    private Store() {}

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("rn", Context.MODE_PRIVATE);
    }

    static String getState(Context c) { return prefs(c).getString("state", ""); }
    static void saveState(Context c, String json) { prefs(c).edit().putString("state", json).apply(); }

    static String getDone(Context c, String day) { return prefs(c).getString("done-" + day, "[]"); }
    static void setDone(Context c, String day, String json) { prefs(c).edit().putString("done-" + day, json).apply(); }

    static Set<String> doneSet(Context c, String day) {
        Set<String> out = new HashSet<>();
        try {
            JSONArray a = new JSONArray(getDone(c, day));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (Exception ignored) {}
        return out;
    }

    static void toggleDone(Context c, String day, String id) {
        try {
            JSONArray a = new JSONArray(getDone(c, day));
            JSONArray b = new JSONArray();
            boolean found = false;
            for (int i = 0; i < a.length(); i++) {
                if (a.getString(i).equals(id)) found = true; else b.put(a.getString(i));
            }
            if (!found) b.put(id);
            setDone(c, day, b.toString());
        } catch (Exception ignored) {}
    }

    static final class Block {
        String id, name, tip, color;
        int s, e;
        boolean alert, check, important;
    }

    static int toMin(String t) {
        String[] p = t.split(":");
        return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
    }

    static boolean clock24(Context c) {
        try { return "24".equals(new JSONObject(getState(c)).getJSONObject("settings").optString("clock")); }
        catch (Exception e) { return false; }
    }

    static String fmt(int mins, boolean h24) {
        int h = (mins / 60) % 24, m = mins % 60;
        if (h24) return String.format("%02d:%02d", h, m);
        String ap = h >= 12 ? "PM" : "AM";
        int hh = h % 12 == 0 ? 12 : h % 12;
        return String.format("%d:%02d %s", hh, m, ap);
    }

    /** JavaScript day numbers: Sunday = 0 ... Saturday = 6 */
    static int jsDay(LocalDate d) { return d.getDayOfWeek().getValue() % 7; }

    static List<Block> blocksFor(Context c, LocalDate d) {
        List<Block> out = new ArrayList<>();
        try {
            JSONObject st = new JSONObject(getState(c));
            JSONArray scheds = st.getJSONArray("schedules");
            int day = jsDay(d);
            for (int i = 0; i < scheds.length(); i++) {
                JSONObject sc = scheds.getJSONObject(i);
                JSONArray days = sc.optJSONArray("days");
                boolean match = false;
                if (days != null) for (int k = 0; k < days.length(); k++) if (days.optInt(k, -1) == day) match = true;
                if (!match) continue;
                JSONArray bl = sc.getJSONArray("blocks");
                for (int j = 0; j < bl.length(); j++) {
                    JSONObject o = bl.getJSONObject(j);
                    Block b = new Block();
                    b.id = o.optString("id");
                    b.name = o.optString("name");
                    b.tip = o.optString("tip");
                    b.color = o.optString("color", "teal");
                    b.s = toMin(o.getString("start"));
                    b.e = toMin(o.getString("end"));
                    b.alert = o.optBoolean("alert");
                    b.check = o.optBoolean("check", true);
                    b.important = o.optBoolean("important");
                    out.add(b);
                }
                break;
            }
        } catch (Exception ignored) {}
        Collections.sort(out, (a, b) -> Integer.compare(a.s, b.s));
        return out;
    }

    static int color(String name) {
        switch (name == null ? "" : name) {
            case "gray": return Color.parseColor("#9ba7b6");
            case "blue": return Color.parseColor("#6d96f2");
            case "orange": return Color.parseColor("#f0a446");
            case "purple": return Color.parseColor("#a983f2");
            case "red": return Color.parseColor("#f2695e");
            case "green": return Color.parseColor("#4dc476");
            case "indigo": return Color.parseColor("#8f9ad0");
            case "pink": return Color.parseColor("#ee7eab");
            default: return Color.parseColor("#2db5a5");
        }
    }

    /** Everything the widget shows. */
    static final class Now {
        String label = "RIGHT NOW", name = "Nothing planned", range = "", next = "", count = "";
        int color = Color.parseColor("#4dc476");
        String doneId = null;
        boolean isDone = false;
        int done = 0, total = 0;
    }

    static Now now(Context c) {
        Now n = new Now();
        boolean h24 = clock24(c);
        LocalDate today = LocalDate.now();
        LocalTime t = LocalTime.now();
        int mins = t.getHour() * 60 + t.getMinute();
        List<Block> rows = blocksFor(c, today);
        Set<String> done = doneSet(c, today.toString());

        for (Block b : rows) if (b.check) { n.total++; if (done.contains(b.id)) n.done++; }
        if (n.total > 0) n.count = n.done + "/" + n.total + " done";

        if (rows.isEmpty()) {
            n.label = "FREE DAY";
            n.range = "No schedule for today";
            return n;
        }
        int cur = -1;
        for (int i = 0; i < rows.size(); i++) if (mins >= rows.get(i).s && mins < rows.get(i).e) { cur = i; break; }

        if (cur >= 0) {
            Block b = rows.get(cur);
            n.name = b.name;
            n.color = color(b.color);
            n.range = fmt(b.s, h24) + " – " + fmt(b.e, h24) + "  ·  " + left(b.e - mins) + " left";
            if (b.check) { n.doneId = b.id; n.isDone = done.contains(b.id); }
            n.next = cur + 1 < rows.size() ? nextText(rows.get(cur + 1), h24) : tomorrowText(c, today, h24);
        } else if (mins < rows.get(0).s) {
            n.label = "SLEEP";
            n.name = "Sleep";
            n.color = color("indigo");
            n.range = "Your day starts at " + fmt(rows.get(0).s, h24);
            n.next = nextText(rows.get(0), h24);
        } else if (mins >= rows.get(rows.size() - 1).e) {
            n.label = "SLEEP";
            n.name = "Sleep";
            n.color = color("indigo");
            n.range = "Done for today";
            n.next = tomorrowText(c, today, h24);
        } else {
            Block nx = null;
            for (Block b : rows) if (b.s > mins) { nx = b; break; }
            n.label = "OPEN TIME";
            n.name = "Open time";
            n.color = color("gray");
            n.range = nx != null ? "Until " + fmt(nx.s, h24) : "";
            n.next = nx != null ? nextText(nx, h24) : "";
        }
        return n;
    }

    static String left(int m) {
        if (m >= 60) return (m / 60) + " hr " + (m % 60) + " min";
        return m + " min";
    }

    static String nextText(Block b, boolean h24) { return "Next: " + b.name + " · " + fmt(b.s, h24); }

    static String tomorrowText(Context c, LocalDate today, boolean h24) {
        List<Block> tm = blocksFor(c, today.plusDays(1));
        if (tm.isEmpty()) return "";
        return "Tomorrow: " + tm.get(0).name + " · " + fmt(tm.get(0).s, h24);
    }
}
