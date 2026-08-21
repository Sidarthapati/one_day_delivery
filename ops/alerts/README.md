# Alerting rules

Prometheus/Grafana alert rules for the Godspeed backend — golden signals, saturation, and
dependency health. Rules live in [`godspeed-alerts.yml`](./godspeed-alerts.yml).

## Metric sources

| Rule group | Metric prefix | Source |
|------------|---------------|--------|
| golden-signals | `http_server_requests_*` | App `/actuator/prometheus` (Micrometer) |
| saturation | `hikaricp_*`, `jvm_*`, `tomcat_*` | App `/actuator/prometheus` |
| infra: `up`, readiness | `up`, `http_server_requests_*` | Prometheus scrape + app |
| infra: DLQ, broker | `rabbitmq_*` | **RabbitMQ exporter / CloudAMQP metrics** (not the app) |

The `rabbitmq_*` series are **not** produced by the app. Either scrape CloudAMQP's Prometheus
endpoint or run [`rabbitmq_exporter`](https://github.com/kbudde/rabbitmq_exporter) against the broker
and add it as a Prometheus target. Until that target exists, the DLQ/broker alerts stay dormant (no
series → no firing); the app-metric alerts work as soon as Prometheus scrapes the app.

## Wiring

1. Add the app as a scrape target (`job: godspeed-backend`, path `/actuator/prometheus`). The
   actuator endpoint is behind auth — configure the scraper with the credentials, or expose
   `prometheus` on an internal-only path.
2. Reference the rule file from Prometheus:
   ```yaml
   rule_files:
     - /etc/prometheus/rules/godspeed-alerts.yml
   ```
   or import the group into Grafana Cloud → Alerting.
3. Point Alertmanager/Grafana at a notification channel (Slack/email/PagerDuty).

## Thresholds

Starting points for a single Render web service — see
[`../../docs/prod-readiness/CAPACITY-PLAN.md`](../../docs/prod-readiness/CAPACITY-PLAN.md). Re-tune
error-rate/latency/pool bars from the k6 load run so alerts fire before users feel it, not after.

## Note on prod activation

These rules describe the **prod** golden signals; they can run against staging today for validation
(staging exposes the same metrics). Nothing here changes app behaviour — it's an external observer.
