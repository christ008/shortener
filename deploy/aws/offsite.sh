#!/bin/sh
# Creates what the copy of the backups off the node needs on AWS (docs/DEPLOY.md#a-copy-off-the-node): a private S3 bucket and a role for the VM that can
# write and read it and cannot delete from it. Run it from anywhere with the AWS CLI signed in (`aws login --region us-east-2`); it holds no credentials. It can be
# run again: it keeps what exists.
#
#   AWS_REGION=us-east-2 deploy/aws/offsite.sh
#   AWS_REGION=us-east-2 YES=1 deploy/aws/offsite.sh teardown
#
# The bucket refuses what is not TLS and public access, keeps versions, encrypts with S3's own keys, and ages objects out (90 days; an older version of a
# file, 30): the role cannot change that, because it cannot delete or change the lifecycle. Backups stop reaching the bucket if the VM stops, and the ones in it age out
# after 90 days, so a node that is off for longer than that has nothing off the node.
#
# Settings, all optional:
#   NAME     name of the VM that deploy/aws/launch.sh made, and of the role and instance profile with `-backup` after it, default shortener-aws
#   BUCKET   name of the bucket, default shortener-backups-ACCOUNT (the name is global to AWS)
#   YES=1    teardown deletes the bucket with every version in it, and the backups with them: it does nothing without this
#
# The VM gets the role when one named $NAME exists. Without one, the role is made and nothing is attached.
set -eu

name=${NAME:-shortener-aws}
role=$name-backup

command -v aws >/dev/null || { echo "aws CLI not found" >&2; exit 1; }
account=$(aws sts get-caller-identity --query Account --output text) || { echo "not signed in: aws login" >&2; exit 1; }
region=${AWS_REGION:-${AWS_DEFAULT_REGION:-$(aws configure get region || true)}}
[ -n "$region" ] || { echo "set AWS_REGION" >&2; exit 1; }
export AWS_REGION=$region AWS_PAGER=
bucket=${BUCKET:-shortener-backups-$account}
echo "account $account, region $region, bucket $bucket, role $role"

find_instance() {
  aws ec2 describe-instances --filters "Name=tag:Name,Values=$name" 'Name=instance-state-name,Values=pending,running,stopping,stopped' \
    --query 'Reservations[].Instances[].InstanceId' --output text
}

if [ "${1:-}" = teardown ]; then
  [ -n "${YES:-}" ] || { echo "this deletes the bucket $bucket with every backup in it: run it with YES=1" >&2; exit 1; }
  instance=$(find_instance)
  if [ -n "$instance" ]; then
    association=$(aws ec2 describe-iam-instance-profile-associations --filters "Name=instance-id,Values=$instance" \
      --query 'IamInstanceProfileAssociations[0].AssociationId' --output text)
    [ "$association" = None ] || aws ec2 disassociate-iam-instance-profile --association-id "$association" >/dev/null
  fi
  if aws s3api head-bucket --bucket "$bucket" >/dev/null 2>&1; then
    # delete-objects takes a thousand at a time, versions and delete markers alike.
    for kind in Versions DeleteMarkers; do
      while [ "$(aws s3api list-object-versions --bucket "$bucket" --max-items 1 --query "length($kind || \`[]\`)" --output text)" != 0 ]; do
        aws s3api delete-objects --bucket "$bucket" --output text --query 'length(Deleted || `[]`)' --delete \
          "$(aws s3api list-object-versions --bucket "$bucket" --max-items 1000 --query "{Objects: $kind[].{Key: Key, VersionId: VersionId}, Quiet: \`true\`}" --output json)" >/dev/null
      done
    done
    aws s3api delete-bucket --bucket "$bucket"
    echo "deleted bucket $bucket"
  fi
  if aws iam get-instance-profile --instance-profile-name "$role" >/dev/null 2>&1; then
    aws iam remove-role-from-instance-profile --instance-profile-name "$role" --role-name "$role" 2>/dev/null || true
    aws iam delete-instance-profile --instance-profile-name "$role"
  fi
  if aws iam get-role --role-name "$role" >/dev/null 2>&1; then
    aws iam delete-role-policy --role-name "$role" --policy-name backup-bucket 2>/dev/null || true
    aws iam delete-role --role-name "$role"
  fi
  echo "done"
  exit 0
fi

if ! aws s3api head-bucket --bucket "$bucket" >/dev/null 2>&1; then
  aws s3api create-bucket --bucket "$bucket" --create-bucket-configuration "LocationConstraint=$region" >/dev/null
  echo "created bucket $bucket"
fi
aws s3api put-public-access-block --bucket "$bucket" \
  --public-access-block-configuration BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
aws s3api put-bucket-ownership-controls --bucket "$bucket" --ownership-controls 'Rules=[{ObjectOwnership=BucketOwnerEnforced}]'
aws s3api put-bucket-versioning --bucket "$bucket" --versioning-configuration Status=Enabled
aws s3api put-bucket-encryption --bucket "$bucket" \
  --server-side-encryption-configuration '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"},"BucketKeyEnabled":true}]}'
aws s3api put-bucket-lifecycle-configuration --bucket "$bucket" --lifecycle-configuration \
  '{"Rules":[{"ID":"age-out","Status":"Enabled","Filter":{"Prefix":""},"Expiration":{"Days":90},"NoncurrentVersionExpiration":{"NoncurrentDays":30},"AbortIncompleteMultipartUpload":{"DaysAfterInitiation":7}}]}' >/dev/null
aws s3api put-bucket-tagging --bucket "$bucket" --tagging "TagSet=[{Key=Name,Value=$name}]"
aws s3api put-bucket-policy --bucket "$bucket" --policy "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Sid\":\"TlsOnly\",\"Effect\":\"Deny\",\"Principal\":\"*\",\"Action\":\"s3:*\",\"Resource\":[\"arn:aws:s3:::$bucket\",\"arn:aws:s3:::$bucket/*\"],\"Condition\":{\"Bool\":{\"aws:SecureTransport\":\"false\"}}}]}"

if ! aws iam get-role --role-name "$role" >/dev/null 2>&1; then
  aws iam create-role --role-name "$role" --description "$name: the VM writes its Postgres backups to one bucket and cannot delete them" \
    --assume-role-policy-document '{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"}]}' \
    --tags "Key=Name,Value=$name" >/dev/null
  echo "created role $role"
fi
aws iam put-role-policy --role-name "$role" --policy-name backup-bucket --policy-document "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Sid\":\"List\",\"Effect\":\"Allow\",\"Action\":[\"s3:ListBucket\",\"s3:GetBucketLocation\"],\"Resource\":\"arn:aws:s3:::$bucket\"},{\"Sid\":\"WriteAndRead\",\"Effect\":\"Allow\",\"Action\":[\"s3:PutObject\",\"s3:GetObject\",\"s3:GetObjectVersion\"],\"Resource\":\"arn:aws:s3:::$bucket/*\"},{\"Sid\":\"NeverDelete\",\"Effect\":\"Deny\",\"Action\":[\"s3:DeleteObject\",\"s3:DeleteObjectVersion\",\"s3:PutLifecycleConfiguration\",\"s3:PutBucketVersioning\"],\"Resource\":[\"arn:aws:s3:::$bucket\",\"arn:aws:s3:::$bucket/*\"]}]}"
if ! aws iam get-instance-profile --instance-profile-name "$role" >/dev/null 2>&1; then
  aws iam create-instance-profile --instance-profile-name "$role" --tags "Key=Name,Value=$name" >/dev/null
  aws iam add-role-to-instance-profile --instance-profile-name "$role" --role-name "$role"
  # A new profile takes a few seconds to be known to EC2.
  sleep 10
fi

instance=$(find_instance)
if [ -z "$instance" ]; then
  echo "no instance named $name: attach the profile $role to the VM yourself (aws ec2 associate-iam-instance-profile)"
else
  current=$(aws ec2 describe-iam-instance-profile-associations --filters "Name=instance-id,Values=$instance" 'Name=state,Values=associated,associating' \
    --query 'IamInstanceProfileAssociations[0].IamInstanceProfile.Arn' --output text)
  if [ "$current" = None ]; then
    aws ec2 associate-iam-instance-profile --instance-id "$instance" --iam-instance-profile "Name=$role" >/dev/null
    echo "attached $role to $instance"
  else
    echo "$instance has $current already"
  fi
fi

echo
echo "ready. On the VM: aws CLI (sudo snap install aws-cli --classic), then docs/DEPLOY.md#a-copy-off-the-node"
echo "  OFFSITE_BUCKET=$bucket"
