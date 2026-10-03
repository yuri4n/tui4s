#!/usr/bin/env bash
# claude 2026-10-03: created: CPU time (utime + stime from /proc/<pid>/stat) of one process over <secs> seconds.
pid=$1; secs=$2
tck=$(getconf CLK_TCK)
cpu() { awk '{print $14 + $15}' /proc/$pid/stat; }
a=$(cpu); t0=$(date +%s.%N); sleep "$secs"; b=$(cpu); t1=$(date +%s.%N)
python3 -c "a,b,t0,t1,tck=$a,$b,$t0,$t1,$tck; ms=(b-a)*1000/tck; print(f'cpu_ms={ms:.0f} wall_s={t1-t0:.1f} pct_of_one_core={ms/10/(t1-t0):.2f}')"
