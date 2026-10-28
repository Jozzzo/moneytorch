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

/** Price table of a season: latest price, 1d/7d change and a 7 day sparkline per item. */
@Service
public class OverviewService {
    private static final long DAY = 86_400_000L;
    private static final long TTL_MS = 10 * 60_000L;
    private static final int SPARK_DAYS = 7;

    public record Row(String id, String name, String nameRu, String main, String mainLabel, String sub,
                      String subLabel, double price, Double change1d, Double change7d, Double[] spark, long updatedAt) {
    }

    public record Overview(int seasonId, String source, Long refTs, List<Row> rows) {
    }

    private record Cached(long at, Overview value) {
    }

    private final PriceRepository repo;
    private final CatalogService catalog;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public OverviewService(PriceRepository repo, CatalogService catalog) {
        this.repo = repo;
        this.catalog = catalog;
    }

    public void invalidate(int seasonId) {
        cache.keySet().removeIf(k -> k.startsWith(seasonId + "|"));
    }

    public Overview get(int seasonId, String source) {
        String key = seasonId + "|" + source;
        Cached c = cache.get(key);
        if (c != null && System.currentTimeMillis() - c.at() < TTL_MS) {
            return c.value();
        }
        Overview fresh = compute(seasonId, source);
        cache.put(key, new Cached(System.currentTimeMillis(), fresh));
        return fresh;
    }

    private Overview compute(int seasonId, String source) {
        // "now" of a season: its newest point, so ended seasons show their final week
        Long ref = repo.maxTs(seasonId, source);
        if (ref == null) {
            return new Overview(seasonId, source, null, List.of());
        }
        Map<String, List<PricePoint>> window = new HashMap<>();
        for (PricePoint p : repo.pointsSince(seasonId, source, ref - (SPARK_DAYS + 1) * DAY)) {
            window.computeIfAbsent(p.itemId(), k -> new ArrayList<>()).add(p);
        }
        Map<String, Item> items = catalog.items();
        List<Row> rows = new ArrayList<>();
        repo.latest(seasonId, source).forEach((id, last) -> {
            Double[] spark = spark(window.get(id), last, ref);
            Item item = items.get(id);
            rows.add(new Row(id,
                    item != null ? item.nameEn() : "#" + id,
                    item != null ? item.nameRu() : null,
                    item != null ? item.main() : "unknown",
                    item != null ? item.mainLabel() : "Unknown",
                    item != null ? item.sub() : null,
                    item != null ? item.subLabel() : null,
                    last.price(), change(last.price(), spark[SPARK_DAYS - 1]), change(last.price(), spark[0]),
                    spark, last.ts()));
        });
        rows.sort(Comparator.comparingDouble(Row::price).reversed());
        return new Overview(seasonId, source, ref, rows);
    }

    /** spark[d] = price at the end of day d, d = 0 is 7 days before ref, the last slot is ref itself. */
    private static Double[] spark(List<PricePoint> points, PricePoint last, long ref) {
        Double[] out = new Double[SPARK_DAYS + 1];
        if (points == null) {
            // no movement inside the window, the last known price held the whole time
            Arrays.fill(out, last.price());
            return out;
        }
        int i = 0;
        Double value = null;
        for (int d = 0; d <= SPARK_DAYS; d++) {
            long end = ref - (long) (SPARK_DAYS - d) * DAY;
            while (i < points.size() && points.get(i).ts() <= end) {
                value = points.get(i++).price();
            }
            out[d] = value;
        }
        return out;
    }

    private static Double change(double now, Double before) {
        if (before == null || before <= 0) {
            return null;
        }
        return (now - before) / before * 100.0;
    }
}
