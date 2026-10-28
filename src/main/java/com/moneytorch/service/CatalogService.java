package com.moneytorch.service;

import com.moneytorch.db.Item;
import com.moneytorch.db.PriceRepository;
import com.moneytorch.db.Season;
import com.moneytorch.source.TliPricesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Seasons and item names/categories, mirrored from TLI Prices. */
@Service
public class CatalogService {
    private static final Logger log = LoggerFactory.getLogger(CatalogService.class);

    private final TliPricesClient tli;
    private final PriceRepository repo;
    private volatile Map<String, Item> items = Map.of();
    private volatile List<Season> seasons = List.of();

    public CatalogService(TliPricesClient tli, PriceRepository repo) {
        this.tli = tli;
        this.repo = repo;
        reload();
    }

    @Scheduled(cron = "0 15 4 * * *")
    public void scheduledSync() {
        try {
            sync();
        } catch (IOException e) {
            log.warn("Catalog sync failed: {}", e.getMessage());
        }
    }

    public synchronized void sync() throws IOException {
        repo.upsertSeasons(tli.seasons());
        reload();
        List<Item> fresh = tli.catalog(currentSeasonId());
        repo.upsertItems(fresh);
        reload();
        log.info("Catalog synced: {} items, current season {}", fresh.size(), currentSeasonId());
    }

    /** Adds items that only existed in an older season, keeping current names untouched. */
    public void addMissing(List<Item> older) {
        Map<String, Item> known = items;
        List<Item> missing = older.stream().filter(i -> !known.containsKey(i.id())).toList();
        if (!missing.isEmpty()) {
            repo.upsertItems(missing);
            reload();
        }
    }

    private void reload() {
        seasons = repo.seasons();
        items = repo.items().stream().collect(Collectors.toUnmodifiableMap(Item::id, Function.identity()));
    }

    public Map<String, Item> items() { return items; }
    public List<Season> seasons() { return seasons; }

    public int currentSeasonId() {
        return seasons.stream().filter(Season::current).mapToInt(Season::id).findFirst()
                .orElseThrow(() -> new IllegalStateException("Seasons not loaded yet"));
    }

    /** The standard season right before the current one (no Expert/Solo/Permanent). */
    public Integer previousStandardSeasonId(int seasonId) {
        boolean passed = false;
        for (Season s : seasons) {
            if (s.id() == seasonId) {
                passed = true;
            } else if (passed && s.id() != 1 && !s.label().contains("-")) {
                return s.id();
            }
        }
        return null;
    }
}
