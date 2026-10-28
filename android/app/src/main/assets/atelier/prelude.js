// Croissant Atelier, croissant 1: everything an app's croissant.js can call, loaded before it.
// The device draws the pieces in the design on screen; an app only says what is on its page.
//
//   state({ count: 0 })                  // the state the first time the app opens
//   render(s => [ big(s.count, "taps"), pill("tap", s => ({ count: s.count + 1 })) ])
//   onWords((s, words) => ...)           // a hold on the page brings its words (uses "words")
//   onTool("add_item", (s, args) => ...) // Ask calls a tool from croissant.json's "tools": the page opens,
//                                        // the handler changes the state in place and returns the reply
//
// A handler gets the state (and a value: a toggle's new state, a field's text) and returns
// the next state, or a promise of it; returning nothing keeps the state as it is, mutated or
// not. The state is saved as JSON after every handler: it is the app's storage.

const __at = { seed: undefined, view: null, heard: null, tools: {}, state: undefined, handlers: [], gen: 0 };

function state(seed) { __at.seed = seed; }
function render(view) {
  if (typeof view !== "function") throw new TypeError("render takes a function of the state");
  __at.view = view;
}
function onWords(fn) {
  if (typeof fn !== "function") throw new TypeError("onWords takes a function of the state and the words");
  __words();   // refused when croissant.json does not use "words"
  __at.heard = fn;
}
function onTool(name, fn) {
  if (typeof fn !== "function") throw new TypeError("onTool takes a tool's name and a function of the state and its arguments");
  __at.tools[String(name)] = fn;
}

const __text = v => (v == null ? null : String(v));
function __handler(fn) {
  if (fn == null) return null;
  if (typeof fn !== "function") throw new TypeError("a handler must be a function");
  __at.handlers.push(fn);
  return __at.handlers.length - 1;
}

// The pieces.
function words(text) { return { t: "words", text: String(text) }; }
function big(value, label) { return { t: "big", value: String(value), label: __text(label) }; }
function row(text, o) {
  o = o || {};
  return { t: "row", text: String(text), value: __text(o.value), tap: __handler(o.tap) };
}
function toggle(text, on, change) { return { t: "toggle", text: String(text), on: !!on, change: __handler(change) }; }
function field(value, o) {
  o = o || {};
  return {
    t: "field", value: value == null ? "" : String(value), hint: __text(o.hint),
    keys: o.keys === "number" ? "number" : "text", change: __handler(o.change), done: __handler(o.done),
  };
}
function pill(text, tap) { return { t: "pill", text: String(text), tap: __handler(tap) }; }

// The bridge. __ask, __fetch and __cue are the device's, refused when croissant.json does not use them.
// At most four fetch calls, and four ask calls, run at once; the rest wait their turn.
async function ask(text) { return __ask(String(text)); }
async function fetch(url, opts) {
  // A plain GET and nothing else: anything more is refused out loud rather than sent as a GET.
  if (opts != null) {
    const method = String(opts.method || "GET").toUpperCase();
    if (method !== "GET" || opts.body != null || opts.headers != null) {
      throw new TypeError("fetch sends a plain GET: no method, headers or body");
    }
  }
  const r = JSON.parse(await __fetch(String(url)));
  return {
    ok: r.status >= 200 && r.status < 300, status: r.status,
    text: async () => r.body, json: async () => JSON.parse(r.body),
  };
}
function cue(name) { __cue(String(name)); }

// console.log and its kin go to the device's log, which `croissant atelier run` shows.
const __show = a => a.map(v => (typeof v === "string" ? v : JSON.stringify(v))).join(" ");
const console = {
  log: (...a) => __log("log", __show(a)), info: (...a) => __log("log", __show(a)),
  warn: (...a) => __log("warn", __show(a)), error: (...a) => __log("error", __show(a)),
};

// Called by the device, never by an app.
// The saved state comes as JSON text, read here and never run; unreadable, the app starts afresh.
function __start(text) {
  let saved;
  try { saved = text === undefined ? undefined : JSON.parse(text); } catch (e) { saved = undefined; }
  __at.state = saved !== undefined ? saved : (__at.seed === undefined ? {} : JSON.parse(JSON.stringify(__at.seed)));
}
function __draw() {
  __at.handlers = [];
  __at.gen += 1;
  const out = __at.view ? __at.view(__at.state) : [];
  const pieces = (Array.isArray(out) ? out : [out]).flat(Infinity).filter(p => p != null && p !== false && p !== "");
  return JSON.stringify({ gen: __at.gen, words: typeof __at.heard === "function", pieces });
}
function __saved() { return JSON.stringify(__at.state === undefined ? null : __at.state); }
async function __call(gen, id, value) {
  if (gen !== __at.gen) return false;   // the page changed under the finger
  const fn = __at.handlers[id];
  if (!fn) return false;
  const next = await fn(__at.state, value);
  if (next !== undefined) __at.state = next;
  return true;
}
async function __tool(name, args) {
  const fn = __at.tools[name];
  if (!fn) throw new Error(`croissant.js has no onTool("${name}")`);
  const reply = await fn(__at.state, args || {});
  return JSON.stringify(reply === undefined ? "done" : reply);
}
async function __hear(words) {
  if (typeof __at.heard !== "function") return false;
  const next = await __at.heard(__at.state, words);
  if (next !== undefined) __at.state = next;
  return true;
}
