import { BookOpen, LockKeyhole } from "lucide-react";
export function Guide() {
  const cards = [
    [
      "01",
      "A retry is a new delivery, not a new intent.",
      "Each merchant/key identifies an immutable command. ATLAS locks that key, compares a SHA-256 fingerprint, and returns the stored original response. A different payload receives a conflict.",
      "Why does a unique constraint alone not solve response replay?",
    ],
    [
      "02",
      "Keep the decision and its effects atomic.",
      "State, audit event, ledger posting, and response commit together. If the connection drops after commit, the original key recovers the result. JDBC keeps the SQL and locks visible.",
      "What happens when two different keys capture the same payment?",
    ],
    [
      "03",
      "Balanced is necessary. It is not sufficient.",
      "A paired posting prevents a one-sided entry. Reconciliation additionally compares captured and refunded totals with payment state, and checks posting currencies.",
      "Could the books balance while a capture posting is missing?",
    ],
    [
      "04",
      "Use the smallest reliable boundary.",
      "One application and one database make this journey understandable. A separate worker uses durable outbox/inbox records and three commit boundaries. Repository indexing and AI remain future milestones.",
      "Which demonstrated requirement would justify a message broker?",
    ],
  ];
  return (
    <>
      <div className="guide-intro">
        <BookOpen size={26} />
        <p>
          Read the source. Run the experiments. Defend the tradeoffs.
          <br />
          <span>
            These notes describe the implementation; they do not claim your
            understanding.
          </span>
        </p>
      </div>
      <div className="guide-grid">
        {cards.map(([n, title, text, q]) => (
          <article className="panel guide-card" key={n}>
            <span className="guide-number">{n}</span>
            <h2>{title}</h2>
            <p>{text}</p>
            <div>
              <span className="mini-label">DESIGN REVIEW QUESTION</span>
              <p>{q}</p>
            </div>
          </article>
        ))}
      </div>
      <div className="local-notice">
        <LockKeyhole size={18} />
        <p>
          Keep your learning internship-safe. Never enter proprietary code,
          production logs, credentials, customer data, or confidential incident
          details.
        </p>
      </div>
    </>
  );
}
