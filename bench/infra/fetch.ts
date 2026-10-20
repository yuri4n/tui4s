// claude 2026-10-20: created (L5): downloads the results of the AWS run into bench/results/<machine>/.
// Usage: BENCH_MACHINE=aws-c7i.xlarge [BENCH_COMMIT=<sha>] pnpm fetch   (the stage defaults to $USER, as in alchemy)
// The credentials come only from the AWS SDK default chain.
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import {
  GetObjectCommand,
  ListObjectsV2Command,
  S3Client,
} from "@aws-sdk/client-s3";
import { GetCallerIdentityCommand, STSClient } from "@aws-sdk/client-sts";

const region = "us-east-1";
const stage = process.env.STAGE ?? process.env.USER ?? "dev";
const machine = process.env.BENCH_MACHINE ?? "aws-c7i.xlarge";
const commit = process.env.BENCH_COMMIT ?? ""; // one commit, or the newest files of all
const account = (
  await new STSClient({ region }).send(new GetCallerIdentityCommand({}))
).Account;
const bucket = `tui4s-bench-${account}-${stage}`.toLowerCase();
const out = join(
  dirname(fileURLToPath(import.meta.url)),
  "..",
  "results",
  machine,
);
const s3 = new S3Client({ region });

const listed = await s3.send(
  new ListObjectsV2Command({ Bucket: bucket, Prefix: `${machine}/${commit}` }),
);
const keys = (listed.Contents ?? [])
  .sort(
    (a, b) =>
      (a.LastModified?.getTime() ?? 0) - (b.LastModified?.getTime() ?? 0),
  )
  .map((o) => o.Key!)
  .filter((k) => k.endsWith(".json") || k.endsWith(".log"));
if (keys.length === 0) {
  console.log(`fetch: no results in s3://${bucket}/${machine}/ yet`);
  process.exit(1);
}
mkdirSync(out, { recursive: true });
for (const key of keys) {
  const body = await (
    await s3.send(new GetObjectCommand({ Bucket: bucket, Key: key }))
  ).Body!.transformToString();
  writeFileSync(join(out, key.split("/").pop()!), body);
}
console.log(
  `fetch: ${keys.length} files into ${out}; now run: python3 bench/harness/report.py ${machine}`,
);
