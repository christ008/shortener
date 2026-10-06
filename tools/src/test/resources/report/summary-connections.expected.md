| | maxconn-1000 | maxconn-250 | maxconn-500 | maxconn-8192 |
|---|---|---|---|---|
| Time until ready (ms, from `docker run`) | 356 | 435 | 425 | 434 |
| Spring startup | 0.258 seconds | 0.269 seconds | 0.265 seconds | 0.266 seconds |
| Memory when idle | 92.09MiB | 108.4MiB | 91.91MiB | 98.02MiB |
| Peak memory under load (MiB) | 168 | 174 | 136 | 142 |
| **5000 req/s for 60s** |  |  |  |  |
| achieved req/s | 4939 | 4942 | 4917 | 4936 |
| failed requests | 0.00% | 0.00% | 0.00% | 0.00% |
| redirect p50 / p95 / p99 (ms, server) | 0.5 / 1.0 / 1.0 | 0.5 / 1.0 / 1.0 | 0.5 / 1.0 / 1.0 | 0.5 / 1.0 / 1.0 |
| create p50 / p99 (ms, server) | 3.2 / 37.4 | 3.3 / 46.7 | 3.2 / 90.0 | 3.4 / 44.8 |
| redirect p95 (ms, as k6 saw it) | 7.8 | 4.8 | 8.6 | 5.0 |
| pool acquire max (ms) / timeouts | 306.7 / 0 | 124.2 / 0 | 108.1 / 0 | 106.2 / 0 |
