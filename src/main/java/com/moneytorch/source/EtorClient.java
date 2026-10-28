package com.moneytorch.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.moneytorch.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/** ETor price API, tries each configured host in turn. */
@Component
public class EtorClient {
    private static final Logger log = LoggerFactory.getLogger(EtorClient.class);

    private final Http http;
    private final List<String> hosts;

    public EtorClient(Http http, AppProperties props) {
        this.http = http;
        this.hosts = props.getEtorBaseUrls();
    }

    public int currentSeason() throws IOException {
        return get("/etor-api/api/player/season-status").path("currentSeasonId").asInt();
    }

    public List<PricePoint> snapshot(int seasonId) throws IOException {
        List<PricePoint> out = new ArrayList<>();
        for (JsonNode p : get("/etor-api/api/prices-snapshot/" + seasonId).path("data")) {
            if (!p.path("price").isNumber() || !p.hasNonNull("updatedAt")) {
                continue;
            }
            // TLI Prices mirrors ETor with second precision, truncate so both dedupe to one row
            long ts = Instant.parse(p.path("updatedAt").asText()).truncatedTo(ChronoUnit.SECONDS).toEpochMilli();
            out.add(new PricePoint(p.path("id").asText(), ts, p.path("price").asDouble()));
        }
        return out;
    }

    private JsonNode get(String path) throws IOException {
        IOException last = null;
        for (String host : hosts) {
            try {
                return http.getJson(host + path);
            } catch (IOException e) {
                log.warn("ETor host {} failed: {}", host, e.getMessage());
                last = e;
            }
        }
        throw last != null ? last : new IOException("No ETor hosts configured");
    }
}
