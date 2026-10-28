# Atelier apps

Atelier is the C1's place for small apps anyone writes: a tally, a list, a calculator, a
page that fetches something and shows it. An app is a folder with two files,
`croissant.json` and `croissant.js`. It never draws anything itself: it returns the pieces
of its page, and the C1 draws them in its own design, with its wheel, side button and keys.

This page is the whole contract. Give it to whoever writes the app, a person or an agent.

## The folder

```
my-app/
  croissant.json
  croissant.js
```

The quickest start is a working one: from a checkout of Croissant,

```bash
./croissant atelier new my-app            # asks the rest: owner, name, hue, glyph, features
./croissant atelier run my-app            # onto the C1 on USB, again on every save
gh repo create you/my-app --public --source my-app --push
```

`new` asks for the app's GitHub owner, its name on the C1, a line about it, its hue, a Lucide
glyph (searched by a word) and what it needs: the network, the chat model, your words, a tool
for Ask. It makes `my-app/` with `croissant.json`, `croissant.js` (a count the wheel and the keys
move, and each feature asked for in use), a README showing the app's QR code (`qr.svg`, with
`qr.png` to share), as a git repository with a first commit, and offers to publish it with `gh`.
Once the repository is public, a C1 adds it by scanning that code: Atelier, +, the scan key.
`./croissant atelier qr my-app` draws the code again after a new hue or glyph; with `--readme`,
an older app's README is made again in the same shape, its code centred at the top.

`croissant.json`:

```json
{
  "croissant": 1,
  "name": "water",
  "hue": "#1FA2FF",
  "glyph": "droplet",
  "version": "1.0.0",
  "uses": []
}
```

| Field | |
|---|---|
| `croissant` | Required: the pieces and calls the app was written for. This page is `1`. A C1 that runs an older one refuses the app until it is updated. |
| `name` | The page's header word and its name in Atelier, up to 24 letters. Lowercase reads best. |
| `hue` | `#RRGGBB`, the app's colour. Bright colours read best on the C1's black. |
| `glyph` | A [Lucide](https://lucide.dev/icons) icon's name, such as `shopping-cart`. Optional: without one, or with a name Lucide does not have, the app gets Atelier's own glyph in its hue. |
| `version` | Any text, shown nowhere yet. |
| `uses` | What the app reaches beyond its page, from `network`, `model` and `words`. Each is listed before the app is installed, and a call the app did not declare fails. |
| `tools` | Optional: tools the app adds to Ask (below). |

### Its id

An app does not name its id: the C1 gives it one, the key of its folder, its saved state,
its place on the side wheel and its tools' names.

- **From GitHub**, the id is the repository's name: `alice/water-log` is `water-log`.
  Letters are lowercased, anything but letters, digits and dashes becomes a dash, runs of
  dashes become one and none is left at either end, and it is cut to 40 (`Water.Log` is
  `water-log`).
- **The same repository again is an update**, compared ignoring case: the app keeps its id,
  its saved state, whether it is disabled and its place on the wheel.
- **Another repository never takes an app's place.** One whose name is already an app's id
  gets the next free one, `water-log-2`, `-3` and so on, and a state of its own.
- **Pushed from a computer** (`croissant atelier run`), the id is the folder's name, made the
  same way: `~/code/Water Log` is `water-log`. A push replaces whatever app has that id,
  one from GitHub too, keeping its state: it is your own C1, and that is how an app is
  worked on.

An `id` left in `croissant.json` is ignored.

## `croissant.js`

```js
state({ count: 0, goal: 8 })

render(s => [
  big(s.count, "glasses today"),
  pill("add a glass", s => ({ ...s, count: s.count + 1 })),
  row("goal", { value: s.goal }),
])
```

- **`state(seed)`**: the state the first time the app opens.
- **`render(s => pieces)`**: the page, from the state. Return a list of pieces; nested lists
  are flattened, and `null`, `false` and `""` are skipped, so `s.done && words("done")` works.
- **Handlers** are functions given to pieces. Each gets the state (and a value: a switch's
  new position, a field's text) and returns the next state, or a promise of it. Returning
  nothing keeps the state, changed in place or not.
- **The state is the app's storage.** After a handler, the page is drawn from the new
  state, then the state is saved as JSON and is what the app opens on next time. A handler
  whose new state cannot be drawn (`render` throws) is undone: the state goes back to the
  one that last drew, and the error shows under the page. Keep it to what JSON can hold.
- **The state outlives updates.** `state(seed)` runs only the first time; an updated app
  opens on the state the old one saved. Read fields that a later version added
  defensively: `(s.items ?? [])`, `s.goal ?? 8`.
- **Plain JavaScript, one file.** It runs in QuickJS (modern JavaScript, `async`/`await`,
  `Date`, `JSON`, `Math`). There is no DOM, no `import` or `require`, no `setTimeout` or
  `setInterval`, and nothing from Node or the browser beyond the calls below.
- **In a sandbox of its own.** Apps run in a separate, isolated process with no files,
  network or permissions; everything beyond the page goes through the calls below. If that
  process stops, the page says so ("it stopped running") with the key to start it again.
- **One page.** The header word is the app's name; back is the C1's own.
- **Nothing runs while the page is closed.** No timers in the background, no notifications.
  A call from Ask to one of the app's tools opens its page first.

### Pieces

| Piece | |
|---|---|
| `words(text)` | Body text. |
| `big(value, label)` | A big value, with its label under it in the app's hue. |
| `row(text, { value, tap })` | A row, a value at its end, and what a tap does. |
| `toggle(text, on, change)` | A switch. `change(s, on)` gets the new position. |
| `field(value, { hint, keys, change, done })` | A text field typed on the C1's keys. `keys: "number"` for digits. `change(s, text)` on every edit, `done(s, text)` when the keys go away. |
| `pill(text, tap)` | A key. |

How the C1 lays them out, which an app does not choose:

- Pieces go top to bottom, in the order `render` returns them. Pills next to each other sit
  side by side and wrap to the next line.
- The wheel moves between the rows, switches and pills and scrolls the page; a field is
  tapped to type, and the wheel then moves its caret. Up at the top steps back.
- `keys: "number"` gives the digits only: whole numbers, no point or minus.
- A dark hue is lightened, so that black words on it stay readable.
- Text that does not fit a row is cut with an ellipsis; `words` wraps.

### Calls

| Call | Needs | |
|---|---|---|
| `await ask(text)` | `"model"` | The answer of the chat model picked on the C1, in plain text, asked by the C1 itself (no server for a cloud model): one call, no tools, no conversation, up to 16,000 characters asked. Ask for a form ("one word", "JSON with keys a and b") and it answers in that form. With no model, no key for it, or the provider refusing, it throws why. Four at once; more wait their turn. |
| `await fetch(url)` | `"network"` | A plain GET on the internet: `{ ok, status, text(), json() }`. No method, headers or body: passing any throws. Answers up to 1 MB, within 30 seconds; four at once, more wait their turn. Never the tailnet, the local network or the C1 itself: private and reserved addresses, and any on one of the C1's own networks (a LAN on IPv6 too), are refused, typed, by name or through a redirect, before anything is sent. A refused address throws as one that does not answer: `fetch: <host> cannot be reached`. |
| `onWords((s, words) => ...)` | `"words"` | A hold of the side button on the app's page brings it what was said, instead of Ask. Without `"words"` in `uses`, `onWords` throws and the app does not start. |
| `cue(name)` | | One of the C1's sounds: `tap`, `on`, `off`, `done`, `undone`, `arrived`, `error`, `delete`. |
| `console.log(...)` | | Into the C1's log, which `croissant atelier run` shows. |

### Tools for Ask

An app can add tools to Ask, the assistant on the side button: "add eggs to my shopping
list" said anywhere. Declare each in `croissant.json`, so the C1 can offer them without running
the app:

```json
"tools": [
  {
    "name": "add_item",
    "description": "Add an item to the shopping list",
    "parameters": {
      "type": "object",
      "properties": { "name": { "type": "string", "description": "the item, a word or two" } },
      "required": ["name"]
    },
    "examples": [
      "put milk on the list",
      { "say": "add eggs to my shopping list", "args": { "name": "eggs" } }
    ]
  }
]
```

and handle them in `croissant.js`:

```js
onTool("add_item", (s, args) => {
  s.items.unshift({ name: args.name, done: false })
  return `added ${args.name}`
})
```

- `name`: lowercase letters, digits and `_`, up to 23. The model sees it as
  `<app id>_<name>` (`shopping_add_item`, the id as [the C1 gave it](#its-id)). One whose whole name is one of Ask's own (a
  built-in tool's, such as `agenda_add`, or `tape_read`) is never offered.
- `description`: the exact text the model reads. Ask's prompt lists the enabled apps'
  tools after the C1's own, under a line saying apps add tools of their own, one line each:

  ```
  - <app id>_<name>: <description> (the <app name> app)
  - shopping_add_item: Add an item to the shopping list (the shopping app)
  ```

  There, line ends and runs of spaces become one space; the tool's spec carries the
  description as written, with the app's name added. Say what the tool is for, not what
  it is not for: a model follows a narrow trigger and ignores a prohibition.
- `parameters`: a JSON Schema object, as in function calling. Keep it small: every tool
  is read by the model on every question.
- `examples`: sentences that should call the tool. The C1's model test (Settings > ask >
  test) asks each and passes it when the model called this tool and no other. An example
  may also name what the call must pass, `{ "say": ..., "args": { ... } }`: each argument
  named there must come out as given (text compared ignoring case, numbers by value); others
  may be added. The test's built-in sentences run beside them, so a tool that takes a
  sentence meant for another ("remind me to buy eggs" is a reminder) fails there.
- When Ask calls a tool, the app's page comes up and its `onTool` handler runs there: it
  changes the state in place (saved, as after any handler) and returns the reply for the
  model, any JSON (`"done"` when it returns nothing). The page opening and the handler
  together have 15 seconds; past that the model is told the device did not answer.
- A disabled app (its info page) offers no tools.

An error thrown in a handler shows under the page, which stays; one thrown by `render`
replaces the page with the error and a key to start the app again. Limits: `croissant.js` up to
256 kB, 32 MB of memory, `render` under 2 seconds, a handler under 60 (a tool, 15), a page,
the saved state and a tool's reply each up to 64 kB of JSON.

## Putting it on a C1

- **From GitHub:** push the folder's two files to the root of a public repository. On the
  C1, Atelier > + and type `owner/repo` (or `owner/repo@branch`), or scan a QR code of the
  repository's link (`./croissant atelier qr` draws one in the app's hue and glyph). A repository without `croissant.json` at its root is not a Croissant
  app. The C1 shows where the app comes from, what it uses and what it adds to Ask: each
  tool by the name the model reads, its description and arguments as written, its
  examples; install comes after them. It installs it pinned at the commit it found.
- **From this computer**, with the C1 on USB:

  ```bash
  ./croissant atelier run path/to/my-app
  ```

  It checks `croissant.json`, puts the app on the C1 under its folder's name, and puts it
  there again every time a file is saved, with the app's log and errors in the terminal.
  The app's page, if it is open, starts again on the new script. `--fresh` drops its saved
  state, `--once` pushes once.
- **Updating:** add the same repository again (or push the same folder again): the app
  keeps its id, its saved state and whether it is disabled, and runs the new commit.

## Examples

A shopping list that takes words (`"uses": ["words"]`):

```js
state({ items: [] })

render(s => [
  ...s.items.map((it, i) =>
    toggle(it.name, it.done, (s, on) => {
      s.items[i].done = on
    })),
  s.items.length
    ? pill("clear ticked", s => ({ items: s.items.filter(it => !it.done) }))
    : words("hold the side button and say what to buy"),
])

onWords((s, text) => ({ items: [{ name: text, done: false }, ...s.items] }))
```

A bill split three ways:

```js
state({ bill: "", tip: 15, people: 3 })

const each = s => ((Number(s.bill) || 0) * (1 + s.tip / 100) / s.people).toFixed(2)

render(s => [
  field(s.bill, { hint: "the bill", keys: "number", change: (s, t) => ({ ...s, bill: t }) }),
  big(each(s), "each"),
  row("tip", { value: s.tip + " %", tap: s => ({ ...s, tip: s.tip >= 25 ? 10 : s.tip + 5 }) }),
  row("people", { value: s.people, tap: s => ({ ...s, people: s.people >= 8 ? 2 : s.people + 1 }) }),
])
```

A question to the model (`"uses": ["model"]`):

```js
state({ word: "", answer: "" })

render(s => [
  field(s.word, { hint: "a word", done: async (s, w) => ({ word: w, answer: await ask(`One sentence defining "${w}".`) }) }),
  s.answer && words(s.answer),
])
```
