#!/bin/bash
# The user data of the bench instance (bench/infra/alchemy.run.ts fills the
# __NAMES__). It runs once, as root, at the first boot of Ubuntu 24.04.
# claude 2026-10-20: created (L5)

# The hard bound first: the instance ends after 90 minutes, whatever happens.
# The instance terminates on shutdown (InstanceInitiatedShutdownBehavior).
shutdown -h +90
trap 'shutdown -h now' EXIT
set -uo pipefail
exec >/var/log/tui4s-bench.log 2>&1
export HOME=/root DEBIAN_FRONTEND=noninteractive
TOOLS=/opt/tools
mkdir -p "$TOOLS"

# The toolchains, at the versions of the bench (bench/impl/*/README.md).
apt-get update -q
apt-get install -y -q git curl ca-certificates xz-utils unzip build-essential patch expect \
    python3 python3-venv python3-pip
curl -fsSL "https://api.adoptium.net/v3/binary/latest/25/ga/linux/x64/jdk/hotspot/normal/eclipse" \
    | tar -xz -C "$TOOLS" && export JAVA_HOME=$(ls -d "$TOOLS"/jdk-25*)
curl -fsSL "https://github.com/sbt/sbt/releases/download/v1.13.0/sbt-1.13.0.tgz" | tar -xz -C "$TOOLS"
curl -fsSL "https://go.dev/dl/go1.24.7.linux-amd64.tar.gz" | tar -xz -C "$TOOLS"
curl -fsSL "https://nodejs.org/dist/v22.22.0/node-v22.22.0-linux-x64.tar.xz" | tar -xJ -C "$TOOLS"
export PATH="$JAVA_HOME/bin:$TOOLS/sbt/bin:$TOOLS/go/bin:$TOOLS/node-v22.22.0-linux-x64/bin:$PATH"
npm install -g pnpm@10.28.0
curl -fsSL https://sh.rustup.rs | sh -s -- -y --profile minimal --component rustfmt --default-toolchain 1.97.0
export PATH="$HOME/.cargo/bin:$PATH"

# The bench at the commit, then the upload of its results.
git clone "__REPO__" /opt/tui4s && cd /opt/tui4s && git checkout "__COMMIT__" \
    && git submodule update --init --recursive
BENCH_MACHINE="__MACHINE__" BENCH_MACHINE_NOTE="AWS __MACHINE__ in __REGION__, on demand, dedicated to the run" \
    bench/run.sh full
python3 -m venv /opt/upload && /opt/upload/bin/pip install -q boto3
/opt/upload/bin/python - <<'PY'
import glob, os
import boto3  # the role of the instance (IMDSv2); it may only put objects into the bucket
s3 = boto3.client("s3", region_name="__REGION__")
prefix = "__MACHINE__/__COMMIT__/"
files = glob.glob("/opt/tui4s/bench/results/__MACHINE__/*.json") + ["/var/log/tui4s-bench.log"]
for path in files:
    if os.path.exists(path):
        s3.upload_file(path, "__BUCKET__", prefix + os.path.basename(path))
PY
# The trap shuts the instance down (and so terminates it).
