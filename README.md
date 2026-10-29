# moneyTorch

Price tracker for Torchlight: Infinite in the spirit of poe.ninja: item tables by category with a 7 day
sparkline, and per-item charts covering a whole season.

## Online (GitHub Pages)

`.github/workflows/update.yml` runs every 30 minutes (and on every push to `main`):

1. restores the H2 database from the Actions cache (`data/`),
2. runs the jar in export mode: catches up on history (at most 90 min per run, continues next run),
   collects fresh prices once, writes static JSON into `site/`,
3. saves the database back to the cache and deploys `site/` to GitHub Pages.

In static mode (`config.js` sets `window.MT_STATIC = true`) the frontend reads `data/*.json` instead of `/api`
and the hourly finder runs in the browser. The very first runs take longer while the history downloads.

One-time setup: repository Settings → Pages → Source: **GitHub Actions**, then Actions → *Update prices* → Run.

Export locally:

```
java -jar target/moneytorch-0.1.0.jar --moneytorch.mode=export --spring.main.web-application-type=none --moneytorch.export-dir=site
```

## Run locally

```
mvn package -DskipTests
java -jar target/moneytorch-0.1.0.jar
```

Open http://localhost:8080. Data lives in `./data` (H2 database + cached icons).

## Data

Only PoeBench prices are used, via TLI Prices. The API is neither official nor documented, so it can change or disappear.

| Source | Used for |
|---|---|
| [TLI Prices](https://tli.re-side.dev) `/api/v1/market/list` | item catalog, names (en/ru/zh), categories |
| TLI Prices `/api/v1/market/item/{id}?range=all` | one-time backfill of full season history |
| TLI Prices `/api/v1/prices` | current price table every 30 min |

On first start the backfill downloads the current and previous standard season, about 3k requests
throttled by `moneytorch.backfill.delay-ms`, so roughly an hour or more. It is resumable: progress is stored per item and
the job continues after a restart. Seasons can be pinned with `moneytorch.backfill.seasons` in
`application.yml`.

All prices are in Flame Elementium (FE) and are one reference price per item, not an order book.
Items whose value depends on mods (e.g. Divinity Slates) only have a placeholder price.

## API

- `GET /api/meta`: seasons, categories, job status
- `GET /api/overview?season=1601&source=poebench`: price table
- `GET /api/items/{id}?season=1601[&compare=1501]`: full history of one item
- `GET /api/status`

## Flips

- **League days** (`#/{season}/flips/{buyDay}/{sellDay}`): prices of every item for every season day
  (day 1 = UTC date of the season start), as day low / median / high / open / close. Compare a buy day with a
  sell day (profit after tax), or switch to the grid to see all days at once coloured against the buy day.
- **Hourly patterns**: backtests "buy at hour X, sell at hour Y" strategies on recent days.

For frontend work, serve static files straight from the source folder:
`java -jar target/moneytorch-0.1.0.jar --spring.web.resources.static-locations=file:src/main/resources/static/`
