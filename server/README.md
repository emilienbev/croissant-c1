# The server

Python, run with uv. One WebSocket per hold carries the microphone audio up and the
words back down. Ask's model runs on the C1 since 2026-10-16: it starts on the words, and
asks this server only for its own speech and picture models and web searches (a cloud speech
or picture model picked on the R1 is called from the C1 itself). Runs on a Mac or on Linux.
A C1 on cloud models alone needs it only for the web search (README.md, "Backend").

| Job | On Apple Silicon | Elsewhere |
|---|---|---|
| Speech to text | Whisper large-v3-turbo on MLX (`mlx-whisper`) | Whisper on faster-whisper, or a provider |
| Pictures | Z-Image Turbo on MLX (`mflux`) or a provider | a provider only |
| Web search | keyless (`ddgs`), plus Open-Meteo, Frankfurter and news feeds | the same |

Chat is not the server's: the C1 calls Ollama on this machine itself (port 11434), or a
provider with its own key. A C1 that picked no chat model gets this server's default
(`--llm`) while its own providers list it.

## Install

- [uv](https://docs.astral.sh/uv/). `uv run` installs the dependencies on first use.
- [Ollama](https://ollama.com/), for a chat model on this machine (a cloud one needs none),
  running, with the default model: `ollama pull qwen3.5:9b-mlx`. The C1 reaches it at this
  machine's address, so Ollama has to listen there and not on loopback alone.
- On Apple Silicon, the local weights in `models/` (see `models/README.md`):

  ```bash
  cd server
  uvx --from huggingface_hub hf download mlx-community/whisper-large-v3-turbo --local-dir models/whisper-large-v3-turbo
  uvx --from huggingface_hub hf download filipstrand/Z-Image-Turbo-mflux-4bit --local-dir models/z-image-turbo-mflux-4bit
  ```

  Elsewhere faster-whisper fetches its model on first use, and pictures need a provider.

## Run

```bash
cd server
uv run croissant-server
```

Ready when it logs `listening on ws://...`. Restart it after pulling new code. It then
prints a QR code (`croissant://server?host=...&port=...&name=...`, this machine's tailnet
address) for the C1's Server step to scan; with no tailnet address it says so and prints none.

It listens on loopback and on this machine's tailnet address, port 8765, never on
`0.0.0.0`. The R1 comes in over the tailnet; the Android emulator comes in on loopback
through `10.0.2.2`. If no tailnet address is found it warns, and the R1 cannot connect.
There is no authentication: the tailnet is the only gate.

Flags (`uv run croissant-server --help` for all):

| Flag | |
|---|---|
| `--port N` | default 8765 |
| `--host ADDR` | listen here instead (repeatable) |
| `--llm ID` | the default chat model, a C1's with no pick: `provider:model`, or an Ollama model name |
| `--stt ID` | the default speech model: `provider:model`, or a local Whisper |
| `--lang CODE` | Whisper's language, `en` by default; `auto` to detect |
| `--providers FILE` | another `providers.json` |
| `--no-images` | no picture tool, no image weights loaded |
| `--image-model NAME` | a local image model (`--list-image-models`) |
| `--no-search` | no web search tool |
| `--search-home CITY` | what a weather question with no place means |
| `--debug-keep` | keep transcripts, utterance audio and recordings, for debugging |
| `-v` | verbose |

## Providers

The file is the server's own: its speech and picture providers, and the chat list
`tools/eval_tools.py` runs on. Ask's providers and
keys are the C1's (Settings > ask > providers). Without a `providers.json` the server's
speech and pictures run on this machine and its chat on Ollama. The R1 picks speech,
pictures and transform among this machine's own models (the `local` entries of
`GET /providers`) and its own providers'; a hold that picks none gets this server's
default, which may be one of the file's providers.

```json
{"providers": {"gemini": {"key": "..."}, "openai": {"key": "sk-..."},
               "myserver": {"kind": "openai", "url": "http://100.x.y.z:8000/v1", "title": "My server"}}}
```

`providers.example.json` is a template. The file is gitignored because it holds keys.

- **Kinds:** `ollama`, `openai` (any OpenAI-compatible endpoint), `gemini`, `local`
  (this process).
- **Presets:** `openai`, `gemini`, `groq`, `openrouter`, `mistral`, `together`,
  `deepseek`, `lmstudio` and `ollama` know their address and need only a key, in the
  file or in their usual environment variable (`OPENAI_API_KEY`, `GEMINI_API_KEY`, ...).
- **Models:** each provider's own model list, sorted into the slots and cached in
  `providers.models.json`. Prices come from models.dev, cached in
  `providers.prices.json`.
- **The settings page:** `http://<server>:8765/settings` from any browser on the
  tailnet. Add the server's providers and keys there; changes apply on the next hold, no
  restart. A provider being added has Test, which lists its models with the key given and
  saves it only if it answers; Save is for the providers already there.
  The page never receives a key back.

Code: `croissant_server/providers.py`, `catalog.py`, `pictures.py`, `prices.py`.

## What is kept

Nothing the device says stays on the server. A hold is transcribed and forgotten.
`data/` holds only a picture until the device collects it, and `usage.jsonl` (which model, how many tokens, seconds or pictures, never what
was said, 60 days). `--debug-keep` turns on a transcript log, the utterance WAVs (in
`captures/`) and copies of recordings.

## The protocol

The frames are in `croissant_server/session.py` and the device side in
`android/app/src/main/java/com/bleural/croissant/net/`. In short: a `start` frame with
`"mode":"dictate"` (and `from`, the language Whisper is told, for the Translator), binary
PCM (16 kHz mono), `end`; back come `partial` while it is held, `final` and an empty
`answer_done`. The C1 starts Ask's model on `final`. A recording goes up the same way as
`"mode":"transcribe"` (transcription.py). A picture is one `generate` frame, with its
photo for a transform, answered by a `generation` frame with its id; the picture is
fetched over HTTP on the same port (`GET`/`DELETE /generated/<id>`). So are a web search
(`GET /search?q=`, the digest as text), the model lists and the Ask tools this server runs
(`GET /providers`, its `tools`: `image_generate` when something can draw, `web_search`
when the search is on), usage (`GET /usage`) and the version (`GET /version`, also after `croissant-server ok` at `GET /`).

An app from before 2026-10-16 sent Ask's holds with their prompt and tools, Transform and
Translator holds, an Atelier app's `ask` and the model test's `bench` here; each is now
answered "the app is older than the server: update the app".

## The prompt

Ask's system prompt is the C1's, and with the loop it never reaches the server. So are the
fixed prompts of the C1's other calls (the catalog's `calls`: the Transform's description
pass, the Translator, a tape question, an app's `ask()`). Of the catalog's tools, those
marked `"runs": "server"` are offered while `GET /providers` lists them, which the startup
log does too: `image_generate`, which the C1 answers at once and turns into a `generate`
frame, and `web_search`, a `GET /search`.

The C1 puts a hold's prompt together (`data/AskPrompt.kt`) from the app's catalog,
`android/app/src/main/assets/ask/catalog.json`, in blocks joined by a blank line, an empty
one left out:

1. The variant's text.
2. `Your tools:` (`parts.tools`) and a line per tool on offer, `- name: description` on one
   line: the built-in ones in the catalog's order, a server one (`"runs": "server"`) only
   when `GET /providers` lists it (all of them before the C1 has a list; it asks again on
   every hold). Then, when an enabled Atelier app offers tools, `parts.apps` and a line for
   each (docs/atelier.md, "Tools for Ask").
3. The clock (`parts.clock`): the device's date, time and offset and the next seven dates,
   so "tomorrow at 1 pm" resolves in its zone. Always last.

A photo hold offers no tools: the photo rules for its model (`photo`) go in the list's
place. With a real photo attached the model called whatever it was offered for a plain
question about it, the transform 6/6 and the agenda or the web as readily (2026-09-30), so
a picture from a photo is the Transform button's alone.

The variants live on the C1, in `prompts.json` in the app's device-protected files, so a
hold before unlock has them (`data/Prompts.kt`). They start from the catalog's seeds
(`prompts`: "default" and "gemma"), each added once; a seed deleted on the C1 does not come
back. "default" is always there: its text can be edited, it is never renamed or deleted. A
chat model (`provider:model`) gets the variant picked for it, else the one seeded from the
catalog's suggestion for its name (`suggest`: Gemma 4 gets "gemma"), else "default". An
unedited seed takes the catalog's text at every start, so a reworded seed reaches the C1
with the app; an edited one keeps its text until it is reset to the seed's. On the R1 they
are Settings > ask > prompts (Beam's): added, renamed, typed on the keys, picked per model by
a switch, and each shown whole as a hold sends it.

## Testing without the device

```bash
uv run python tools/fake_r1.py --say "Set a timer for ten minutes"   # a hold's words, from a WAV or macOS say
uv run python tools/eval_tools.py "What is the capital of France?"   # Ask's loop, no audio, no device
uv run python tools/eval_tools.py --repeat 5 --score                 # the built-in set, scored
uv run python tools/send_recording.py recording.wav                  # a recording's transcription
uv run python tools/try_again.py "a red bicycle"                     # one picture, end to end
uv run python tools/probe_search.py                                  # the web search alone
uv run python tools/settings_page.py                                 # the settings page, no models
```

`uv run python -m unittest discover tests` runs the unit tests (no models).

`eval_tools.py` stands in for the C1's loop. It builds a hold's prompt the C1's way, with
`tools/c1_prompt.py` (no apps), and runs it on `tools/c1_loop.py` and `tools/c1_chat.py`, the
C1's loop and chat clients in Python (`ask/Loop.kt`, `ask/Chat.kt`), held to them by the
prompt tokens each round reports. Its tools are answered at once, as the C1's own test
answers them. `--variant NAME` picks a seed from the catalog (by default the one it
suggests for the model, else "default"); `--variant-file PATH` takes any text as the
variant instead. `fake_r1.py` is the speech half: `--from CODE` is the Translator's
language, `--stt provider:model` the speech model.

Prompt wording is judged with `eval_tools.py --repeat 3 --score`: a change to a seed, to a
tool's description or to the parts can stop the model calling tools, so run the scored set
before and after. All three are in the app's catalog, with the set's cases.

`c1_prompt.py` must build what the C1 builds, byte for byte.
`android/app/src/test/resources/ask/prompts.golden.json` holds a few holds as the C1 builds
them: the app's `AskPromptTest` owns it, and `tests/test_c1_prompt.py` holds the Python to
it. A deliberate change goes in Kotlin first: `./gradlew :app:testDebugUnitTest` in
`android/` then fails and writes this build's holds to `android/app/build/ask-golden.json`;
copy that over the golden, then make `c1_prompt.py` match.

The C1 logs a hold's prompt with one tag, `<variant>, <length> chars, <sha256's first 8
hex>`, on `utterance begun` and `model test begun` (logcat). The same tag is the same
bytes.

## Known issues

- The model sometimes gets a date's weekday wrong in its confirmation; the date passed
  to the tool is right.
- No silence-based segmentation: press and release define an utterance.
