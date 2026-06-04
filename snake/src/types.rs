use std::collections::VecDeque;

// ---------------------------------------------------------------------------
// Grid constants
// ---------------------------------------------------------------------------

/// Number of columns in the play grid.
pub const GRID_COLS: i32 = 20;
/// Number of rows in the play grid.
pub const GRID_ROWS: i32 = 20;
/// Pixel size of a single grid cell (square).
pub const CELL_PX: f32 = 24.0;
/// Seconds between snake ticks (movement steps).
pub const TICK_SECS: f32 = 0.15;

// ---------------------------------------------------------------------------
// Direction
// ---------------------------------------------------------------------------

/// Cardinal direction the snake is (or will be) moving.
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum Direction {
    Up,
    Down,
    Left,
    Right,
}

// ---------------------------------------------------------------------------
// Phase
// ---------------------------------------------------------------------------

/// High-level game phase.
#[derive(Debug, Clone, PartialEq)]
pub enum Phase {
    /// Normal play — the snake is alive and moving.
    Playing,
    /// The snake has collided; the game is over.
    GameOver,
}

// ---------------------------------------------------------------------------
// GameState
// ---------------------------------------------------------------------------

/// Complete, self-contained state for one game session.
///
/// `snake` is stored head-first: `snake[0]` is the head, the last element is
/// the tail. Coordinates are grid cells, not pixels.
#[derive(Debug, Clone)]
pub struct GameState {
    /// Body of the snake. Front (`[0]`) = head; back = tail.
    pub snake: VecDeque<(i32, i32)>,
    /// Direction the snake is currently moving.
    pub dir: Direction,
    /// Direction queued by player input; applied on the next tick.
    pub next_dir: Direction,
    /// Grid position of the food pellet.
    pub food: (i32, i32),
    /// Player score (increments each time food is eaten).
    pub score: u32,
    /// Seconds accumulated since the last movement tick.
    pub tick_acc: f32,
    /// Current phase of the game.
    pub phase: Phase,
}
