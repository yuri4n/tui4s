// claude 2026-10-20: created: the dashboard task (bench/tasks/dashboard.md) in ink
import { useRef, useState } from "react";
import {
  render,
  Box,
  Text,
  useAnimation,
  useApp,
  useFocus,
  useInput,
  useWindowSize,
} from "ink";

const ITEMS = 1000;
const LEVELS = "▁▂▃▄▅▆▇█";

/** v(T) of the specification. */
const value = (t) => Math.round(50 + 40 * Math.sin(t / 5));
const pad = (n) => String(n).padStart(4, "0");

function Panel({ title, focused, width, children }) {
  return (
    <Box
      flexDirection="column"
      width={width}
      borderStyle="single"
      borderColor={focused ? "yellow" : "gray"}
    >
      <Text bold>{title}</Text>
      {children}
    </Box>
  );
}

function Dashboard() {
  const { exit } = useApp();
  const { columns, rows } = useWindowSize();
  const { frame: ticks } = useAnimation({ interval: 100 });
  const list = useFocus({ autoFocus: true });
  const load = useFocus();
  const [selected, setSelected] = useState(0);
  const offset = useRef(0);
  const visible = Math.max(1, rows - 5);

  useInput((input, key) => {
    if (input === "q") exit();
    else if (key.ctrl && input === "x")
      throw new Error("forced failure (Ctrl-X)");
  });
  useInput(
    (_, key) => {
      const move = key.downArrow
        ? 1
        : key.upArrow
          ? -1
          : key.pageDown
            ? visible
            : key.pageUp
              ? -visible
              : 0;
      setSelected((s) => Math.max(0, Math.min(ITEMS - 1, s + move)));
    },
    { isActive: list.isFocused },
  );

  offset.current = Math.max(
    Math.min(offset.current, selected),
    selected - visible + 1,
  );
  const shown = [];
  for (
    let i = offset.current;
    i < Math.min(ITEMS, offset.current + visible);
    i++
  ) {
    shown.push(
      <Text
        key={i}
      >{`${i === selected ? "> " : "  "}Item ${pad(i + 1)}`}</Text>,
    );
  }
  const history = [];
  for (let t = Math.max(1, ticks - 39); t <= ticks; t++) history.push(value(t));
  const v = value(ticks);
  const filled = Math.round((v / 100) * 40);
  const left = Math.floor(columns / 2);

  return (
    <Box flexDirection="column" height={rows}>
      <Box justifyContent="space-between">
        <Text>Dashboard</Text>
        <Text>{new Date().toTimeString().slice(0, 8)}</Text>
      </Box>
      <Box flexGrow={1}>
        <Panel title="Items" focused={list.isFocused} width={left}>
          {shown}
        </Panel>
        <Panel title="Load" focused={load.isFocused} width={columns - left}>
          <Text>Ticks: {ticks}</Text>
          <Text color="green">{`${"█".repeat(filled)}${"░".repeat(40 - filled)} ${String(v).padStart(3)}%`}</Text>
          <Text color="green">
            {history
              .map((x) => LEVELS[Math.min(7, Math.floor((x * 8) / 101))])
              .join("")}
          </Text>
        </Panel>
      </Box>
      <Text>{`Selected ${pad(selected + 1)}/${ITEMS} | Focus: ${list.isFocused ? "list" : "load"} | Tab focus, q quit`}</Text>
    </Box>
  );
}

render(<Dashboard />, { alternateScreen: true });
