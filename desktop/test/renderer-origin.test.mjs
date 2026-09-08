import assert from "node:assert/strict";
import test from "node:test";

import { isTrustedRendererUrl } from "../src/main/renderer-origin.mjs";

test("accepts only the packaged renderer origin for IPC", () => {
  assert.equal(isTrustedRendererUrl("app://quota-guard/index.html"), true);
  assert.equal(isTrustedRendererUrl("app://quota-guard/assets/renderer.mjs"), true);
  assert.equal(isTrustedRendererUrl("https://quota-guard.example/index.html"), false);
  assert.equal(isTrustedRendererUrl("app://quota-guard.evil/index.html"), false);
  assert.equal(isTrustedRendererUrl("not a url"), false);
});
