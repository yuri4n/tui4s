// claude 2026-10-20: created: the counter task (bench/tasks/counter.md) in bubbletea
package main

import (
	"fmt"
	"os"
	"strings"

	tea "github.com/charmbracelet/bubbletea"
)

type model struct {
	count  int
	height int
}

func (m model) Init() tea.Cmd { return nil }

func (m model) Update(msg tea.Msg) (tea.Model, tea.Cmd) {
	switch msg := msg.(type) {
	case tea.WindowSizeMsg:
		m.height = msg.Height
	case tea.KeyMsg:
		// bubbletea gives the runes of one read as one message ("+++"): one update per rune.
		if msg.Type == tea.KeyRunes && len(msg.Runes) > 1 {
			var model tea.Model = m
			var cmd tea.Cmd
			for _, r := range msg.Runes {
				model, cmd = model.Update(tea.KeyMsg{Type: tea.KeyRunes, Runes: []rune{r}})
			}
			return model, cmd
		}
		switch msg.String() {
		case "+", "up":
			m.count++
		case "-", "down":
			m.count--
		case "q", "ctrl+c":
			return m, tea.Quit
		case "ctrl+x":
			panic("forced failure (Ctrl-X)")
		}
	}
	return m, nil
}

func (m model) View() string {
	lines := []string{"Counter", "", fmt.Sprintf("Count: %d", m.count)}
	for len(lines) < m.height-1 {
		lines = append(lines, "")
	}
	lines = append(lines, "+/- or Up/Down change the count, q quits")
	return strings.Join(lines, "\n")
}

func main() {
	if _, err := tea.NewProgram(model{}, tea.WithAltScreen()).Run(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
