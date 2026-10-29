'use strict';

const DAY = 86400000;
const FE_ICON = 'icons/100300.png';
const SOURCES = [
    {key: 'poebench', label: 'PoeBench', color: '#60a5fa'},
];

const state = {
    meta: null,
    source: 'poebench',
    lang: load('lang', 'en'),
    sort: {key: 'price', asc: false},
    search: '',
    overviews: new Map(),
    chart: null,
};

const $ = sel => document.querySelector(sel);
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c]));

function load(key, def) {
    try { return localStorage.getItem('mt.' + key) || def; } catch { return def; }
}
function save(key, value) {
    try { localStorage.setItem('mt.' + key, value); } catch { /* storage unavailable */ }
}

async function api(path) {
    const r = await fetch(path);
    if (!r.ok) throw new Error(`${r.status} ${path}`);
    return r.json();
}

// ---------- data access: live API (local server) or pre-built JSON (GitHub Pages)

const STATIC = !!window.MT_STATIC;
// cache-bust static files per export so browsers pick up each update
const json = path => api(path + '?v=' + (state.meta?.generatedAt || Date.now()));

const DATA = {
    meta: () => STATIC ? api('data/meta.json?v=' + Date.now()) : api('api/meta'),
    status: () => api('api/status'),
    overview: (season, source) => STATIC ? json(`data/overview/${season}-${source}.json`)
        : api(`api/overview?season=${season}&source=${source}`),
    days: (season, source) => STATIC ? json(`data/days/${season}-${source}.json`)
        : api(`api/days?season=${season}&source=${source}`),
    item: (id, season) => STATIC ? staticItem(id, season) : api(`api/items/${encodeURIComponent(id)}?season=${season}`),
    flips: async (season, source, days, minHold, maxHold, tax) => STATIC
        ? clientFlips(await json(`data/hourly/${season}-${source}.json`), days, minHold, maxHold, tax / 100)
        : api(`api/flips?season=${season}&source=${source}&days=${days}&minHold=${minHold}&maxHold=${maxHold}&tax=${tax}`),
};

/** Same shape as /api/items/{id}: series in ms, plus the previous standard season for comparison. */
async function staticItem(id, season) {
    const toMs = pts => pts.map(([t, v]) => [t * 1000, v]);
    const file = await json(`data/items/${season}/${encodeURIComponent(id)}.json`);
    const seasons = state.meta.seasons;
    const me = seasons.find(s => s.id === season) || {};
    const series = {};
    for (const [k, v] of Object.entries(file.series)) series[k] = toMs(v);
    let compare = null;
    const prev = seasons.slice(seasons.indexOf(me) + 1).find(s => s.hasData && s.id !== 1 && !s.label.includes('-'));
    if (prev) {
        try {
            const old = await json(`data/items/${prev.id}/${encodeURIComponent(id)}.json`);
            compare = {seasonId: prev.id, label: prev.label, start: prev.start, points: toMs(old.series.poebench || [])};
        } catch { /* item did not exist back then */ }
    }
    return {item: file.item, seasonId: season, seasonStart: me.start, seasonEnd: me.end, series, compare};
}

/**
 * Browser port of FlipService: on the hourly grid, backtest every "buy at hour b, sell at hour s after hold days",
 * pick the best on the older days (train) and check it on the newest third (test).
 */
function clientFlips(h, days, minHold, maxHold, tax) {
    const rows = [];
    if (!h.refTs) return {seasonId: h.seasonId, source: h.source, refTs: null, rows};
    const off = h.days - days;
    const trainCount = n => Math.max(0, n - Math.max(2, Math.round(n / 3)));
    const rets = new Float64Array(days), buyDays = new Int32Array(days);
    const mean = (a, from, to) => { let s = 0; for (let i = from; i < to; i++) s += a[i]; return to > from ? s / (to - from) : 0; };
    const std = (a, from, to) => {
        if (to - from < 2) return 0;
        const m = mean(a, from, to);
        let s = 0;
        for (let i = from; i < to; i++) s += (a[i] - m) ** 2;
        return Math.sqrt(s / (to - from - 1));
    };
    const win = (a, from, to) => { let w = 0; for (let i = from; i < to; i++) if (a[i] > 0) w++; return to > from ? w / (to - from) : 0; };
    const median = v => {
        if (!v.length) return 0;
        const s = [...v].sort((a, b) => a - b), n = s.length;
        return n % 2 ? s[n >> 1] : (s[n / 2 - 1] + s[n / 2]) / 2;
    };

    for (const it of h.items) {
        const G = (d, hr) => { const v = it.g[(off + d) * 24 + hr]; return v == null ? null : v; };
        let best = null, bestScore = -Infinity;
        for (let hold = minHold; hold <= maxHold; hold++) {
            for (let b = 0; b < 24; b++) {
                for (let s = 0; s < 24; s++) {
                    if (hold === 0 && s <= b) continue;
                    let n = 0;
                    for (let d = 0; d + hold < days; d++) {
                        const buy = G(d, b), sell = G(d + hold, s);
                        if (buy > 0 && sell != null) rets[n++] = sell * (1 - tax) / buy - 1;
                    }
                    const train = trainCount(n);
                    if (train < 3) continue;
                    // risk adjusted: a steady small edge beats one lucky spike
                    const score = mean(rets, 0, train) - 0.5 * std(rets, 0, train);
                    if (score > bestScore) { bestScore = score; best = {b, s, hold}; }
                }
            }
        }
        if (!best) continue;

        let n = 0;
        for (let d = 0; d + best.hold < days; d++) {
            const buy = G(d, best.b), sell = G(d + best.hold, best.s);
            if (buy > 0 && sell != null) { buyDays[n] = d; rets[n++] = sell * (1 - tax) / buy - 1; }
        }
        const train = trainCount(n), testN = n - train;
        const trainAvg = mean(rets, 0, train) * 100;
        const testAvg = testN ? mean(rets, train, n) * 100 : null;
        const buys = [], sells = [];
        for (let i = testN ? train : 0; i < n; i++) {
            buys.push(G(buyDays[i], best.b));
            sells.push(G(buyDays[i] + best.hold, best.s));
        }
        const buyPrice = median(buys), sellPrice = median(sells);

        // daily closes, daily ranges and hourly price moves inside the window
        const closes = [], ranges = [];
        let changes = 0, first = null, prev = null;
        for (let d = 0; d < days; d++) {
            let lo = Infinity, hi = -Infinity, close = null;
            for (let hr = 0; hr < 24; hr++) {
                const v = G(d, hr);
                if (v == null) continue;
                if (first == null) first = v;
                if (prev != null && v !== prev) changes++;
                prev = v;
                close = v;
                lo = Math.min(lo, v);
                hi = Math.max(hi, v);
            }
            if (close != null) {
                closes.push(close);
                if (lo > 0) ranges.push((hi - lo) / lo);
            }
        }
        const dailyRet = Float64Array.from(closes.slice(1).map((c, i) => c / closes[i] - 1));
        const ret = Array.from(rets.slice(0, n), x => x * 100);

        rows.push({
            id: it.id, name: it.name, nameRu: it.nameRu, main: it.main, mainLabel: it.mainLabel, subLabel: it.subLabel,
            price: it.last, buyHour: best.b, sellHour: best.s, hold: best.hold,
            buyPrice, sellPrice, profitPerUnit: sellPrice * (1 - tax) - buyPrice,
            trainAvg, trainWin: win(rets, 0, train) * 100, trainDays: train,
            testAvg, testWin: testN ? win(rets, train, n) * 100 : null, testDays: testN,
            worst: Math.min(...ret), score: testAvg != null ? Math.min(trainAvg, testAvg) : trainAvg,
            volatility: std(dailyRet, 0, dailyRet.length) * 100,
            dailyRange: ranges.length ? ranges.reduce((a, b) => a + b, 0) / ranges.length * 100 : 0,
            trend: first > 0 ? (it.last - first) / first * 100 : 0,
            changesPerDay: changes / days, returns: ret,
        });
    }
    rows.sort((a, b) => b.score - a.score);
    return {seasonId: h.seasonId, source: h.source, refTs: h.refTs, rows};
}

// ---------- formatting

function fmtPrice(v) {
    if (v == null) return '—';
    if (v >= 1000) return Math.round(v).toLocaleString('en-US');
    if (v >= 100) return v.toFixed(0);
    if (v >= 10) return v.toFixed(1);
    if (v >= 1) return v.toFixed(2);
    return v.toPrecision(2);
}
function fmtChange(v) {
    if (v == null) return '<span class="change flat">—</span>';
    const cls = Math.abs(v) < 0.5 ? 'flat' : v > 0 ? 'up' : 'down';
    return `<span class="change ${cls}">${v > 0 ? '+' : ''}${v.toFixed(Math.abs(v) >= 100 ? 0 : 1)}%</span>`;
}
function fmtDate(ts, withTime) {
    const d = new Date(ts);
    const date = d.toLocaleDateString('ru-RU', {day: '2-digit', month: '2-digit', year: '2-digit'});
    return withTime ? `${date} ${d.toLocaleTimeString('ru-RU', {hour: '2-digit', minute: '2-digit'})}` : date;
}
function ago(ts) {
    if (!ts) return 'never';
    const m = Math.round((Date.now() - ts) / 60000);
    if (m < 60) return `${m} min ago`;
    if (m < 48 * 60) return `${Math.round(m / 60)} h ago`;
    return `${Math.round(m / 1440)} d ago`;
}
/** Season day by UTC calendar date: the start date is day 1. */
function seasonDay(ts, seasonStart) {
    if (!seasonStart) return null;
    const midnight = t => Math.floor(t / DAY) * DAY;
    return Math.round((midnight(ts) - midnight(seasonStart)) / DAY) + 1;
}
const itemName = row => (state.lang === 'ru' && row.nameRu) || row.nameEn || row.name;
const altName = row => (state.lang === 'ru' ? row.nameEn || row.name : row.nameRu) || '';

function sparkline(values, change) {
    const pts = values.map((v, i) => [i, v]).filter(p => p[1] != null);
    if (pts.length < 2) return '<svg width="90" height="26"></svg>';
    const ys = pts.map(p => p[1]);
    const min = Math.min(...ys), max = Math.max(...ys);
    const w = 90, h = 26, n = values.length - 1;
    const path = pts.map(([i, v], k) => {
        const x = (i / n) * (w - 2) + 1;
        const y = max === min ? h / 2 : h - 3 - ((v - min) / (max - min)) * (h - 6);
        return `${k ? 'L' : 'M'}${x.toFixed(1)},${y.toFixed(1)}`;
    }).join('');
    const color = change == null || Math.abs(change) < 0.5 ? '#8d8d8d' : change > 0 ? '#4caf50' : '#e5534b';
    return `<svg width="${w}" height="${h}"><path d="${path}" fill="none" stroke="${color}" stroke-width="1.6"/></svg>`;
}

// ---------- routing

function route() {
    const parts = location.hash.replace(/^#\/?/, '').split('/').filter(Boolean).map(decodeURIComponent);
    const current = state.meta.seasons.find(s => s.current) || state.meta.seasons[0];
    if (!parts.length || !state.meta.seasons.some(s => String(s.id) === parts[0])) {
        location.replace(`#/${current.id}/currency`);
        return;
    }
    const season = Number(parts[0]);
    $('#season').value = season;
    $('#tabPrices').href = `#/${season}/currency`;
    $('#tabFlips').href = `#/${season}/flips`;
    const flipTab = parts[1] === 'flips' || parts[1] === 'hourly';
    $('#tabPrices').classList.toggle('active', !flipTab);
    $('#tabFlips').classList.toggle('active', flipTab);
    if (parts[1] === 'flips') {
        renderFlipSidebar(season, 'flips');
        renderDays(season, Number(parts[2]) || null, Number(parts[3]) || null).catch(showError);
    } else if (parts[1] === 'hourly') {
        renderFlipSidebar(season, 'hourly');
        renderFlips(season).catch(showError);
    } else if (parts[1] === 'item' && parts[2]) {
        renderSidebar(season, null, null);
        renderItem(season, parts[2]).catch(showError);
    } else {
        const main = parts[1] || 'currency';
        const sub = parts[2] || null;
        renderSidebar(season, main, sub);
        renderOverview(season, main, sub).catch(showError);
    }
}

function showError(e) {
    console.error(e);
    $('#main').innerHTML = `<div class="empty">Failed to load: ${esc(e.message)}</div>`;
}

// ---------- sidebar

function renderSidebar(season, main, sub) {
    const html = ['<h4>Categories</h4>'];
    for (const cat of state.meta.categories) {
        const total = Object.values(cat.subs).reduce((a, b) => a + b, 0);
        const active = cat.key === main && !sub ? ' active' : '';
        html.push(`<a class="main-link${active}" href="#/${season}/${encodeURIComponent(cat.key)}">${esc(cat.label)}<span class="count">${total}</span></a>`);
        if (cat.key === main) {
            for (const [label, count] of Object.entries(cat.subs)) {
                const on = label === sub ? ' active' : '';
                html.push(`<a class="${on}" href="#/${season}/${encodeURIComponent(cat.key)}/${encodeURIComponent(label)}">${esc(label)}<span class="count">${count}</span></a>`);
            }
        }
    }
    $('#sidebar').innerHTML = html.join('');
}

// ---------- overview table

async function getOverview(season) {
    const key = `${season}|${state.source}`;
    const cached = state.overviews.get(key);
    if (cached && Date.now() - cached.at < 120000) return cached.data;
    const data = await DATA.overview(season, state.source);
    state.overviews.set(key, {at: Date.now(), data});
    return data;
}

async function renderOverview(season, main, sub) {
    destroyChart();
    const cat = state.meta.categories.find(c => c.key === main);
    const title = sub || (cat ? cat.label : main);
    $('#main').innerHTML = `<div class="empty">Loading…</div>`;
    const ov = await getOverview(season);

    const draw = () => {
        const q = state.search.trim().toLowerCase();
        let rows = ov.rows.filter(r => r.main === main && (!sub || (r.subLabel || 'Other') === sub));
        if (q) rows = rows.filter(r => (r.name + ' ' + (r.nameRu || '')).toLowerCase().includes(q));
        const {key, asc} = state.sort;
        const val = r => key === 'name' ? itemName(r).toLowerCase() : r[key] ?? -Infinity;
        rows.sort((a, b) => (val(a) < val(b) ? -1 : val(a) > val(b) ? 1 : 0) * (asc ? 1 : -1));

        const th = (k, label, cls = '') =>
            `<th data-sort="${k}" class="${cls} ${state.sort.key === k ? 'sorted' + (asc ? ' asc' : '') : ''}">${label}</th>`;
        const body = rows.map(r => `
            <tr data-id="${esc(r.id)}">
                <td><div class="name-cell"><img loading="lazy" src="icons/${esc(r.id)}.png" alt="">
                    <div><div class="n">${esc(itemName(r))}</div><div class="s">${esc(r.subLabel || '')}</div></div></div></td>
                <td class="hide-sm"><div class="spark-cell">${sparkline(r.spark, r.change7d)}${fmtChange(r.change7d)}</div></td>
                <td class="num hide-sm">${fmtChange(r.change1d)}</td>
                <td class="num"><span class="value">${fmtPrice(r.price)}<img src="${FE_ICON}" alt="FE"></span>
                    ${ov.refTs && ov.refTs - r.updatedAt > 3 * DAY ? `<div class="stale">${fmtDate(r.updatedAt)}</div>` : ''}</td>
            </tr>`).join('');

        $('#tableWrap').innerHTML = rows.length ? `
            <table class="prices">
                <thead><tr>${th('name', 'Name')}${th('change7d', 'Last 7 days', 'hide-sm')}${th('change1d', '24h', 'num hide-sm')}${th('price', 'Value', 'num')}</tr></thead>
                <tbody>${body}</tbody>
            </table>` : `<div class="empty">${ov.rows.length ? 'Nothing found' : 'No prices yet for this season/source — backfill may still be running'}</div>`;

        $('#tableWrap').querySelectorAll('th[data-sort]').forEach(el => el.onclick = () => {
            const k = el.dataset.sort;
            state.sort = {key: k, asc: state.sort.key === k ? !state.sort.asc : k === 'name'};
            draw();
        });
        $('#tableWrap').querySelectorAll('tr[data-id]').forEach(el => el.onclick = () => {
            location.hash = `#/${season}/item/${el.dataset.id}`;
        });
    };

    $('#main').innerHTML = `
        <div class="page-head">
            <div><h1>${esc(title)}</h1>
                <div class="sub">${esc(SOURCES.find(s => s.key === state.source).label)} · prices in Flame Elementium${ov.refTs ? ' · as of ' + fmtDate(ov.refTs, true) : ''}</div></div>
            <input type="search" id="search" placeholder="Search item…" value="${esc(state.search)}">
        </div>
        <div id="tableWrap"></div>`;
    $('#search').oninput = e => { state.search = e.target.value; draw(); };
    draw();
}

// ---------- item page

function destroyChart() {
    if (state.chart) { state.chart.destroy(); state.chart = null; }
}

async function renderItem(season, id) {
    destroyChart();
    $('#main').innerHTML = `<div class="empty">Loading…</div>`;
    const d = await DATA.item(id, season);
    const item = d.item;
    const seasonLabel = state.meta.seasons.find(s => s.id === season)?.label || season;
    const mainPts = d.series[state.source] || [];

    const lastPt = mainPts[mainPts.length - 1];
    const cards = [`<div class="card"><div class="k">Price</div>
            <div class="v">${lastPt ? fmtPrice(lastPt[1]) : '—'}</div>
            <div class="d">${lastPt ? ago(lastPt[0]) : 'no data'}</div></div>`];
    if (mainPts.length) {
        const prices = mainPts.map(p => p[1]);
        const min = mainPts.reduce((a, b) => b[1] < a[1] ? b : a);
        const max = mainPts.reduce((a, b) => b[1] > a[1] ? b : a);
        const first = mainPts[0][1], last = mainPts[mainPts.length - 1][1];
        cards.push(
            `<div class="card"><div class="k">Season low</div><div class="v">${fmtPrice(min[1])}</div><div class="d">${fmtDate(min[0], true)}</div></div>`,
            `<div class="card"><div class="k">Season high</div><div class="v">${fmtPrice(max[1])}</div><div class="d">${fmtDate(max[0], true)}</div></div>`,
            `<div class="card"><div class="k">Since start</div><div class="v">${fmtChange((last - first) / first * 100)}</div><div class="d">${fmtPrice(first)} → ${fmtPrice(last)}</div></div>`,
            `<div class="card"><div class="k">Average</div><div class="v">${fmtPrice(prices.reduce((a, b) => a + b, 0) / prices.length)}</div><div class="d">${prices.length} points</div></div>`);
    }

    const names = {nameEn: item.nameEn, nameRu: item.nameRu};
    $('#main').innerHTML = `
        <div class="page-head"><a href="javascript:history.back()">← back</a></div>
        <div class="item-head">
            <img src="icons/${esc(item.id)}.png" alt="">
            <div>
                <h1>${esc(itemName(names))}</h1>
                <div class="alt">${esc(altName(names))}</div>
                <div class="alt">${esc(item.mainLabel)} · ${esc(item.subLabel || '')} · season ${esc(seasonLabel)}
                    ${item.tlidbSlug ? ` · <a href="https://tlidb.com/en/${encodeURIComponent(item.tlidbSlug)}" target="_blank" rel="noopener">tlidb</a>` : ''}</div>
            </div>
        </div>
        <div class="cards">${cards.join('')}</div>
        <div class="panel">
            <div class="toolbar">
                <div class="group" id="ranges">
                    <button data-r="1">24h</button><button data-r="7">7d</button><button data-r="30">30d</button><button data-r="0" class="on">Season</button>
                </div>
                ${d.compare && d.compare.points.length ? `<label><input type="checkbox" id="cmp"><span class="swatch" style="background:#9e9e9e"></span>${esc(d.compare.label)} (aligned to season start)</label>` : ''}
                <label><input type="checkbox" id="logScale">log scale</label>
            </div>
            <div id="chart"></div>
        </div>
        <div class="panel">
            <h3 style="margin:0 0 8px">Daily</h3>
            ${dailyTable(mainPts, d.seasonStart)}
        </div>`;

    const opts = {range: 0, log: false, compare: false};
    const redraw = () => drawChart(d, opts);
    $('#ranges').querySelectorAll('button').forEach(b => b.onclick = () => {
        $('#ranges').querySelectorAll('button').forEach(x => x.classList.toggle('on', x === b));
        opts.range = Number(b.dataset.r);
        redraw();
    });
    $('#logScale').onchange = e => { opts.log = e.target.checked; redraw(); };
    if ($('#cmp')) $('#cmp').onchange = e => { opts.compare = e.target.checked; redraw(); };
    redraw();
}

function drawChart(d, opts) {
    destroyChart();
    const el = $('#chart');
    const lines = SOURCES.filter(s => d.series[s.key]?.length)
        .map(s => ({label: s.label, color: s.color, pts: d.series[s.key]}));
    if (opts.compare && d.compare && d.seasonStart) {
        // shift the old season so its day N lands on this season's day N
        const shift = d.seasonStart - d.compare.start;
        lines.push({label: d.compare.label, color: '#9e9e9e', dash: [5, 4], pts: d.compare.points.map(([t, v]) => [t + shift, v])});
    }
    if (!lines.length) { el.innerHTML = '<div class="empty">No data</div>'; return; }

    // uPlot wants one shared x array: merge all timestamps, null where a line has no point
    const xsSet = new Set();
    lines.forEach(l => l.pts.forEach(p => xsSet.add(p[0])));
    const xs = [...xsSet].sort((a, b) => a - b);
    const idx = new Map(xs.map((x, i) => [x, i]));
    const data = [xs.map(x => x / 1000)];
    for (const l of lines) {
        const ys = new Array(xs.length).fill(null);
        l.pts.forEach(([t, v]) => { ys[idx.get(t)] = v; });
        data.push(ys);
    }

    const end = d.seasonEnd || xs[xs.length - 1];
    const min = opts.range ? (end - opts.range * DAY) / 1000 : (d.seasonStart || xs[0]) / 1000;
    const dayOf = ts => seasonDay(ts * 1000, d.seasonStart);

    state.chart = new uPlot({
        width: el.clientWidth,
        height: 380,
        scales: {x: {time: true, min, max: end / 1000}, y: {distr: opts.log ? 3 : 1}},
        axes: [
            {stroke: '#8d8d8d', grid: {stroke: '#262626'}, ticks: {stroke: '#333'}},
            {stroke: '#8d8d8d', grid: {stroke: '#262626'}, ticks: {stroke: '#333'}, size: 60, values: (u, vals) => vals.map(fmtPrice)},
        ],
        series: [
            {value: (u, ts) => ts == null ? '—' : `${fmtDate(ts * 1000, true)}${dayOf(ts) ? ' · day ' + dayOf(ts) : ''}`},
            ...lines.map(l => ({label: l.label, stroke: l.color, width: 1.6, dash: l.dash, spanGaps: true, points: {show: false},
                value: (u, v) => v == null ? '—' : fmtPrice(v)})),
        ],
        cursor: {drag: {x: true, y: false}},
    }, data, el);
}

function dailyTable(pts, seasonStart) {
    if (!pts.length) return '<div class="empty">No data</div>';
    const days = new Map();
    for (const [t, v] of pts) {
        const key = new Date(t).toISOString().slice(0, 10);
        const d = days.get(key) || {min: v, max: v, sum: 0, n: 0, last: v, first: t};
        d.min = Math.min(d.min, v); d.max = Math.max(d.max, v);
        d.sum += v; d.n++; d.last = v;
        days.set(key, d);
    }
    const keys = [...days.keys()];
    const rows = keys.map((k, i) => {
        const d = days.get(k), prev = i ? days.get(keys[i - 1]).last : null;
        const day = seasonDay(d.first, seasonStart) ?? '';
        return `<tr><td>${k}</td><td>${day}</td><td>${fmtPrice(d.min)}</td><td>${fmtPrice(d.max)}</td>
            <td>${fmtPrice(d.sum / d.n)}</td><td>${fmtPrice(d.last)}</td><td>${prev ? fmtChange((d.last - prev) / prev * 100) : '—'}</td></tr>`;
    }).reverse().join('');
    return `<table class="daily"><thead><tr><th>Date (UTC)</th><th>Day</th><th>Min</th><th>Max</th><th>Avg</th><th>Close</th><th>Change</th></tr></thead><tbody>${rows}</tbody></table>`;
}

// ---------- league days: prices per season day, buy on day A, sell on day B

const BASIS = {open: 0, close: 1, low: 2, med: 3, high: 4};
const BASIS_LABEL = {low: 'Day low', med: 'Day median', high: 'Day high', open: 'Open', close: 'Close'};
const POINTS = 5;
const DAYS_DEFAULTS = {
    buyBasis: 'med', sellBasis: 'med', tax: 12.5, view: 'compare',
    main: '', search: '', minPrice: 1, maxPrice: '', minProfit: '', minUnit: '', minPoints: 3, hideFlat: true,
};

async function getDays(season) {
    const key = `days|${season}|${state.source}`;
    const cached = state.overviews.get(key);
    if (cached && Date.now() - cached.at < 120000) return cached.data;
    const data = await DATA.days(season, state.source);
    // one-time per load: season-wide cheapest / priciest day and a "never moves" flag
    for (const it of data.items) {
        let lo = null, hi = null, distinct = new Set();
        it.d.forEach((x, i) => {
            if (!x) return;
            if (lo == null || x[BASIS.med] < it.d[lo][BASIS.med]) lo = i;
            if (hi == null || x[BASIS.med] > it.d[hi][BASIS.med]) hi = i;
            distinct.add(x[BASIS.low]); distinct.add(x[BASIS.high]);
        });
        // 99999 is a "not for sale" placeholder some sources use
        it.cheapest = lo; it.priciest = hi; it.flat = distinct.size <= 1 || [...distinct].some(v => v >= 99999);
    }
    state.overviews.set(key, {at: Date.now(), data});
    return data;
}

function sparkDays(it, a, b) {
    const vals = it.d.map(x => x ? x[BASIS.med] : null);
    const pts = vals.map((v, i) => [i, v]).filter(p => p[1] != null);
    const w = 130, h = 30, n = Math.max(1, vals.length - 1);
    if (pts.length < 2) return `<svg width="${w}" height="${h}"></svg>`;
    const ys = pts.map(p => p[1]);
    const min = Math.min(...ys), max = Math.max(...ys);
    const X = i => (i / n) * (w - 6) + 3;
    const Y = v => max === min ? h / 2 : h - 4 - ((v - min) / (max - min)) * (h - 8);
    const path = pts.map(([i, v], k) => `${k ? 'L' : 'M'}${X(i).toFixed(1)},${Y(v).toFixed(1)}`).join('');
    const dot = (i, color) => vals[i] == null ? '' : `<circle cx="${X(i).toFixed(1)}" cy="${Y(vals[i]).toFixed(1)}" r="3" fill="${color}"/>`;
    return `<svg width="${w}" height="${h}"><path d="${path}" fill="none" stroke="#8d8d8d" stroke-width="1.3"/>${dot(a, '#60a5fa')}${dot(b, '#d9a54a')}</svg>`;
}

function pctColor(p) {
    if (p == null) return 'transparent';
    const a = Math.min(1, Math.abs(p) / 60) * 0.55 + 0.05;
    return p >= 0 ? `rgba(76,175,80,${a.toFixed(2)})` : `rgba(229,83,75,${a.toFixed(2)})`;
}

async function renderDays(season, aDay, bDay) {
    destroyChart();
    let f;
    try { f = {...DAYS_DEFAULTS, ...JSON.parse(localStorage.getItem('mt.days') || '{}')}; } catch { f = {...DAYS_DEFAULTS}; }
    const saveF = () => { try { localStorage.setItem('mt.days', JSON.stringify(f)); } catch { /* ignore */ } };
    const num = v => v === '' || v == null ? null : Number(v);

    $('#main').innerHTML = '<div class="empty">Loading league days…</div>';
    const data = await getDays(season);
    const nDays = data.days.length;
    if (!nDays) { $('#main').innerHTML = '<div class="empty">No data for this season yet</div>'; return; }
    // day numbers in the URL are 1-based; default: yesterday → today
    const clampDay = d => Math.min(nDays, Math.max(1, d));
    let A = clampDay(aDay || nDays - 1) - 1;
    let B = clampDay(bDay || nDays) - 1;
    let sort = {key: 'pct', asc: false};
    const seasonLabel = state.meta.seasons.find(s => s.id === season)?.label || season;
    const setDays = (a, b) => { location.hash = `#/${season}/flips/${a + 1}/${b + 1}`; };
    const dayOpt = sel => data.days.map((d, i) =>
        `<option value="${i}" ${i === sel ? 'selected' : ''}>Day ${d.day} · ${d.date.slice(5).split('-').reverse().join('.')}</option>`).join('');
    const basisOpt = sel => Object.entries(BASIS_LABEL).map(([k, l]) => `<option value="${k}" ${k === sel ? 'selected' : ''}>${l}</option>`).join('');

    $('#main').innerHTML = `
        <div class="page-head"><div><h1>League days · ${esc(seasonLabel)}</h1>
            <div class="sub">Price of every item on every day of the season. Pick a buy day and a sell day, the table shows the profit after tax.</div></div></div>
        <div class="daybar">
            <div class="dayctl">
                <label>Buy on<select id="dayA">${dayOpt(A)}</select></label>
                <label>at<select id="basisA">${basisOpt(f.buyBasis)}</select></label>
                <button id="swap" title="Swap days">⇄</button>
                <label>Sell on<select id="dayB">${dayOpt(B)}</select></label>
                <label>at<select id="basisB">${basisOpt(f.sellBasis)}</select></label>
                <label>Tax %<input type="number" id="tax" min="0" max="50" step="0.5" value="${esc(f.tax)}"></label>
                <div class="presets">
                    <button data-p="yt">Yesterday → today</button>
                    <button data-p="1t">Day 1 → today</button>
                    <button data-p="w">7 days ago → today</button>
                    <button data-p="12">Day 1 → day 2</button>
                </div>
            </div>
            <div id="strip" class="strip"></div>
            <div class="hint">Market index: median price change of all items vs the buy day. Click a day = sell day, Shift+click = buy day.</div>
        </div>
        <div class="filters">
            <label>View<select data-k="view"><option value="compare">Compare A → B</option><option value="grid">All days grid</option></select></label>
            <label>Category<select data-k="main"><option value="">All</option>${state.meta.categories.map(c => `<option value="${esc(c.key)}">${esc(c.label)}</option>`).join('')}</select></label>
            <label>Search<input type="search" data-k="search" placeholder="Item name"></label>
            <label>Min buy price (FE)<input type="number" data-k="minPrice" min="0" step="any"></label>
            <label>Max buy price / budget<input type="number" data-k="maxPrice" min="0" step="any"></label>
            <label>Min profit %<input type="number" data-k="minProfit" step="any"></label>
            <label>Min profit / unit (FE)<input type="number" data-k="minUnit" step="any"></label>
            <label>Min price points / day<input type="number" data-k="minPoints" min="0" step="1"></label>
            <label class="check"><input type="checkbox" data-k="hideFlat"> Hide items whose price never moves</label>
        </div>
        <div id="summary" class="cards"></div>
        <div id="daysTable"></div>`;

    $('#dayA').onchange = e => setDays(Number(e.target.value), B);
    $('#dayB').onchange = e => setDays(A, Number(e.target.value));
    $('#swap').onclick = () => setDays(B, A);
    $('#basisA').onchange = e => { f.buyBasis = e.target.value; saveF(); drawAll(); };
    $('#basisB').onchange = e => { f.sellBasis = e.target.value; saveF(); drawAll(); };
    $('#tax').oninput = e => { f.tax = e.target.value; saveF(); drawAll(); };
    document.querySelectorAll('.presets button').forEach(b => b.onclick = () => {
        const last = nDays - 1;
        ({yt: () => setDays(Math.max(0, last - 1), last), '1t': () => setDays(0, last),
            w: () => setDays(Math.max(0, last - 7), last), '12': () => setDays(0, Math.min(1, last))})[b.dataset.p]();
    });
    document.querySelectorAll('.filters [data-k]').forEach(el => {
        const k = el.dataset.k;
        if (el.type === 'checkbox') el.checked = !!f[k]; else el.value = f[k];
        el.addEventListener(el.tagName === 'SELECT' || el.type === 'checkbox' ? 'change' : 'input', () => {
            f[k] = el.type === 'checkbox' ? el.checked : el.value;
            saveF();
            drawAll();
        });
    });

    function compute() {
        const ia = BASIS[f.buyBasis], ib = BASIS[f.sellBasis], tax = (num(f.tax) ?? 12.5) / 100;
        const q = (f.search || '').trim().toLowerCase();
        const minPrice = num(f.minPrice), maxPrice = num(f.maxPrice), minProfit = num(f.minProfit),
            minUnit = num(f.minUnit), minPoints = num(f.minPoints);
        const rows = [];
        for (const it of data.items) {
            if (f.main && it.main !== f.main) continue;
            if (f.hideFlat && it.flat) continue;
            if (q && !(it.name + ' ' + (it.nameRu || '')).toLowerCase().includes(q)) continue;
            const da = it.d[A], db = it.d[B];
            if (!da || !db) continue;
            const buy = da[ia], sell = db[ib];
            if (!(buy > 0)) continue;
            const unit = sell * (1 - tax) - buy, pct = unit / buy * 100, points = Math.min(da[POINTS], db[POINTS]);
            if (minPrice != null && buy < minPrice) continue;
            if (maxPrice != null && buy > maxPrice) continue;
            if (minProfit != null && pct < minProfit) continue;
            if (minUnit != null && unit < minUnit) continue;
            if (minPoints != null && points < minPoints) continue;
            rows.push({it, buy, sell, unit, pct, points, name: itemName(it)});
        }
        const val = r => sort.key === 'name' ? r.name.toLowerCase() : r[sort.key];
        rows.sort((x, y) => (val(x) < val(y) ? -1 : val(x) > val(y) ? 1 : 0) * (sort.asc ? 1 : -1));
        return rows;
    }

    function drawStrip() {
        // median change vs day A of the items that pass category/flat filters
        const pool = data.items.filter(it => (!f.main || it.main === f.main) && !(f.hideFlat && it.flat) && it.d[A]);
        const idx = data.days.map((_, d) => {
            const ch = pool.filter(it => it.d[d]).map(it => it.d[d][BASIS.med] / it.d[A][BASIS.med] - 1).sort((a, b) => a - b);
            return ch.length ? ch[Math.floor(ch.length / 2)] * 100 : null;
        });
        const maxAbs = Math.max(5, ...idx.filter(v => v != null).map(Math.abs));
        $('#strip').innerHTML = data.days.map((d, i) => {
            const v = idx[i];
            const hgt = v == null ? 0 : Math.max(2, Math.abs(v) / maxAbs * 15);
            return `<div class="daychip ${i === A ? 'a' : ''} ${i === B ? 'b' : ''}" data-i="${i}" title="Day ${d.day} (${d.date})">
                <div class="bar"><span style="height:${hgt}px;${v >= 0 ? 'bottom:50%' : 'top:50%'};background:${v >= 0 ? '#4caf50' : '#e5534b'}"></span></div>
                <div class="dn">D${d.day}</div><div class="dv ${v == null ? 'flat' : v > 0.5 ? 'up' : v < -0.5 ? 'down' : 'flat'}">${v == null ? '—' : (v > 0 ? '+' : '') + v.toFixed(0) + '%'}</div>
            </div>`;
        }).join('');
        $('#strip').querySelectorAll('.daychip').forEach(el => el.onclick = e => {
            const i = Number(el.dataset.i);
            e.shiftKey ? setDays(i, B) : setDays(A, i);
        });
        const sel = $('#strip').querySelector('.b');
        if (sel) sel.scrollIntoView({block: 'nearest', inline: 'center'});
    }

    function drawSummary(rows) {
        const n = rows.length;
        const up = rows.filter(r => r.pct > 0).length;
        const med = n ? rows.map(r => r.pct).sort((a, b) => a - b)[Math.floor(n / 2)] : null;
        const best = rows.reduce((m, r) => !m || r.pct > m.pct ? r : m, null);
        const bestUnit = rows.reduce((m, r) => !m || r.unit > m.unit ? r : m, null);
        $('#summary').innerHTML = `
            <div class="card"><div class="k">Compared</div><div class="v">${n}</div><div class="d">Day ${A + 1} → Day ${B + 1}</div></div>
            <div class="card"><div class="k">Profitable after tax</div><div class="v">${n ? Math.round(up / n * 100) : 0}%</div><div class="d">${up} of ${n} items</div></div>
            <div class="card"><div class="k">Median profit</div><div class="v">${fmtChange(med)}</div><div class="d">${esc(BASIS_LABEL[f.buyBasis])} → ${esc(BASIS_LABEL[f.sellBasis])}</div></div>
            <div class="card"><div class="k">Best %</div><div class="v">${best ? fmtChange(best.pct) : '—'}</div><div class="d">${best ? esc(best.name) : ''}</div></div>
            <div class="card"><div class="k">Best per unit</div><div class="v">${bestUnit ? (bestUnit.unit >= 0 ? '+' : '-') + fmtPrice(Math.abs(bestUnit.unit)) : '—'}</div><div class="d">${bestUnit ? esc(bestUnit.name) : ''}</div></div>`;
    }

    const th = (k, label, cls = 'num') =>
        `<th data-sort="${k}" class="${cls} ${sort.key === k ? 'sorted' + (sort.asc ? ' asc' : '') : ''}">${label}</th>`;
    const bindTable = () => {
        $('#daysTable').querySelectorAll('th[data-sort]').forEach(el => el.onclick = e => {
            const k = el.dataset.sort;
            if (k.startsWith('day:')) {
                const i = Number(k.slice(4));
                e.shiftKey ? setDays(A, i) : setDays(i, B);
                return;
            }
            sort = {key: k, asc: sort.key === k ? !sort.asc : k === 'name'};
            drawTable();
        });
        $('#daysTable').querySelectorAll('[data-id]').forEach(el => el.onclick = () => {
            location.hash = `#/${season}/item/${el.dataset.id}`;
        });
    };
    const dayShort = i => `D${data.days[i].day}`;
    const nameCell = it => `<div class="name-cell"><img loading="lazy" src="icons/${esc(it.id)}.png" alt="">
        <div><div class="n">${esc(itemName(it))}</div><div class="s">${esc(it.mainLabel)} · ${esc(it.subLabel || '')}</div></div></div>`;

    function drawCompare(rows) {
        const shown = rows.slice(0, 300);
        const body = shown.map(r => {
            const da = r.it.d[A], db = r.it.d[B];
            return `<tr data-id="${esc(r.it.id)}">
                <td>${nameCell(r.it)}</td>
                <td class="num"><span class="value">${fmtPrice(r.buy)}<img src="${FE_ICON}" alt=""></span><div class="m">${fmtPrice(da[BASIS.low])} – ${fmtPrice(da[BASIS.high])}</div></td>
                <td class="num"><span class="value">${fmtPrice(r.sell)}<img src="${FE_ICON}" alt=""></span><div class="m">${fmtPrice(db[BASIS.low])} – ${fmtPrice(db[BASIS.high])}</div></td>
                <td class="num"><span class="value ${r.unit >= 0 ? 'up' : 'down'}">${r.unit >= 0 ? '+' : '-'}${fmtPrice(Math.abs(r.unit))}<img src="${FE_ICON}" alt=""></span></td>
                <td class="num">${fmtChange(r.pct)}</td>
                <td class="hide-sm">${sparkDays(r.it, A, B)}</td>
                <td class="num hide-sm"><span class="up">${r.it.cheapest != null ? dayShort(r.it.cheapest) : '—'}</span> / <span class="down">${r.it.priciest != null ? dayShort(r.it.priciest) : '—'}</span></td>
                <td class="num hide-sm">${r.points}</td>
            </tr>`;
        }).join('');
        $('#daysTable').innerHTML = shown.length ? `
            <div class="result-count">${rows.length} items${rows.length > shown.length ? `, showing top ${shown.length}` : ''} · range under the price = that day's low – high</div>
            <table class="prices flips">
                <thead><tr>${th('name', 'Item', '')}${th('buy', `Buy · ${dayShort(A)}`)}${th('sell', `Sell · ${dayShort(B)}`)}${th('unit', 'Profit / unit')}${th('pct', 'Profit %')}
                    <th class="hide-sm">Season <span class="m"><span style="color:#60a5fa">●</span> buy <span style="color:#d9a54a">●</span> sell</span></th>
                    <th class="num hide-sm">Cheapest / priciest day</th>${th('points', 'Points', 'num hide-sm')}</tr></thead>
                <tbody>${body}</tbody>
            </table>` : '<div class="empty">No items match. Try other days or relax the filters</div>';
        bindTable();
    }

    function drawGrid(rows) {
        const shown = rows.slice(0, 200);
        const ib = BASIS[f.sellBasis];
        const head = data.days.map((d, i) =>
            `<th data-sort="day:${i}" class="num day ${i === A ? 'col-a' : ''} ${i === B ? 'col-b' : ''}" title="${d.date} · click = buy day, Shift+click = sell day">D${d.day}<div class="m">${d.date.slice(5).split('-').reverse().join('.')}</div></th>`).join('');
        const body = shown.map(r => {
            const base = r.it.d[A] ? r.it.d[A][ib] : null;
            const cells = r.it.d.map((x, i) => {
                if (!x) return `<td class="num cell ${i === A ? 'col-a' : ''} ${i === B ? 'col-b' : ''}"></td>`;
                const v = x[ib], p = base ? (v / base - 1) * 100 : null;
                const mark = i === r.it.cheapest ? '<span class="mk up">▼</span>' : i === r.it.priciest ? '<span class="mk down">▲</span>' : '';
                return `<td class="num cell ${i === A ? 'col-a' : ''} ${i === B ? 'col-b' : ''}" style="background:${i === A ? 'transparent' : pctColor(p)}"
                    title="Day ${i + 1}: ${fmtPrice(v)} FE${p != null ? ` (${p >= 0 ? '+' : ''}${p.toFixed(1)}% vs D${A + 1})` : ''} · low ${fmtPrice(x[BASIS.low])} · high ${fmtPrice(x[BASIS.high])}">${mark}${fmtPrice(v)}</td>`;
            }).join('');
            return `<tr><td class="sticky" data-id="${esc(r.it.id)}">${nameCell(r.it)}</td>${cells}</tr>`;
        }).join('');
        $('#daysTable').innerHTML = shown.length ? `
            <div class="result-count">${rows.length} items${rows.length > shown.length ? `, showing top ${shown.length}` : ''} · cells: ${esc(BASIS_LABEL[f.sellBasis])}, colour = change vs buy day D${A + 1} · ▼ cheapest ▲ priciest day of the season · sorted by A → B profit</div>
            <div class="grid-wrap"><table class="prices grid">
                <thead><tr><th class="sticky">Item</th>${head}</tr></thead>
                <tbody>${body}</tbody>
            </table></div>` : '<div class="empty">No items match. Try other days or relax the filters</div>';
        bindTable();
        const col = $('#daysTable').querySelector('th.col-b');
        if (col) col.scrollIntoView({block: 'nearest', inline: 'center'});
    }

    function drawTable() {
        const rows = compute();
        drawSummary(rows);
        f.view === 'grid' ? drawGrid(rows) : drawCompare(rows);
    }

    function drawAll() {
        drawStrip();
        drawTable();
    }

    drawAll();
}

// ---------- global search

function currentSeasonId() {
    const n = Number(location.hash.replace(/^#\/?/, '').split('/')[0]);
    return n || (state.meta.seasons.find(s => s.current) || state.meta.seasons[0]).id;
}

function setupGlobalSearch() {
    const input = $('#globalSearch'), box = $('#searchResults');
    let matches = [], sel = 0;

    const go = row => {
        box.hidden = true;
        input.value = '';
        input.blur();
        location.hash = `#/${currentSeasonId()}/item/${row.id}`;
    };
    const paint = () => {
        box.querySelectorAll('a').forEach((a, i) => a.classList.toggle('sel', i === sel));
    };
    const update = async () => {
        const q = input.value.trim().toLowerCase();
        if (q.length < 2) { box.hidden = true; return; }
        const ov = await getOverview(currentSeasonId());
        const words = q.split(/\s+/);
        matches = ov.rows
            .filter(r => { const hay = (r.name + ' ' + (r.nameRu || '')).toLowerCase(); return words.every(w => hay.includes(w)); })
            .slice(0, 15);
        sel = 0;
        box.innerHTML = matches.length ? matches.map(r => `
            <a href="#/${currentSeasonId()}/item/${esc(r.id)}" data-id="${esc(r.id)}">
                <img class="i" loading="lazy" src="icons/${esc(r.id)}.png" alt="">
                <div class="grow"><div>${esc(itemName(r))}</div><div class="s">${esc(r.mainLabel)} · ${esc(r.subLabel || '')}</div></div>
                <span class="value">${fmtPrice(r.price)}<img src="${FE_ICON}" alt=""></span>
            </a>`).join('') : '<div class="empty-hint">Nothing found</div>';
        box.querySelectorAll('a').forEach((a, i) => a.onmousedown = e => { e.preventDefault(); go(matches[i]); });
        box.hidden = false;
        paint();
    };

    input.oninput = () => update().catch(console.error);
    input.onfocus = () => update().catch(console.error);
    input.onblur = () => setTimeout(() => { box.hidden = true; }, 100);
    input.onkeydown = e => {
        if (box.hidden || !matches.length) return;
        if (e.key === 'ArrowDown') { sel = Math.min(sel + 1, matches.length - 1); paint(); e.preventDefault(); }
        else if (e.key === 'ArrowUp') { sel = Math.max(sel - 1, 0); paint(); e.preventDefault(); }
        else if (e.key === 'Enter') { go(matches[sel]); e.preventDefault(); }
        else if (e.key === 'Escape') { box.hidden = true; }
    };
    document.addEventListener('keydown', e => {
        if (e.key === '/' && document.activeElement.tagName !== 'INPUT') { input.focus(); e.preventDefault(); }
    });
}

// ---------- flips

const HOLDS = {
    next: {label: 'Next day', min: 1, max: 1},
    upto1: {label: 'Same or next day', min: 0, max: 1},
    same: {label: 'Same day', min: 0, max: 0},
    any: {label: 'Up to 2 days', min: 0, max: 2},
};

const FLIP_DEFAULTS = {
    days: 7, hold: 'next', tax: 12.5,
    main: '', search: '',
    minPrice: 1, maxPrice: '', minProfit: 3, minWin: 60, maxVol: 20, maxRange: '', maxTrend: '', minActivity: 2, minUnit: '',
    confirmed: true,
};

function loadFlipFilters() {
    try { return {...FLIP_DEFAULTS, ...JSON.parse(localStorage.getItem('mt.flips2') || '{}')}; } catch { return {...FLIP_DEFAULTS}; }
}

// local time of a UTC hour; returns {hh, dayShift}
const tzShiftH = -new Date().getTimezoneOffset() / 60;
function localHour(utcHour, dayOffset = 0) {
    const abs = dayOffset * 24 + utcHour + tzShiftH;
    return {hh: ((Math.floor(abs) % 24) + 24) % 24, day: Math.floor(abs / 24)};
}
const hhmm = h => String(h).padStart(2, '0') + ':00';

function returnBars(returns, testDays) {
    if (!returns.length) return '';
    const w = 6, gap = 2, h = 28, mid = h / 2;
    const maxAbs = Math.max(5, ...returns.map(Math.abs));
    const split = returns.length - testDays;
    const bars = returns.map((r, i) => {
        const len = Math.max(1, Math.abs(r) / maxAbs * (mid - 1));
        const y = r >= 0 ? mid - len : mid;
        const color = r >= 0 ? '#4caf50' : '#e5534b';
        return `<rect x="${i * (w + gap)}" y="${y.toFixed(1)}" width="${w}" height="${len.toFixed(1)}" fill="${color}" opacity="${i >= split ? 1 : 0.45}"><title>${r > 0 ? '+' : ''}${r.toFixed(1)}%${i >= split ? ' (test)' : ''}</title></rect>`;
    }).join('');
    const sx = split * (w + gap) - gap / 2;
    return `<svg width="${returns.length * (w + gap)}" height="${h}">
        <line x1="0" x2="${returns.length * (w + gap)}" y1="${mid}" y2="${mid}" stroke="#444"/>
        ${testDays ? `<line x1="${sx}" x2="${sx}" y1="0" y2="${h}" stroke="#d9a54a" stroke-dasharray="2,2"/>` : ''}${bars}</svg>`;
}

function renderFlipSidebar(season, page) {
    $('#sidebar').innerHTML = `<h4>Flips</h4>
        <a class="main-link ${page === 'flips' ? 'active' : ''}" href="#/${season}/flips">League days</a>
        <a class="main-link ${page === 'hourly' ? 'active' : ''}" href="#/${season}/hourly">Hourly patterns</a>
        <h4>Categories</h4>
        ${state.meta.categories.map(c => `<a class="main-link" href="#/${season}/${encodeURIComponent(c.key)}">${esc(c.label)}</a>`).join('')}`;
}

async function renderFlips(season) {
    destroyChart();
    const f = loadFlipFilters();
    const saveFilters = () => { try { localStorage.setItem('mt.flips2', JSON.stringify(f)); } catch { /* ignore */ } };
    const num = v => v === '' || v == null ? null : Number(v);
    let data = null;
    let sort = {key: 'score', asc: false};

    $('#main').innerHTML = `
        <div class="page-head"><div><h1>Flip finder</h1>
            <div class="sub">Buy at one hour, sell later: every hourly strategy backtested on price history, then checked on the most recent days</div></div></div>
        <details class="explain">
            <summary>How it works / read this before trading</summary>
            <ul>
                <li>Prices are resampled to an hourly grid. For each item every "buy at hour X → sell at hour Y" for the chosen hold (default: next day) is tested over the lookback window, including the sale tax.</li>
                <li>The strategy is chosen on the older days (<b>Train</b>) by average profit minus half its spread, so steady beats spiky. The newest third of days (<b>Test</b>, bright bars right of the dashed line) were not used for choosing: if the edge holds there, it is more likely real.</li>
                <li>Early in a season many prices just trend up, and then "buy, hold 2 days" wins on the trend alone. Check the <b>Trend</b> column or cap it with "Max trend" to keep only repeating daily cycles.</li>
                <li><b>Volatility</b> = spread of day-to-day price changes, <b>Daily range</b> = average gap between a day's low and high, <b>Activity</b> = how many times a day the price moves (a rough liquidity proxy: low activity means few trades and stale prices).</li>
                <li>These are reference prices (around the low end of listings), not real fills. Real buy price may be higher and you may not sell instantly. Start with small quantities.</li>
                <li>Times are in your local timezone (UTC${tzShiftH >= 0 ? '+' : ''}${tzShiftH}).</li>
            </ul>
        </details>
        <div class="filters">
            <label>Lookback<select data-k="days">${[3, 5, 7, 10, 14].map(d => `<option value="${d}">${d} days</option>`).join('')}</select></label>
            <label>Hold<select data-k="hold">${Object.entries(HOLDS).map(([k, h]) => `<option value="${k}">${h.label}</option>`).join('')}</select></label>
            <label>Sale tax %<input type="number" data-k="tax" min="0" max="50" step="0.5"></label>
            <label>Category<select data-k="main"><option value="">All</option>${state.meta.categories.map(c => `<option value="${esc(c.key)}">${esc(c.label)}</option>`).join('')}</select></label>
            <label>Search<input type="search" data-k="search" placeholder="Item name"></label>
            <div class="sep"></div>
            <label>Min price (FE)<input type="number" data-k="minPrice" min="0" step="any"></label>
            <label>Max price / budget (FE)<input type="number" data-k="maxPrice" min="0" step="any"></label>
            <label>Min test profit %<input type="number" data-k="minProfit" step="any"></label>
            <label>Min test win %<input type="number" data-k="minWin" min="0" max="100" step="5"></label>
            <label>Max volatility %<input type="number" data-k="maxVol" min="0" step="any"></label>
            <label>Max daily range %<input type="number" data-k="maxRange" min="0" step="any"></label>
            <label>Max trend % (exclude pumps)<input type="number" data-k="maxTrend" step="any"></label>
            <label>Min activity /day<input type="number" data-k="minActivity" min="0" step="any"></label>
            <label>Min profit / unit (FE)<input type="number" data-k="minUnit" min="0" step="any"></label>
            <label class="check"><input type="checkbox" data-k="confirmed"> Profitable in train and test</label>
        </div>
        <div id="flipCount" class="result-count"></div>
        <div id="flipTable"><div class="empty">Backtesting…</div></div>`;

    const serverKeys = new Set(['days', 'hold', 'tax']);
    document.querySelectorAll('.filters [data-k]').forEach(el => {
        const k = el.dataset.k;
        if (el.type === 'checkbox') el.checked = !!f[k]; else el.value = f[k];
        el.addEventListener(el.tagName === 'SELECT' || el.type === 'checkbox' ? 'change' : 'input', () => {
            f[k] = el.type === 'checkbox' ? el.checked : el.value;
            saveFilters();
            serverKeys.has(k) ? fetchData() : draw();
        });
    });

    async function fetchData() {
        const h = HOLDS[f.hold] || HOLDS.next;
        $('#flipTable').innerHTML = '<div class="empty">Backtesting…</div>';
        const tax = num(f.tax) ?? 12.5;
        data = await DATA.flips(season, state.source, Number(f.days), h.min, h.max, tax);
        draw();
    }

    function draw() {
        if (!data) return;
        const q = (f.search || '').trim().toLowerCase();
        const minPrice = num(f.minPrice), maxPrice = num(f.maxPrice), minProfit = num(f.minProfit), minWin = num(f.minWin),
            maxVol = num(f.maxVol), maxRange = num(f.maxRange), maxTrend = num(f.maxTrend), minAct = num(f.minActivity), minUnit = num(f.minUnit);
        let rows = data.rows.filter(r =>
            (!f.main || r.main === f.main)
            && (!q || (r.name + ' ' + (r.nameRu || '')).toLowerCase().includes(q))
            && (minPrice == null || r.buyPrice >= minPrice)
            && (maxPrice == null || r.buyPrice <= maxPrice)
            && (minProfit == null || (r.testAvg ?? r.trainAvg) >= minProfit)
            && (minWin == null || (r.testWin ?? r.trainWin) >= minWin)
            && (maxVol == null || r.volatility <= maxVol)
            && (maxRange == null || r.dailyRange <= maxRange)
            && (maxTrend == null || r.trend <= maxTrend)
            && (minAct == null || r.changesPerDay >= minAct)
            && (minUnit == null || r.profitPerUnit >= minUnit)
            && (!f.confirmed || (r.trainAvg > 0 && r.testAvg != null && r.testAvg > 0)));
        const val = r => sort.key === 'name' ? itemName(r).toLowerCase() : r[sort.key] ?? -Infinity;
        rows.sort((a, b) => (val(a) < val(b) ? -1 : val(a) > val(b) ? 1 : 0) * (sort.asc ? 1 : -1));
        const total = rows.length;
        rows = rows.slice(0, 300);

        $('#flipCount').textContent = `${total} of ${data.rows.length} items match`
            + (total > rows.length ? `, showing top ${rows.length}` : '')
            + (data.refTs ? ` · data up to ${fmtDate(data.refTs, true)} · ${SOURCES.find(s => s.key === data.source).label}` : '');

        const th = (k, label, cls = 'num') =>
            `<th data-sort="${k}" class="${cls} ${sort.key === k ? 'sorted' + (sort.asc ? ' asc' : '') : ''}">${label}</th>`;
        const pct = (v, win) => v == null ? '<span class="flat">—</span>'
            : `${fmtChange(v)}<div class="m">${win != null ? Math.round(win) + '% win' : ''}</div>`;
        const body = rows.map(r => {
            const b = localHour(r.buyHour), s = localHour(r.sellHour, r.hold);
            const dayShift = s.day - b.day;
            return `<tr data-id="${esc(r.id)}">
                <td><div class="name-cell"><img loading="lazy" src="icons/${esc(r.id)}.png" alt="">
                    <div><div class="n">${esc(itemName(r))}</div><div class="s">${esc(r.mainLabel)} · ${esc(r.subLabel || '')}</div></div></div></td>
                <td class="num"><span class="t">${hhmm(b.hh)}</span><div class="m">~${fmtPrice(r.buyPrice)} FE</div></td>
                <td class="num"><span class="t">${hhmm(s.hh)}${dayShift ? ` <span class="m">+${dayShift}d</span>` : ''}</span><div class="m">~${fmtPrice(r.sellPrice)} FE</div></td>
                <td class="num"><span class="value ${r.profitPerUnit > 0 ? 'up' : 'down'}">${r.profitPerUnit >= 0 ? '+' : '-'}${fmtPrice(Math.abs(r.profitPerUnit))}<img src="${FE_ICON}" alt=""></span></td>
                <td class="num">${pct(r.trainAvg, r.trainWin)}</td>
                <td class="num">${pct(r.testAvg, r.testWin)}</td>
                <td class="num">${fmtChange(r.worst)}</td>
                <td class="num">${r.volatility.toFixed(1)}%<div class="m">range ${r.dailyRange.toFixed(1)}%</div></td>
                <td class="num">${r.changesPerDay.toFixed(1)}</td>
                <td class="num">${fmtChange(r.trend)}</td>
                <td>${returnBars(r.returns, r.testDays)}</td>
            </tr>`;
        }).join('');

        $('#flipTable').innerHTML = rows.length ? `
            <table class="prices flips">
                <thead><tr>${th('name', 'Item', '')}${th('buyPrice', 'Buy')}${th('sellPrice', 'Sell')}${th('profitPerUnit', 'Profit / unit')}
                    ${th('trainAvg', 'Train avg')}${th('testAvg', 'Test avg')}${th('worst', 'Worst')}${th('volatility', 'Volatility')}
                    ${th('changesPerDay', 'Activity')}${th('trend', 'Trend')}${th('score', 'Each trade', '')}</tr></thead>
                <tbody>${body}</tbody>
            </table>` : `<div class="empty">${data.rows.length ? 'No items match these filters, try relaxing them' : 'Not enough history yet: the backfill may still be running'}</div>`;

        $('#flipTable').querySelectorAll('th[data-sort]').forEach(el => el.onclick = () => {
            const k = el.dataset.sort;
            sort = {key: k, asc: sort.key === k ? !sort.asc : k === 'name'};
            draw();
        });
        $('#flipTable').querySelectorAll('tr[data-id]').forEach(el => el.onclick = () => {
            location.hash = `#/${season}/item/${el.dataset.id}`;
        });
    }

    await fetchData();
}

// ---------- status footer

function renderStatus(s) {
    const bf = s.backfill === 'done' ? 'history loaded'
        : s.backfillTotal ? `loading history: ${esc(s.backfill)} ${s.backfillDone}/${s.backfillTotal}` : esc(s.backfill);
    $('#status').innerHTML = (s.generatedAt ? `Site updated ${ago(s.generatedAt)} · ` : '') + `PoeBench prices: ${ago(s.lastTablesAt)} · ${bf}`
        + (s.pricePoints != null ? ` · ${s.pricePoints.toLocaleString('en-US')} price points` : '')
        + ` · data: <a href="https://tli.re-side.dev" target="_blank" rel="noopener">TLI Prices</a> (PoeBench)`;
}

// ---------- boot

async function boot() {
    state.meta = await DATA.meta();
    $('#season').innerHTML = state.meta.seasons
        .filter(s => s.hasData || s.current)
        .map(s => `<option value="${s.id}">${esc(s.label)}${s.current ? ' (current)' : ''}</option>`).join('');
    $('#lang').value = state.lang;

    $('#season').onchange = e => {
        const parts = location.hash.replace(/^#\/?/, '').split('/');
        parts[0] = e.target.value;
        location.hash = '#/' + parts.join('/');
    };
    $('#lang').onchange = e => { state.lang = e.target.value; save('lang', state.lang); route(); };
    window.addEventListener('hashchange', route);
    window.addEventListener('resize', () => state.chart && state.chart.setSize({width: $('#chart').clientWidth, height: 380}));

    setupGlobalSearch();
    renderStatus({...state.meta.status, generatedAt: state.meta.generatedAt, pricePoints: state.meta.pricePoints});
    if (!STATIC) setInterval(() => DATA.status().then(renderStatus).catch(() => {}), 60000);
    route();
}

boot().catch(showError);
