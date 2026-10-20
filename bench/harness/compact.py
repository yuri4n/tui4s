"""Compactness of the implementations: lines, tokens and gzip bytes of the sources of each task.

- lines: the non-blank lines that are not only a comment (after the stack's formatter).
- tokens: one simple lexer for all the languages, run on the source without its comments: a string literal is
  one token, an identifier or a keyword is one token, a number is one token, a run of operator characters
  (`+-*/%=<>!&|^~?:`) is one token, and each other character that is not a space (a bracket, a comma, a dot)
  is one token. Python docstrings count as comments.
- gzip: the bytes of `gzip -9` of the source without its comments.

The comments are out of all three numbers, so a file with more explanation does not lose.
Usage: python3 bench/harness/compact.py   (writes bench/results/static/compactness.json)
"""
# claude 2026-10-20: created (L5): the compactness numbers of the bench

import gzip
import io
import json
import os
import re
import tokenize

import stacks as st
from term import BENCH

C_LIKE = {"scala", "java", "rust", "go", "js"}


def strip_c_like(src):
    """Removes // and /* */ comments; keeps string and char literals."""
    out, i, n = [], 0, len(src)
    while i < n:
        c = src[i]
        if src.startswith("//", i):
            j = src.find("\n", i)
            i = n if j < 0 else j
        elif src.startswith("/*", i):
            j = src.find("*/", i + 2)
            i = n if j < 0 else j + 2
        elif src.startswith('"""', i):
            j = src.find('"""', i + 3)
            j = n if j < 0 else j + 3
            out.append(src[i:j])
            i = j
        elif c in "\"`" or (c == "'" and re.match(r"'(\\.|[^'\\])'", src[i:i + 4])):
            q, j = c, i + 1
            while j < n and src[j] != q:
                j += 2 if src[j] == "\\" else 1
            out.append(src[i:j + 1])
            i = j + 1
        else:
            out.append(c)
            i += 1
    return "".join(out)


def strip_python(src):
    """Removes comments and docstrings (a string that is a whole statement)."""
    toks = list(tokenize.generate_tokens(io.StringIO(src).readline))
    drop = set()
    prev = tokenize.NEWLINE
    for k, t in enumerate(toks):
        if t.type == tokenize.COMMENT:
            drop.add(k)
        elif t.type == tokenize.STRING and prev in (tokenize.NEWLINE, tokenize.INDENT, tokenize.DEDENT, None):
            nxt = toks[k + 1].type if k + 1 < len(toks) else tokenize.NEWLINE
            if nxt in (tokenize.NEWLINE, tokenize.ENDMARKER):
                drop.add(k)
        if t.type not in (tokenize.NL, tokenize.COMMENT):
            prev = t.type
    lines = src.splitlines(keepends=True)
    cut = [list(l) for l in lines]
    for k in sorted(drop, reverse=True):
        t = toks[k]
        (r0, c0), (r1, c1) = t.start, t.end
        for r in range(r0, r1 + 1):
            a = c0 if r == r0 else 0
            b = c1 if r == r1 else len(cut[r - 1])
            for x in range(a, min(b, len(cut[r - 1]))):
                if cut[r - 1][x] != "\n":
                    cut[r - 1][x] = " "
    return "".join("".join(l) for l in cut)


TOKEN = re.compile(
    r'"""[\s\S]*?"""|\'\'\'[\s\S]*?\'\'\'|"(?:\\.|[^"\\])*"|`(?:\\.|[^`\\])*`|\'(?:\\.|[^\'\\])*\''
    r"|[A-Za-z_][A-Za-z0-9_]*|\d[\d_.]*[A-Za-z]*|[-+*/%=<>!&|^~?:]+|\S"
)


def measure(stack, task):
    lang = st.STACKS[stack]["language"]
    text = ""
    for path in st.sources(stack, task):
        with open(path, encoding="utf-8") as f:
            text += f.read() + "\n"
    code = strip_python(text) if lang == "python" else strip_c_like(text)
    lines = [l for l in code.splitlines() if l.strip()]
    tokens = TOKEN.findall(code)
    packed = "\n".join(l.rstrip() for l in lines).encode()
    return {"lines": len(lines), "tokens": len(tokens), "gzip_bytes": len(gzip.compress(packed, 9, mtime=0))}


def main():
    out = {}
    for stack in st.ORDER:
        for task in st.TASKS:
            out[f"{stack}-{task}"] = measure(stack, task)
    path = os.path.join(BENCH, "results", "static", "compactness.json")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump(out, f, indent=1, sort_keys=True)
        f.write("\n")
    for k, v in out.items():
        print(f"{k:26} {v['lines']:5} lines {v['tokens']:6} tokens {v['gzip_bytes']:6} gzip bytes")


if __name__ == "__main__":
    main()
