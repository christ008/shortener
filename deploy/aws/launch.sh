#!/bin/sh
# Creates the VM of docs/CLOUD.md on AWS: a Graviton (arm64) instance with Ubuntu 24.04, a security group, a key pair and an Elastic IP.
# Run it from anywhere with the AWS CLI signed in (`aws login`); it holds no credentials. Nothing else is created, and what it
# creates is tagged `Name=$NAME`, so `deploy/aws/launch.sh teardown` finds it again.
#
#   AWS_PROFILE=default AWS_REGION=us-east-2 deploy/aws/launch.sh
#   AWS_PROFILE=default AWS_REGION=us-east-2 deploy/aws/launch.sh teardown
#
# It prints the address to put in DNS. Stop there: certbot needs DNS to answer before docs/DEPLOY.md#certificate.
#
# Settings, all optional:
#   NAME            name of the instance, key pair and security group, default shortener-aws
#   INSTANCE_TYPE   default t4g.small (2 vCPU, 2 GB), the largest arm64 type the free plan allows; t4g.medium (4 GB) needs the paid plan. Keep it arm64: the published image is
#   DISK_GB         root volume, default 30
#   SSH_KEY         public key file, default ~/.ssh/aws_shortener.pub
#   ADMIN_CIDR      who may reach port 22, default your current address (a /32, from checkip.amazonaws.com)
#
# Port 22 is open to ADMIN_CIDR only, and 80 and 443 to everyone. The instance requires IMDSv2.
set -eu

name=${NAME:-shortener-aws}
type=${INSTANCE_TYPE:-t4g.small}
disk=${DISK_GB:-30}
key=${SSH_KEY:-$HOME/.ssh/aws_shortener.pub}

command -v aws >/dev/null || { echo "aws CLI not found" >&2; exit 1; }
account=$(aws sts get-caller-identity --query Account --output text) || { echo "not signed in: aws login" >&2; exit 1; }
region=${AWS_REGION:-${AWS_DEFAULT_REGION:-$(aws configure get region || true)}}
[ -n "$region" ] || { echo "set AWS_REGION" >&2; exit 1; }
export AWS_REGION=$region
echo "account $account, region $region"

ec2() { aws ec2 "$@"; }
find_instance() {
  ec2 describe-instances --filters "Name=tag:Name,Values=$name" 'Name=instance-state-name,Values=pending,running,stopping,stopped' \
    --query 'Reservations[].Instances[].InstanceId' --output text
}
find_address() { ec2 describe-addresses --filters "Name=tag:Name,Values=$name" --query 'Addresses[0].AllocationId' --output text; }
find_group() {
  ec2 describe-security-groups --filters "Name=group-name,Values=$name" --query 'SecurityGroups[0].GroupId' --output text
}

if [ "${1:-}" = teardown ]; then
  instance=$(find_instance)
  if [ -n "$instance" ]; then
    echo "terminating $instance"
    # shellcheck disable=SC2086
    ec2 terminate-instances --instance-ids $instance >/dev/null
    # shellcheck disable=SC2086
    ec2 wait instance-terminated --instance-ids $instance
  fi
  allocation=$(find_address)
  [ "$allocation" = None ] || { echo "releasing $allocation"; ec2 release-address --allocation-id "$allocation"; }
  group=$(find_group)
  [ "$group" = None ] || { echo "deleting security group $group"; ec2 delete-security-group --group-id "$group"; }
  ec2 delete-key-pair --key-name "$name" >/dev/null
  echo "done; delete the DNS record too"
  exit 0
fi

[ -z "$(find_instance)" ] || { echo "an instance named $name exists; teardown first" >&2; exit 1; }
[ -r "$key" ] || { echo "cannot read $key (ssh-keygen -t ed25519 -f ${key%.pub})" >&2; exit 1; }
cidr=${ADMIN_CIDR:-$(curl -fsS https://checkip.amazonaws.com | tr -d '[:space:]')/32}

vpc=$(ec2 describe-vpcs --filters Name=isDefault,Values=true --query 'Vpcs[0].VpcId' --output text)
[ "$vpc" != None ] || { echo "no default VPC in $region" >&2; exit 1; }

group=$(find_group)
if [ "$group" = None ]; then
  group=$(ec2 create-security-group --group-name "$name" --description "$name: ssh from the administrator, http and https" \
    --vpc-id "$vpc" --tag-specifications "ResourceType=security-group,Tags=[{Key=Name,Value=$name}]" \
    --query GroupId --output text)
  ec2 authorize-security-group-ingress --group-id "$group" --ip-permissions \
    "IpProtocol=tcp,FromPort=22,ToPort=22,IpRanges=[{CidrIp=$cidr,Description=ssh}]" \
    'IpProtocol=tcp,FromPort=80,ToPort=80,IpRanges=[{CidrIp=0.0.0.0/0,Description=http}]' \
    'IpProtocol=tcp,FromPort=443,ToPort=443,IpRanges=[{CidrIp=0.0.0.0/0,Description=https}]' >/dev/null
fi
echo "security group $group (ssh from $cidr)"

ec2 describe-key-pairs --key-names "$name" >/dev/null 2>&1 \
  || ec2 import-key-pair --key-name "$name" --public-key-material "fileb://$key" >/dev/null

ami=$(aws ssm get-parameter --name /aws/service/canonical/ubuntu/server/24.04/stable/current/arm64/hvm/ebs-gp3/ami-id \
  --query Parameter.Value --output text)
echo "image $ami, $type, ${disk} GB"

instance=$(ec2 run-instances --image-id "$ami" --instance-type "$type" --key-name "$name" --security-group-ids "$group" \
  --block-device-mappings "DeviceName=/dev/sda1,Ebs={VolumeSize=$disk,VolumeType=gp3,DeleteOnTermination=true,Encrypted=true}" \
  --metadata-options HttpTokens=required,HttpEndpoint=enabled \
  --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=$name}]" "ResourceType=volume,Tags=[{Key=Name,Value=$name}]" \
  --query 'Instances[0].InstanceId' --output text)
echo "instance $instance"
ec2 wait instance-running --instance-ids "$instance"

allocation=$(find_address)
if [ "$allocation" = None ]; then
  allocation=$(ec2 allocate-address --domain vpc --tag-specifications "ResourceType=elastic-ip,Tags=[{Key=Name,Value=$name}]" \
    --query AllocationId --output text)
fi
ec2 associate-address --instance-id "$instance" --allocation-id "$allocation" >/dev/null
address=$(ec2 describe-addresses --allocation-ids "$allocation" --query 'Addresses[0].PublicIp' --output text)

echo
echo "ready: ssh -i ${key%.pub} ubuntu@$address"
echo "DNS:   an A record for your host name -> $address"
echo "next:  docs/DEPLOY.md#certificate, after deploy/aws/bootstrap.sh on the VM"
