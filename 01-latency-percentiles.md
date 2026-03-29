# Understanding p50, p95, and p99 Latency

## Who is this for?

You know what an API is. You can build one. You call it, it responds. But you've never thought deeply about **how long** it takes to respond, and why some users have a worse experience than others. This guide takes you from zero to understanding percentile latency like an engineer at scale.

---

## Part 1: Prerequisites (What You Need to Know First)

### 1.1 What is Latency?

Latency is the time between **sending a request** and **receiving the response**.

```text
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

```text
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

> **Note — GC pauses explained:**
> When GC runs, it triggers a "stop-the-world" pause — all application threads freeze while memory is cleaned up. Any in-flight requests just wait.
>
> This is why p99/p999 latency looks much worse than p50:
>
> - **p50**: most requests finish fast, no GC happened
> - **p99**: that 1-in-100 request got unlucky — a GC pause hit mid-request
> - **p999**: a full (major) GC hit, which can pause for 100ms–several seconds
>
> Example:
>
> ```text
> Request A: starts at t=0ms,  finishes at t=5ms    ← normal
> Request B: starts at t=10ms, GC fires at t=11ms,
>            GC pauses 150ms,  finishes at t=165ms  ← GC victim
> ```
>
> Request B shows up as a p99 outlier even though your business logic took 5ms.
>
> **Java GC options by pause behavior:**
>
> | GC                 | Pause                                                         |
> | ------------------ | ------------------------------------------------------------- |
> | Serial/Parallel GC | Long stop-the-world (avoid for APIs)                          |
> | G1GC (default)     | Short, incremental (~10–200ms)                                |
> | ZGC / Shenandoah   | Sub-millisecond (concurrent, best for latency-sensitive APIs) |
>
> For latency-sensitive services, use ZGC: `-XX:+UseZGC`

| **Network congestion** | The packet took a longer route or hit a busy switch  |
| **Cold cache**         | First request after deploy has no cache, hits DB     |
| **Noisy neighbor**     | Another service on the same machine is hogging CPU   |
| **Connection pool**    | All DB connections were busy, request waited in line |
| **Retry**              | Upstream service failed, your code retried once      |

The point: **latency is not a single number. It's a distribution.**

### 1.3 The Problem with Averages

Your boss asks: "How fast is our API?"

You calculate the average of the 10 requests above:

```text
(45 + 52 + 48 + 51 + 310 + 47 + 49 + 53 + 46 + 780) / 10 = 148.1ms
```

Average = 148ms. Sounds okay. But look at the data:

- **8 out of 10** requests were under 55ms
- **1 request** was 310ms
- **1 request** was 780ms

The average of 148ms **describes nobody**. No real user experienced 148ms. Most got ~50ms, a few got destroyed.

This is the fundamental problem: **averages hide the pain of your worst-off users.**

Now, you might wonder — if the causes are things like GC pauses, cache misses, and network delays, what can you even do? You can't stop GC from running. You can't guarantee a cache hit. You don't own the network.

The answer is that you don't need to eliminate these causes. You need to **reduce their impact and bound how bad they get.**

GC pauses, for example, are unavoidable — but switching to a concurrent collector like ZGC brings stop-the-world pauses from hundreds of milliseconds down to under a millisecond. The GC still runs. You just stop noticing it. Similarly, a cache miss will always happen on the first request after a deploy — but if you warm the cache on startup before accepting traffic, users never see it.

The outliers you can't prevent, you can contain. Two patterns matter most here:

- **Timeouts** — cap how long you'll wait for a downstream call. Return a degraded response instead of hanging.
- **Hedged requests** — after waiting, say, 95ms, fire a second identical request in parallel and take whichever responds first. The cost is roughly 5% extra load. The gain is that your slowest requests suddenly look fast. Google uses this technique extensively in their internal infrastructure.

Some p999 outliers are just physics — a packet retransmitted, a GC hiccup, a noisy neighbor on shared hardware. These will happen. The engineering goal is not zero bad responses. It's making sure that when things go wrong, they go wrong **predictably and within acceptable bounds** — which is exactly what SLOs are for.

### 1.4 What is a Percentile?

A percentile is always a pair: a **percentage** and a **response time**. Together they tell you: "X% of requests completed in Y milliseconds or less."

For example, p90 = 310ms means 90% of requests finished in 310ms or less. The 90 is the percentage. The 310ms is the response time at that cut-off. To find it: sort all recorded response times from lowest to highest, then pick the value at the 90th position out of 100.

Let's sort our 10 requests:

```text
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

The whole idea is simple: hit your API some number of times, collect the response time of each request, sort them, and pick positions. p50 is the 50th value, p95 is the 95th, p99 is the 99th. That value at that position is your latency at that percentile.

In production you don't do this manually — your app records every request's response time automatically, and tools like Prometheus and Grafana calculate and graph these percentiles continuously in real time. But the underlying mechanic is exactly that: sort and pick.

### 1.5 Clearing Up What This Concept Actually Is

A few things tend to confuse people when they first encounter percentile latency.

The 100-request example only exists to make the math easy to follow. In production, your API receives thousands of requests per minute and percentiles are calculated continuously over a rolling time window — last 5 minutes, last hour. Every new request shifts the numbers slightly. You're not running a one-time test; you're watching a live picture that updates constantly. The question you're answering in production is not "what was p99 for my test run" but "what is p99 right now over the last 5 minutes of real traffic."

A natural follow-up is: why not just optimize for p100, the absolute worst case? Because p100 is almost meaningless. It is the single slowest request ever recorded, which could be one packet that got retransmitted due to a hardware blip at 2am on a Tuesday. Chasing it would mean optimizing for a one-in-a-million fluke instead of patterns that actually affect real users. p99 gives you the tail without being held hostage by a single outlier.

Another common assumption is that a good p50 means the API is fine. It doesn't. p50 tells you the typical request is fast — it says nothing about the other 50%. You could have a p50 of 40ms and a p99 of 4000ms, meaning most users are happy but one in a hundred is waiting 4 full seconds. A good p50 is necessary but not sufficient.

Finally, p99 is per request, not per user. A single user visiting your app might trigger 20 API calls behind the scenes. Each of those calls independently has a 1% chance of hitting the slow path, so that user has far more than a 1% chance of experiencing something slow during their session. This is the tail-at-scale problem covered in section 2.2 — the more calls involved, the more the tail matters.

---

## Part 2: p50, p95, p99 -- The Core Concept

### 2.1 What Each Percentile Means

| Percentile        | What it answers                                   | In plain English                            |
| ----------------- | ------------------------------------------------- | ------------------------------------------- |
| **p50** (median)  | What does the **typical** user experience?        | "Half of all requests are faster than this" |
| **p95**           | What do your **unlucky** users experience?        | "1 in 20 requests is slower than this"      |
| **p99**           | What do your **most unlucky** users experience?   | "1 in 100 requests is slower than this"     |

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
  - The formula is `1 - (chance of fast)^number_of_calls`. Each call has a 99% chance of being fast. For all 20 to be fast, that's 0.99 × 0.99 × ... 20 times = (0.99)^20. Subtract from 1 to get the chance that at least one is slow.
- This is called the **tail-at-scale** problem

### 2.3 A Real Example

Imagine an e-commerce checkout page that calls 5 services:

```text
User clicks "Pay"
  -> Auth service      (verify user)
  -> Inventory service (check stock)
  -> Payment service   (charge card)
  -> Email service     (send receipt)
  -> Analytics service (log event)
```

Each service has these latencies:

```text
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

```text
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

```text
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

| Tool                              | How                                                            |
| --------------------------------- | -------------------------------------------------------------- |
| **Micrometer** (Java/Spring Boot) | Built-in. `Timer` metric records latency, exposes percentiles  |
| **Prometheus**                    | Scrapes histogram buckets, calculates percentiles server-side  |
| **Grafana**                       | Visualizes percentile data as time-series graphs               |
| **Datadog / New Relic**           | SaaS platforms that compute and alert on percentiles           |

### 3.4 Actually Setting This Up (Spring Boot)

Knowing the concept is one thing. Here is how you wire it up in a real Spring Boot API so percentiles are measured and visible in Grafana.

The pieces fit together like this:

```text
Your API  -->  Micrometer  -->  Prometheus  -->  Grafana
(records        (library,        (scrapes &        (dashboards
every req)      built into       stores metrics)    & alerts)
                Spring Boot)
```

#### Step 1 — Add dependencies

Micrometer is already inside Spring Boot. You only need the Prometheus exporter and Actuator:

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

#### Step 2 — Configure which percentiles to expose

In `application.yml`:

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

#### Step 3 — Verify it works

Start your app and hit any endpoint, then open:

```text
http://localhost:8080/actuator/prometheus
```

You will see raw metrics like this:

```text
http_server_requests_seconds{quantile="0.5",...}   0.045
http_server_requests_seconds{quantile="0.95",...}  0.310
http_server_requests_seconds{quantile="0.99",...}  0.780
```

That is your p50, p95, p99 in raw form. Prometheus will scrape this endpoint and store it over time.

#### Step 4 — Point Prometheus at your app

Prometheus runs as a separate process. Its config tells it where to scrape:

```yaml
# prometheus.yml
scrape_configs:
  - job_name: 'my-api'
    scrape_interval: 15s
    static_configs:
      - targets: ['localhost:8080']
    metrics_path: '/actuator/prometheus'
```

Every 15 seconds, Prometheus pulls the metrics from your app and stores them.

#### Step 5 — Query percentiles in Grafana

Connect Grafana to Prometheus as a data source, then use this query to graph p99 latency over time per endpoint:

```text
histogram_quantile(0.99,
  sum(rate(http_server_requests_seconds_bucket[5m])) by (le, uri)
)
```

Or skip writing queries entirely — import dashboard ID **4701** from Grafana's public dashboard library. It is a pre-built Spring Boot dashboard that shows p50, p95, and p99 out of the box.

**Summary of effort:**

| What                                    | Effort                     |
| --------------------------------------- | -------------------------- |
| 2 dependencies + 6 lines of config      | One time setup             |
| Request latency recording               | Automatic, zero extra code |
| Prometheus scraping                     | Run as a Docker container  |
| Grafana p50/p95/p99 graphs              | Import dashboard 4701      |

---

## Part 4: Rules of Thumb

### What's "good"?

There's no universal answer -- it depends on what the API does. But general guidelines:

| API type            | p50 target | p95 target | p99 target |
| ------------------- | ---------- | ---------- | ---------- |
| In-memory lookup    | < 5ms      | < 20ms     | < 50ms     |
| Database read       | < 20ms     | < 100ms    | < 300ms    |
| Database write      | < 50ms     | < 200ms    | < 500ms    |
| External API call   | < 100ms    | < 500ms    | < 1500ms   |
| LLM inference       | < 2s       | < 10s      | < 30s      |

### Warning Signs

| Signal                      | What it means                                                              |
| --------------------------- | -------------------------------------------------------------------------- |
| p50 is high                 | Your core logic is slow. Profile it.                                       |
| p95 is much higher than p50 | You have occasional slowdowns (GC, cache miss, connection pool contention) |
| p99 is much higher than p95 | You have rare but severe outliers (timeouts, retries, cold starts)         |
| p99 keeps growing over time | You have a resource leak (memory, connections, threads)                    |

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

## Part 6: Hands-On Project

We'll build a Spring Boot app that:

1. Has an API endpoint with **realistic variable latency** (simulates DB calls, cache hits/misses)
2. Records every request's response time
3. Calculates and exposes **p50, p95, p99** via a metrics endpoint
4. Uses **Micrometer + Prometheus** (industry standard) for real percentile tracking
5. Optionally visualizes with a simple HTML dashboard

This will let you:

- See percentiles change in real time as you hit the API
- Understand how a few slow requests shift p95/p99
- Simulate real-world scenarios (cache miss, GC pause, timeout)

### Project structure

```text
latency-lab/
  src/main/java/.../
    config/         MetricsConfig
    controller/     LabController (the API), MetricsController (view percentiles)
    service/        LatencySimulator (simulates variable-speed work)
  src/main/resources/
    application.yml
    static/
      index.html    (simple dashboard)
  pom.xml
```

**Ready to build? Let's go step by step in the next file.**

---

## Quick Reference

```text
Percentile    Meaning                   At 1000 req/s, this many are slower
----------    -----------------------   -----------------------------------
p50           Half are slower           500 req/s
p90           10% are slower            100 req/s
p95           5% are slower             50 req/s
p99           1% are slower             10 req/s
p99.9         0.1% are slower           1 req/s
```
