//! Pure game logic — no rendering, no I/O.
//!
//! Grid coordinates: (0,0) is top-left; x grows right, y grows down.

use macroquad::rand::gen_range;
use std::collections::VecDeque;

// ---------------------------------------------------------------------------
// Grid dimensions
// ---------------------------------------------------------------------------

pub const GRID_W: i32 = 20;
pub const GRID_H: i32 = 20;

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Direction {
    Up,
    Down,
    Left,
    Right,
}

impl Direction {
    /// The direction that would immediately reverse travel.
    pub fn opposite(self) -> Direction {
        match self {
            Direction::Up => Direction::Down,
            Direction::Down => Direction::Up,
            Direction::Left => Direction::Right,
            Direction::Right => Direction::Left,
        }
    }
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Phase {
    Playing,
    GameOver,
}

#[derive(Clone, Debug)]
pub struct GameState {
    /// Body segments; index 0 is the **head**.
    pub snake: VecDeque<(i32, i32)>,
    /// Committed movement direction for the current frame.
    pub dir: Direction,
    /// Buffered input direction — applied at the next `tick`.
    pub next_dir: Direction,
    /// Current food cell.
    pub food: (i32, i32),
    /// Number of food items eaten this game.
    pub score: u32,
    /// Whether the game is still running or the snake has died.
    pub phase: Phase,
}

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

/// Build a fresh [`GameState`]: length-3 snake centered on the grid,
/// heading right, with food randomly placed on a free cell.
pub fn reset() -> GameState {
    let cx = GRID_W / 2;
    let cy = GRID_H / 2;

    // Head is rightmost; body extends left.
    let mut snake = VecDeque::new();
    snake.push_back((cx, cy));
    snake.push_back((cx - 1, cy));
    snake.push_back((cx - 2, cy));

    let mut state = GameState {
        snake,
        dir: Direction::Right,
        next_dir: Direction::Right,
        food: (0, 0), // overwritten immediately below
        score: 0,
        phase: Phase::Playing,
    };
    spawn_food(&mut state);
    state
}

/// Replace `state.food` with a randomly chosen cell that is not occupied
/// by the snake.  Uses macroquad's `rand::gen_range` for the pick.
/// Does nothing if the snake fills the entire grid.
pub fn spawn_food(state: &mut GameState) {
    let free: Vec<(i32, i32)> = (0..GRID_H)
        .flat_map(|y| (0..GRID_W).map(move |x| (x, y)))
        .filter(|pos| !state.snake.contains(pos))
        .collect();

    if free.is_empty() {
        // Entire grid is occupied — nowhere to place food.
        return;
    }

    let idx = gen_range(0, free.len());
    state.food = free[idx];
}

/// Advance the game by one step.
///
/// Algorithm:
/// 1. Commit `next_dir` as the new `dir` (180-degree reversal is blocked).
/// 2. Compute the candidate new-head cell.
/// 3. Wall collision → `GameOver`.
/// 4. Self collision → `GameOver`.
/// 5. Food: grow + score++ + respawn food; otherwise pop tail.
///
/// A no-op when `phase == GameOver`.
pub fn tick(state: &mut GameState) {
    if state.phase == Phase::GameOver {
        return;
    }

    // Apply buffered direction; silently ignore an attempted 180° reversal.
    if state.next_dir != state.dir.opposite() {
        state.dir = state.next_dir;
    }

    // Compute candidate new-head position.
    let &(hx, hy) = state.snake.front().expect("snake must not be empty");
    let (nx, ny) = match state.dir {
        Direction::Up => (hx, hy - 1),
        Direction::Down => (hx, hy + 1),
        Direction::Left => (hx - 1, hy),
        Direction::Right => (hx + 1, hy),
    };

    // --- Wall collision ---
    if nx < 0 || nx >= GRID_W || ny < 0 || ny >= GRID_H {
        state.phase = Phase::GameOver;
        return;
    }

    // --- Self collision ---
    // Whether the snake is eating food determines how many body segments
    // remain by the time the head arrives: if NOT eating, the tail leaves
    // (so the tail cell is safe); if eating, the tail stays (entire body
    // is occupied).
    let eating = (nx, ny) == state.food;
    let occupied_count = if eating {
        state.snake.len() // tail stays — check full body
    } else {
        state.snake.len().saturating_sub(1) // tail leaves — exclude it
    };
    if state
        .snake
        .iter()
        .take(occupied_count)
        .any(|&seg| seg == (nx, ny))
    {
        state.phase = Phase::GameOver;
        return;
    }

    // --- Advance snake ---
    state.snake.push_front((nx, ny));
    if eating {
        state.score += 1;
        spawn_food(state);
        // Tail is kept — snake grows by 1.
    } else {
        state.snake.pop_back();
    }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    fn make_state(snake: Vec<(i32, i32)>, dir: Direction, food: (i32, i32)) -> GameState {
        GameState {
            snake: VecDeque::from(snake),
            dir,
            next_dir: dir,
            food,
            score: 0,
            phase: Phase::Playing,
        }
    }

    // reset() ----------------------------------------------------------------

    #[test]
    fn reset_gives_length_3_snake() {
        let s = reset();
        assert_eq!(s.snake.len(), 3);
    }

    #[test]
    fn reset_starts_playing() {
        let s = reset();
        assert_eq!(s.phase, Phase::Playing);
    }

    #[test]
    fn reset_head_is_right_of_body() {
        let s = reset();
        let (hx, hy) = s.snake[0];
        let (b1x, b1y) = s.snake[1];
        assert_eq!(hy, b1y, "head and first body segment must share row");
        assert_eq!(hx, b1x + 1, "head must be one cell right of first body");
    }

    #[test]
    fn reset_direction_is_right() {
        let s = reset();
        assert_eq!(s.dir, Direction::Right);
        assert_eq!(s.next_dir, Direction::Right);
    }

    #[test]
    fn reset_food_not_on_snake() {
        let s = reset();
        assert!(
            !s.snake.contains(&s.food),
            "food must not overlap the snake"
        );
    }

    // tick() — normal move ---------------------------------------------------

    #[test]
    fn tick_moves_head_right() {
        let mut s = make_state(vec![(5, 5), (4, 5), (3, 5)], Direction::Right, (0, 0));
        tick(&mut s);
        assert_eq!(s.snake.front(), Some(&(6, 5)));
        assert_eq!(s.snake.len(), 3, "length unchanged when not eating");
    }

    #[test]
    fn tick_pops_tail_on_normal_move() {
        let mut s = make_state(vec![(5, 5), (4, 5), (3, 5)], Direction::Right, (0, 0));
        tick(&mut s);
        // tail (3,5) must be gone
        assert!(!s.snake.contains(&(3, 5)));
    }

    // tick() — eating --------------------------------------------------------

    #[test]
    fn tick_eating_grows_snake() {
        let mut s = make_state(vec![(5, 5), (4, 5), (3, 5)], Direction::Right, (6, 5));
        tick(&mut s);
        assert_eq!(s.snake.len(), 4);
        assert_eq!(s.score, 1);
        assert_eq!(s.phase, Phase::Playing);
    }

    #[test]
    fn tick_eating_keeps_old_tail() {
        let mut s = make_state(vec![(5, 5), (4, 5), (3, 5)], Direction::Right, (6, 5));
        tick(&mut s);
        // tail (3,5) must still be present
        assert!(s.snake.contains(&(3, 5)));
    }

    // tick() — collisions ----------------------------------------------------

    #[test]
    fn tick_wall_top() {
        let mut s = make_state(vec![(5, 0), (5, 1), (5, 2)], Direction::Up, (10, 10));
        tick(&mut s);
        assert_eq!(s.phase, Phase::GameOver);
    }

    #[test]
    fn tick_wall_left() {
        let mut s = make_state(vec![(0, 5), (1, 5), (2, 5)], Direction::Left, (10, 10));
        tick(&mut s);
        assert_eq!(s.phase, Phase::GameOver);
    }

    #[test]
    fn tick_wall_right() {
        let mut s = make_state(
            vec![(GRID_W - 1, 5), (GRID_W - 2, 5), (GRID_W - 3, 5)],
            Direction::Right,
            (0, 0),
        );
        tick(&mut s);
        assert_eq!(s.phase, Phase::GameOver);
    }

    #[test]
    fn tick_wall_bottom() {
        let mut s = make_state(
            vec![(5, GRID_H - 1), (5, GRID_H - 2), (5, GRID_H - 3)],
            Direction::Down,
            (0, 0),
        );
        tick(&mut s);
        assert_eq!(s.phase, Phase::GameOver);
    }

    #[test]
    fn tick_self_collision() {
        // Snake coiled so the head at (5,5) moving Right will hit body segment
        // [1] at (6,5) — a non-tail cell that does NOT vacate before the head
        // arrives.
        //
        // Layout:
        //   (4,4) — (5,4) — (6,4)
        //                     |
        //   (5,5) ← (6,5)   (head is (5,5), next_dir=Right → collides at (6,5))
        let mut s = make_state(
            vec![(5, 5), (6, 5), (6, 4), (5, 4), (4, 4)],
            Direction::Right,
            (0, 0),
        );
        tick(&mut s);
        assert_eq!(s.phase, Phase::GameOver);
    }

    #[test]
    fn tick_tail_chase_is_safe() {
        // Moving into the tail's current cell is SAFE because the tail vacates
        // before the head arrives (no food is being eaten).
        //
        // Snake: head (4,4) moving Up → (4,3) which is the tail.
        //   body: (4,4) → (5,4) → (5,3) → (4,3=tail)
        let mut s = make_state(
            vec![(4, 4), (5, 4), (5, 3), (4, 3)],
            Direction::Up,
            (0, 0),
        );
        tick(&mut s);
        assert_eq!(s.phase, Phase::Playing, "chasing the tail should be safe");
    }

    // tick() — 180° reversal blocked -----------------------------------------

    #[test]
    fn tick_blocks_180_reversal() {
        let mut s = make_state(vec![(5, 5), (4, 5), (3, 5)], Direction::Right, (0, 0));
        s.next_dir = Direction::Left; // attempt reversal
        tick(&mut s);
        // Should continue rightward, not die on self.
        assert_eq!(s.snake.front(), Some(&(6, 5)));
        assert_eq!(s.phase, Phase::Playing);
    }

    // tick() — no-op after game over -----------------------------------------

    #[test]
    fn tick_noop_after_game_over() {
        let mut s = make_state(vec![(5, 5), (4, 5), (3, 5)], Direction::Right, (0, 0));
        s.phase = Phase::GameOver;
        let before = s.snake.clone();
        tick(&mut s);
        assert_eq!(s.snake, before);
    }

    // spawn_food() -----------------------------------------------------------

    #[test]
    fn spawn_food_not_on_snake() {
        // Build a snake that covers most of the grid; leave only one free cell.
        // We'll just do a short snake and verify food lands off it.
        let mut s = make_state(vec![(0, 0), (1, 0), (2, 0)], Direction::Right, (5, 5));
        for _ in 0..20 {
            spawn_food(&mut s);
            assert!(!s.snake.contains(&s.food));
        }
    }
}
