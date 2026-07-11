#!/bin/bash
# Keeloke TV Server - Cloud Recording Uploader launcher.
#
# Watches <ANT_MEDIA_HOME>/webapps/*/streams for finished .mp4 recordings and
# uploads them to S3-compatible storage. Requires the AWS CLI to be installed
# and configured (aws configure, or env vars, or an S3-compatible profile).
#
# Usage:
#   ./run.sh /path/to/ant-media-server/webapps my-recordings-bucket
#   ./run.sh /path/to/webapps my-bucket --endpoint-url=https://s3.us-west-000.backblazeb2.com --delete-after-upload

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if ! command -v aws >/dev/null 2>&1; then
    echo "ERROR: the AWS CLI ('aws') is required. Install it first: https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html" >&2
    exit 1
fi

if [ ! -f "$SCRIPT_DIR/CloudRecordingUploader.class" ]; then
    javac -d "$SCRIPT_DIR" "$SCRIPT_DIR/CloudRecordingUploader.java"
fi

exec java -cp "$SCRIPT_DIR" CloudRecordingUploader "$@"
