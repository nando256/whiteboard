# Whiteboard (Paper plugin)

> Render text from a **writable book** onto an **Item-Frame whiteboard**.
> Supports grid boards, lock, background fill, fonts, undo/redo—server-side only.

## Summary

Whiteboard lets players place a board on item frames and push book text onto it with a right-click. Multiple maps can be merged into a large grid board—handy for PDCA-style planning with kids, without client mods.

## Images

![whiteboard](assets/images/Minecraft_papar_whiteboard-plugin.png)

## Requirements

* **Server:** Paper 1.21.9 (or compatible)
* **Java:** 21
* **Client:** Vanilla (no mods needed)

## Install

1. Download the JAR from Releases (or Hangar).
2. Drop it into `plugins/` and start the server.

## Usage

1. Place an **Item Frame** (map inside is recommended).
2. (Optional) Create a grid board:

   ```
   /wb grid 3x2
   ```
3. Write in a **writable book**, hold it, and right-click the board to render.
   * Book directives let you control setup without commands. Example first page:

     ```
     [boardsize 7x4] [clear] [lock off]
     [text 18] Hello Whiteboard!
     ```

     `boardsize` creates (or resets) a board using the frame you right-clicked as the top-left corner.
     Add `lock off` to leave the board unlocked; omit it or use `lock on` to keep protection enabled.

### Commands

```
/wb grid <COLS>x<ROWS>     # e.g., /wb grid 3x2
/wb text [<text>]          # no arg: use held book; with arg: draw the text
/wb font size=<n> color=#RRGGBB
/wb bg <#RRGGBB>
/wb lock on|off
/wb clear
/wb undo
/wb redo
```

## Build (dev)

```bash
./gradlew clean build
# JAR: build/libs/Whiteboard-<version>.jar
```

## Troubleshooting

* **Nothing renders:** check permissions and console; ensure you’re right-clicking while holding a written book.
* **Hangar publish 400 (noColor):** create the channel (e.g., `Snapshot`) on Hangar UI and assign a color, then publish again.

## License

MIT (see `LICENSE`).

## Credit
* [納戸工房 / Closet Workshop](https://donguri3.net/)
