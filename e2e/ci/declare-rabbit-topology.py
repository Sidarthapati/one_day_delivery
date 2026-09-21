#!/usr/bin/env python3
"""
Pre-declare the full RabbitMQ topology into a FRESH broker (CI service container) before the backend
boots. A virgin broker has no queues, and the app's auto-configured RabbitAdmin only partially
declares them on an empty vhost (the documented "14/18 on a fresh vhost" race — listener containers
race RabbitAdmin on the shared connection; consumers that lose the race stay in a NOT_FOUND retry
loop forever, so e.g. m5.shipments never receives ShipmentCreated → bookings never reach
PICKUP_ASSIGNED). Locally this is invisible because the puffin vhost has the queues persisted.

We declare EXACTLY what common/RabbitStreamSupport builds — durable topic exchanges, one durable
queue per (consumer, exchange) with x-dead-letter-exchange=<exchange>.dlx, a <exchange>.dlx +
<exchange>.dlq, and "#" bindings — so the app's later re-declaration is idempotent (identical args).

Env: RABBIT_MGMT_URL (default http://localhost:15672), RABBIT_USER/RABBIT_PASS (default guest/guest).
"""
import base64, json, os, sys, time, urllib.error, urllib.request

MGMT = os.environ.get("RABBIT_MGMT_URL", "http://localhost:15672").rstrip("/")
USER = os.environ.get("RABBIT_USER", "guest")
PASS = os.environ.get("RABBIT_PASS", "guest")
VHOST = "%2F"  # "/"

# (consumer queue name) -> (source exchange). Mirrors every RabbitStreamSupport.consumer(...) call.
CONSUMERS = {
    "m5.shipments": "oneday.shipments.events",
    "m5.cron": "oneday.cron.events",
    "m5.hub": "oneday.hub.events",
    "m5.delivery-confirmations": "oneday.delivery.confirmations",
    "exceptions.shipments": "oneday.shipments.events",
    "exceptions.da": "oneday.da.events",
    "exceptions.delivery_confirmations": "oneday.delivery.confirmations",
    "sla.shipments": "oneday.shipments.events",
    "sla.hub": "oneday.hub.events",
    "sla.cron": "oneday.cron.events",
    "sla.flight": "oneday.flight.events",
    "sla.da": "oneday.da.events",
    "sla.exceptions": "oneday.exceptions.events",
    "routing.hub": "oneday.hub.events",
    "routing.da": "oneday.da.events",
    "orders.da": "oneday.da.events",
    "orders.hub": "oneday.hub.events",
    "orders.scan": "oneday.scan.events",
    "orders.flight": "oneday.flight.events",
    "orders.cron": "oneday.cron.events",
    "orders.exceptions": "oneday.exceptions.events",
    "airline.hub": "oneday.hub.events",
    "hub.shipments": "oneday.shipments.events",
    "hub.flight": "oneday.flight.events",
    "grid.tile-queue-depth": "orders.tile_queue_depth",
}

# Producer-only exchanges with no in-app consumer yet — declare so publishes aren't dropped.
PRODUCER_ONLY = [
    "oneday.sla.events",
    "oneday.asset.events",
    "oneday.grid.events",
    "oneday.notifications.events",
]

_auth = "Basic " + base64.b64encode(f"{USER}:{PASS}".encode()).decode()


def call(method, path, body=None):
    url = f"{MGMT}{path}"
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method,
                                headers={"Authorization": _auth, "Content-Type": "application/json"})
    with urllib.request.urlopen(req) as r:
        return r.status


def wait_for_mgmt(timeout=90):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            call("GET", "/api/overview")
            return
        except Exception:
            time.sleep(2)
    print("::error::RabbitMQ management API never came up", file=sys.stderr)
    sys.exit(1)


def exchange(name):
    call("PUT", f"/api/exchanges/{VHOST}/{name}", {"type": "topic", "durable": True})


def queue(name, dlx=None):
    args = {"x-dead-letter-exchange": dlx} if dlx else {}
    call("PUT", f"/api/queues/{VHOST}/{name}", {"durable": True, "arguments": args})


def bind(exch, q):
    call("POST", f"/api/bindings/{VHOST}/e/{exch}/q/{q}", {"routing_key": "#"})


def main():
    wait_for_mgmt()
    exchanges = set(CONSUMERS.values()) | set(PRODUCER_ONLY)
    # Source exchanges + their dead-letter exchanges + dead-letter queues.
    for ex in sorted(exchanges):
        exchange(ex)
        exchange(f"{ex}.dlx")
        queue(f"{ex}.dlq")
        bind(f"{ex}.dlx", f"{ex}.dlq")
    # Consumer queues (dead-lettering to <exchange>.dlx) + their bindings.
    for q, ex in sorted(CONSUMERS.items()):
        queue(q, dlx=f"{ex}.dlx")
        bind(ex, q)
    n_q = len(CONSUMERS) + len(exchanges)
    print(f"Declared {len(exchanges)} exchanges (+ .dlx), {n_q} queues, and their bindings.")


if __name__ == "__main__":
    main()
