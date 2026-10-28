package com.moneytorch.service;

import com.moneytorch.db.Item;
import com.moneytorch.db.PriceRepository;
import com.moneytorch.source.PricePoint;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Daily price stats of every item for every day of a season. Day 1 is the UTC date of the season's first price.
 * Each day is {open, close, low, median, high, points}, or null when the item had no price that day.
 */
@Service
public class DailyService {
    private static final long DAY = 86_400_000L;
    private static final long CURRENT_TTL_MS = 10 * 60_000L;

    public record DayInfo(int day, String date) {
    }

    public record ItemDays(String id, String name, String nameRu, String main, String mainLabel, String subLabel,
                           float[][] d) {
    }

    public record Result(int seasonId, String source, Long start, List<DayInfo> days, List<ItemDays> items) {
    }

    private record Cached(long at, Result value) {
    }

    private final PriceRepository repo;
    private final CatalogService catalog;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public DailyService(PriceRepository repo, CatalogService catalog) {
        this.repo = repo;
        this.catalog = catalog;
    }

    public Result get(int seasonId, String source) {
        String key = seasonId + "|" + source;
        Cached c = cache.get(key);
        // ended seasons do not change, but a running backfill still adds to them
        long ttl = seasonId == catalog.currentSeasonId() ? CURRENT_TTL_MS : 6 * CURRENT_TTL_MS;
        if (c != null && System.currentTimeMillis() - c.at() < ttl) {
            return c.value();
        }
        Result fresh = compute(seasonId, source);
        cache.put(key, new Cached(System.currentTimeMillis(), fresh));
        return fresh;
    }

    private Result compute(int seasonId, String source) {
        long[] bounds = repo.seasonBounds(seasonId);
        if (bounds == null) {
            return new Result(seasonId, source, null, List.of(), List.of());
        }
        long day0 = Math.floorDiv(bounds[0], DAY);
        int numDays = (int) (Math.floorDiv(bounds[1], DAY) - day0 + 1);
        List<DayInfo> days = new ArrayList<>(numDays);
        for (int d = 0; d < numDays; d++) {
            days.add(new DayInfo(d + 1, LocalDate.ofEpochDay(day0 + d).toString()));
        }

        Map<String, Item> items = catalog.items();
        List<ItemDays> out = new ArrayList<>();
        Accumulator acc = new Accumulator(numDays, day0);
        repo.forEachPoint(seasonId, source, p -> {
            if (!p.itemId().equals(acc.itemId)) {
                acc.flushItem(items, out);
                acc.itemId = p.itemId();
            }
            acc.add(p);
        });
        acc.flushItem(items, out);
        out.sort(Comparator.comparing(ItemDays::name));
        return new Result(seasonId, source, bounds[0], days, out);
    }

    /** Collects one item's points day by day while the ordered stream goes past. */
    private static final class Accumulator {
        private final int numDays;
        private final long day0;
        private String itemId;
        private float[][] days;
        private int currentDay = -1;
        private final List<Double> prices = new ArrayList<>();
        private double open;
        private double close;

        Accumulator(int numDays, long day0) {
            this.numDays = numDays;
            this.day0 = day0;
        }

        void add(PricePoint p) {
            int d = (int) (Math.floorDiv(p.ts(), DAY) - day0);
            if (d < 0 || d >= numDays) {
                return;
            }
            if (days == null) {
                days = new float[numDays][];
            }
            if (d != currentDay) {
                flushDay();
                currentDay = d;
                open = p.price();
            }
            prices.add(p.price());
            close = p.price();
        }

        private void flushDay() {
            if (currentDay < 0 || prices.isEmpty()) {
                return;
            }
            double[] s = prices.stream().mapToDouble(Double::doubleValue).sorted().toArray();
            int n = s.length;
            double median = n % 2 == 1 ? s[n / 2] : (s[n / 2 - 1] + s[n / 2]) / 2;
            days[currentDay] = new float[]{(float) open, (float) close, (float) s[0], (float) median, (float) s[n - 1], n};
            prices.clear();
        }

        void flushItem(Map<String, Item> items, List<ItemDays> out) {
            flushDay();
            if (itemId != null && days != null) {
                Item item = items.get(itemId);
                out.add(new ItemDays(itemId,
                        item != null ? item.nameEn() : "#" + itemId,
                        item != null ? item.nameRu() : null,
                        item != null ? item.main() : "unknown",
                        item != null ? item.mainLabel() : "Unknown",
                        item != null ? item.subLabel() : null,
                        days));
            }
            days = null;
            currentDay = -1;
        }
    }
}
