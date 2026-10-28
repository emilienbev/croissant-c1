// {{name}}: {{about}}
// render draws the page from the state, and each handler gets the state and returns the next
// one, which the C1 saves. docs/atelier.md in the Croissant repo has every piece and call.

// The state the first time the app opens; after that, the one it saved.
state({ count: 0{{#network}}, line: ""{{/network}}{{#model}}, question: "", answer: ""{{/model}}{{#words}}, heard: []{{/words}} })

const add = (s, n) => {
  console.log(`count ${s.count} + ${n}`)   // in the terminal of ./croissant atelier run
  return { ...s, count: s.count + n }
}
{{#network}}

// fetch: a plain GET on the internet ("network" in croissant.json's uses).
const refresh = async s => {
  const r = await fetch("https://api.github.com/zen")
  return { ...s, line: r.ok ? await r.text() : `github answered ${r.status}` }
}
{{/network}}
{{#model}}

// ask: one question to the chat model picked on the C1 ("model" in uses).
const answer = async (s, question) => {
  if (!question.trim()) return { ...s, question: "", answer: "" }
  return { ...s, question, answer: await ask(`Answer in one short sentence: ${question}`) }
}
{{/model}}

render(s => [
  big(s.count, s.count === 1 ? "tap" : "taps"),
  pill("+1", s => add(s, 1)),
  pill("-1", s => add(s, -1)),
{{#network}}
  row("a line from github", { tap: refresh }),
  s.line && words(s.line),
{{/network}}
{{#model}}
  field(s.question, { hint: "ask the model", done: answer }),
  s.answer && words(s.answer),
{{/model}}
{{#words}}
  (s.heard ?? []).length === 0 && words("hold the side button here and say something"),
  (s.heard ?? []).map(said => row(said)),
{{/words}}
  s.count !== 0 && row("start again", {
    tap: s => {
      cue("undone")
      return { ...s, count: 0 }
    },
  }),
])
{{#words}}

// A hold of the side button on this page brings what was said here, not to Ask ("words" in uses).
onWords((s, said) => ({ ...s, heard: [said, ...(s.heard ?? [])].slice(0, 5) }))
{{/words}}
{{#tool}}

// The {{tool}} tool in croissant.json, which Ask calls for "{{tool_example}}":
// the page opens, this runs and changes the state in place, and what it returns goes to Ask.
onTool("{{tool}}", (s, args) => {
  const n = Math.round(Number(args.n ?? 1))
  if (!Number.isFinite(n)) return "say how many"
  s.count = add(s, n).count
  cue("done")
  return { count: s.count }
})
{{/tool}}
