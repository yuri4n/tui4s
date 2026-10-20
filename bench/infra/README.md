<!-- claude 2026-10-20: created: the AWS run of the bench (L5, D10) -->
# The AWS run of the bench

This directory runs the bench on a dedicated machine in your AWS account, in
`us-east-1`, with [alchemy](https://alchemy.run) 0.94.0 (`alchemy.run.ts`).
One run makes these resources:

| Resource | Settings |
| -------- | -------- |
| S3 bucket `tui4s-bench-<account>-<stage>` | Private (all public access blocked, owner-enforced objects), encrypted (SSE-S3), no versioning, objects expire after 30 days. |
| IAM role and instance profile `tui4s-bench-runner-<stage>` | The instance can only put objects into that bucket (`s3:PutObject` on `<bucket>/*`). Nothing else. |
| Security group | No inbound rule at all. The default outbound rule stays (the toolchains and the upload need it). |
| EC2 instance (`c7i.xlarge`, on demand) | Ubuntu 24.04, no key pair, IMDSv2 only, a 40 GB encrypted gp3 disk that is deleted with the instance. A shutdown terminates the instance. |

The user data of the instance (`user-data.sh`):

1. schedules a shutdown after 90 minutes **first**, whatever happens next (the
   hard bound of the run);
2. installs the toolchains (JDK 25, sbt 1.13.0, Rust 1.97.0, Go 1.24.7,
   Node.js 22.22.0 with pnpm 10.28.0, Python 3);
3. clones the repository at the commit that you give, and runs
   `bench/run.sh full`;
4. uploads `bench/results/<machine>/*.json` and its log to
   `s3://<bucket>/<machine>/<commit>/` when the script ends (the EXIT trap,
   also after a failure or a TERM signal), and once at 85 minutes with an
   object `PARTIAL`, so a run that reaches the bound keeps its results;
5. shuts down, and so terminates the instance (also when a step fails).

The JDK is the latest Temurin 25 GA build of the day of the run; the log of
the run records its `java -version`.
<!-- claude 2026-10-20: changed: the upload from the EXIT trap and at 85 minutes; the JDK build (review fixes) -->

**Status: not deployed from the build container.** The container reached AWS
(STS answered), but it rejected the credentials of the container
(`InvalidClientTokenId`). Nothing was created. The fix stage deploys once,
after the review of these safety rails.

## Cost

| Item | Price (us-east-1, on demand, at the time of writing) | One run |
| ---- | ---------------------------------------------------- | ------- |
| `c7i.xlarge` | about USD 0.18 per hour | at most 1.5 h: about USD 0.27 |
| 40 GB gp3 | about USD 0.08 per GB-month | about USD 0.01 |
| S3 (a few JSON files, 30 days) | | less than USD 0.01 |
| **Total** | | **about USD 0.30** |

Check the prices before a run. The 90-minute bound limits the cost of a run
that does not end by itself.

## Run it

You need Node.js 22, pnpm, and AWS credentials in the standard places of the
AWS SDK (environment variables, a profile, or a role). The scripts read the
credentials only from the default credential chain of the SDK. Do not put a
credential in a file of this repository.

```
cd bench/infra
pnpm install --frozen-lockfile
pnpm whoami                                    # read-only: the account (last 4 digits) and the caller
BENCH_COMMIT=$(git rev-parse HEAD) pnpm alchemy deploy
# wait: the run takes about 60 minutes, at most 90
BENCH_MACHINE=aws-c7i.xlarge pnpm fetch        # into bench/results/aws-c7i.xlarge/
pnpm alchemy destroy                           # always: it empties the bucket, then removes everything
python3 ../harness/report.py aws-c7i.xlarge    # the tables of bench/README.md and PERFORMANCE.md
```

`BENCH_INSTANCE_TYPE` selects another instance type (the closest
compute-optimized type when `c7i.xlarge` is not available), `BENCH_MACHINE`
names the machine in the results (default `aws-<type>`), and `BENCH_REPO`
selects another clone URL. The commit must be pushed: the instance clones it.
Alchemy keeps its state in `.alchemy/` (git ignores it). The scripts turn off
the telemetry of alchemy (`ALCHEMY_TELEMETRY_DISABLED=1`).

## The rule

**Destroy always.** After the fetch, run `pnpm alchemy destroy`. When the run
is complete, nothing stays in the account: the instance terminated itself, and
the destroy removes the bucket, the role, the instance profile and the security
group. If a deploy fails in the middle, run the destroy too.
