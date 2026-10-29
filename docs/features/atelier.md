# Atelier

<p align="center">
  <img alt="The atelier board: five apps three across, water, split, shopping, dice and define, each its glyph in its own colour over its name, water on a blue disc, and the coral plus key" src="../images/features/atelier-board.png" width="300">
  <img alt="The water app's page: a big 5 over glasses today in blue, an add a glass key in blue and a goal row with 8 at its end" src="../images/features/atelier-app.png" width="300">
</p>

Small apps anyone can write, in the C1's own design.

- Each app has a place on the board, its glyph in its own colour.
- The plus key adds one from GitHub, by typing its owner/repo or scanning its QR code. Before you install it, the C1 shows where it comes from, that nobody has checked it, what it may use and what it adds to Ask.
- On an app's page the wheel moves between its rows, switches and keys. An app that takes your words gets what you say when you hold the side button there. It only runs while its page is open.
- Press and hold an app to pin it to the side wheel, turn it off or delete it.

An app can add tools to Ask, so it works from anywhere. They reach Ask as `<app id>_<name>`, and a call opens the app's page.

| You say | Ask calls |
|---|---|
| "add eggs to my shopping list" | `shopping_add_item {"name": "eggs"}` |

To write your own, see [Atelier apps](../atelier.md).
