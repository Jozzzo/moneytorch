package com.moneytorch;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties("moneytorch")
public class AppProperties {
    private String tliBaseUrl = "https://tli.re-side.dev";
    private String iconsDir = "./data/icons";
    /** server = web app with scheduled collectors; export = collect once, write a static site, exit */
    private String mode = "server";
    private String exportDir = "./site";
    private Backfill backfill = new Backfill();

    public static class Backfill {
        private boolean enabled = true;
        private List<Integer> seasons = new ArrayList<>();
        private List<String> sources = List.of("poebench");
        private long delayMs = 350;
        /** stop the backfill after this many minutes and continue next run; 0 = no limit */
        private long budgetMinutes = 0;

        public long getBudgetMinutes() { return budgetMinutes; }
        public void setBudgetMinutes(long budgetMinutes) { this.budgetMinutes = budgetMinutes; }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public List<Integer> getSeasons() { return seasons; }
        public void setSeasons(List<Integer> seasons) { this.seasons = seasons; }
        public List<String> getSources() { return sources; }
        public void setSources(List<String> sources) { this.sources = sources; }
        public long getDelayMs() { return delayMs; }
        public void setDelayMs(long delayMs) { this.delayMs = delayMs; }
    }

    public String getTliBaseUrl() { return tliBaseUrl; }
    public void setTliBaseUrl(String tliBaseUrl) { this.tliBaseUrl = tliBaseUrl; }
    public String getIconsDir() { return iconsDir; }
    public void setIconsDir(String iconsDir) { this.iconsDir = iconsDir; }
    public Backfill getBackfill() { return backfill; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public boolean isServer() { return !"export".equals(mode); }
    public String getExportDir() { return exportDir; }
    public void setExportDir(String exportDir) { this.exportDir = exportDir; }
    public void setBackfill(Backfill backfill) { this.backfill = backfill; }
}
