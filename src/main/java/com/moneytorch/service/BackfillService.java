package com.moneytorch.service;

import com.moneytorch.AppProperties;
import com.moneytorch.db.Item;
import com.moneytorch.db.PriceRepository;
import com.moneytorch.source.PricePoint;
import com.moneytorch.source.TliPricesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * One-time download of full season histories from TLI Prices.
 * Progress is stored per item, so a restart continues where it stopped.
 */
@Service
public class BackfillService {
    private static final Logger log = LoggerFactory.getLogger(BackfillService.class);

    private final AppProperties.Backfill props;
    private final boolean serverMode;
    private final TliPricesClient tli;
    private final PriceRepository repo;
    private final CatalogService catalog;
    private final OverviewService overview;
    private final StatusService status;

    public BackfillService(AppProperties props, TliPricesClient tli, PriceRepository repo, CatalogService catalog,
                           OverviewService overview, StatusService status) {
        this.props = props.getBackfill();
        this.serverMode = props.isServer();
        this.tli = tli;
        this.repo = repo;
        this.catalog = catalog;
        this.overview = overview;
        this.status = status;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!serverMode) {
            return; // export mode calls runOnce() itself
        }
        Thread t = new Thread(() -> runOnce(0), "backfill");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Syncs the catalog and downloads missing history.
     *
     * @param budgetMs stop after this long and leave the rest for the next run, 0 = no limit
     * @return true when everything is backfilled
     */
    public boolean runOnce(long budgetMs) {
        long deadline = budgetMs > 0 ? System.currentTimeMillis() + budgetMs : Long.MAX_VALUE;
        try {
            syncCatalogWithRetry();
            if (!props.isEnabled()) {
                status.backfill("disabled", 0, 0);
                return true;
            }
            for (int season : seasonsToBackfill()) {
                List<Item> items = season == catalog.currentSeasonId()
                        ? new ArrayList<>(catalog.items().values())
                        : tli.catalog(season);
                catalog.addMissing(items);
                for (String source : props.getSources()) {
                    if (!backfill(season, source, items, deadline)) {
                        status.backfill("paused, continues next run", 0, 0);
                        log.info("Backfill time budget used up, {} price points in DB", repo.countPoints());
                        return false;
                    }
                }
            }
            status.backfill("done", 0, 0);
            log.info("Backfill finished, {} price points in DB", repo.countPoints());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            status.backfill("failed: " + e.getMessage(), 0, 0);
            log.error("Backfill failed", e);
            return false;
        }
    }

    private void syncCatalogWithRetry() throws InterruptedException {
        for (int attempt = 1; ; attempt++) {
            try {
                catalog.sync();
                return;
            } catch (Exception e) {
                log.warn("Catalog sync attempt {} failed: {}", attempt, e.getMessage());
                status.error("Catalog: " + e.getMessage());
                Thread.sleep(Math.min(300_000L, 10_000L * attempt));
            }
        }
    }

    private List<Integer> seasonsToBackfill() {
        if (!props.getSeasons().isEmpty()) {
            return props.getSeasons();
        }
        int current = catalog.currentSeasonId();
        List<Integer> out = new ArrayList<>(List.of(current));
        Integer prev = catalog.previousStandardSeasonId(current);
        if (prev != null) {
            out.add(prev);
        }
        return out;
    }

    /** @return false when the deadline stopped it early */
    private boolean backfill(int season, String source, List<Item> items, long deadline) throws InterruptedException {
        Set<String> done = repo.backfilled(season, source);
        List<Item> todo = items.stream().filter(i -> !done.contains(i.id())).toList();
        if (todo.isEmpty()) {
            return true;
        }
        String phase = "season " + season + " / " + source;
        log.info("Backfill {}: {} items", phase, todo.size());
        int n = 0;
        for (Item item : todo) {
            if (System.currentTimeMillis() > deadline) {
                overview.invalidate(season);
                return false;
            }
            status.backfill(phase, n, todo.size());
            try {
                List<PricePoint> points = tli.history(item.id(), season, source);
                repo.insertPoints(season, source, points);
                repo.markBackfilled(season, source, item.id());
            } catch (Exception e) {
                // not marked, so it is retried on the next start
                log.warn("Backfill {} item {} failed: {}", phase, item.id(), e.getMessage());
            }
            if (++n % 100 == 0) {
                log.info("Backfill {}: {}/{}", phase, n, todo.size());
                overview.invalidate(season);
            }
            Thread.sleep(props.getDelayMs());
        }
        overview.invalidate(season);
        return true;
    }
}
