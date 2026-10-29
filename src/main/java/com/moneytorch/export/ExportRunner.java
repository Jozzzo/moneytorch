package com.moneytorch.export;

import com.moneytorch.AppProperties;
import com.moneytorch.service.BackfillService;
import com.moneytorch.service.CollectorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/** Export mode (used by GitHub Actions): catch up on history, collect fresh prices once, write the site, exit. */
@Component
@ConditionalOnProperty(prefix = "moneytorch", name = "mode", havingValue = "export")
public class ExportRunner implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(ExportRunner.class);

    private final BackfillService backfill;
    private final CollectorService collector;
    private final SiteExporter exporter;
    private final AppProperties props;
    private final ApplicationContext context;

    public ExportRunner(BackfillService backfill, CollectorService collector, SiteExporter exporter,
                        AppProperties props, ApplicationContext context) {
        this.backfill = backfill;
        this.collector = collector;
        this.exporter = exporter;
        this.props = props;
        this.context = context;
    }

    @Override
    public void run(String... args) {
        int code = 0;
        try {
            backfill.runOnce(props.getBackfill().getBudgetMinutes() * 60_000L);
            collector.collectTables();
            exporter.export();
        } catch (Exception e) {
            log.error("Export failed", e);
            code = 1;
        }
        int exit = code;
        System.exit(SpringApplication.exit(context, () -> exit));
    }
}
