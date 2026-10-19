import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "./api";
export type Document = {
  id: string;
  kind: string;
  title: string;
  version: number;
  body: Record<string, unknown>;
  updatedAt: string;
};
export function useDocuments(token: string, page = 0, kind?: string) {
  const cache = useQueryClient();
  const query = useQuery({
    queryKey: ["documents", kind ?? "ALL", page],
    queryFn: () =>
      api<Document[]>(
        token,
        `/workspace/documents?page=${page}${kind ? `&kind=${kind}` : ""}`,
      ),
    retry: false,
  });
  const save = useMutation({
    mutationFn: (doc: Omit<Document, "updatedAt">) =>
      api<Document>(token, `/workspace/documents/${doc.id}`, {
        method: "PUT",
        body: {
          kind: doc.kind,
          title: doc.title,
          body: doc.body,
          expectedVersion: doc.version,
        },
      }),
    onSuccess: () => cache.invalidateQueries({ queryKey: ["documents"] }),
  });
  const remove = useMutation({
    mutationFn: (id: string) =>
      api(token, `/workspace/documents/${id}`, { method: "DELETE" }),
    onSuccess: () => cache.invalidateQueries({ queryKey: ["documents"] }),
  });
  return { query, save, remove };
}
export function download(
  name: string,
  content: string,
  type = "application/json",
) {
  const url = URL.createObjectURL(new Blob([content], { type }));
  const link = document.createElement("a");
  link.href = url;
  link.download = name;
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
