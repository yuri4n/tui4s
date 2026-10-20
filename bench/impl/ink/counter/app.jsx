// claude 2026-10-20: created: the counter task (bench/tasks/counter.md) in ink
import { useState } from "react";
import { render, Box, Text, useApp, useInput, useWindowSize } from "ink";

function Counter() {
  const [count, setCount] = useState(0);
  const { exit } = useApp();
  const { rows } = useWindowSize();
  useInput((input, key) => {
    if (key.upArrow) setCount((c) => c + 1);
    else if (key.downArrow) setCount((c) => c - 1);
    else if (key.ctrl && input === "x")
      throw new Error("forced failure (Ctrl-X)");
    // ink gives the characters of one read as one input ("+++"): one step per character.
    for (const ch of key.ctrl ? "" : input) {
      if (ch === "+") setCount((c) => c + 1);
      else if (ch === "-") setCount((c) => c - 1);
      else if (ch === "q") exit();
    }
  });
  return (
    <Box flexDirection="column" height={rows}>
      <Text>Counter</Text>
      <Text> </Text>
      <Text>Count: {count}</Text>
      <Box flexGrow={1} />
      <Text>+/- or Up/Down change the count, q quits</Text>
    </Box>
  );
}

render(<Counter />, { alternateScreen: true });
