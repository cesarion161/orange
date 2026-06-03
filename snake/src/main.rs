use macroquad::prelude::*;

mod input;

// ---------------------------------------------------------------------------
// Direction
// ---------------------------------------------------------------------------

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Dir {
    Up,
    Down,
    Left,
    Right,
}

impl Dir {
    /// Returns `true` when `other` is the exact opposite direction.
    pub fn is_opposite(self, other: Dir) -> bool {
        matches!(
            (self, other),
            (Dir::Up, Dir::Down)
                | (Dir::Down, Dir::Up)
                | (Dir::Left, Dir::Right)
                | (Dir::Right, Dir::Left)
        )
    }
}

// ---------------------------------------------------------------------------
// Game phase
// ---------------------------------------------------------------------------

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Phase {
    Playing,
    GameOver,
}

// ---------------------------------------------------------------------------
// Game state
// ---------------------------------------------------------------------------

pub struct State {
    /// The direction the snake is currently moving.
    pub dir: Dir,
    /// The direction buffered from player input (applied at the next tick).
    pub next_dir: Dir,
    /// Current game phase.
    pub phase: Phase,
}

/// Return a fresh initial `State` (snake starts moving right).
pub fn reset() -> State {
    State {
        dir: Dir::Right,
        next_dir: Dir::Right,
        phase: Phase::Playing,
    }
}

// ---------------------------------------------------------------------------
// Entry point
// ---------------------------------------------------------------------------

#[macroquad::main("Snake")]
async fn main() {
    let mut state = reset();

    loop {
        // Poll keyboard — must come before drawing so input is applied this frame.
        input::handle_input(&mut state);

        clear_background(BLACK);

        match state.phase {
            Phase::Playing => {
                draw_text("Snake", 20.0, 40.0, 30.0, GREEN);
                draw_text(
                    &format!("dir: {:?}   next: {:?}", state.dir, state.next_dir),
                    20.0,
                    70.0,
                    20.0,
                    WHITE,
                );
            }
            Phase::GameOver => {
                draw_text("GAME OVER", 20.0, 40.0, 40.0, RED);
                draw_text("Press R or Enter to restart", 20.0, 90.0, 20.0, WHITE);
            }
        }

        next_frame().await;
    }
}
