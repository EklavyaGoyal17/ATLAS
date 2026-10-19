const token = process.env.ATLAS_API_TOKEN;
if (!token) throw new Error("Set ATLAS_API_TOKEN");
const base = process.env.ATLAS_URL ?? "http://127.0.0.1:8080";
const scenarios = [
  { currency: "USD", amount: 12500, action: "capture" },
  { currency: "EUR", amount: 8900, action: "refund" },
  { currency: "INR", amount: 245000, action: "reverse" },
  { currency: "USD", amount: 50_000_001, action: null },
  { currency: "USD", amount: 3200, action: null },
];
async function command(path, key, body) {
  const r = await fetch(`${base}/api${path}`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token}`,
      "Idempotency-Key": key,
      ...(body ? { "Content-Type": "application/json" } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const data = await r.json();
  if (!r.ok) throw new Error(JSON.stringify(data));
  return data;
}
// Stable keys make seeding rerunnable, and preserve intentionally completed workflows.
for (const [index, s] of scenarios.entries()) {
  const r = await command("/payments", `atlas-seed-v1-authorize-${index}`, {
    merchantId: "merchant_demo",
    amountMinor: s.amount,
    currency: s.currency,
  });
  const id = r.payment.id;
  if (s.action === "capture" || s.action === "refund")
    await command(`/payments/${id}/capture`, `atlas-seed-v1-capture-${index}`);
  if (s.action === "refund")
    await command(`/payments/${id}/refund`, `atlas-seed-v1-refund-${index}`, {
      amountMinor: 2400,
    });
  if (s.action === "reverse")
    await command(`/payments/${id}/reverse`, `atlas-seed-v1-reverse-${index}`);
  console.log(
    `Synthetic scenario ${index + 1}: ${id} (${s.action ?? r.payment.status})`,
  );
}
