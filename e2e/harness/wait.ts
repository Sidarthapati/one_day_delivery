/** State-gating helpers — always poll for an observed condition, never a hard sleep. */

export interface PollOpts {
  timeoutMs?: number;
  intervalMs?: number;
  label?: string;
}

export async function pollUntil<T>(
  fetchFn: () => Promise<T>,
  predicate: (value: T) => boolean,
  opts: PollOpts = {},
): Promise<T> {
  const timeoutMs = opts.timeoutMs ?? 60_000;
  const intervalMs = opts.intervalMs ?? 1_500;
  const deadline = Date.now() + timeoutMs;
  let last: T | undefined;
  while (Date.now() < deadline) {
    try {
      last = await fetchFn();
      if (predicate(last)) return last;
    } catch {
      // transient — keep polling until the deadline
    }
    await sleep(intervalMs);
  }
  throw new Error(
    `pollUntil timed out after ${timeoutMs}ms${opts.label ? ` waiting for ${opts.label}` : ''}` +
      (last !== undefined ? ` (last: ${JSON.stringify(last)})` : ''),
  );
}

export function sleep(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms));
}
