// claude 2026-10-20: created: the dashboard task (bench/tasks/dashboard.md) in ratatui
use std::collections::VecDeque;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

use crossterm::event::{self, Event, KeyCode, KeyEventKind, KeyModifiers};
use ratatui::layout::{Constraint, Layout};
use ratatui::style::{Color, Style};
use ratatui::widgets::{Block, Gauge, HighlightSpacing, List, ListState, Sparkline};
use ratatui::{DefaultTerminal, Frame};

const TICK: Duration = Duration::from_millis(100);
const ITEMS: usize = 1000;

#[derive(PartialEq, Clone, Copy)]
enum Focus {
    List,
    Load,
}

struct App {
    ticks: u64,
    history: VecDeque<u64>,
    list: ListState,
    focus: Focus,
    page: usize, // the visible item rows of the last frame
    items: Vec<String>,
}

/// v(T) of the specification.
fn value(t: u64) -> u64 {
    (50.0 + 40.0 * (t as f64 / 5.0).sin()).round() as u64
}

fn main() -> std::io::Result<()> {
    let stop = Arc::new(AtomicBool::new(false));
    for sig in [signal_hook::consts::SIGTERM, signal_hook::consts::SIGINT] {
        signal_hook::flag::register(sig, Arc::clone(&stop))?;
    }
    let mut terminal = ratatui::init();
    let result = run(&mut terminal, &stop);
    ratatui::restore();
    result
}

fn run(terminal: &mut DefaultTerminal, stop: &AtomicBool) -> std::io::Result<()> {
    let mut app = App {
        ticks: 0,
        history: VecDeque::new(),
        list: ListState::default().with_selected(Some(0)),
        focus: Focus::List,
        page: 1,
        items: (1..=ITEMS).map(|i| format!("Item {i:04}")).collect(),
    };
    let mut next_tick = Instant::now() + TICK;
    while !stop.load(Ordering::Relaxed) {
        terminal.draw(|frame| render(frame, &mut app))?;
        let wait = next_tick.saturating_duration_since(Instant::now());
        if event::poll(wait)? {
            if let Event::Key(key) = event::read()? {
                if key.kind == KeyEventKind::Press && !handle(&mut app, key.code, key.modifiers) {
                    break;
                }
            }
        }
        if Instant::now() >= next_tick {
            app.ticks += 1;
            app.history.push_back(value(app.ticks));
            if app.history.len() > 40 {
                app.history.pop_front();
            }
            next_tick += TICK;
        }
    }
    Ok(())
}

/// Handles one key; false ends the program.
fn handle(app: &mut App, code: KeyCode, modifiers: KeyModifiers) -> bool {
    let ctrl = modifiers.contains(KeyModifiers::CONTROL);
    let selected = app.list.selected().unwrap_or(0);
    let on_list = app.focus == Focus::List;
    let target = match code {
        KeyCode::Char('c') if ctrl => return false,
        KeyCode::Char('x') if ctrl => panic!("forced failure (Ctrl-X)"),
        KeyCode::Char('q') => return false,
        KeyCode::Tab => {
            app.focus = if on_list { Focus::Load } else { Focus::List };
            return true;
        }
        KeyCode::Down if on_list => selected.saturating_add(1),
        KeyCode::Up if on_list => selected.saturating_sub(1),
        KeyCode::PageDown if on_list => selected.saturating_add(app.page),
        KeyCode::PageUp if on_list => selected.saturating_sub(app.page),
        _ => return true,
    };
    app.list.select(Some(target.min(ITEMS - 1)));
    true
}

fn panel(title: &str, focused: bool) -> Block<'_> {
    let color = if focused { Color::Yellow } else { Color::Gray };
    Block::bordered()
        .title(title)
        .border_style(Style::new().fg(color))
}

fn render(frame: &mut Frame, app: &mut App) {
    let [header, body, status] = Layout::vertical([
        Constraint::Length(1),
        Constraint::Fill(1),
        Constraint::Length(1),
    ])
    .areas(frame.area());
    let [name, clock] =
        Layout::horizontal([Constraint::Fill(1), Constraint::Length(8)]).areas(header);
    frame.render_widget("Dashboard", name);
    frame.render_widget(chrono::Local::now().format("%H:%M:%S").to_string(), clock);

    let [left, right] =
        Layout::horizontal([Constraint::Percentage(50), Constraint::Percentage(50)]).areas(body);
    app.page = (left.height as usize).saturating_sub(2).max(1);
    let list = List::new(app.items.iter().map(String::as_str))
        .block(panel("Items", app.focus == Focus::List))
        .highlight_symbol("> ")
        .highlight_spacing(HighlightSpacing::Always);
    frame.render_stateful_widget(list, left, &mut app.list);

    let load = panel("Load", app.focus == Focus::Load);
    let [ticks, gauge, spark, _] = Layout::vertical([
        Constraint::Length(1),
        Constraint::Length(1),
        Constraint::Length(1),
        Constraint::Fill(1),
    ])
    .areas(load.inner(right));
    frame.render_widget(load, right);
    frame.render_widget(format!("Ticks: {}", app.ticks), ticks);
    frame.render_widget(Gauge::default().percent(value(app.ticks) as u16), gauge);
    let data: Vec<u64> = app.history.iter().copied().collect();
    frame.render_widget(Sparkline::default().data(&data).max(100), spark);

    let focus = if app.focus == Focus::List {
        "list"
    } else {
        "load"
    };
    let selected = app.list.selected().unwrap_or(0) + 1;
    frame.render_widget(
        format!("Selected {selected:04}/{ITEMS} | Focus: {focus} | Tab focus, q quit"),
        status,
    );
}
