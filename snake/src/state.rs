use std::collections::VecDeque;

/// Number of grid columns.
pub const GRID_W: usize = 30;
/// Number of grid rows.
pub const GRID_H: usize = 20;

/// Integer 2-D position in grid coordinates.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct IVec2 {
    pub x: i32,
    pub y: i32,
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Direction {
    Up,
    Down,
    Left,
    Right,
}

/// Full game state passed to the renderer each frame.
pub struct GameState {
    /// Body segments; `body[0]` is the **head**.
    pub body: VecDeque<IVec2>,
    pub food: IVec2,
    pub dir: Direction,
    pub alive: bool,
}

impl GameState {
    pub fn new() -> Self {
        let head = IVec2 {
            x: (GRID_W / 2) as i32,
            y: (GRID_H / 2) as i32,
        };
        let mut body = VecDeque::new();
        body.push_back(head);
        body.push_back(IVec2 { x: head.x - 1, y: head.y });
        body.push_back(IVec2 { x: head.x - 2, y: head.y });
        Self {
            body,
            food: IVec2 {
                x: (GRID_W / 4) as i32,
                y: (GRID_H / 4) as i32,
            },
            dir: Direction::Right,
            alive: true,
        }
    }
}
