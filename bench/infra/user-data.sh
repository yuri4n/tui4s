#!/bin/bash
# The user data of the bench instance (bench/infra/alchemy.run.ts fills the
# __NAMES__). It runs once, as root, at the first boot of Ubuntu 24.04.
# claude 2026-10-20: changed: the upload runs from the EXIT trap and once at 85 minutes, so a run that hits the
# bound keeps its results (review fix)

# The hard bound first: the instance ends after 90 minutes, whatever happens.
# The instance terminates on shutdown (InstanceInitiatedShutdownBehavior).
shutdown -h +90
exec >/var/log/tui4s-bench.log 2>&1
set -uo pipefail
export HOME=/root DEBIAN_FRONTEND=noninteractive
TOOLS=/opt/tools
mkdir -p "$TOOLS"

# Uploads the results that exist now and the log to s3://<bucket>/<machine>/<commit>/. With "partial", it also
# writes the object PARTIAL. The role of the instance (IMDSv2) may only put objects into the bucket.
upload() {
    [ -x /opt/upload/bin/python ] || return 0
    /opt/upload/bin/python - "$1" <<'PY' || true
import glob, os, sys
import boto3
s3 = boto3.client("s3", region_name="__REGION__")
prefix = "__MACHINE__/__COMMIT__/"
files = glob.glob("/opt/tui4s/bench/results/__MACHINE__/*.json") + ["/var/log/tui4s-bench.log"]
for path in files:
    if os.path.exists(path):
        s3.upload_file(path, "__BUCKET__", prefix + os.path.basename(path))
if sys.argv[1] == "partial":
    s3.put_object(Bucket="__BUCKET__", Key=prefix + "PARTIAL", Body=b"the run reached 85 minutes\n")
PY
}
trap 'upload final; shutdown -h now' EXIT
trap 'exit 143' TERM INT HUP

# The toolchains, at the versions of the bench (bench/impl/*/README.md).
apt-get update -q
apt-get install -y -q git curl ca-certificates xz-utils unzip build-essential patch expect \
    python3 python3-venv python3-pip
python3 -m venv /opt/upload && /opt/upload/bin/pip install -q boto3
(sleep 5100 && upload partial) &
curl -fsSL "https://api.adoptium.net/v3/binary/latest/25/ga/linux/x64/jdk/hotspot/normal/eclipse" \
    | tar -xz -C "$TOOLS" && export JAVA_HOME=$(ls -d "$TOOLS"/jdk-25*)
curl -fsSL "https://github.com/sbt/sbt/releases/download/v1.13.0/sbt-1.13.0.tgz" | tar -xz -C "$TOOLS"
curl -fsSL "https://go.dev/dl/go1.24.7.linux-amd64.tar.gz" | tar -xz -C "$TOOLS"
curl -fsSL "https://nodejs.org/dist/v22.22.0/node-v22.22.0-linux-x64.tar.xz" | tar -xJ -C "$TOOLS"
export PATH="$JAVA_HOME/bin:$TOOLS/sbt/bin:$TOOLS/go/bin:$TOOLS/node-v22.22.0-linux-x64/bin:$PATH"
npm install -g pnpm@10.28.0
curl -fsSL https://sh.rustup.rs | sh -s -- -y --profile minimal --component rustfmt --default-toolchain 1.97.0
export PATH="$HOME/.cargo/bin:$PATH"
java -version

# The bench at the commit. The EXIT trap uploads its results and shuts down.
git clone "__REPO__" /opt/tui4s && cd /opt/tui4s && git checkout "__COMMIT__" \
    && git submodule update --init --recursive
BENCH_MACHINE="__MACHINE__" BENCH_MACHINE_NOTE="AWS __MACHINE__ in __REGION__, on demand, dedicated to the run" \
    bench/run.sh full
