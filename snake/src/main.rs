use macroquad::prelude::*;

#[macroquad::main("Snake")]
async fn main() {
    loop {
        clear_background(BLACK);

        draw_text("Snake — coming soon!", 20.0, 40.0, 30.0, GREEN);

        next_frame().await;
    }
}
