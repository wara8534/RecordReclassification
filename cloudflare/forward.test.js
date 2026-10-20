import test from "node:test";
import assert from "node:assert/strict";
import { forwardToJava } from "./forward.js";

test("forwards paths, queries and binary uploads without rewriting the request", async () => {
  const bytes = new Uint8Array([80, 75, 0, 128, 255]);
  const request = new Request("https://record-on.example/api/records/process?format=xlsx", {
    method: "POST", body: bytes, headers: { "Content-Type": "application/octet-stream" }
  });
  const namespace = { getByName(name) {
    assert.equal(name, "record-on");
    return { async fetch(forwarded) {
      assert.equal(forwarded, request);
      assert.deepEqual(new Uint8Array(await forwarded.arrayBuffer()), bytes);
      return new Response(bytes, { headers: {
        "Content-Type": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "Content-Disposition": "attachment; filename=result.xlsx"
      } });
    } };
  } };
  const response = await forwardToJava(request, namespace);
  assert.equal(response.headers.get("Content-Disposition"), "attachment; filename=result.xlsx");
  assert.deepEqual(new Uint8Array(await response.arrayBuffer()), bytes);
});

test("preserves Java validation errors and health responses", async () => {
  for (const path of ["/", "/styles.css", "/fonts/PretendardVariable.woff2", "/api/health", "/api/records/import"]) {
    const expected = Response.json({ path }, { status: path.endsWith("import") ? 400 : 200 });
    const response = await forwardToJava(new Request("https://record-on.example" + path), {
      getByName: () => ({ fetch: async () => expected })
    });
    assert.equal(response, expected);
  }
});

test("returns a safe transient error without retrying failed uploads", async () => {
  let attempts = 0;
  const response = await forwardToJava(new Request("https://record-on.example/api/records/import", { method: "POST", body: "data" }), {
    getByName: () => ({ fetch: async () => { attempts++; throw new Error("private data"); } })
  });
  assert.equal(attempts, 1);
  assert.equal(response.status, 503);
  assert.equal(response.headers.get("Cache-Control"), "no-store");
  assert.equal(response.headers.get("Retry-After"), "10");
  assert.ok((await response.json()).message);
});
