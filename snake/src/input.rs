use macroquad::prelude::*;

use crate::{reset, Dir, Phase, State};

/// Poll keyboard input once per frame — non-blocking.
///
/// # Playing phase
/// Checks whether any of the four arrow keys was pressed this frame.
/// If the new direction is **not** a 180° reversal of the snake's current
/// direction (`state.dir`), it is stored in `state.next_dir`.  The first
/// matching key wins; at most one direction change is buffered per frame.
///
/// # GameOver phase
/// Pressing **R** or **Enter** calls [`reset`] and replaces `*state` with the
/// returned fresh state, restarting the game.
pub fn handle_input(state: &mut State) {
    match state.phase {
        Phase::Playing => {
            // Arrow key → direction pairs, checked in priority order.
            let candidates: [(KeyCode, Dir); 4] = [
                (KeyCode::Up, Dir::Up),
                (KeyCode::Down, Dir::Down),
                (KeyCode::Left, Dir::Left),
                (KeyCode::Right, Dir::Right),
            ];

            for (key, dir) in candidates {
                if is_key_pressed(key) {
                    // Ignore 180° reversals — the snake cannot turn back into itself.
                    if !state.dir.is_opposite(dir) {
                        state.next_dir = dir;
                    }
                    // Consume the first key pressed this frame regardless.
                    break;
                }
            }
        }

        Phase::GameOver => {
            if is_key_pressed(KeyCode::R) || is_key_pressed(KeyCode::Enter) {
                *state = reset();
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{Dir, Phase, State};

    fn playing_state(dir: Dir) -> State {
        State {
            dir,
            next_dir: dir,
            phase: Phase::Playing,
        }
    }

    fn game_over_state() -> State {
        State {
            dir: Dir::Right,
            next_dir: Dir::Right,
            phase: Phase::GameOver,
        }
    }

    #[test]
    fn is_opposite_pairs() {
        assert!(Dir::Up.is_opposite(Dir::Down));
        assert!(Dir::Down.is_opposite(Dir::Up));
        assert!(Dir::Left.is_opposite(Dir::Right));
        assert!(Dir::Right.is_opposite(Dir::Left));
    }

    #[test]
    fn is_opposite_non_pairs() {
        assert!(!Dir::Up.is_opposite(Dir::Left));
        assert!(!Dir::Up.is_opposite(Dir::Right));
        assert!(!Dir::Up.is_opposite(Dir::Up));
        assert!(!Dir::Down.is_opposite(Dir::Left));
        assert!(!Dir::Left.is_opposite(Dir::Up));
    }

    /// next_dir should update when the new direction is not a reversal.
    #[test]
    fn next_dir_updates_on_valid_turn() {
        let mut s = playing_state(Dir::Right);
        // Simulate "Up was pressed" by calling the logic directly.
        // We can't call handle_input without a macroquad window, so test
        // the Dir guard independently.
        let dir = Dir::Up;
        if !s.dir.is_opposite(dir) {
            s.next_dir = dir;
        }
        assert_eq!(s.next_dir, Dir::Up);
    }

    /// A 180° reversal must be rejected — next_dir stays unchanged.
    #[test]
    fn next_dir_unchanged_on_reversal() {
        let mut s = playing_state(Dir::Right);
        let dir = Dir::Left; // opposite of Right
        if !s.dir.is_opposite(dir) {
            s.next_dir = dir;
        }
        assert_eq!(s.next_dir, Dir::Right); // unchanged
    }

    /// reset() produces a Playing state headed Right.
    #[test]
    fn reset_produces_fresh_state() {
        let s = reset();
        assert_eq!(s.dir, Dir::Right);
        assert_eq!(s.next_dir, Dir::Right);
        assert_eq!(s.phase, Phase::Playing);
    }

    /// Verify game_over_state helper — sanity check for future tests.
    #[test]
    fn game_over_state_phase() {
        let s = game_over_state();
        assert_eq!(s.phase, Phase::GameOver);
    }
}
