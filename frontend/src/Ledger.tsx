import { ShieldCheck } from "lucide-react";
import { money, type Reconciliation } from "./api";
import { Status } from "./ui";
export function Ledger({ rows }: { rows: Reconciliation[] }) {
  return (
    <>
      <div className="ledger-intro">
        <ShieldCheck size={25} />
        <div>
          <h2>Check the business effect, too.</h2>
          <p>
            Equal debits and credits are necessary. ATLAS also compares each
            payment’s captured and refunded amounts with its postings.
          </p>
        </div>
      </div>
      <div className="ledger-grid">
        {rows.map((r) => (
          <section className="panel currency-card" key={r.currency}>
            <div className="currency-heading">
              <h2>{r.currency}</h2>
              <Status value={r.balanced ? "BALANCED" : "MISMATCH"} />
            </div>
            <dl>
              <div>
                <dt>Debits</dt>
                <dd>{money(r.debitsMinor, r.currency)}</dd>
              </div>
              <div>
                <dt>Credits</dt>
                <dd>{money(r.creditsMinor, r.currency)}</dd>
              </div>
              <div>
                <dt>Paired postings</dt>
                <dd>{r.postingCount}</dd>
              </div>
              <div>
                <dt>Payment mismatches</dt>
                <dd>{r.unbalancedPayments}</dd>
              </div>
            </dl>
          </section>
        ))}
      </div>
      <p className="field-help">
        Read from one PostgreSQL snapshot. Currencies are never combined.
        Reconciliation detects inconsistencies; it does not repair records.
      </p>
    </>
  );
}
