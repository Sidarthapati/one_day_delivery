-- M5 dispatch: track how many times a deferred dispatch has been retried, so DeferredRetryJob can
-- escalate to M11 after the configured cap (dispatch.deferred.max-retries) instead of retrying forever.
ALTER TABLE deferred_dispatch
    ADD COLUMN retry_count INT NOT NULL DEFAULT 0;
