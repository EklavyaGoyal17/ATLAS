type Edge = { from: string; to: string; path: string; line: number };
export function SourceMap({
  edges,
  open,
}: {
  edges: Edge[];
  open: (path: string, line: number) => void;
}) {
  const names = [...new Set(edges.flatMap((e) => [e.from, e.to]))]
    .sort()
    .slice(0, 24);
  const positions = new Map(
    names.map((name, i) => [
      name,
      { x: 35 + (i % 4) * 240, y: 35 + Math.floor(i / 4) * 120 },
    ]),
  );
  const visible = edges
    .filter((e) => positions.has(e.from) && positions.has(e.to))
    .slice(0, 48);
  return (
    <>
      <svg
        className="source-map"
        viewBox={`0 0 1000 ${Math.max(180, Math.ceil(names.length / 4) * 120)}`}
        aria-label="Java declared type dependency graph"
      >
        <defs>
          <marker
            id="source-arrow"
            markerWidth="8"
            markerHeight="8"
            refX="7"
            refY="3"
            orient="auto"
          >
            <path d="M0,0 L0,6 L8,3 z" fill="#66bcac" />
          </marker>
        </defs>
        {visible.map((e, i) => {
          const a = positions.get(e.from)!,
            b = positions.get(e.to)!;
          return (
            <line
              key={i}
              x1={a.x + 90}
              y1={a.y + 25}
              x2={b.x + 90}
              y2={b.y + 25}
              stroke="#456d6b"
              strokeWidth="2"
              markerEnd="url(#source-arrow)"
            />
          );
        })}
        {names.map((name) => {
          const p = positions.get(name)!,
            edge =
              visible.find((e) => e.from === name) ??
              visible.find((e) => e.to === name);
          return (
            <g
              key={name}
              role="button"
              tabIndex={0}
              aria-label={`Inspect declaration ${name}`}
              onClick={() => edge && open(edge.path, edge.line)}
              onKeyDown={(e) => {
                if (e.key === "Enter" || e.key === " ") {
                  e.preventDefault();
                  if (edge) open(edge.path, edge.line);
                }
              }}
            >
              <rect
                x={p.x}
                y={p.y}
                width="185"
                height="50"
                rx="8"
                fill="#142b32"
                stroke="#597c7d"
              />
              <text
                x={p.x + 92}
                y={p.y + 30}
                textAnchor="middle"
                fill="#c8e7e1"
                fontSize="12"
              >
                {name.slice(0, 23)}
              </text>
            </g>
          );
        })}
      </svg>
      <p className="muted">
        Bounded overview: first 24 type names and 48 declarations. Full
        declaration evidence appears below. Select a type to inspect a field
        declaration involving it.
      </p>
    </>
  );
}
