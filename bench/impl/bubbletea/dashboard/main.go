// claude 2026-10-20: created: the dashboard task (bench/tasks/dashboard.md) in bubbletea
package main

import (
	"fmt"
	"math"
	"os"
	"strings"
	"time"

	"github.com/charmbracelet/bubbles/progress"
	tea "github.com/charmbracelet/bubbletea"
	"github.com/charmbracelet/lipgloss"
)

const items = 1000

type tickMsg time.Time

type model struct {
	width, height int
	ticks         int
	history       []int
	selected      int // 0-based
	offset        int // the first visible item
	onLoad        bool
	gauge         progress.Model
}

// value is v(T) of the specification.
func value(t int) int { return int(math.Round(50 + 40*math.Sin(float64(t)/5))) }

func tick() tea.Cmd {
	return tea.Tick(100*time.Millisecond, func(t time.Time) tea.Msg { return tickMsg(t) })
}

func (m model) Init() tea.Cmd { return tick() }

// rows is the number of visible item rows: the body without the borders and the title.
func (m model) rows() int { return max(1, m.height-2-2-1) }

func (m model) Update(msg tea.Msg) (tea.Model, tea.Cmd) {
	switch msg := msg.(type) {
	case tea.WindowSizeMsg:
		m.width, m.height = msg.Width, msg.Height
	case tickMsg:
		m.ticks++
		m.history = append(m.history, value(m.ticks))
		if len(m.history) > 40 {
			m.history = m.history[1:]
		}
		return m, tick()
	case tea.KeyMsg:
		switch k := msg.String(); {
		case k == "q" || k == "ctrl+c":
			return m, tea.Quit
		case k == "ctrl+x":
			panic("forced failure (Ctrl-X)")
		case k == "tab":
			m.onLoad = !m.onLoad
		case m.onLoad:
		case k == "down":
			m.selected++
		case k == "up":
			m.selected--
		case k == "pgdown":
			m.selected += m.rows()
		case k == "pgup":
			m.selected -= m.rows()
		}
	}
	m.selected = min(max(m.selected, 0), items-1)
	m.offset = min(max(m.offset, m.selected-m.rows()+1), m.selected)
	return m, nil
}

func panel(focused bool, w, h int, body string) string {
	color := lipgloss.Color("7")
	if focused {
		color = lipgloss.Color("11")
	}
	return lipgloss.NewStyle().Border(lipgloss.NormalBorder()).BorderForeground(color).
		Width(w - 2).Height(h - 2).MaxHeight(h).Render(body)
}

func sparkline(values []int) string {
	levels := []rune("▁▂▃▄▅▆▇█")
	var b strings.Builder
	for _, v := range values {
		b.WriteRune(levels[min(7, v*8/101)])
	}
	return b.String()
}

func (m model) View() string {
	if m.width == 0 {
		return ""
	}
	clock := time.Now().Format("15:04:05")
	header := "Dashboard" + strings.Repeat(" ", max(0, m.width-9-len(clock))) + clock

	var list strings.Builder
	list.WriteString("Items")
	for i := m.offset; i < min(items, m.offset+m.rows()); i++ {
		marker := "  "
		if i == m.selected {
			marker = "> "
		}
		fmt.Fprintf(&list, "\n%sItem %04d", marker, i+1)
	}
	left := m.width / 2
	bodyHeight := m.height - 2
	m.gauge.Width = min(40, m.width-left-4)
	load := strings.Join([]string{
		"Load",
		fmt.Sprintf("Ticks: %d", m.ticks),
		m.gauge.ViewAs(float64(value(m.ticks)) / 100),
		sparkline(m.history),
	}, "\n")
	body := lipgloss.JoinHorizontal(lipgloss.Top,
		panel(!m.onLoad, left, bodyHeight, list.String()),
		panel(m.onLoad, m.width-left, bodyHeight, load))

	focus := "list"
	if m.onLoad {
		focus = "load"
	}
	status := fmt.Sprintf("Selected %04d/%d | Focus: %s | Tab focus, q quit", m.selected+1, items, focus)
	return lipgloss.JoinVertical(lipgloss.Left, header, body, status)
}

func main() {
	m := model{gauge: progress.New(progress.WithSolidFill("2"))}
	if _, err := tea.NewProgram(m, tea.WithAltScreen()).Run(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
