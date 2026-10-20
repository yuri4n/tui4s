// claude 2026-10-20: created (L5): a read-only check of the AWS credentials and the network path.
// It prints only the last 4 digits of the account and the name of the caller: no key, no full ARN.
import { GetCallerIdentityCommand, STSClient } from "@aws-sdk/client-sts";

const region = process.env.AWS_REGION ?? "us-east-1";
const sts = new STSClient({ region }); // the default credential chain: the environment, a profile, a role
try {
  const me = await sts.send(new GetCallerIdentityCommand({}));
  const name =
    (me.Arn ?? "").split("/").slice(1).join("/") || "(root or unknown)";
  console.log(
    `aws: reachable in ${region}; account ****${(me.Account ?? "").slice(-4)}; caller ${name}`,
  );
} catch (e) {
  // An error with an HTTP status came from AWS: the network path works, and AWS rejected the request.
  const status = (e as { $metadata?: { httpStatusCode?: number } }).$metadata
    ?.httpStatusCode;
  const where = status
    ? `reachable (HTTP ${status}), request rejected`
    : "not reachable";
  console.log(`aws: ${where} in ${region}: ${(e as Error).name}`);
  process.exitCode = 1;
}
