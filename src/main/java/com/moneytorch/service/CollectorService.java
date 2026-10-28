package com.moneytorch.service;

import com.moneytorch.db.PriceRepository;
import com.moneytorch.source.EtorClient;
import com.moneytorch.source.PricePoint;
import com.moneytorch.source.TliPricesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Keeps the current season up to date after the backfill. */
@Service
public class CollectorService {
    private static final Logger log = LoggerFactory.getLogger(CollectorService.class);
    private static final List<String> TABLE_SOURCES = List.of("poebench", "community");
    /** An unchanged price from a timestamp-less table is still recorded this often. */
    private static final long KEEPALIVE_MS = 3 * 3600_000L;

    private final EtorClient etor;
    private final TliPricesClient tli;
    private final PriceRepository repo;
    private final CatalogService catalog;
    private final OverviewService overview;
    private final StatusService status;

    public CollectorService(EtorClient etor, TliPricesClient tli, PriceRepository repo, CatalogService catalog,
                            OverviewService overview, StatusService status) {
        this.etor = etor;
        this.tli = tli;
        this.repo = repo;
        this.catalog = catalog;
        this.overview = overview;
        this.status = status;
    }

    @Scheduled(initialDelayString = "PT30S", fixedDelayString = "PT10M")
    public void collectEtor() {
        try {
            int season = etor.currentSeason();
            List<PricePoint> points = etor.snapshot(season);
            repo.insertPoints(season, "etor", points);
            overview.invalidate(season);
            status.etorCollected();
            log.info("ETor: {} prices for season {}", points.size(), season);
        } catch (Exception e) {
            status.error("ETor: " + e.getMessage());
            log.warn("ETor collection failed: {}", e.getMessage());
        }
    }

    @Scheduled(initialDelayString = "PT60S", fixedDelayString = "PT30M")
    public void collectTables() {
        try {
            int season = catalog.currentSeasonId();
            long now = Instant.now().truncatedTo(ChronoUnit.MINUTES).toEpochMilli();
            for (String source : TABLE_SOURCES) {
                Map<String, Double> prices = tli.prices(season, source);
                Map<String, PricePoint> last = repo.latest(season, source);
                List<PricePoint> fresh = new ArrayList<>();
                prices.forEach((id, price) -> {
                    PricePoint prev = last.get(id);
                    if (prev == null || Math.abs(prev.price() - price) > 1e-9 || now - prev.ts() >= KEEPALIVE_MS) {
                        fresh.add(new PricePoint(id, now, price));
                    }
                });
                repo.insertPoints(season, source, fresh);
                log.info("TLI {}: {} prices, {} new points", source, prices.size(), fresh.size());
            }
            overview.invalidate(season);
            status.tablesCollected();
        } catch (Exception e) {
            status.error("TLI Prices: " + e.getMessage());
            log.warn("TLI table collection failed: {}", e.getMessage());
        }
    }
}
