package com.moneytorch.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.moneytorch.AppProperties;
import com.moneytorch.db.Item;
import com.moneytorch.db.Season;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Undocumented API behind https://tli.re-side.dev (TLI Prices). */
@Component
public class TliPricesClient {
    private static final int PAGE = 200;

    private final Http http;
    private final String base;

    public TliPricesClient(Http http, AppProperties props) {
        this.http = http;
        this.base = props.getTliBaseUrl();
    }

    public List<Season> seasons() throws IOException {
        JsonNode root = http.getJson(base + "/api/v1/meta/seasons");
        int current = root.path("current_season").asInt();
        List<Season> out = new ArrayList<>();
        int order = 0;
        for (JsonNode s : root.path("seasons")) {
            int id = s.path("season_id").asInt();
            out.add(new Season(id, s.path("label").asText(), s.path("name_en").asText(),
                    id == current || s.path("is_current").asBoolean(), order++));
        }
        return out;
    }

    public List<Item> catalog(int seasonId) throws IOException {
        List<Item> out = new ArrayList<>();
        for (int offset = 0; ; offset += PAGE) {
            JsonNode root = http.getJson(base + "/api/v1/market/list?season_id=" + seasonId
                    + "&lang=en&limit=" + PAGE + "&offset=" + offset);
            JsonNode items = root.path("items");
            for (JsonNode i : items) {
                out.add(new Item(i.path("id").asText(), i.path("name").asText(), i.path("name_ru").asText(null),
                        i.path("name_zh").asText(null), i.path("main").asText(), i.path("main_label").asText(),
                        i.path("sub").asText(null), i.path("sub_label").asText(null),
                        i.path("tlidb_slug").asText(null)));
            }
            int total = root.path("total").asInt(0);
            if (items.size() < PAGE || offset + PAGE >= total) {
                return out;
            }
        }
    }

    /** Full season history of one item from one source. */
    public List<PricePoint> history(String itemId, int seasonId, String source) throws IOException {
        JsonNode root = http.getJson(base + "/api/v1/market/item/" + itemId + "?season_id=" + seasonId
                + "&source=" + source + "&range=all");
        List<PricePoint> out = new ArrayList<>();
        for (JsonNode h : root.path("history")) {
            String ts = h.hasNonNull("server_updated_at") ? h.path("server_updated_at").asText()
                    : h.path("fetched_at").asText(null);
            if (ts == null || !h.path("price").isNumber()) {
                continue;
            }
            out.add(new PricePoint(itemId, Instant.parse(ts).toEpochMilli(), h.path("price").asDouble()));
        }
        return out;
    }

    /** Current price table (no timestamps) for a season and source. */
    public Map<String, Double> prices(int seasonId, String source) throws IOException {
        JsonNode root = http.getJson(base + "/api/v1/prices?season_id=" + seasonId + "&source=" + source);
        Map<String, Double> out = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = root.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode price = e.getValue().path("price");
            if (price.isNumber()) {
                out.put(e.getKey(), price.asDouble());
            }
        }
        return out;
    }

    public byte[] icon(String itemId) throws IOException {
        return http.getBytes(base + "/img/icons/" + itemId + ".png", "image/png");
    }
}
