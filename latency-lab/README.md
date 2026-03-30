# Latency Percentile Lab

A hands-on Spring Boot project to learn **p50, p95, and p99 latency** by seeing it, not just reading about it.

Fire requests at a simulated API, watch the percentile distribution form in real time, and understand why averages lie.

## Quick Start

### 1. Start the Spring Boot app

```bash
cd latency-lab
mvn spring-boot:run
```

Open [http://localhost:8090](http://localhost:8090) — built-in dashboard with live histogram.

### 2. Start Prometheus + Grafana (optional but recommended)

```bash
docker compose up -d
```

- Prometheus: [http://localhost:9090](http://localhost:9090)
- Grafana: [http://localhost:3000](http://localhost:3000) (admin / admin) — "Latency Lab" dashboard is pre-provisioned

### 3. Fire load batches

```bat
load.bat
```

Fires 20 → 100 → 200 → 500 requests with pauses so you can watch p50/p95/p99 shift in Grafana in real time.

No database, no Redis. Just Java 17 + Maven. Docker only needed for Prometheus/Grafana.

## Tech Stack

- Java 17 + Spring Boot 3.4
- Micrometer + Prometheus (industry-standard metrics)
- Vanilla HTML/JS dashboard (no frontend framework)

## Project Structure

```
latency-lab/
  src/main/java/com/example/latencylab/
    config/
      LabProperties.java          -- configurable latency weights from application.yml
      MetricsConfig.java          -- Micrometer Timer bean with percentile publishing
    controller/
      LabController.java          -- API endpoints: simulate, batch, percentiles, reset
    service/
      LatencySimulator.java       -- simulates cache hits, DB reads, timeouts with jitter
      PercentileCalculator.java   -- manual sort-and-pick calculator (see the math)
  src/main/resources/
    application.yml               -- latency weights, Prometheus config
    static/
      index.html                  -- live dashboard with histogram and stats
  pom.xml
```

## API Endpoints

| Endpoint | Method | What it does |
|----------|--------|-------------|
| `/api/simulate` | POST | Fire one request with random latency, returns scenario + duration |
| `/api/simulate/batch?count=N` | POST | Fire N requests (max 500), builds up data fast |
| `/api/percentiles` | GET | Returns manually calculated p50/p90/p95/p99/avg/min/max |
| `/api/samples` | GET | Returns raw sorted latency samples for the histogram |
| `/api/reset` | POST | Clears all collected data |
| `/actuator/prometheus` | GET | Prometheus-format metrics (histogram buckets, counts, sum) |
| `/` | GET | Live dashboard |

---

# Understanding p50, p95, and p99 Latency

## Who is this for?

You know what an API is. You can build one. You call it, it responds. But you've never thought deeply about **how long** it takes to respond, and why some users have a worse experience than others. This guide takes you from zero to understanding percentile latency like an engineer at scale.

---

## Part 1: Prerequisites (What You Need to Know First)

### 1.1 What is Latency?

Latency is the time between **sending a request** and **receiving the response**.

```
You (client)                    Server
  |                               |
  |---- request sent ---->        |
  |                          [processing]
  |        <---- response --------|
  |                               |
  |<------- latency ------------>|
```

If you call `POST /api/v1/llm/chat` and it takes 320ms to get the response back, the **latency** of that request is **320ms**.

Simple. But here's the thing -- not every request takes the same time.

### 1.2 Why Do Requests Take Different Times?

Call the same API 10 times. You might get:

```
Request 1:   45ms
Request 2:   52ms
Request 3:   48ms
Request 4:   51ms
Request 5:  310ms   <-- slow! why?
Request 6:   47ms
Request 7:   49ms
Request 8:   53ms
Request 9:   46ms
Request 10: 780ms   <-- very slow! why?
```

Why is Request 5 slower? Why is Request 10 even slower? Real reasons:

| Cause                  | What happens                                         |
| ---------------------- | ---------------------------------------------------- |
| **Database query**     | Sometimes the DB cache misses and it reads from disk |
| **Garbage collection** | Java/Go/.NET pause to clean up memory                |
| **Network congestion** | The packet took a longer route or hit a busy switch  |
| **Cold cache**         | First request after deploy has no cache, hits DB     |
| **Noisy neighbor**     | Another service on the same machine is hogging CPU   |
| **Connection pool**    | All DB connections were busy, request waited in line |
| **Retry**              | Upstream service failed, your code retried once      |

> **GC pauses explained:** When GC runs it triggers a stop-the-world pause — all threads freeze while memory is cleaned up, and any in-flight requests just wait. This is why p99 looks so much worse than p50: most requests finish before GC fires, but the unlucky one that runs during a pause gets hit with 100–200ms of extra latency for free. Switching to ZGC (`-XX:+UseZGC`) brings those pauses down to under 1ms.

The point: **latency is not a single number. It's a distribution.**

### 1.3 The Problem with Averages

Your boss asks: "How fast is our API?"

You calculate the average of the 10 requests above:

```
(45 + 52 + 48 + 51 + 310 + 47 + 49 + 53 + 46 + 780) / 10 = 148.1ms
```

Average = 148ms. Sounds okay. But look at the data:

- **8 out of 10** requests were under 55ms
- **1 request** was 310ms
- **1 request** was 780ms

The average of 148ms **describes nobody**. No real user experienced 148ms. Most got ~50ms, a few got destroyed.

This is the fundamental problem: **averages hide the pain of your worst-off users.**

You might wonder — if the causes are GC pauses, cache misses, and network delays, what can you even do? You can't stop GC from running. You can't guarantee a cache hit. The answer is that you don't need to eliminate these causes — you need to reduce their impact and bound how bad they get. Switching to ZGC makes GC pauses negligible. Warming the cache on startup means users never see a cold start. And for the outliers you can't prevent, two patterns matter most: **timeouts** (cap how long you'll wait and return a degraded response instead of hanging) and **hedged requests** (after waiting 95ms, fire a second identical request in parallel and take whichever responds first — Google uses this to kill tail latency at a cost of only ~5% extra load).

### 1.4 What is a Percentile?

A percentile is always a pair: a **percentage** and a **response time**. Together they tell you: "X% of requests completed in Y milliseconds or less."

For example, p90 = 310ms means 90% of requests finished in 310ms or less. To find it: sort all recorded response times from lowest to highest, then pick the value at the 90th position out of 100.

Let's sort our 10 requests:

```
Position:  1     2     3     4     5     6     7     8     9     10
Value:    45ms  46ms  47ms  48ms  49ms  51ms  52ms  53ms  310ms 780ms
```

- **50th percentile (p50):** The value where 50% of requests are at or below.
  Position = 50% of 10 = 5th value = **49ms**

- **90th percentile (p90):** 90% of requests are at or below.
  Position = 90% of 10 = 9th value = **310ms**

- **100th percentile (p100):** The maximum.
  Position = 10th value = **780ms**

The p50 (49ms) tells a very different story than the average (148ms). It says "half your users get 49ms or better." The p90 (310ms) says "10% of your users are waiting over 310ms."

The whole idea: hit your API some number of times, collect the response time of each request, sort them, and pick positions. p50 is the 50th value, p95 is the 95th, p99 is the 99th. In production your app records this automatically and Prometheus calculates it continuously on a rolling window — but the mechanic is exactly that: sort and pick.

### 1.5 Common Misconceptions

The 100-request example only exists to make the math easy. In production percentiles are calculated over a rolling time window (last 5 minutes, last hour) and update continuously with every request. You're not running a one-time test — you're watching a live picture that shifts with traffic.

A natural follow-up is: why not optimize for p100, the absolute worst case? Because p100 is almost meaningless — it's the single slowest request ever recorded, possibly one packet retransmitted at 2am due to a hardware blip. p99 gives you the tail without being held hostage by a one-in-a-million fluke.

A good p50 does not mean the API is fine. You could have p50 = 40ms and p99 = 4000ms — most users happy, but one in a hundred waiting 4 full seconds. A good p50 is necessary but not sufficient.

Finally, p99 is per request, not per user. A user visiting your app might trigger 20 API calls behind the scenes. Each independently has a 1% chance of being slow, so that user has far more than a 1% chance of experiencing something slow during their session — which leads directly into the tail-at-scale problem in section 2.2.

---

## Part 2: p50, p95, p99 -- The Core Concept

### 2.1 What Each Percentile Means

| Percentile | What it answers | In plain English |
|------------|----------------|-----------------|
| **p50** (median) | What does the **typical** user experience? | "Half of all requests are faster than this" |
| **p95** | What do your **unlucky** users experience? | "1 in 20 requests is slower than this" |
| **p99** | What do your **most unlucky** users experience? | "1 in 100 requests is slower than this" |

### 2.2 Why These Specific Numbers?

**p50 (median)** -- Your baseline. If this is slow, everything is slow. Fix your core performance.

**p95** -- At scale, this matters a lot. If you serve 1,000 requests/second:
- p95 = 500ms means **50 requests every second** take over 500ms
- That's 50 users per second having a bad time
- That's 180,000 frustrated users per hour

**p99** -- The tail. Seems negligible (1 in 100), but:
- A single web page might make 20-50 API calls
- If each call has a 1% chance of being slow, the probability that **at least one** is slow:
  - 20 calls: 1 - (0.99)^20 = **18% chance** the page feels slow
  - 50 calls: 1 - (0.99)^50 = **39% chance** the page feels slow
  - The formula is `1 - (chance of fast)^number_of_calls`. Each call has a 99% chance of being fast. For all 20 to be fast: 0.99 × 0.99 × ... 20 times = (0.99)^20. Subtract from 1 to get the chance at least one is slow.
- This is called the **tail-at-scale** problem

### 2.3 A Real Example

Imagine an e-commerce checkout page that calls 5 services:

```
User clicks "Pay"
  -> Auth service      (verify user)
  -> Inventory service (check stock)
  -> Payment service   (charge card)
  -> Email service     (send receipt)
  -> Analytics service (log event)
```

Each service has these latencies:

```
Service      p50     p95     p99
Auth         12ms    45ms    200ms
Inventory    8ms     30ms    150ms
Payment      50ms    200ms   800ms
Email        15ms    60ms    300ms
Analytics    5ms     20ms    100ms
```

These 5 services are called **sequentially** — one after the other, each waiting for the previous to finish. So the total latency is just the sum.

**For the typical user (p50):** 12 + 8 + 50 + 15 + 5 = **90ms** -- great!

**For the unlucky user (if any ONE service hits its p99):**
Worst case: 12 + 8 + **800** + 15 + 5 = **840ms** -- almost a full second just waiting.

And with 5 services, the probability that at least one hits p99:
1 - (0.99)^5 = **~5%** of checkouts have at least one slow call.

That's why companies like Amazon, Google, and Netflix obsess over p99.

### 2.4 Visual Intuition

```
Number of
requests
  |
  |  ****
  | ******
  |********
  |*********
  |**********
  |***********
  |************                *
  |*************          *   * *
  |**************     *  * * *   *     *        *
  +-----|---------|---------|---------|---------|-----> Latency (ms)
       50ms     100ms     200ms     500ms    1000ms
        ^                   ^                  ^
       p50                 p95                p99

  [-- most requests --]  [tail]            [far tail]
```

Most requests cluster on the left (fast). The "tail" stretches to the right. p95 and p99 capture how bad that tail gets.

---

## Part 3: How to Measure Percentiles

### 3.1 The Naive Way (collect all values, sort, pick)

```
Collect 1000 response times in a list
Sort the list
p50 = list[499]    (500th value)
p95 = list[949]    (950th value)
p99 = list[989]    (990th value)
```

Problem: storing all values forever uses too much memory.

### 3.2 The Real Way (sliding window + histograms)

In production, you use libraries that:
1. Keep a **rolling time window** (e.g., last 5 minutes)
2. Use **histogram buckets** or **digest algorithms** (t-digest, HDR histogram) to approximate percentiles without storing every value
3. Expose metrics that monitoring tools (Prometheus, Grafana, Datadog) can scrape

### 3.3 Tools That Do This

| Tool | How |
|------|-----|
| **Micrometer** (Java/Spring Boot) | Built-in. `Timer` metric records latency, exposes percentiles |
| **Prometheus** | Scrapes histogram buckets, calculates percentiles server-side |
| **Grafana** | Visualizes percentile data as time-series graphs |
| **Datadog / New Relic** | SaaS platforms that compute and alert on percentiles |

This project uses **both** approaches:

- `PercentileCalculator.java` — the naive sort-and-pick (so you see the math)
- Micrometer `Timer` + Prometheus endpoint — the production way

### 3.4 Actually Setting This Up (Spring Boot)

Knowing the concept is one thing. Here is how it is wired in this project so percentiles are visible in Grafana.

The flow:

```text
Your API  -->  Micrometer  -->  Prometheus  -->  Grafana
(records        (already in      (scrapes every    (dashboards
every req)      Spring Boot)     5s, stores data)   & alerts)
```

#### Dependencies

Micrometer is already inside Spring Boot. You only need:

```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

#### Config

Tell Spring which percentiles to expose (`application.yml`):

```yaml
management:
  endpoints:
    web:
      exposure:
        include: prometheus, health
  metrics:
    distribution:
      percentiles-histogram:
        http.server.requests: true
      percentiles:
        http.server.requests: 0.5, 0.95, 0.99
```

That is the only code change needed. Spring Boot + Micrometer automatically intercepts every HTTP request and records its response time. No annotations, no manual instrumentation.

#### Verify

Start the app, hit any endpoint, then open [http://localhost:8090/actuator/prometheus](http://localhost:8090/actuator/prometheus). You will see:

```text
http_server_requests_seconds{quantile="0.5",...}   0.045
http_server_requests_seconds{quantile="0.95",...}  0.310
http_server_requests_seconds{quantile="0.99",...}  0.780
```

Prometheus scrapes that endpoint every 5s (`monitoring/prometheus.yml`). **Grafana** queries Prometheus with:

```text
histogram_quantile(0.99,
  sum(rate(lab_request_latency_seconds_bucket[1m])) by (le)
)
```

The dashboard is pre-provisioned — just open [http://localhost:3000](http://localhost:3000) after `docker compose up -d`.

---

## Part 4: Rules of Thumb

### What's "good"?

There's no universal answer -- it depends on what the API does. But general guidelines:

| API type | p50 target | p95 target | p99 target |
|----------|-----------|-----------|-----------|
| In-memory lookup | < 5ms | < 20ms | < 50ms |
| Database read | < 20ms | < 100ms | < 300ms |
| Database write | < 50ms | < 200ms | < 500ms |
| External API call | < 100ms | < 500ms | < 1500ms |
| LLM inference | < 2s | < 10s | < 30s |

### Warning Signs

| Signal | What it means |
|--------|--------------|
| p50 is high | Your core logic is slow. Profile it. |
| p95 is much higher than p50 | You have occasional slowdowns (GC, cache miss, connection pool contention) |
| p99 is much higher than p95 | You have rare but severe outliers (timeouts, retries, cold starts) |
| p99 keeps growing over time | You have a resource leak (memory, connections, threads) |

### The Golden Rule

> **Optimize for p99, not for averages.**
> If your p99 is good, your p50 is almost certainly good too.
> But a good p50 tells you nothing about your p99.

---

## Part 5: Common Interview / System Design Questions

**Q: Why not just use average latency?**
A: Averages hide outliers. 99 requests at 10ms and 1 request at 10,000ms gives an average of 109ms -- which describes nobody's experience.

**Q: Which percentile should I alert on?**
A: Alert on p95 or p99, not p50. p50 moving means a systemic problem. p99 spiking means tail latency is degrading, which affects more users than you think (tail-at-scale).

**Q: How does p99 relate to SLAs?**
A: SLAs are often written as "p99 latency < 500ms for 99.9% of the time." This means: 99 out of 100 requests must complete in under 500ms, and this must hold true 99.9% of the time (measured over a month).

**Q: How do microservices make this worse?**
A: Each hop adds latency. If you chain 5 services sequentially, the overall p99 is dominated by the slowest service's p99. This is why service meshes, caching, and async patterns matter.

---

## Part 6: Experiments

### Experiment 1: See the Distribution Form

1. Open the dashboard at [http://localhost:8090](http://localhost:8090)
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

## Part 7: Code Walkthrough

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

You can see the raw Prometheus output at: [http://localhost:8090/actuator/prometheus](http://localhost:8090/actuator/prometheus)

### The Controller (`LabController.java`)

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

Hit [http://localhost:8090/actuator/prometheus](http://localhost:8090/actuator/prometheus) and search for `lab_request_latency`.

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

---

## Quick Reference

```
Percentile    Meaning                   At 1000 req/s, this many are slower
----------    -----------------------   -----------------------------------
p50           Half are slower           500 req/s
p90           10% are slower            100 req/s
p95           5% are slower             50 req/s
p99           1% are slower             10 req/s
p99.9         0.1% are slower           1 req/s
```
