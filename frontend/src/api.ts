export type Payment = {
  id: string;
  merchantId: string;
  amountMinor: number;
  currency: string;
  status: "AUTHORIZED" | "DECLINED" | "CAPTURED" | "REFUNDED" | "REVERSED";
  capturedMinor: number;
  refundedMinor: number;
  version: number;
  createdAt: string;
};
export type PaymentPage = {
  items: Payment[];
  page: number;
  size: number;
  total: number;
};
export type Detail = {
  payment: Payment;
  events: {
    sequence: number;
    type: string;
    detail: string;
    correlationId: string;
    createdAt: string;
  }[];
  postings: {
    id: string;
    kind: string;
    debitAccount: string;
    creditAccount: string;
    amountMinor: number;
    currency: string;
    createdAt: string;
  }[];
};
export type Reconciliation = {
  currency: string;
  debitsMinor: number;
  creditsMinor: number;
  unbalancedPayments: number;
  postingCount: number;
  balanced: boolean;
};
export type Result = { payment: Payment; replayed: boolean };
export type Authorization = {
  merchantId: string;
  amountMinor: number;
  currency: string;
};
export async function api<T>(
  token: string,
  path: string,
  options: {
    method?: string;
    body?: unknown;
    key?: string;
    timeoutMs?: number;
  } = {},
): Promise<T> {
  const response = await fetch(`/api${path}`, {
    method: options.method ?? "GET",
    headers: {
      Authorization: `Bearer ${token}`,
      ...(options.body ? { "Content-Type": "application/json" } : {}),
      ...(options.key ? { "Idempotency-Key": options.key } : {}),
    },
    body: options.body ? JSON.stringify(options.body) : undefined,
    signal: AbortSignal.timeout(options.timeoutMs ?? 15000),
  });
  const data = await response.json();
  if (!response.ok)
    throw new Error(
      `${data.message ?? `Request failed (${response.status})`}${data.correlationId ? ` · Reference ${data.correlationId}` : ""}`,
    );
  return data as T;
}
export const money = (minor: number, currency: string) =>
  new Intl.NumberFormat("en-US", { style: "currency", currency }).format(
    minor / 100,
  );
export const timestamp = (value: string) =>
  new Intl.DateTimeFormat(undefined, {
    dateStyle: "medium",
    timeStyle: "medium",
  }).format(new Date(value));
