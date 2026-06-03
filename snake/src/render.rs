use macroquad::prelude::*;

use crate::state::{GameState, GRID_H, GRID_W};

/// Pixel size of one grid cell.
pub const CELL_PX: f32 = 20.0;

// ── Palette ───────────────────────────────────────────────────────────────────

/// Near-black background.
const BG: Color = Color { r: 0.08, g: 0.08, b: 0.10, a: 1.0 };

/// Very faint grid lines — just enough to feel the grid without distracting.
const GRID_LINE: Color = Color { r: 0.14, g: 0.14, b: 0.17, a: 1.0 };

/// Snake body — mid-green.
const BODY: Color = Color { r: 0.18, g: 0.72, b: 0.30, a: 1.0 };

/// Snake head — lighter / brighter green so it stands out instantly.
const HEAD: Color = Color { r: 0.62, g: 0.97, b: 0.62, a: 1.0 };

/// Food — warm red-orange, high contrast against the green snake.
const FOOD: Color = Color { r: 0.95, g: 0.28, b: 0.18, a: 1.0 };

// ── Public API ────────────────────────────────────────────────────────────────

/// Draw one complete frame for `state`.
///
/// Call order:
/// 1. Clear background.
/// 2. Faint grid lines.
/// 3. Food (drawn before the snake so the snake always appears on top).
/// 4. Snake body segments (all segments except the head).
/// 5. Snake head (brighter shade).
pub fn render(state: &GameState) {
    // ── 1. Background ─────────────────────────────────────────────────────────
    clear_background(BG);

    // ── 2. Grid lines ─────────────────────────────────────────────────────────
    let total_w = GRID_W as f32 * CELL_PX;
    let total_h = GRID_H as f32 * CELL_PX;

    // Vertical lines (one per column boundary, plus the right edge).
    for col in 0..=GRID_W {
        let x = col as f32 * CELL_PX;
        draw_line(x, 0.0, x, total_h, 1.0, GRID_LINE);
    }
    // Horizontal lines (one per row boundary, plus the bottom edge).
    for row in 0..=GRID_H {
        let y = row as f32 * CELL_PX;
        draw_line(0.0, y, total_w, y, 1.0, GRID_LINE);
    }

    // ── 3. Food ───────────────────────────────────────────────────────────────
    // Drawn as a circle that almost fills its cell (1 px inset on each side).
    let fx = state.food.x as f32 * CELL_PX + CELL_PX / 2.0;
    let fy = state.food.y as f32 * CELL_PX + CELL_PX / 2.0;
    draw_circle(fx, fy, CELL_PX / 2.0 - 1.0, FOOD);

    // ── 4. Snake body (all segments except the head) ─────────────────────────
    // A 1 px gap on every side gives a subtle separation between adjacent
    // segments and makes the snake look less like a solid bar.
    for seg in state.body.iter().skip(1) {
        let x = seg.x as f32 * CELL_PX + 1.0;
        let y = seg.y as f32 * CELL_PX + 1.0;
        draw_rectangle(x, y, CELL_PX - 2.0, CELL_PX - 2.0, BODY);
    }

    // ── 5. Head ───────────────────────────────────────────────────────────────
    if let Some(head) = state.body.front() {
        let x = head.x as f32 * CELL_PX + 1.0;
        let y = head.y as f32 * CELL_PX + 1.0;
        draw_rectangle(x, y, CELL_PX - 2.0, CELL_PX - 2.0, HEAD);
    }
}
