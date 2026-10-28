package com.moneytorch.web;

import com.moneytorch.db.Item;
import com.moneytorch.db.PriceRepository;
import com.moneytorch.db.Season;
import com.moneytorch.service.CatalogService;
import com.moneytorch.service.DailyService;
import com.moneytorch.service.FlipService;
import com.moneytorch.service.OverviewService;
import com.moneytorch.service.StatusService;
import com.moneytorch.source.PricePoint;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

@RestController
@RequestMapping("/api")
public class ApiController {
    private static final Set<String> SOURCES = Set.of("poebench", "etor", "community");

    private final CatalogService catalog;
    private final OverviewService overview;
    private final PriceRepository repo;
    private final StatusService status;
    private final FlipService flips;
    private final DailyService daily;

    public ApiController(CatalogService catalog, OverviewService overview, PriceRepository repo, StatusService status,
                         FlipService flips, DailyService daily) {
        this.catalog = catalog;
        this.overview = overview;
        this.repo = repo;
        this.status = status;
        this.flips = flips;
        this.daily = daily;
    }

    @GetMapping("/meta")
    public Map<String, Object> meta() {
        List<Map<String, Object>> seasons = new ArrayList<>();
        for (Season s : catalog.seasons()) {
            long[] bounds = repo.seasonBounds(s.id());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.id());
            m.put("label", s.label());
            m.put("name", s.name());
            m.put("current", s.current());
            m.put("hasData", bounds != null);
            m.put("start", bounds != null ? bounds[0] : null);
            m.put("end", bounds != null ? bounds[1] : null);
            seasons.add(m);
        }
        // main category -> sub category -> item count
        Map<String, Map<String, Object>> categories = new TreeMap<>();
        for (Item i : catalog.items().values()) {
            Map<String, Object> cat = categories.computeIfAbsent(i.main(), k -> {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("key", i.main());
                c.put("label", i.mainLabel());
                c.put("subs", new TreeMap<String, Integer>());
                return c;
            });
            @SuppressWarnings("unchecked")
            Map<String, Integer> subs = (Map<String, Integer>) cat.get("subs");
            subs.merge(i.subLabel() != null ? i.subLabel() : "Other", 1, Integer::sum);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("seasons", seasons);
        out.put("categories", categories.values());
        out.put("status", status.snapshot());
        return out;
    }

    @GetMapping("/overview")
    public OverviewService.Overview overview(@RequestParam int season, @RequestParam(defaultValue = "poebench") String source) {
        return overview.get(season, checkSource(source));
    }

    @GetMapping("/days")
    public DailyService.Result days(@RequestParam int season, @RequestParam(defaultValue = "poebench") String source) {
        return daily.get(season, checkSource(source));
    }

    @GetMapping("/flips")
    public FlipService.Result flips(@RequestParam int season,
                                    @RequestParam(defaultValue = "poebench") String source,
                                    @RequestParam(defaultValue = "7") int days,
                                    @RequestParam(defaultValue = "1") int minHold,
                                    @RequestParam(defaultValue = "1") int maxHold,
                                    @RequestParam(defaultValue = "12.5") double tax) {
        if (days < 3 || days > 30 || minHold < 0 || maxHold > 3 || minHold > maxHold || tax < 0 || tax >= 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bad flip parameters");
        }
        return flips.get(season, checkSource(source), days, minHold, maxHold, tax / 100.0);
    }

    @GetMapping("/items/{id}")
    public Map<String, Object> item(@PathVariable String id, @RequestParam int season,
                                    @RequestParam(required = false) Integer compare) {
        Item item = catalog.items().get(id);
        if (item == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown item " + id);
        }
        Map<String, Object> series = new LinkedHashMap<>();
        for (String source : List.of("poebench", "etor", "community")) {
            series.put(source, points(repo.history(id, season, source)));
        }
        long[] bounds = repo.seasonBounds(season);

        Integer compareId = compare != null ? compare : catalog.previousStandardSeasonId(season);
        Map<String, Object> cmp = null;
        long[] cmpBounds = compareId != null && compareId > 0 ? repo.seasonBounds(compareId) : null;
        if (cmpBounds != null) {
            cmp = new LinkedHashMap<>();
            cmp.put("seasonId", compareId);
            cmp.put("label", catalog.seasons().stream().filter(s -> s.id() == compareId)
                    .map(Season::label).findFirst().orElse(String.valueOf(compareId)));
            cmp.put("start", cmpBounds[0]);
            cmp.put("points", points(repo.history(id, compareId, "poebench")));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("item", item);
        out.put("seasonId", season);
        out.put("seasonStart", bounds != null ? bounds[0] : null);
        out.put("seasonEnd", bounds != null ? bounds[1] : null);
        out.put("series", series);
        out.put("compare", cmp);
        return out;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> s = new LinkedHashMap<>(status.snapshot());
        s.put("pricePoints", repo.countPoints());
        return s;
    }

    private static List<double[]> points(List<PricePoint> pts) {
        List<double[]> out = new ArrayList<>(pts.size());
        for (PricePoint p : pts) {
            out.add(new double[]{p.ts(), p.price()});
        }
        return out;
    }

    private static String checkSource(String source) {
        if (!SOURCES.contains(source)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown source " + source);
        }
        return source;
    }
}
