export type EndpointMetric = {
  operation: string;
  requests: number;
  errors: number;
  errorRate: number;
  latencySamples: number;
  p50Ms: number;
  p95Ms: number;
  p99Ms: number;
  windowStart: string;
  windowEnd: string;
};
export type EndpointLimits = {
  operations: number;
  samplesPerOperation: number;
  totalSamples: number;
};
export type HttpEndpointMetrics = {
  endpoints: EndpointMetric[];
  endpointLimits: EndpointLimits;
  endpointScope: string;
};

export function EndpointMetrics({
  endpoints,
  endpointLimits,
  endpointScope,
}: HttpEndpointMetrics) {
  const rows = [...endpoints].sort(
    (a, b) => b.p95Ms - a.p95Ms || a.operation.localeCompare(b.operation),
  );
  return (
    <section className="panel padded" aria-label="HTTP operation metrics">
      <h2>Which operation is slow?</h2>
      <p>{endpointScope}</p>
      <p className="muted">
        Budget: {endpointLimits.operations} fixed operation labels, at most{" "}
        {endpointLimits.samplesPerOperation} latency samples each (
        {endpointLimits.totalSamples} total). Ordered by retained p95. Different
        sample counts and windows are not controlled performance comparisons.
      </p>
      {rows.length ? (
        <div
          className="table-scroll"
          role="region"
          aria-label="HTTP operation measurements"
          tabIndex={0}
        >
          <table>
            <thead>
              <tr>
                <th scope="col">Operation</th>
                <th scope="col">Lifetime requests</th>
                <th scope="col">Lifetime errors</th>
                <th scope="col">Retained samples</th>
                <th scope="col">p50 / p95 / p99 (ms)</th>
                <th scope="col">Sample start-time window</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={row.operation}>
                  <th scope="row">
                    <code>{row.operation}</code>
                  </th>
                  <td>{row.requests}</td>
                  <td>
                    {row.errors} ({(row.errorRate * 100).toFixed(1)}%)
                  </td>
                  <td>{row.latencySamples}</td>
                  <td>
                    {row.p50Ms.toFixed(2)} / {row.p95Ms.toFixed(2)} /{" "}
                    {row.p99Ms.toFixed(2)}
                  </td>
                  <td>
                    <time dateTime={row.windowStart}>{row.windowStart}</time>
                    {" → "}
                    <time dateTime={row.windowEnd}>{row.windowEnd}</time>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <p>
          No authenticated operation samples in this JVM yet. Run a payment or
          repository workflow, then refresh telemetry.
        </p>
      )}
    </section>
  );
}
