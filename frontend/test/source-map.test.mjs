import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { createRequire } from "node:module";
import { fileURLToPath, pathToFileURL } from "node:url";
import { test } from "node:test";
import { transformWithOxc } from "vite";

// Compile the actual component in memory with the project's existing TSX transformer.
// This inspects React elements and invokes their real handlers; it does not model DOM focus.
const source = new URL("../src/SourceMap.tsx", import.meta.url);
const jsxRuntime = pathToFileURL(
  createRequire(import.meta.url).resolve("react/jsx-runtime"),
).href;
const transformed = await transformWithOxc(
  await readFile(source, "utf8"),
  fileURLToPath(source),
  {
    jsx: { runtime: "automatic" },
    sourcemap: false,
  },
);
const code = transformed.code.replace(
  /(["'])react\/jsx-runtime\1/g,
  JSON.stringify(jsxRuntime),
);
const { SourceMap } = await import(
  `data:text/javascript;base64,${Buffer.from(code).toString("base64")}`
);

function elements(tree) {
  if (Array.isArray(tree)) return tree.flatMap(elements);
  if (!tree || typeof tree !== "object" || !tree.props) return [];
  return [tree, ...elements(tree.props.children)];
}

function render(edges) {
  const opened = [];
  const tree = elements(
    SourceMap({ edges, open: (path, line) => opened.push({ path, line }) }),
  );
  return {
    tree,
    opened,
    buttons: tree.filter((element) => element.props.role === "button"),
  };
}

test("all 24 displayed leaves open indexed evidence even when their 25th hub is outside the graph", () => {
  const edges = Array.from({ length: 24 }, (_, index) => ({
    from: `Leaf${String(index).padStart(2, "0")}`,
    to: "ZZZHub",
    path: `src/Leaf${String(index).padStart(2, "0")}.java`,
    line: index + 11,
  }));
  const { tree, buttons, opened } = render(edges);
  assert.equal(buttons.length, 24);
  assert.equal(
    tree.filter((element) => element.type === "line").length,
    0,
    "No drawn edge can reach the omitted hub",
  );
  assert.ok(
    buttons.every(
      (button) => button.props["aria-label"] !== "Inspect declaration ZZZHub",
    ),
  );
  for (const [index, button] of buttons.entries()) {
    assert.equal(
      button.props["aria-label"],
      `Inspect declaration ${edges[index].from}`,
    );
    button.props.onClick();
  }
  assert.deepEqual(
    opened,
    edges.map(({ path, line }) => ({ path, line })),
  );
});

test("Enter and Space activate actual indexed evidence without requiring a visible edge", () => {
  const edges = Array.from({ length: 24 }, (_, index) => ({
    from: `Leaf${String(index).padStart(2, "0")}`,
    to: "ZZZHub",
    path: `src/Leaf${index}.java`,
    line: index + 2,
  }));
  const { buttons, opened } = render(edges);
  let prevented = 0;
  const button = buttons[17];
  assert.equal(button.props.tabIndex, 0);
  for (const key of ["Enter", " "])
    button.props.onKeyDown({
      key,
      preventDefault() {
        prevented++;
      },
    });
  assert.equal(prevented, 2);
  assert.deepEqual(opened, [
    { path: "src/Leaf17.java", line: 19 },
    { path: "src/Leaf17.java", line: 19 },
  ]);
  for (const key of ["Tab", "Escape", "ArrowRight"])
    button.props.onKeyDown({
      key,
      preventDefault() {
        assert.fail(
          "Unrelated navigation keys must retain their default behavior",
        );
      },
    });
  assert.equal(opened.length, 2);
});

test("the 48 drawn-edge limit does not remove source evidence from a later displayed node", () => {
  const early = Array.from({ length: 49 }, (_, index) => ({
    from: "A",
    to: "B",
    path: `src/A${index}.java`,
    line: index + 1,
  }));
  const later = { from: "C", to: "D", path: "src/C.java", line: 57 };
  const { tree, buttons, opened } = render([...early, later]);
  assert.equal(tree.filter((element) => element.type === "line").length, 48);
  const button = buttons.find(
    (element) => element.props["aria-label"] === "Inspect declaration C",
  );
  assert.ok(button);
  button.props.onClick();
  assert.deepEqual(opened, [{ path: later.path, line: later.line }]);
});

test("outgoing evidence is preferred and incoming-only types open their actual field evidence", () => {
  const incoming = {
    from: "AConsumer",
    to: "BService",
    path: "src/AConsumer.java",
    line: 12,
  };
  const outgoing = {
    from: "BService",
    to: "CStore",
    path: "src/BService.java",
    line: 27,
  };
  const { buttons, opened } = render([incoming, outgoing]);
  buttons
    .find(
      (button) => button.props["aria-label"] === "Inspect declaration BService",
    )
    .props.onClick();
  buttons
    .find(
      (button) => button.props["aria-label"] === "Inspect declaration CStore",
    )
    .props.onClick();
  assert.deepEqual(opened, [
    { path: "src/BService.java", line: 27 },
    { path: "src/BService.java", line: 27 },
  ]);
});

test("empty evidence renders no interactive source nodes", () => {
  const { buttons, opened } = render([]);
  assert.equal(buttons.length, 0);
  assert.deepEqual(opened, []);
});
