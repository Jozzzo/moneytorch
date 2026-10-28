package com.moneytorch.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneytorch.AppProperties;
import com.moneytorch.db.Item;
import com.moneytorch.db.PriceRepository;
import com.moneytorch.service.CatalogService;
import com.moneytorch.service.DailyService;
import com.moneytorch.service.OverviewService;
import com.moneytorch.source.PricePoint;
import com.moneytorch.source.TliPricesClient;
import com.moneytorch.web.ApiController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes everything the frontend needs as static JSON so the site can be hosted on GitHub Pages:
 * data/meta.json, data/overview|days|hourly/{season}-{source}.json, data/items/{season}/{id}.json, icons/{id}.png.
 */
@Service
public class SiteExporter {
    private static final Logger log = LoggerFactory.getLogger(SiteExporter.class);
    private static final List<String> SOURCES = List.of("poebench", "etor", "community");
    private static final long HOUR = 3_600_000L;
    private static final long DAY = 24 * HOUR;
    /** hourly grid length for the in-browser hourly-pattern finder */
    private static final int HOURLY_DAYS = 15;
    private static final MathContext SIG4 = new MathContext(4);
    private static final String FE_ICON_ID = "100300";

    private final ObjectMapper mapper;
    private final ApiController api;
    private final OverviewService overview;
    private final DailyService daily;
    private final PriceRepository repo;
    private final CatalogService catalog;
    private final TliPricesClient tli;
    private final Path out;
    private final Path iconsDir;

    public SiteExporter(ObjectMapper mapper, ApiController api, OverviewService overview, DailyService daily,
                        PriceRepository repo, CatalogService catalog, TliPricesClient tli, AppProperties props) {
        this.mapper = mapper;
        this.api = api;
        this.overview = overview;
        this.daily = daily;
        this.repo = repo;
        this.catalog = catalog;
        this.tli = tli;
        this.out = Path.of(props.getExportDir());
        this.iconsDir = Path.of(props.getIconsDir());
    }

    public void export() throws IOException, InterruptedException {
        Path data = out.resolve("data");
        Files.createDirectories(data);

        Map<String, Object> meta = new LinkedHashMap<>(api.meta());
        meta.put("generatedAt", System.currentTimeMillis());
        meta.put("pricePoints", repo.countPoints());
        write(data.resolve("meta.json"), meta);
        Files.writeString(out.resolve("config.js"), "window.MT_STATIC = true;\n");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> seasons = (List<Map<String, Object>>) meta.get("seasons");
        for (Map<String, Object> s : seasons) {
            if (!Boolean.TRUE.equals(s.get("hasData"))) {
                continue;
            }
            int season = (Integer) s.get("id");
            for (String source : SOURCES) {
                write(data.resolve("overview/" + season + "-" + source + ".json"), overview.get(season, source));
                write(data.resolve("days/" + season + "-" + source + ".json"), daily.get(season, source));
                write(data.resolve("hourly/" + season + "-" + source + ".json"), hourly(season, source));
            }
            int n = exportItems(season, data.resolve("items/" + season));
            log.info("Exported season {}: {} item histories", season, n);
        }
        exportIcons();
        log.info("Static site written to {}", out.toAbsolutePath());
    }

    // --- item histories, downsampled to the last price of each hour (and only when it changed)

    private int exportItems(int season, Path dir) throws IOException {
        Files.createDirectories(dir);
        int n = 0;
        for (Item item : catalog.items().values()) {
            Map<String, Object> series = new LinkedHashMap<>();
            boolean any = false;
            for (String source : SOURCES) {
                List<double[]> pts = downsample(repo.history(item.id(), season, source));
                any |= !pts.isEmpty();
                series.put(source, pts);
            }
            if (!any) {
                continue;
            }
            Map<String, Object> file = new LinkedHashMap<>();
            file.put("item", item);
            file.put("series", series);
            write(dir.resolve(item.id() + ".json"), file);
            n++;
        }
        return n;
    }

    /** [tsSeconds, price] pairs, one per hour at most, unchanged prices skipped except the very last. */
    private static List<double[]> downsample(List<PricePoint> pts) {
        List<double[]> outPts = new ArrayList<>();
        Double lastPrice = null;
        for (int i = 0; i < pts.size(); i++) {
            PricePoint p = pts.get(i);
            boolean lastOfHour = i == pts.size() - 1 || pts.get(i + 1).ts() / HOUR != p.ts() / HOUR;
            if (!lastOfHour) {
                continue;
            }
            double price = round(p.price());
            if (lastPrice == null || price != lastPrice || i == pts.size() - 1) {
                outPts.add(new double[]{p.ts() / 1000, price});
                lastPrice = price;
            }
        }
        return outPts;
    }

    // --- hourly grid of the last days, used by the hourly finder in the browser

    private Map<String, Object> hourly(int season, String source) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("seasonId", season);
        res.put("source", source);
        res.put("days", HOURLY_DAYS);
        Long ref = repo.maxTs(season, source);
        res.put("refTs", ref);
        List<Map<String, Object>> items = new ArrayList<>();
        res.put("items", items);
        if (ref == null) {
            return res;
        }
        long day0 = Math.floorDiv(ref, DAY) * DAY - (HOURLY_DAYS - 1) * DAY;
        res.put("day0", day0);
        Map<String, List<PricePoint>> byItem = new HashMap<>();
        for (PricePoint p : repo.pointsSince(season, source, day0 - 3 * DAY)) {
            byItem.computeIfAbsent(p.itemId(), k -> new ArrayList<>()).add(p);
        }
        Map<String, Item> catalogItems = catalog.items();
        byItem.forEach((id, pts) -> {
            PricePoint last = pts.get(pts.size() - 1);
            if (last.ts() < day0) {
                return; // nothing traded inside the window
            }
            Double[] g = new Double[HOURLY_DAYS * 24];
            int i = 0;
            Double value = null;
            for (int k = 0; k < g.length; k++) {
                long t = day0 + k * HOUR;
                if (t > ref) {
                    break;
                }
                while (i < pts.size() && pts.get(i).ts() <= t) {
                    value = round(pts.get(i++).price());
                }
                g[k] = value;
            }
            Item item = catalogItems.get(id);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("name", item != null ? item.nameEn() : "#" + id);
            row.put("nameRu", item != null ? item.nameRu() : null);
            row.put("main", item != null ? item.main() : "unknown");
            row.put("mainLabel", item != null ? item.mainLabel() : "Unknown");
            row.put("subLabel", item != null ? item.subLabel() : null);
            row.put("last", round(last.price()));
            row.put("g", g);
            items.add(row);
        });
        return res;
    }

    // --- icons: downloaded once into the cached data dir, copied into the site on every export

    private void exportIcons() throws IOException, InterruptedException {
        Path target = out.resolve("icons");
        Files.createDirectories(target);
        Files.createDirectories(iconsDir);
        int downloaded = 0;
        List<String> ids = new ArrayList<>(catalog.items().keySet());
        ids.add(FE_ICON_ID); // currency icon used all over the UI, not a tradable item
        for (String id : ids) {
            Path cached = iconsDir.resolve(id + ".png");
            if (!Files.exists(cached)) {
                try {
                    Files.write(cached, tli.icon(id));
                    downloaded++;
                    Thread.sleep(100);
                } catch (IOException e) {
                    continue; // no icon for this item
                }
            }
            Files.copy(cached, target.resolve(id + ".png"), StandardCopyOption.REPLACE_EXISTING);
        }
        log.info("Icons: {} downloaded", downloaded);
    }

    private void write(Path file, Object value) throws IOException {
        Files.createDirectories(file.getParent());
        mapper.writeValue(file.toFile(), value);
    }

    private static double round(double v) {
        return v == 0 || !Double.isFinite(v) ? v : new BigDecimal(v).round(SIG4).doubleValue();
    }
}
