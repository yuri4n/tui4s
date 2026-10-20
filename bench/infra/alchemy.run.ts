// claude 2026-10-20: created (L5): the AWS run of the bench (D10), with alchemy.
//
// It creates, in us-east-1:
//   - one private S3 bucket for the results (no versioning, objects expire after 30 days);
//   - one IAM role and instance profile that can only put objects into that bucket;
//   - one security group with no inbound rule;
//   - one on-demand EC2 instance (no key pair, IMDSv2 only) whose user data runs bench/run.sh at a given
//     commit, uploads bench/results/<machine>/ to the bucket and shuts down. A shutdown terminates the
//     instance, and the user data schedules a shutdown after 90 minutes first, whatever happens.
//
// Deploy:  BENCH_COMMIT=<sha> pnpm alchemy deploy
// Destroy: pnpm alchemy destroy   (it empties the bucket first; always destroy after the fetch)
// The credentials come only from the AWS SDK default chain (the environment, a profile, a role).
import { readFileSync } from "node:fs";
import {
  DeleteObjectsCommand,
  ListObjectsV2Command,
  S3Client,
} from "@aws-sdk/client-s3";
import { GetParameterCommand, SSMClient } from "@aws-sdk/client-ssm";
import { GetCallerIdentityCommand, STSClient } from "@aws-sdk/client-sts";
import alchemy from "alchemy";
import AWS from "alchemy/aws/control";

const region = "us-east-1";
process.env.AWS_REGION = region;
const repo = process.env.BENCH_REPO ?? "https://github.com/yuri4n/tui4s.git";
const commit = process.env.BENCH_COMMIT ?? "";
const instanceType = process.env.BENCH_INSTANCE_TYPE ?? "c7i.xlarge";
const machine = process.env.BENCH_MACHINE ?? `aws-${instanceType}`;

const app = await alchemy("tui4s-bench");
const tags = [
  { Key: "project", Value: "tui4s-bench" },
  { Key: "stage", Value: app.stage },
];

// The bucket name is global: the account and the stage make it unique and let destroy find it again.
const account = (
  await new STSClient({ region }).send(new GetCallerIdentityCommand({}))
).Account;
const bucketName = `tui4s-bench-${account}-${app.stage}`.toLowerCase();

if (app.phase === "destroy") {
  // CloudFormation cannot delete a bucket that has objects. Then finalize removes every resource in the state
  // of this stage, so the destroy does not declare them again.
  await emptyBucket(bucketName);
  await app.finalize();
  process.exit(0);
} else if (
  !/^[0-9a-f]{7,40}$/.test(commit) ||
  !/^[A-Za-z0-9._-]+$/.test(machine)
) {
  throw new Error(
    "set BENCH_COMMIT to the commit to measure (a hex sha); BENCH_MACHINE may only have [A-Za-z0-9._-]",
  );
}

const bucket = await AWS.S3.Bucket("results", {
  BucketName: bucketName,
  PublicAccessBlockConfiguration: {
    BlockPublicAcls: true,
    BlockPublicPolicy: true,
    IgnorePublicAcls: true,
    RestrictPublicBuckets: true,
  },
  OwnershipControls: { Rules: [{ ObjectOwnership: "BucketOwnerEnforced" }] },
  BucketEncryption: {
    ServerSideEncryptionConfiguration: [
      { ServerSideEncryptionByDefault: { SSEAlgorithm: "AES256" } },
    ],
  },
  LifecycleConfiguration: {
    Rules: [
      { Id: "expire-after-30-days", Status: "Enabled", ExpirationInDays: 30 },
      {
        Id: "abort-uploads",
        Status: "Enabled",
        AbortIncompleteMultipartUpload: { DaysAfterInitiation: 1 },
      },
    ],
  },
  Tags: tags,
});

const role = await AWS.IAM.Role("runner-role", {
  RoleName: `tui4s-bench-runner-${app.stage}`,
  Description:
    "The tui4s bench instance: it may only put the results into its bucket.",
  AssumeRolePolicyDocument: {
    Version: "2012-10-17",
    Statement: [
      {
        Effect: "Allow",
        Principal: { Service: "ec2.amazonaws.com" },
        Action: "sts:AssumeRole",
      },
    ],
  },
  Policies: [
    {
      PolicyName: "put-results-only",
      PolicyDocument: {
        Version: "2012-10-17",
        Statement: [
          {
            Effect: "Allow",
            Action: "s3:PutObject",
            Resource: `arn:aws:s3:::${bucket.BucketName}/*`,
          },
        ],
      },
    },
  ],
  MaxSessionDuration: 3600,
  Tags: tags,
});

const profile = await AWS.IAM.InstanceProfile("runner-profile", {
  InstanceProfileName: `tui4s-bench-runner-${app.stage}`,
  Roles: [role.RoleName ?? `tui4s-bench-runner-${app.stage}`],
});

// No ingress rule at all: nothing can connect to the instance. The default egress rule stays (the toolchains
// and the upload need it).
const group = await AWS.EC2.SecurityGroup("runner-sg", {
  GroupDescription: "tui4s bench runner: no inbound rule",
  Tags: tags,
});

// The latest Ubuntu 24.04 LTS image of Canonical, from its public SSM parameter.
const image = (
  await new SSMClient({ region }).send(
    new GetParameterCommand({
      Name: "/aws/service/canonical/ubuntu/server/24.04/stable/current/amd64/hvm/ebs-gp3/ami-id",
    }),
  )
).Parameter?.Value;
if (!image) throw new Error("no Ubuntu 24.04 image id in the SSM parameter");

const userData = readFileSync(
  new URL("./user-data.sh", import.meta.url),
  "utf8",
)
  .replaceAll("__REPO__", repo)
  .replaceAll("__COMMIT__", commit)
  .replaceAll("__BUCKET__", bucketName)
  .replaceAll("__MACHINE__", machine)
  .replaceAll("__REGION__", region);

const instance = await AWS.EC2.Instance("runner", {
  ImageId: image,
  InstanceType: instanceType,
  IamInstanceProfile:
    profile.InstanceProfileName ?? `tui4s-bench-runner-${app.stage}`,
  SecurityGroupIds: [group.GroupId],
  InstanceInitiatedShutdownBehavior: "terminate",
  MetadataOptions: {
    HttpTokens: "required",
    HttpEndpoint: "enabled",
    HttpPutResponseHopLimit: 1,
  },
  BlockDeviceMappings: [
    {
      DeviceName: "/dev/sda1",
      Ebs: {
        VolumeSize: 40,
        VolumeType: "gp3",
        DeleteOnTermination: true,
        Encrypted: true,
      },
    },
  ],
  UserData: Buffer.from(userData).toString("base64"),
  Tags: [...tags, { Key: "Name", Value: `tui4s-bench-${machine}` }],
});

console.log(
  `bench: instance ${instance.InstanceId} (${instanceType}) runs commit ${commit}`,
);
console.log(
  `bench: results go to s3://${bucketName}/${machine}/${commit}/; fetch them with pnpm fetch`,
);
await app.finalize();

async function emptyBucket(name: string) {
  const s3 = new S3Client({ region });
  try {
    for (;;) {
      const page = await s3.send(new ListObjectsV2Command({ Bucket: name }));
      const keys = (page.Contents ?? []).map((o) => ({ Key: o.Key! }));
      if (keys.length === 0) return;
      await s3.send(
        new DeleteObjectsCommand({ Bucket: name, Delete: { Objects: keys } }),
      );
    }
  } catch (e) {
    if ((e as Error).name !== "NoSuchBucket") throw e;
  }
}
