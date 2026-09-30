package com.gpsmhn.tools;

import android.content.Context;
import android.content.res.AssetManager;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Dữ liệu Pokémon phục vụ máy tính IV (chuyển từ AnyTo).
 *
 * <p>Nguồn dữ liệu nằm trong {@code assets/tools/pokemon}. Bản gốc AnyTo đọc file nhị phân plist;
 * ở đây đã chuyển sẵn sang JSON (cpm.json, powerup_costs.json) để không phải kéo thêm thư viện.
 *
 * <p>Bảng base stat dùng đúng cách ghép của AnyTo: đọc {@code poke_list.txt} trước (dữ liệu cũ,
 * lấy ô số 3/4/5 làm stamina/attack/defense), rồi {@code basestats_11_14_18.txt} và
 * {@code basestats_12_13_18.txt} ghi đè lại theo từng form. Nhờ vậy kết quả IV trùng với AnyTo.
 */
public final class PokemonData {

    /** Base stat của 1 form (stamina = máu gốc). */
    public static final class BaseStat {
        public final int stamina, attack, defense;
        public BaseStat(int stamina, int attack, int defense) {
            this.stamina = stamina;
            this.attack = attack;
            this.defense = defense;
        }
        @Override public String toString() { return stamina + "/" + attack + "/" + defense; }
    }

    /** Một loài Pokémon + các form. */
    public static final class Species {
        public int id;
        public String en = "";
        public final List<String> names = new ArrayList<>();
        public final List<String> types = new ArrayList<>();
        public final LinkedHashMap<String, BaseStat> forms = new LinkedHashMap<>();
        public int evolveCandy;
        public final List<String> evolvesTo = new ArrayList<>();

        public BaseStat defaultStat() {
            for (BaseStat s : forms.values()) return s;   // form đầu tiên = form gốc
            return null;
        }
        public String displayName() {
            if (en != null && !en.isEmpty()) return en;
            for (String n : names) if (n != null && !n.isEmpty()) return n;
            return "#" + id;
        }
        @Override public String toString() { return displayName(); }
    }

    /** Một mốc lên cấp: tốn bao nhiêu bụi/kẹo. */
    public static final class PowerUp {
        public double level;
        public int stardust, candy, xlCandy;
        public int totalStardust, totalCandy, totalXlCandy, totalPowerUps;
    }

    private static volatile PokemonData instance;

    private final List<Species> species = new ArrayList<>();
    private final Map<Integer, Species> byId = new HashMap<>();
    private final LinkedHashMap<Double, Double> cpm = new LinkedHashMap<>();
    private final List<PowerUp> powerUps = new ArrayList<>();

    public static PokemonData get(Context context) {
        PokemonData local = instance;
        if (local == null) {
            synchronized (PokemonData.class) {
                local = instance;
                if (local == null) {
                    local = new PokemonData(context.getApplicationContext());
                    instance = local;
                }
            }
        }
        return local;
    }

    private PokemonData(Context context) {
        AssetManager am = context.getAssets();
        try {
            Map<Integer, List<String>> translations = parseTranslations(am, "tools/pokemon/names_translation.txt");
            parsePokemonList(am, "tools/pokemon/poke_list.txt", translations);
            applyBaseStats(am, "tools/pokemon/basestats_11_14_18.txt");
            applyBaseStats(am, "tools/pokemon/basestats_12_13_18.txt");
            parseEvolutions(am, "tools/pokemon/evolve_data.txt");
            parseCpm(am, "tools/pokemon/cpm.json");
            parsePowerUps(am, "tools/pokemon/powerup_costs.json");
        } catch (Exception e) {
            throw new RuntimeException("Không nạp được dữ liệu Pokémon", e);
        }
        for (Species s : species) byId.put(s.id, s);
    }

    // ------------------------------------------------------------------ lookups

    public List<Species> all() { return Collections.unmodifiableList(species); }

    public Species byId(int id) { return byId.get(id); }

    public Species byName(String name) {
        if (name == null || name.isEmpty()) return null;
        String needle = name.trim().toLowerCase(Locale.ROOT);
        for (Species s : species) {
            if (s.en.equalsIgnoreCase(needle)) return s;
            for (String n : s.names) if (n != null && n.equalsIgnoreCase(needle)) return s;
        }
        return null;
    }

    /** Khớp tên gần đúng (dùng cho kết quả OCR). Trả về null nếu không đủ giống. */
    public Species fuzzyName(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        String needle = text.trim().toLowerCase(Locale.ROOT);
        Species best = null;
        double bestScore = 0;
        for (Species s : species) {
            double d = similarity(s.en.toLowerCase(Locale.ROOT), needle);
            if (d > bestScore) { bestScore = d; best = s; }
            for (String n : s.names) {
                if (n == null || n.isEmpty()) continue;
                double dn = similarity(n.toLowerCase(Locale.ROOT), needle);
                if (dn > bestScore) { bestScore = dn; best = s; }
            }
        }
        return bestScore >= 0.62 ? best : null;
    }

    /** CPM theo cấp (bước 0.5). Trả về -1 nếu không có dữ liệu. */
    public double cpmForLevel(double level) {
        Double v = cpm.get(level);
        return v == null ? -1 : v;
    }

    public List<Double> levels() { return new ArrayList<>(cpm.keySet()); }

    public double maxLevel() {
        double max = 0;
        for (Double d : cpm.keySet()) if (d > max) max = d;
        return max;
    }

    /** Các cấp có thể ứng với mức bụi cho trước (nút Power Up hiển thị). */
    public List<Double> levelsForStardust(int stardust) {
        List<Double> out = new ArrayList<>();
        for (PowerUp p : powerUps) if (p.stardust == stardust) out.add(p.level);
        return out;
    }

    public List<PowerUp> powerUps() { return Collections.unmodifiableList(powerUps); }

    // ------------------------------------------------------------------ parsing

    private static Map<Integer, List<String>> parseTranslations(AssetManager am, String path) throws Exception {
        Map<Integer, List<String>> map = new HashMap<>();
        try (BufferedReader r = reader(am, path)) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split(",", 7);
                if (parts.length < 7) continue;
                try {
                    int id = Integer.parseInt(parts[0].trim());
                    List<String> names = new ArrayList<>();
                    for (int i = 1; i < parts.length; i++) {
                        String n = parts[i].trim();
                        if (!n.isEmpty()) names.add(n);
                    }
                    map.put(id, names);
                } catch (Exception ignored) { }
            }
        }
        return map;
    }

    private void parsePokemonList(AssetManager am, String path, Map<Integer, List<String>> translations) throws Exception {
        try (BufferedReader r = reader(am, path)) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] p = line.split(",", -1);
                if (p.length < 7) continue;
                try {
                    int id = Integer.parseInt(p[0].trim());
                    Species s = byId.get(id);
                    if (s == null) {
                        s = new Species();
                        s.id = id;
                        s.en = p[1].trim();
                        List<String> tr = translations.get(id);
                        if (tr != null) s.names.addAll(tr);
                        byId.put(id, s);
                        species.add(s);
                    }
                    String form = p[2].trim();
                    if (form.isEmpty()) form = "Normal";
                    int stamina = intOr(p[3], 0), attack = intOr(p[4], 0), defense = intOr(p[5], 0);
                    s.forms.put(form, new BaseStat(stamina, attack, defense));
                    if (p.length > 6 && !p[6].trim().isEmpty()) s.types.add(p[6].trim());
                    if (p.length > 7 && !p[7].trim().isEmpty()) s.types.add(p[7].trim());
                } catch (Exception ignored) { }
            }
        }
    }

    /** Đọc file base stat dạng {@code id,name,stamina,attack,defense[,form]} và ghi đè form. */
    private void applyBaseStats(AssetManager am, String path) throws Exception {
        try (BufferedReader r = reader(am, path)) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] p = line.split(",", -1);
                if (p.length < 5) continue;
                try {
                    int id = Integer.parseInt(p[0].trim());
                    Species s = byId.get(id);
                    if (s == null) continue;
                    String form = p.length >= 6 && !p[5].trim().isEmpty() ? p[5].trim() : "Normal";
                    s.forms.put(form, new BaseStat(intOr(p[2], 0), intOr(p[3], 0), intOr(p[4], 0)));
                } catch (Exception ignored) { }
            }
        }
    }

    private void parseEvolutions(AssetManager am, String path) throws Exception {
        try (BufferedReader r = reader(am, path)) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] p = line.split(",", -1);
                if (p.length < 4) continue;
                String from = p[1].trim();
                String targets = p[2].trim();
                if (targets.isEmpty() || targets.equals("0")) continue;
                Species s = byName(from);
                if (s == null) continue;
                s.evolveCandy = intOr(p[3], 0);
                for (String t : targets.split(";")) if (!t.trim().isEmpty()) s.evolvesTo.add(t.trim());
            }
        }
    }

    private void parseCpm(AssetManager am, String path) throws Exception {
        JSONArray arr = new JSONArray(readAll(am, path));
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            cpm.put(o.optDouble("level"), o.optDouble("cpm"));
        }
    }

    private void parsePowerUps(AssetManager am, String path) throws Exception {
        JSONArray arr = new JSONArray(readAll(am, path));
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            PowerUp p = new PowerUp();
            p.level = o.optDouble("Level");
            p.stardust = o.optInt("Stardust");
            p.candy = o.optInt("Candy");
            p.xlCandy = o.optInt("XLCandy");
            p.totalStardust = o.optInt("TotalStardust");
            p.totalCandy = o.optInt("TotalCandy");
            p.totalXlCandy = o.optInt("TotalXLCandy");
            p.totalPowerUps = o.optInt("TotalPowerUps");
            powerUps.add(p);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static BufferedReader reader(AssetManager am, String path) throws Exception {
        InputStream in = am.open(path);
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    private static String readAll(AssetManager am, String path) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = reader(am, path)) {
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) != -1) sb.append(buf, 0, n);
        }
        return sb.toString();
    }

    private static int intOr(String s, int fallback) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return fallback; }
    }

    // Levenshtein -> độ giống 0..1 (giống DataManager.similarityBetween của AnyTo).
    private static double similarity(String a, String b) {
        if (a == null || b == null) return 0;
        int la = a.length(), lb = b.length();
        if (la == 0 || lb == 0) return la == lb ? 1 : 0;
        int[] prev = new int[lb + 1], cur = new int[lb + 1];
        for (int j = 0; j <= lb; j++) prev[j] = j;
        for (int i = 1; i <= la; i++) {
            cur[0] = i;
            for (int j = 1; j <= lb; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + cost);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        int max = Math.max(la, lb);
        return 1.0 - (prev[lb] / (double) max);
    }
}
