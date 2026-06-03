use macroquad::prelude::*;

mod render;
mod state;

use render::{render, CELL_PX};
use state::{GameState, GRID_H, GRID_W};

fn window_conf() -> Conf {
    Conf {
        window_title: "Snake".to_string(),
        window_width: (GRID_W as f32 * CELL_PX) as i32,
        window_height: (GRID_H as f32 * CELL_PX) as i32,
        window_resizable: false,
        ..Default::default()
    }
}

#[macroquad::main(window_conf)]
async fn main() {
    let state = GameState::new();

    loop {
        render(&state);
        next_frame().await;
    }
}
