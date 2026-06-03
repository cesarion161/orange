# Snake

A classic Snake game built with [macroquad](https://macroquad.rs/) in Rust.

## Prerequisites

- [Rust](https://rustup.rs/) (stable, 1.70+)

On macOS, macroquad also requires the Xcode command-line tools for the Metal
graphics back-end:

```sh
xcode-select --install
```

## Build

```sh
cd snake
cargo build           # debug build
cargo build --release # optimised build
```

## Run

```sh
cd snake
cargo run             # debug (window opens immediately)
cargo run --release   # optimised
```

## Controls

| Key | Action |
|-----|--------|
| ↑ / ↓ / ← / → | Steer the snake (180° reversals are ignored) |
| R or Enter | Restart after game over |

## Project layout

```
snake/
├── Cargo.toml        # macroquad dependency
├── README.md         # this file
└── src/
    ├── main.rs       # entry point — state types, reset(), game loop
    └── input.rs      # handle_input() — arrow-key steering + GameOver restart
```
