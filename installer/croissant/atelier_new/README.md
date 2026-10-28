# {{name}}

{{#description}}
{{description}}

{{/description}}
<!-- croissant:qr -->
{{qr}}
<!-- /croissant:qr -->

An [Atelier](https://github.com/{{croissant}}/blob/main/docs/atelier.md) app for the C1, the
Rabbit R1 running [Croissant](https://github.com/{{croissant}}){{#starter}}: a count the wheel and the
keys move{{#network}}, a line fetched from the internet{{/network}}{{#model}}, a question to the chat model{{/model}}{{#words}}, what you say holding the side button on its page{{/words}}{{#tool}}, and a tool Ask calls ("{{tool_example}}"){{/tool}}{{/starter}}{{^starter}}{{#tool}}, with a tool Ask calls ("{{tool_example}}"){{/tool}}{{/starter}}.
{{#uses}}

Before it is installed, the C1 says what it reaches beyond its page:

{{#network}}
- **the network**: `fetch`, a plain GET on the internet.
{{/network}}
{{#model}}
- **the model**: `ask`, a question to the chat model picked on the C1.
{{/model}}
{{#words}}
- **your words**: a hold of the side button on its page brings them to the app, not to Ask.
{{/words}}
{{/uses}}

## Its files

- `croissant.json`: its name, hue and glyph{{#uses}}, what it uses{{/uses}}{{#tool}}, the tool it adds to Ask{{/tool}}.
- `croissant.js`: its page, drawn from its state.
{{#owner}}
- `qr.svg`, `qr.png`: the code above, of `https://github.com/{{slug}}`.
{{/owner}}

## Try it on a C1

With the C1 on USB, from a checkout of Croissant:

```bash
./croissant atelier run path/to/{{repo}}
```

It appears in Atelier and is pushed again on every save, with its log in the terminal.

## Share it

Push this folder to a public GitHub repository{{#owner}}, `{{slug}}`{{/owner}}: a C1 adds it from
there, by its QR code or its name typed. To update it, push, then add the same repository again
on the C1: it keeps its state.

{{#owner}}
`./croissant atelier qr path/to/{{repo}}` draws the QR code again, after a new hue or glyph.
{{/owner}}
{{^owner}}
`./croissant atelier qr path/to/{{repo}} --owner OWNER` draws its QR code once it has a home on
GitHub.
{{/owner}}

How to write one: [docs/atelier.md](https://github.com/{{croissant}}/blob/main/docs/atelier.md).
