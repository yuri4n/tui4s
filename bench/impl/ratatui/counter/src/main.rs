// claude 2026-10-20: created: the counter task (bench/tasks/counter.md) in ratatui
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

use crossterm::event::{self, Event, KeyCode, KeyEventKind, KeyModifiers};
use ratatui::layout::{Constraint, Layout};
use ratatui::{DefaultTerminal, Frame};

fn main() -> std::io::Result<()> {
    // SIGTERM and SIGINT set a flag; the loop sees it and restores the terminal.
    let stop = Arc::new(AtomicBool::new(false));
    for sig in [signal_hook::consts::SIGTERM, signal_hook::consts::SIGINT] {
        signal_hook::flag::register(sig, Arc::clone(&stop))?;
    }
    let mut terminal = ratatui::init(); // raw mode, alternate screen, a panic hook that restores
    let result = run(&mut terminal, &stop);
    ratatui::restore();
    result
}

fn run(terminal: &mut DefaultTerminal, stop: &AtomicBool) -> std::io::Result<()> {
    let mut count: i64 = 0;
    while !stop.load(Ordering::Relaxed) {
        terminal.draw(|frame| render(frame, count))?;
        if !event::poll(Duration::from_millis(100))? {
            continue;
        }
        if let Event::Key(key) = event::read()? {
            if key.kind != KeyEventKind::Press {
                continue;
            }
            let ctrl = key.modifiers.contains(KeyModifiers::CONTROL);
            match key.code {
                KeyCode::Char('c') if ctrl => break,
                KeyCode::Char('x') if ctrl => panic!("forced failure (Ctrl-X)"),
                KeyCode::Char('q') => break,
                KeyCode::Char('+') | KeyCode::Up => count += 1,
                KeyCode::Char('-') | KeyCode::Down => count -= 1,
                _ => {}
            }
        }
    }
    Ok(())
}

fn render(frame: &mut Frame, count: i64) {
    let [title, _, value, _, help] = Layout::vertical([
        Constraint::Length(1),
        Constraint::Length(1),
        Constraint::Length(1),
        Constraint::Fill(1),
        Constraint::Length(1),
    ])
    .areas(frame.area());
    frame.render_widget("Counter", title);
    frame.render_widget(format!("Count: {count}"), value);
    frame.render_widget("+/- or Up/Down change the count, q quits", help);
}
