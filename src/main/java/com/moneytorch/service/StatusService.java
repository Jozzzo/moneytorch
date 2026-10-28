package com.moneytorch.service;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/** In-memory state of the background jobs, shown in the UI footer. */
@Service
public class StatusService {
    private volatile String backfill = "waiting";
    private volatile int backfillDone;
    private volatile int backfillTotal;
    private volatile Long lastEtorAt;
    private volatile Long lastTablesAt;
    private volatile String lastError;

    public void backfill(String phase, int done, int total) {
        this.backfill = phase;
        this.backfillDone = done;
        this.backfillTotal = total;
    }

    public void etorCollected() { lastEtorAt = System.currentTimeMillis(); }
    public void tablesCollected() { lastTablesAt = System.currentTimeMillis(); }
    public void error(String message) { lastError = message; }

    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("backfill", backfill);
        m.put("backfillDone", backfillDone);
        m.put("backfillTotal", backfillTotal);
        m.put("lastEtorAt", lastEtorAt);
        m.put("lastTablesAt", lastTablesAt);
        m.put("lastError", lastError);
        return m;
    }
}
