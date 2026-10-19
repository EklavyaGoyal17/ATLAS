import { useRef, useState } from "react";
import { api } from "./api";
import { Notice } from "./ui";
import { useDocuments, download, type Document } from "./workspace";
type Node = {
  id: string;
  name: string;
  type: string;
  responsibility: string;
  replicas: number;
  x: number;
  y: number;
};
type Edge = { id: string; from: string; to: string; pattern: string };
type Design = { nodes: Node[]; edges: Edge[]; assumptions: string };
const types = [
  "API gateway",
  "Backend service",
  "Relational database",
  "Cache",
  "Queue",
  "Load balancer",
  "Object storage",
  "Authentication",
  "Worker",
  "External dependency",
];
const templates: Record<string, string[]> = {
  "Payment processing": [
    "API gateway",
    "Backend service",
    "Relational database",
    "Queue",
    "Worker",
  ],
  "Transaction history API": [
    "Load balancer",
    "Backend service",
    "Cache",
    "Relational database",
  ],
  "Notification delivery": [
    "Backend service",
    "Queue",
    "Worker",
    "External dependency",
  ],
  "Distributed job processor": [
    "API gateway",
    "Queue",
    "Worker",
    "Object storage",
  ],
  "Idempotent webhook handler": [
    "External dependency",
    "API gateway",
    "Backend service",
    "Relational database",
  ],
  "Audit event pipeline": [
    "Backend service",
    "Queue",
    "Worker",
    "Object storage",
  ],
};
function template(name: string): Design {
  const responsibilities:Record<string,string>={"API gateway":"Authenticate and validate bounded requests; propagate request identity.","Backend service":"Own business rules and the transaction boundary; expose typed operations.","Relational database":"Persist durable state, constraints and transactional records.","Cache":"Serve replaceable read data with an explicit invalidation/expiry policy.","Queue":"Retain pending messages and expose acknowledgement/redelivery semantics.","Load balancer":"Distribute requests across healthy service instances.","Object storage":"Persist artifacts or batched audit archives with a retention policy.","Authentication":"Verify identities and issue scoped credentials.","Worker":"Consume durable work with bounded retries and idempotent effects.","External dependency":"Receive bounded network requests; failures and timeouts are independent."};
  const nodes = templates[name].map((type, i) => ({
    id: `n${i}`,
    name: type,
    type,
    responsibility: responsibilities[type],
    replicas: 1,
    x: 70 + (i % 3) * 310,
    y: 70 + Math.floor(i / 3) * 260,
  }));
  const edges=nodes.slice(1).map((n,i)=>({id:`e${i}`,from:nodes[i].id,to:n.id,pattern:n.type==="Queue"?"async enqueue":nodes[i].type==="Queue"?"async consume":n.type==="Relational database"?"SQL transaction":"sync request"}));
  if(name==="Payment processing") {
    nodes[4].name="Outbox relay";nodes[4].responsibility="Poll committed outbox records, publish immutable envelopes, and record acknowledgement with retry fencing.";
    edges.splice(2,2,{id:"e2",from:"n4",to:"n2",pattern:"poll committed outbox"},{id:"e3",from:"n4",to:"n3",pattern:"publish durable event"});
  }
  if(name==="Transaction history API") edges[2]={id:"e2",from:"n1",to:"n3",pattern:"SQL on cache miss"};
  return {
    nodes,
    edges,
    assumptions:
      "Educational design. Capacity and availability are unverified. State workload, latency budget, replication and failure domains before estimating.",
  };
}
export function Studio({ token }: { token: string }) {
  const docs = useDocuments(token, 0, "DIAGRAM");
  const [id, setId] = useState<string>(crypto.randomUUID());
  const [version, setVersion] = useState(0);
  const [title, setTitle] = useState("Payment processing");
  const [design, setDesign] = useState<Design>(() =>
    template("Payment processing"),
  );
  const [selected, setSelected] = useState("n0");
  const [from, setFrom] = useState("n0");
  const [to, setTo] = useState("n1");
  const [pattern, setPattern] = useState("sync request");
  const [message, setMessage] = useState("");
  const [error, setError] = useState<Error | null>(null);
  const [revisions, setRevisions] = useState<
    { version: number; title: string; body_json: string }[]
  >([]);
  const svg = useRef<SVGSVGElement>(null);
  const dragging = useRef<string | null>(null);
  const current = design.nodes.find((n) => n.id === selected);
  function patch(fields: Partial<Node>) {
    setDesign((d) => ({
      ...d,
      nodes: d.nodes.map((n) => (n.id === selected ? { ...n, ...fields } : n)),
    }));
    setMessage("");
  }
  function load(doc: Document) {
    setId(doc.id);
    setVersion(doc.version);
    setTitle(doc.title);
    setDesign(doc.body as unknown as Design);
    setSelected("");
    setMessage(`Loaded version ${doc.version}`);
    setRevisions([]);
  }
  async function save() {
    try {
      const saved = await docs.save.mutateAsync({
        id,
        kind: "DIAGRAM",
        title,
        version,
        body: design as unknown as Record<string, unknown>,
      });
      setVersion(saved.version);
      setMessage(`Saved version ${saved.version}`);
      setRevisions([]);
    } catch {
      /* Notice displays the mutation error. */
    }
  }
  const concerns = design.nodes
    .filter((n) => n.replicas === 1)
    .map(
      (n) =>
        `${n.name}: one configured instance is a potential single point of failure. Replication alone does not establish failover.`,
    );
  return (
    <div className="module-stack">
      <Notice
        error={
          error || docs.save.error || docs.remove.error || docs.query.error
        }
      />
      <section className="panel padded">
        <h2>Design a system you can explain</h2>
        <p>
          Drag components or use arrow keys on a focused node. Connections
          describe declared communication. Record assumptions before making
          capacity claims.
        </p>
        <div className="form-row">
          <label>
            Example design
            <select
              onChange={(e) => {
                setDesign(template(e.target.value));
                setTitle(e.target.value);
                setId(crypto.randomUUID());
                setVersion(0);
                setSelected("n0");
                setMessage("");
              }}
              value={Object.hasOwn(templates, title) ? title : "custom"}
            >
              <option value="custom" disabled>
                Custom design
              </option>
              {Object.keys(templates).map((t) => (
                <option key={t}>{t}</option>
              ))}
            </select>
          </label>
          <label>
            Saved architecture
            <select
              value={version ? id : ""}
              onChange={(e) => {
                const doc = docs.query.data?.find(
                  (d) => d.id === e.target.value,
                );
                if (doc) load(doc);
              }}
            >
              <option value="">Unsaved design</option>
              {docs.query.data
                ?.filter((d) => d.kind === "DIAGRAM")
                .map((d) => (
                  <option key={d.id} value={d.id}>
                    {d.title} · v{d.version}
                  </option>
                ))}
            </select>
          </label>
          <button
            className="primary"
            disabled={docs.save.isPending || !title.trim()}
            onClick={() => void save()}
          >
            Save architecture version
          </button>
        </div>
        <label>
          Architecture title
          <input
            value={title}
            maxLength={120}
            onChange={(e) => setTitle(e.target.value)}
          />
        </label>
        {message && <p role="status">{message}</p>}
      </section>
      <section className="panel diagram-panel">
        <svg
          ref={svg}
          className="architecture-canvas"
          viewBox="0 0 1000 640"
          aria-label="Editable architecture diagram"
          onPointerMove={(e) => {
            if (!dragging.current || !svg.current) return;
            const point = new DOMPoint(e.clientX, e.clientY).matrixTransform(
              svg.current.getScreenCTM()!.inverse(),
            );
            const node = dragging.current;
            setDesign((d) => ({
              ...d,
              nodes: d.nodes.map((n) =>
                n.id === node
                  ? {
                      ...n,
                      x: Math.max(10, Math.min(800, point.x - 90)),
                      y: Math.max(20, Math.min(550, point.y - 28)),
                    }
                  : n,
              ),
            }));
          }}
          onPointerUp={() => {
            dragging.current = null;
          }}
          onPointerCancel={() => {
            dragging.current = null;
          }}
        >
          <defs>
            <marker
              id="arrowhead"
              markerWidth="8"
              markerHeight="8"
              refX="7"
              refY="3"
              orient="auto"
            >
              <path d="M0,0 L0,6 L8,3 z" fill="#67cbb0" />
            </marker>
          </defs>
          {design.edges.map((edge) => {
            const a = design.nodes.find((n) => n.id === edge.from),
              b = design.nodes.find((n) => n.id === edge.to);
            return a && b ? (
              <g key={edge.id}>
                <line
                  x1={a.x + 90}
                  y1={a.y + 28}
                  x2={b.x + 90}
                  y2={b.y + 28}
                  stroke="#67cbb0"
                  strokeWidth="2"
                  markerEnd="url(#arrowhead)"
                />
                <text
                  x={(a.x + b.x) / 2 + 90}
                  y={(a.y + b.y) / 2 + 14}
                  fill="#a2b4c5"
                  fontSize="11"
                  textAnchor="middle"
                >
                  {edge.pattern}
                </text>
              </g>
            ) : null;
          })}
          {design.nodes.map((n) => (
            <g
              key={n.id}
              role="button"
              tabIndex={0}
              aria-label={`Component ${n.name}`}
              onClick={() => setSelected(n.id)}
              onPointerDown={(e) => {
                setSelected(n.id);
                dragging.current = n.id;
                e.currentTarget.setPointerCapture(e.pointerId);
              }}
              onKeyDown={(e) => {
                if (
                  ["ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown"].includes(
                    e.key,
                  )
                ) {
                  e.preventDefault();
                  setSelected(n.id);
                  setDesign((d) => ({
                    ...d,
                    nodes: d.nodes.map((v) =>
                      v.id === n.id
                        ? {
                            ...v,
                            x: Math.max(
                              10,
                              Math.min(
                                800,
                                v.x +
                                  (e.key === "ArrowRight"
                                    ? 10
                                    : e.key === "ArrowLeft"
                                      ? -10
                                      : 0),
                              ),
                            ),
                            y: Math.max(
                              20,
                              Math.min(
                                550,
                                v.y +
                                  (e.key === "ArrowDown"
                                    ? 10
                                    : e.key === "ArrowUp"
                                      ? -10
                                      : 0),
                              ),
                            ),
                          }
                        : v,
                    ),
                  }));
                }
                if (e.key === "Enter" || e.key === " ") {
                  e.preventDefault();
                  setSelected(n.id);
                }
              }}
            >
              <rect
                x={n.x}
                y={n.y}
                width="180"
                height="64"
                rx="10"
                fill={selected === n.id ? "#164b45" : "#17232e"}
                stroke={selected === n.id ? "#82e2c2" : "#405366"}
              />
              <text x={n.x + 12} y={n.y + 25} fill="#e5edf2" fontSize="13">
                {n.name.slice(0, 24)}
              </text>
              <text x={n.x + 12} y={n.y + 46} fill="#a1b4c6" fontSize="11">
                {n.type} · {n.replicas} instance(s)
              </text>
            </g>
          ))}
        </svg>
      </section>
      <div className="two-columns">
        <section className="panel padded">
          <h2>Components & responsibilities</h2>
          <div className="button-row">
            <label>
              Selected component
              <select
                value={selected}
                onChange={(e) => setSelected(e.target.value)}
              >
                <option value="">Select component</option>
                {design.nodes.map((n) => (
                  <option key={n.id} value={n.id}>
                    {n.name}
                  </option>
                ))}
              </select>
            </label>
            <button
              className="secondary"
              disabled={design.nodes.length >= 24}
              onClick={() => {
                const node: Node = {
                  id: crypto.randomUUID(),
                  name: "New service",
                  type: "Backend service",
                  responsibility: "",
                  replicas: 1,
                  x: 50,
                  y: 380,
                };
                setDesign((d) => ({ ...d, nodes: [...d.nodes, node] }));
                setSelected(node.id);
              }}
            >
              Add component
            </button>
          </div>
          {current && (
            <div className="module-stack">
              <label>
                Component name
                <input
                  value={current.name}
                  maxLength={50}
                  onChange={(e) => patch({ name: e.target.value })}
                />
              </label>
              <label>
                Component type
                <select
                  value={current.type}
                  onChange={(e) => patch({ type: e.target.value })}
                >
                  {types.map((t) => (
                    <option key={t}>{t}</option>
                  ))}
                </select>
              </label>
              <label>
                Responsibility
                <textarea
                  value={current.responsibility}
                  maxLength={500}
                  onChange={(e) => patch({ responsibility: e.target.value })}
                />
              </label>
              <label>
                Configured instances
                <input
                  type="number"
                  min="1"
                  max="100"
                  value={current.replicas}
                  onChange={(e) =>
                    patch({
                      replicas: Math.max(
                        1,
                        Math.min(100, Number(e.target.value)),
                      ),
                    })
                  }
                />
              </label>
              <button
                className="secondary"
                onClick={() => {
                  setDesign((d) => ({
                    ...d,
                    nodes: d.nodes.filter((n) => n.id !== selected),
                    edges: d.edges.filter(
                      (e) => e.from !== selected && e.to !== selected,
                    ),
                  }));
                  setSelected("");
                }}
              >
                Remove component and its connections
              </button>
            </div>
          )}
        </section>
        <section className="panel padded">
          <h2>Communication</h2>
          <div className="form-row">
            <label>
              From component
              <select value={from} onChange={(e) => setFrom(e.target.value)}>
                <option value="">Choose</option>
                {design.nodes.map((n) => (
                  <option value={n.id} key={n.id}>
                    {n.name}
                  </option>
                ))}
              </select>
            </label>
            <label>
              To component
              <select value={to} onChange={(e) => setTo(e.target.value)}>
                <option value="">Choose</option>
                {design.nodes.map((n) => (
                  <option value={n.id} key={n.id}>
                    {n.name}
                  </option>
                ))}
              </select>
            </label>
          </div>
          <label>
            Communication pattern
            <input
              value={pattern}
              maxLength={80}
              onChange={(e) => setPattern(e.target.value)}
            />
          </label>
          <button
            className="secondary"
            disabled={
              from === to ||
              !design.nodes.some((n) => n.id === from) ||
              !design.nodes.some((n) => n.id === to) ||
              !pattern ||
              design.edges.length >= 48
            }
            onClick={() =>
              setDesign((d) => ({
                ...d,
                edges: [
                  ...d.edges,
                  { id: crypto.randomUUID(), from, to, pattern },
                ],
              }))
            }
          >
            Connect components
          </button>
          {design.edges.map((e) => (
            <div className="edge-row" key={e.id}>
              <span>
                {design.nodes.find((n) => n.id === e.from)?.name} →{" "}
                {design.nodes.find((n) => n.id === e.to)?.name}: {e.pattern}
              </span>
              <button
                className="text-button"
                aria-label={`Remove connection ${e.pattern}`}
                onClick={() =>
                  setDesign((d) => ({
                    ...d,
                    edges: d.edges.filter((v) => v.id !== e.id),
                  }))
                }
              >
                Remove
              </button>
            </div>
          ))}
        </section>
      </div>
      <section className="panel padded">
        <h2>Assumptions & failure analysis</h2>
        <label>
          Design assumptions
          <textarea
            value={design.assumptions}
            maxLength={2000}
            onChange={(e) =>
              setDesign((d) => ({ ...d, assumptions: e.target.value }))
            }
          />
        </label>
        {concerns.map((c) => (
          <p className="callout" key={c}>
            {c}
          </p>
        ))}
        <p>
          Inspect shared storage, failure domains, ordering, backpressure,
          recovery time and data-loss budgets. This topology check does not
          measure availability.
        </p>
        <div className="button-row">
          <button
            className="secondary"
            onClick={() =>
              download(
                "atlas-architecture.json",
                JSON.stringify({ title, version, ...design }, null, 2),
              )
            }
          >
            Export diagram JSON
          </button>
          <button
            className="secondary"
            onClick={() =>
              download(
                "atlas-architecture.svg",
                new XMLSerializer().serializeToString(svg.current!),
                "image/svg+xml",
              )
            }
          >
            Export SVG
          </button>
          <button
            className="secondary"
            disabled={!version}
            onClick={() =>
              void api<typeof revisions>(
                token,
                `/workspace/documents/${id}/revisions`,
              )
                .then(setRevisions)
                .catch((e) => setError(e))
            }
          >
            Load saved versions
          </button>
        </div>
        {revisions.map((r) => (
          <button
            className="secondary"
            key={r.version}
            onClick={() => {
              setTitle(r.title);
              setDesign(JSON.parse(r.body_json));
              setMessage(
                `Previewing revision ${r.version}; Save creates version ${version + 1}.`,
              );
            }}
          >
            Preview v{r.version}
          </button>
        ))}
        {version > 0 && (
          <button
            className="secondary"
            onClick={() =>
              docs.remove.mutate(id, {
                onSuccess: () => {
                  setId(crypto.randomUUID());
                  setVersion(0);
                  setMessage("Saved architecture and its revisions deleted.");
                },
              })
            }
          >
            Delete saved architecture
          </button>
        )}
      </section>
    </div>
  );
}
