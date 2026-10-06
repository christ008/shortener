| | jvm | native | native-young30 |
|---|---|---|---|
| Time until ready (ms, from `docker run`) | 5444 | 732 | 608 |
| Spring startup | 4.594 seconds | 0.429 seconds | 0.384 seconds |
| Memory when idle | 250.4MiB | 209.9MiB | 159.8MiB |
| Peak memory under load (MiB) | 512 | 512 | 512 |
| **1500 req/s for 60s** |  |  |  |
| achieved req/s | 1480 | 1485 | 1485 |
| failed requests | 0.00% | 0.00% | 0.00% |
| redirect p50 / p95 / p99 (ms, server) | 0.5 / 1.0 / 1.0 | 0.5 / 1.0 / 4.6 | 0.5 / 1.2 / 4.8 |
| create p50 / p99 (ms, server) | 2.1 / 6.1 | 2.2 / 10.1 | 2.3 / 12.8 |
| redirect p95 (ms, as k6 saw it) | 0.5 | 1.8 | 1.9 |
| pool acquire max (ms) / timeouts | 921.9 / 0 | 46.3 / 0 | 29.4 / 0 |
| **5000 req/s for 60s** |  |  |  |
| achieved req/s | 4942 | 3264 | 122 |
| failed requests | 0.00% | 1.61% | 29.79% |
| redirect p50 / p95 / p99 (ms, server) | 0.5 / 1.0 / 2.4 | 0.9 / 96.2 / 3018.1 | n/a / n/a / n/a |
| create p50 / p99 (ms, server) | 2.0 / 7.3 | 2.8 / 193.9 | n/a / n/a |
| redirect p95 (ms, as k6 saw it) | 1.0 | 211.8 | 60000.7 |
| pool acquire max (ms) / timeouts | 921.9 / 0 | 21489.8 / 3424 | n/a / 0 |
| **10000 req/s for 30s** |  |  |  |
| achieved req/s | 713 | 318 | 0 |
| failed requests | 3.83% | 51.79% | 50.00% |
| redirect p50 / p95 / p99 (ms, server) | 112.3 / 1427.8 / 2370.4 | 9886.4 / 14730.5 / 15569.3 | n/a / n/a / n/a |
| create p50 / p99 (ms, server) | 128.6 / 968.6 | 11739.6 / 15605.0 | n/a / n/a |
| redirect p95 (ms, as k6 saw it) | 23867.0 | 19323.5 | 0.0 |
| pool acquire max (ms) / timeouts | 17267.8 / 1252 | 22069.3 / 6016 | n/a / 0 |
