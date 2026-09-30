package com.gpsmhn.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Máy tính IV / CP / PVP (công thức chuyển nguyên từ AnyTo {@code ScanResultObject}).
 *
 * <p>CP  = max(10, floor( cpm^2 * (sqrt(sta+ivS) * (sqrt(def+ivD)*defMul) * (atk+ivA)*atkMul) / 10 ))
 * <br>HP  = floor((sta + ivS) * cpm)
 * <br>Shadow: atkMul = 1.2, defMul = 0.833 (còn lại 1.0).
 * <br>Stat product (xếp hạng PVP) = (atk+ivA)*cpm * (def+ivD)*cpm * floor((sta+ivS)*cpm).
 */
public final class IvCalculator {

    public static final int CP_LITTLE = 500;
    public static final int CP_GREAT = 1500;
    public static final int CP_ULTRA = 2500;

    /** Một tổ hợp IV tìm được. */
    public static final class IvCombo implements Comparable<IvCombo> {
        public int attackIV, defenseIV, staminaIV, cp, hp;
        public double level, cpm, perfection, statProduct;

        public String shortForm() {
            return attackIV + "/" + defenseIV + "/" + staminaIV;
        }
        public double percent() { return (attackIV + defenseIV + staminaIV) / 45.0 * 100.0; }

        @Override public int compareTo(IvCombo o) { return Double.compare(o.statProduct, statProduct); }
        @Override public String toString() {
            return String.format(java.util.Locale.US, "L%.1f  %d/%d/%d  CP%d  %.1f%%",
                    level, attackIV, defenseIV, staminaIV, cp, percent());
        }
    }

    /** Kết quả xếp hạng PVP của 1 loài cho 1 hạng cân. */
    public static final class RankEntry {
        public int place;              // thứ hạng (1 = tốt nhất)
        public IvCombo combo;
        public int total;              // tổng số tổ hợp được xếp
    }

    private final PokemonData data;

    public IvCalculator(PokemonData data) { this.data = data; }

    // ------------------------------------------------------------------ core math

    public static double attackMul(boolean shadow) { return shadow ? 1.2 : 1.0; }
    public static double defenseMul(boolean shadow) { return shadow ? 0.833 : 1.0; }

    public static int cp(PokemonData.BaseStat base, double cpm, int ivA, int ivD, int ivS, boolean shadow) {
        double a = (base.attack + ivA) * attackMul(shadow);
        double d = (base.defense + ivD) * defenseMul(shadow);
        double s = base.stamina + ivS;
        int cp = (int) Math.floor((cpm * cpm) * (Math.sqrt(s) * (Math.sqrt(d) * a)) / 10.0);
        return Math.max(10, cp);
    }

    public static int hp(PokemonData.BaseStat base, double cpm, int ivS) {
        return (int) Math.floor((base.stamina + ivS) * cpm);
    }

    public static double statProduct(PokemonData.BaseStat base, double cpm, int ivA, int ivD, int ivS) {
        return (base.attack + ivA) * cpm * (base.defense + ivD) * cpm * Math.floor((base.stamina + ivS) * cpm);
    }

    // ------------------------------------------------------------------ searches

    /**
     * Tìm mọi tổ hợp IV khớp CP + HP, với cấp suy ra từ mức bụi (nút Power Up).
     * Giống {@code ScanResultObject.estimateIVsForStardust} của AnyTo.
     */
    public List<IvCombo> searchFromStardust(PokemonData.Species sp, int cp, int hp, int stardust, boolean shadow) {
        PokemonData.BaseStat base = sp.defaultStat();
        List<IvCombo> out = new ArrayList<>();
        if (base == null || cp <= 0 || hp <= 0) return out;
        for (double level : data.levelsForStardust(stardust)) {
            double cpm = data.cpmForLevel(level);
            if (cpm < 0) continue;
            for (int ivS = 0; ivS <= 15; ivS++) {
                if (hp(base, cpm, ivS) != hp) continue;
                for (int ivA = 0; ivA <= 15; ivA++) {
                    for (int ivD = 0; ivD <= 15; ivD++) {
                        if (cp(base, cpm, ivA, ivD, ivS, shadow) == cp) {
                            out.add(make(base, cpm, level, ivA, ivD, ivS, shadow));
                        }
                    }
                }
            }
        }
        Collections.sort(out);
        return out;
    }

    /** Mọi tổ hợp IV khớp CP + HP ở đúng một cấp đã biết. */
    public List<IvCombo> searchAtLevel(PokemonData.Species sp, int cp, int hp, double level, boolean shadow) {
        PokemonData.BaseStat base = sp.defaultStat();
        List<IvCombo> out = new ArrayList<>();
        if (base == null) return out;
        double cpm = data.cpmForLevel(level);
        if (cpm < 0) return out;
        for (int ivS = 0; ivS <= 15; ivS++) {
            if (hp > 0 && hp(base, cpm, ivS) != hp) continue;
            for (int ivA = 0; ivA <= 15; ivA++) {
                for (int ivD = 0; ivD <= 15; ivD++) {
                    if (cp <= 0 || cp(base, cpm, ivA, ivD, ivS, shadow) == cp) {
                        out.add(make(base, cpm, level, ivA, ivD, ivS, shadow));
                    }
                }
            }
        }
        Collections.sort(out);
        return out;
    }

    /** Toàn bộ 4096 tổ hợp ở 1 cấp, sắp theo stat product. */
    public List<IvCombo> allCombosAtLevel(PokemonData.Species sp, double level, boolean shadow) {
        return searchAtLevel(sp, 0, 0, level, shadow);
    }

    /**
     * Xếp hạng PVP: với mỗi tổ hợp IV, chọn cấp cao nhất mà CP không vượt trần, rồi xếp theo
     * stat product giảm dần. {@code maxLevel} giới hạn cấp (thường 50 hoặc 51).
     */
    public List<RankEntry> rank(PokemonData.Species sp, int cpCap, double maxLevel, boolean shadow, int limit) {
        PokemonData.BaseStat base = sp.defaultStat();
        List<IvCombo> combos = new ArrayList<>();
        if (base == null) return new ArrayList<>();
        List<Double> levels = data.levels();
        for (int ivA = 0; ivA <= 15; ivA++) {
            for (int ivD = 0; ivD <= 15; ivD++) {
                for (int ivS = 0; ivS <= 15; ivS++) {
                    double bestLevel = -1, bestCpm = -1;
                    for (double level : levels) {
                        if (level > maxLevel) continue;
                        double cpm = data.cpmForLevel(level);
                        if (cpm < 0) continue;
                        if (cpCap > 0 && cp(base, cpm, ivA, ivD, ivS, shadow) > cpCap) break;
                        bestLevel = level; bestCpm = cpm;
                    }
                    if (bestLevel < 0) continue;
                    combos.add(make(base, bestCpm, bestLevel, ivA, ivD, ivS, shadow));
                }
            }
        }
        Collections.sort(combos);
        List<RankEntry> out = new ArrayList<>();
        int n = limit > 0 ? Math.min(limit, combos.size()) : combos.size();
        for (int i = 0; i < n; i++) {
            RankEntry e = new RankEntry();
            e.place = i + 1;
            e.combo = combos.get(i);
            e.total = combos.size();
            out.add(e);
        }
        return out;
    }

    /** Thứ hạng của đúng 1 tổ hợp trong hạng cân (0 nếu không nằm trong bảng). */
    public int placeOf(List<RankEntry> ranked, int ivA, int ivD, int ivS) {
        for (RankEntry e : ranked) {
            if (e.combo.attackIV == ivA && e.combo.defenseIV == ivD && e.combo.staminaIV == ivS) return e.place;
        }
        return 0;
    }

    private IvCombo make(PokemonData.BaseStat base, double cpm, double level, int ivA, int ivD, int ivS, boolean shadow) {
        IvCombo c = new IvCombo();
        c.attackIV = ivA; c.defenseIV = ivD; c.staminaIV = ivS;
        c.level = level;
        c.cpm = cpm;
        c.cp = cp(base, cpm, ivA, ivD, ivS, shadow);
        c.hp = hp(base, cpm, ivS);
        c.perfection = (ivA + ivD + ivS) / 45.0 * 100.0;
        c.statProduct = statProduct(base, cpm, ivA, ivD, ivS);
        return c;
    }
}
