package com.moneytorch.db;

import com.moneytorch.source.PricePoint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Repository
public class PriceRepository {
    private final JdbcTemplate jdbc;

    public PriceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // --- seasons & items

    public void upsertSeasons(List<Season> seasons) {
        jdbc.update("UPDATE season SET is_current = FALSE");
        jdbc.batchUpdate("MERGE INTO season (id, label, name, is_current, sort_order) KEY (id) VALUES (?, ?, ?, ?, ?)",
                seasons, 100, (ps, s) -> {
                    ps.setInt(1, s.id());
                    ps.setString(2, s.label());
                    ps.setString(3, s.name());
                    ps.setBoolean(4, s.current());
                    ps.setInt(5, s.sortOrder());
                });
    }

    public List<Season> seasons() {
        return jdbc.query("SELECT * FROM season ORDER BY sort_order", (rs, n) -> new Season(rs.getInt("id"),
                rs.getString("label"), rs.getString("name"), rs.getBoolean("is_current"), rs.getInt("sort_order")));
    }

    public void upsertItems(List<Item> items) {
        jdbc.batchUpdate("MERGE INTO item (id, name_en, name_ru, name_zh, main, main_label, sub, sub_label, tlidb_slug) "
                + "KEY (id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", items, 500, (ps, i) -> {
            ps.setString(1, i.id());
            ps.setString(2, i.nameEn());
            ps.setString(3, i.nameRu());
            ps.setString(4, i.nameZh());
            ps.setString(5, i.main());
            ps.setString(6, i.mainLabel());
            ps.setString(7, i.sub());
            ps.setString(8, i.subLabel());
            ps.setString(9, i.tlidbSlug());
        });
    }

    public List<Item> items() {
        return jdbc.query("SELECT * FROM item", (rs, n) -> new Item(rs.getString("id"), rs.getString("name_en"),
                rs.getString("name_ru"), rs.getString("name_zh"), rs.getString("main"), rs.getString("main_label"),
                rs.getString("sub"), rs.getString("sub_label"), rs.getString("tlidb_slug")));
    }

    // --- prices

    public void insertPoints(int seasonId, String source, List<PricePoint> points) {
        jdbc.batchUpdate("MERGE INTO price_point (season_id, source, item_id, ts, price) "
                + "KEY (season_id, source, item_id, ts) VALUES (?, ?, ?, ?, ?)", points, 1000, (ps, p) -> {
            ps.setInt(1, seasonId);
            ps.setString(2, source);
            ps.setString(3, p.itemId());
            ps.setLong(4, p.ts());
            ps.setDouble(5, p.price());
        });
    }

    /** Points of all items newer than sinceTs, ordered by item then time. */
    public List<PricePoint> pointsSince(int seasonId, String source, long sinceTs) {
        return jdbc.query("SELECT item_id, ts, price FROM price_point WHERE season_id = ? AND source = ? AND ts >= ? "
                        + "ORDER BY item_id, ts",
                (rs, n) -> new PricePoint(rs.getString(1), rs.getLong(2), rs.getDouble(3)), seasonId, source, sinceTs);
    }

    /** Most recent point of every item. */
    public Map<String, PricePoint> latest(int seasonId, String source) {
        Map<String, PricePoint> out = new HashMap<>();
        jdbc.query("SELECT p.item_id, p.ts, p.price FROM price_point p JOIN ("
                        + "SELECT item_id, MAX(ts) mts FROM price_point WHERE season_id = ? AND source = ? GROUP BY item_id"
                        + ") m ON p.item_id = m.item_id AND p.ts = m.mts WHERE p.season_id = ? AND p.source = ?",
                rs -> {
                    out.put(rs.getString(1), new PricePoint(rs.getString(1), rs.getLong(2), rs.getDouble(3)));
                }, seasonId, source, seasonId, source);
        return out;
    }

    /** Streams every point of a season, ordered by item then time, without loading them all into memory. */
    public void forEachPoint(int seasonId, String source, java.util.function.Consumer<PricePoint> consumer) {
        jdbc.query(con -> {
            var ps = con.prepareStatement("SELECT item_id, ts, price FROM price_point WHERE season_id = ? AND source = ? "
                    + "ORDER BY item_id, ts");
            ps.setInt(1, seasonId);
            ps.setString(2, source);
            ps.setFetchSize(5000);
            return ps;
        }, (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                consumer.accept(new PricePoint(rs.getString(1), rs.getLong(2), rs.getDouble(3))));
    }

    public List<PricePoint> history(String itemId, int seasonId, String source) {
        return jdbc.query("SELECT item_id, ts, price FROM price_point WHERE season_id = ? AND source = ? AND item_id = ? "
                        + "ORDER BY ts",
                (rs, n) -> new PricePoint(rs.getString(1), rs.getLong(2), rs.getDouble(3)), seasonId, source, itemId);
    }

    public Long maxTs(int seasonId, String source) {
        return jdbc.queryForObject("SELECT MAX(ts) FROM price_point WHERE season_id = ? AND source = ?",
                Long.class, seasonId, source);
    }

    /** {min, max} timestamp of a season over all sources, nulls when empty. */
    public long[] seasonBounds(int seasonId) {
        return jdbc.queryForObject("SELECT MIN(ts), MAX(ts) FROM price_point WHERE season_id = ?",
                (rs, n) -> rs.getObject(1) == null ? null : new long[]{rs.getLong(1), rs.getLong(2)}, seasonId);
    }

    public long countPoints() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM price_point", Long.class);
        return n == null ? 0 : n;
    }

    // --- backfill bookkeeping

    public Set<String> backfilled(int seasonId, String source) {
        return new HashSet<>(jdbc.queryForList("SELECT item_id FROM backfill_done WHERE season_id = ? AND source = ?",
                String.class, seasonId, source));
    }

    public void markBackfilled(int seasonId, String source, String itemId) {
        jdbc.update("MERGE INTO backfill_done (season_id, source, item_id) KEY (season_id, source, item_id) VALUES (?, ?, ?)",
                seasonId, source, itemId);
    }
}
