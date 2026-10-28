package com.moneytorch.service;

import com.moneytorch.db.Item;
import com.moneytorch.db.PriceRepository;
import com.moneytorch.source.PricePoint;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Buy-low / sell-high finder. For every item the price history is resampled to an hourly UTC grid and every
 * "buy at hour B, sell at hour S after H days" strategy is backtested. The best strategy is picked on the
 * earlier (train) days only and then checked on the most recent (test) days it has never seen.
 */
@Service
public class FlipService {
    private static final long HOUR = 3_600_000L;
    private static final long DAY = 24 * HOUR;
    private static final long TTL_MS = 10 * 60_000L;
    private static final int MIN_TRAIN_DAYS = 3;

    public record Row(String id, String name, String nameRu, String main, String mainLabel, String subLabel,
                      double price, int buyHour, int sellHour, int hold,
                      double buyPrice, double sellPrice, double profitPerUnit,
                      double trainAvg, double trainWin, int trainDays,
                      Double testAvg, Double testWin, int testDays,
                      double worst, double score, double volatility, double dailyRange, double trend,
                      double changesPerDay, double[] returns) {
    }

    public record Result(int seasonId, String source, int days, int maxHold, double tax, Long refTs, List<Row> rows) {
    }

    private record Cached(long at, Result value) {
    }

    private record Strategy(int buyHour, int sellHour, int hold, double[] returns, int[] buyDays) {
    }

    private final PriceRepository repo;
    private final CatalogService catalog;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public FlipService(PriceRepository repo, CatalogService catalog) {
        this.repo = repo;
        this.catalog = catalog;
    }

    /**
     * @param days    lookback window in days
     * @param minHold 0 = same day allowed, 1 = sell no earlier than next day
     * @param maxHold longest holding period in days
     * @param tax     sale tax as a fraction, 0.125 = one of every eight units
     */
    public Result get(int seasonId, String source, int days, int minHold, int maxHold, double tax) {
        String key = seasonId + "|" + source + "|" + days + "|" + minHold + "|" + maxHold + "|" + tax;
        Cached c = cache.get(key);
        if (c != null && System.currentTimeMillis() - c.at() < TTL_MS) {
            return c.value();
        }
        Result fresh = compute(seasonId, source, days, minHold, maxHold, tax);
        cache.put(key, new Cached(System.currentTimeMillis(), fresh));
        return fresh;
    }

    private Result compute(int seasonId, String source, int days, int minHold, int maxHold, double tax) {
        Long ref = repo.maxTs(seasonId, source);
        if (ref == null) {
            return new Result(seasonId, source, days, maxHold, tax, null, List.of());
        }
        // grid covers whole UTC days, the last one being today; points from before the window carry the opening price in
        long day0 = Math.floorDiv(ref, DAY) * DAY - (long) (days - 1) * DAY;
        Map<String, List<PricePoint>> byItem = new HashMap<>();
        for (PricePoint p : repo.pointsSince(seasonId, source, day0 - 3 * DAY)) {
            byItem.computeIfAbsent(p.itemId(), k -> new ArrayList<>()).add(p);
        }
        Map<String, Item> items = catalog.items();
        List<Row> rows = new ArrayList<>();
        byItem.forEach((id, pts) -> {
            Row row = analyse(id, items.get(id), pts, day0, days, ref, minHold, maxHold, tax);
            if (row != null) {
                rows.add(row);
            }
        });
        rows.sort(Comparator.comparingDouble(Row::score).reversed());
        return new Result(seasonId, source, days, maxHold, tax, ref, rows);
    }

    private static Row analyse(String id, Item item, List<PricePoint> pts, long day0, int days, long ref,
                               int minHold, int maxHold, double tax) {
        if (pts.get(pts.size() - 1).ts() < day0) {
            return null; // no trading inside the window
        }
        Double[][] grid = grid(pts, day0, days, ref);

        Strategy best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int hold = minHold; hold <= maxHold; hold++) {
            for (int b = 0; b < 24; b++) {
                for (int s = 0; s < 24; s++) {
                    if (hold == 0 && s <= b) {
                        continue;
                    }
                    Strategy st = backtest(grid, b, s, hold, tax);
                    int train = trainCount(st.returns().length);
                    if (train < MIN_TRAIN_DAYS) {
                        continue;
                    }
                    double[] tr = Arrays.copyOf(st.returns(), train);
                    // risk adjusted: a steady small edge beats one lucky spike
                    double score = mean(tr) - 0.5 * std(tr);
                    if (score > bestScore) {
                        bestScore = score;
                        best = st;
                    }
                }
            }
        }
        if (best == null) {
            return null;
        }

        double[] r = best.returns();
        int train = trainCount(r.length);
        double[] tr = Arrays.copyOf(r, train);
        double[] te = Arrays.copyOfRange(r, train, r.length);
        Double testAvg = te.length > 0 ? mean(te) * 100 : null;
        Double testWin = te.length > 0 ? winRate(te) * 100 : null;
        double trainAvg = mean(tr) * 100;

        // typical prices: medians over the test days (or all days when there are none)
        int from = te.length > 0 ? train : 0;
        List<Double> buys = new ArrayList<>();
        List<Double> sells = new ArrayList<>();
        for (int i = from; i < r.length; i++) {
            int d = best.buyDays()[i];
            buys.add(grid[d][best.buyHour()]);
            sells.add(grid[d + best.hold()][best.sellHour()]);
        }
        double buyPrice = median(buys);
        double sellPrice = median(sells);

        double[] closes = dailyCloses(grid);
        double last = pts.get(pts.size() - 1).price();
        double first = firstNonNull(grid);

        return new Row(id,
                item != null ? item.nameEn() : "#" + id,
                item != null ? item.nameRu() : null,
                item != null ? item.main() : "unknown",
                item != null ? item.mainLabel() : "Unknown",
                item != null ? item.subLabel() : null,
                last, best.buyHour(), best.sellHour(), best.hold(),
                buyPrice, sellPrice, sellPrice * (1 - tax) - buyPrice,
                trainAvg, winRate(tr) * 100, tr.length,
                testAvg, testWin, te.length,
                Arrays.stream(r).min().orElse(0) * 100,
                testAvg != null ? Math.min(trainAvg, testAvg) : trainAvg,
                std(dailyReturns(closes)) * 100,
                dailyRange(pts, day0, ref) * 100,
                first > 0 ? (last - first) / first * 100 : 0,
                changesPerDay(pts, day0, ref),
                Arrays.stream(r).map(x -> x * 100).toArray());
    }

    /** grid[day][hour] = last known price at that hour (UTC), null before the first point or after ref. */
    private static Double[][] grid(List<PricePoint> pts, long day0, int days, long ref) {
        Double[][] g = new Double[days][24];
        int i = 0;
        Double value = null;
        for (int d = 0; d < days; d++) {
            for (int h = 0; h < 24; h++) {
                long t = day0 + d * DAY + h * HOUR;
                if (t > ref) {
                    return g;
                }
                while (i < pts.size() && pts.get(i).ts() <= t) {
                    value = pts.get(i++).price();
                }
                g[d][h] = value;
            }
        }
        return g;
    }

    private static Strategy backtest(Double[][] g, int b, int s, int hold, double tax) {
        double[] out = new double[g.length];
        int[] buyDays = new int[g.length];
        int n = 0;
        for (int d = 0; d + hold < g.length; d++) {
            Double buy = g[d][b];
            Double sell = g[d + hold][s];
            if (buy != null && sell != null && buy > 0) {
                buyDays[n] = d;
                out[n++] = sell * (1 - tax) / buy - 1;
            }
        }
        return new Strategy(b, s, hold, Arrays.copyOf(out, n), Arrays.copyOf(buyDays, n));
    }

    /** The newest third of the trades (at least 2) is held out as the test set. */
    private static int trainCount(int n) {
        int test = Math.max(2, Math.round(n / 3f));
        return Math.max(0, n - test);
    }

    private static double[] dailyCloses(Double[][] g) {
        List<Double> out = new ArrayList<>();
        for (Double[] day : g) {
            for (int h = 23; h >= 0; h--) {
                if (day[h] != null) {
                    out.add(day[h]);
                    break;
                }
            }
        }
        return out.stream().mapToDouble(Double::doubleValue).toArray();
    }

    private static double[] dailyReturns(double[] closes) {
        double[] out = new double[Math.max(0, closes.length - 1)];
        for (int i = 1; i < closes.length; i++) {
            out[i - 1] = closes[i] / closes[i - 1] - 1;
        }
        return out;
    }

    /** Average (max - min) / min within a UTC day, from raw points. */
    private static double dailyRange(List<PricePoint> pts, long day0, long ref) {
        Map<Long, double[]> byDay = new HashMap<>();
        for (PricePoint p : pts) {
            if (p.ts() < day0 || p.ts() > ref) {
                continue;
            }
            byDay.merge(Math.floorDiv(p.ts(), DAY), new double[]{p.price(), p.price()},
                    (a, b) -> new double[]{Math.min(a[0], b[0]), Math.max(a[1], b[1])});
        }
        return byDay.values().stream().filter(mm -> mm[0] > 0).mapToDouble(mm -> (mm[1] - mm[0]) / mm[0])
                .average().orElse(0);
    }

    /** How often the price actually moves, a rough proxy for how actively the item trades. */
    private static double changesPerDay(List<PricePoint> pts, long day0, long ref) {
        int changes = 0;
        for (int i = 1; i < pts.size(); i++) {
            if (pts.get(i).ts() >= day0 && pts.get(i).price() != pts.get(i - 1).price()) {
                changes++;
            }
        }
        return changes / Math.max(1.0, (ref - day0) / (double) DAY);
    }

    private static double firstNonNull(Double[][] g) {
        for (Double[] day : g) {
            for (Double v : day) {
                if (v != null) {
                    return v;
                }
            }
        }
        return 0;
    }

    private static double mean(double[] a) {
        return a.length == 0 ? 0 : Arrays.stream(a).sum() / a.length;
    }

    private static double std(double[] a) {
        if (a.length < 2) {
            return 0;
        }
        double m = mean(a);
        return Math.sqrt(Arrays.stream(a).map(x -> (x - m) * (x - m)).sum() / (a.length - 1));
    }

    private static double winRate(double[] a) {
        return a.length == 0 ? 0 : Arrays.stream(a).filter(x -> x > 0).count() / (double) a.length;
    }

    private static double median(List<Double> v) {
        if (v.isEmpty()) {
            return 0;
        }
        List<Double> s = v.stream().sorted().toList();
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2;
    }
}
