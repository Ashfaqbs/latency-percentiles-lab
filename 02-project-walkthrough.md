# Latency Lab -- Project Walkthrough

## How to Run

```bash
cd C:\tmp\temp\learning-latency-percentiles\latency-lab
mvn spring-boot:run
```

Open your browser: **http://localhost:8090**

You'll see the dashboard. That's it -- no database, no Redis, no Docker. Just a single Spring Boot app.

---

## What to Do (Experiments)

### Experiment 1: See the Distribution Form

1. Open the dashboard at http://localhost:8090
2. Click **"Fire 200"**
3. Wait for it to complete (~15-20 seconds)
4. Look at the stats:

```
Expected result:
  p50:  ~15ms      (most requests are fast cache hits)
  p95:  ~450ms     (1 in 20 is a cache miss + DB)
  p99:  ~1500ms    (1 in 100 is a timeout/GC pause)
  avg:  ~85ms      (pulled up by the slow outliers)
```

**What to notice:**
- The average (~85ms) is 5-6x higher than p50 (~15ms)
- Nobody actually experienced 85ms -- it's a phantom number
- p99 is ~100x worse than p50 -- that's a big tail

### Experiment 2: Average Lies to You

After Experiment 1, look at the insight box. It tells you:

> "Average is 5.3x higher than median. This means outliers are dragging the average up."

If you reported "our average latency is 85ms" to your boss, you'd be telling a misleading story. The real story is:
- 70% of users get ~15ms (great)
- 20% get ~120ms (okay)
- 8% get ~450ms (noticeable)
- 2% get ~1500ms (terrible)

### Experiment 3: Watch p99 Spike

1. Click **Reset All**
2. Click **"Fire 50"** (small sample)
3. Note the p99 -- it might be moderate
4. Click **"Fire 1 Request"** several times
5. If you're unlucky, one request hits the "timeout" path (1500ms)
6. Watch p99 jump dramatically from one slow request

**Lesson:** p99 is volatile with small sample sizes. You need enough data for it to be meaningful.

### Experiment 4: Histogram Shape

After firing 200+ requests, look at the histogram:

```
  |****
  |*******
  |**********
  |*************     **
  |***************  ****  *     *
  +---------|--------|--------|-------> ms
           p50      p95     p99

  [green]        [yellow]   [red]
  < p50          p50-p95    > p95
```

- The tall green bars on the left: your happy users (fast cache hits)
- The yellow bars in the middle: your okay users (DB reads)
- The red bars on the far right: your tail -- few requests, but very slow

This shape is called a **right-skewed distribution** and it's what almost every real API looks like.

---

## Code Walkthrough

### The Simulator (`LatencySimulator.java`)

This simulates what happens in a real API:

```
70% of requests: 15ms   (cache hit -- data already in memory)
20% of requests: 120ms  (database read -- had to go to disk)
8% of requests:  450ms  (cache miss + DB + extra processing)
2% of requests:  1500ms (upstream timeout or GC pause)
```

Each request randomly rolls which scenario it gets, plus +/- 20% jitter for realism. This creates the exact kind of latency distribution you see in production.

**Why these numbers?** They map to real causes:
- 15ms = Redis/in-memory cache lookup
- 120ms = PostgreSQL query (cold cache, index scan)
- 450ms = Multiple DB queries + serialization + network hop
- 1500ms = Connection pool exhaustion, JVM GC pause, or upstream service timeout

### The Manual Calculator (`PercentileCalculator.java`)

This stores the last 1000 latency samples and calculates percentiles by sorting:

```java
// The actual math:
Collections.sort(snapshot);
int index = (percentile / 100.0 * size) - 1;
return sorted.get(index);
```

For p50 with 1000 samples: index = 499 (the 500th value).
For p99 with 1000 samples: index = 989 (the 990th value).

This is the **naive approach** -- it works, but uses memory proportional to sample count. In production, you'd use Micrometer's histogram (which we also have).

### The Micrometer Timer (`MetricsConfig.java`)

```java
Timer.builder("lab.request.latency")
    .publishPercentiles(0.5, 0.95, 0.99)        // compute these percentiles
    .publishPercentileHistogram()                 // also publish histogram buckets
    .register(registry);
```

This creates an industry-standard timer that:
- Tracks every request duration
- Computes p50, p95, p99 using a memory-efficient algorithm (not storing all values)
- Publishes histogram buckets for Prometheus to scrape

You can see the raw Prometheus output at: http://localhost:8090/actuator/prometheus

### The Controller (`LabController.java`)

Three main endpoints:

| Endpoint | What it does |
|----------|-------------|
| `POST /api/simulate` | Fires one request, records latency, returns what happened |
| `POST /api/simulate/batch?count=N` | Fires N requests sequentially, builds up data fast |
| `GET /api/percentiles` | Returns our manually calculated p50/p95/p99/avg/min/max |

Both `simulate` and `batch` record into **two** systems:
1. Our manual `PercentileCalculator` (so you can see the math)
2. Micrometer's `Timer` (the production way, feeds Prometheus)

### The Dashboard (`index.html`)

Pure HTML + vanilla JS. No framework. It:
1. Calls the API endpoints via fetch
2. Displays the stats as cards
3. Draws a histogram on a canvas element
4. Color-codes bars: green (< p50), yellow (p50-p95), red (> p95)
5. Draws dashed lines at p50/p95/p99 positions
6. Shows an insight box comparing avg vs p50 and p99 vs p50

---

## How This Maps to Production

| This lab | Production equivalent |
|----------|----------------------|
| `LatencySimulator.simulateWork()` | Your actual service logic (DB queries, HTTP calls, etc.) |
| `PercentileCalculator` | Micrometer / Prometheus / Datadog agent |
| `GET /api/percentiles` | Grafana dashboard or Datadog APM |
| `POST /api/simulate/batch` | Load testing with k6, JMeter, or Locust |
| The histogram on the dashboard | Grafana's "Heatmap" or "Histogram" panel |
| `GET /actuator/prometheus` | Prometheus scrape endpoint (scraped every 15-30s) |

---

## Prometheus Metrics Explained

Hit http://localhost:8090/actuator/prometheus and search for `lab_request_latency`.

You'll see lines like:

```
lab_request_latency_seconds_bucket{le="0.05"} 141
lab_request_latency_seconds_bucket{le="0.1"} 147
lab_request_latency_seconds_bucket{le="0.5"} 193
lab_request_latency_seconds_bucket{le="1.0"} 199
lab_request_latency_seconds_bucket{le="+Inf"} 201
```

This means:
- 141 requests completed in <= 50ms
- 147 requests completed in <= 100ms
- 193 requests completed in <= 500ms
- 199 requests completed in <= 1 second
- 201 total requests

Prometheus uses these buckets to calculate percentiles server-side. Grafana queries it with:
```
histogram_quantile(0.99, rate(lab_request_latency_seconds_bucket[5m]))
```

---

## Key Takeaways

1. **Average is misleading.** Always look at percentiles.
2. **p50 = the typical user.** If this is slow, your core logic is slow.
3. **p95 = the unlucky user.** At 1000 req/s, 50 users per second experience this.
4. **p99 = the very unlucky user.** But with fan-out (multiple API calls per page), this affects way more users than you think.
5. **The histogram shape tells the story.** A tight cluster with a long right tail is normal. A flat spread means everything is inconsistent.
6. **Optimize for p99, not average.** If p99 is good, average is definitely good.
