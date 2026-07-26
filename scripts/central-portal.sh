#!/usr/bin/bash -p

set -euo pipefail

unset BASH_ENV ENV CDPATH PYTHONHOME PYTHONINSPECT PYTHONPATH PYTHONSTARTUP

usage() {
    cat >&2 <<'EOF'
usage:
  scripts/central-portal.sh upload <bundle.zip> <deployment-name> USER_MANAGED
  scripts/central-portal.sh status <deployment-id>
  scripts/central-portal.sh publish <deployment-id> <same-deployment-id> <version>
  scripts/central-portal.sh drop <deployment-id> <same-deployment-id>

Set CENTRAL_TOKEN_USERNAME and CENTRAL_TOKEN_PASSWORD in the process environment.
EOF
    exit 2
}

if (( $# < 2 )); then
    usage
fi

if [[ -z "${CENTRAL_TOKEN_USERNAME:-}" || -z "${CENTRAL_TOKEN_PASSWORD:-}" ]]; then
    echo "Central Portal token credentials are required" >&2
    exit 1
fi
if [[ "$CENTRAL_TOKEN_USERNAME" == *$'\n'* || "$CENTRAL_TOKEN_USERNAME" == *$'\r'* ||
    "$CENTRAL_TOKEN_PASSWORD" == *$'\n'* || "$CENTRAL_TOKEN_PASSWORD" == *$'\r'* ]]; then
    echo "Central Portal token credentials must not contain line breaks" >&2
    exit 1
fi

authorization="$(
    printf '%s:%s' "$CENTRAL_TOKEN_USERNAME" "$CENTRAL_TOKEN_PASSWORD" |
        env -u CENTRAL_TOKEN_USERNAME -u CENTRAL_TOKEN_PASSWORD base64 -w0
)"
unset CENTRAL_TOKEN_USERNAME CENTRAL_TOKEN_PASSWORD
curl_config() {
    printf 'header = "Authorization: Bearer %s"\n' "$authorization"
}

portal_request() {
    curl --disable \
        --config <(curl_config) \
        --proto '=https' \
        --tlsv1.2 \
        --noproxy '*' \
        --connect-timeout 10 \
        --max-time 120 \
        --fail-with-body \
        --silent \
        --show-error \
        "$@"
}

command="$1"
deployment_id_pattern='^[[:xdigit:]]{8}-[[:xdigit:]]{4}-[[:xdigit:]]{4}-[[:xdigit:]]{4}-[[:xdigit:]]{12}$'
case "$command" in
    upload)
        if (( $# != 4 )); then
            usage
        fi
        bundle="$2"
        deployment_name="$3"
        publishing_type="$4"
        if [[ ! -f "$bundle" || -L "$bundle" || "$bundle" != *.zip ]]; then
            echo "upload requires a regular zip bundle" >&2
            exit 1
        fi
        if [[ ! "$deployment_name" =~ ^[A-Za-z0-9._-]{3,100}$ ]]; then
            echo "deployment name contains unsupported characters" >&2
            exit 1
        fi
        bundle="$(realpath -e -- "$bundle")"
        if [[ ! "$bundle" =~ ^[A-Za-z0-9_./-]+$ ]] ||
            [[ "$bundle" == *$'\n'* || "$bundle" == *$'\r'* || "$bundle" == *';'* || "$bundle" == *','* ]]; then
            echo "bundle path contains unsupported characters" >&2
            exit 1
        fi
        if [[ "$publishing_type" != USER_MANAGED ]]; then
            echo "publication tooling permits USER_MANAGED Central deployments only" >&2
            exit 1
        fi
        response="$(portal_request \
            --request POST \
            --form "bundle=@${bundle};type=application/octet-stream" \
            "https://central.sonatype.com/api/v1/publisher/upload?name=${deployment_name}&publishingType=${publishing_type}")"
        if [[ ! "$response" =~ $deployment_id_pattern ]]; then
            echo "Central Portal returned an invalid deployment ID" >&2
            exit 1
        fi
        printf '%s\n' "$response"
        ;;
    status)
        if (( $# != 2 )) || [[ ! "$2" =~ $deployment_id_pattern ]]; then
            usage
        fi
        portal_request \
            --request POST \
            "https://central.sonatype.com/api/v1/publisher/status?id=$2"
        printf '\n'
        ;;
    publish)
        if (( $# != 4 )) || [[ ! "$2" =~ $deployment_id_pattern ]] || [[ "$2" != "$3" ]] ||
            [[ ! "$4" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z][0-9A-Za-z.-]*)?$ ]]; then
            echo "publish requires the deployment ID twice and the exact release version" >&2
            exit 2
        fi
        status_response="$(portal_request \
            --request POST \
            "https://central.sonatype.com/api/v1/publisher/status?id=$2")"
        expected_purl="pkg:maven/io.github.shuaibrao/cqengine@$4"
        python3 -c 'import json,sys; d=json.load(sys.stdin); d.get("deploymentId") == sys.argv[1] or sys.exit("different deployment"); d.get("deploymentState") == "VALIDATED" or sys.exit("deployment is not validated"); d.get("purls") == [sys.argv[2]] or sys.exit("different coordinate")' \
            "$2" "$expected_purl" <<<"$status_response"
        portal_request \
            --request POST \
            --output /dev/null \
            "https://central.sonatype.com/api/v1/publisher/deployment/$2"
        printf 'publish requested for deployment %s\n' "$2"
        ;;
    drop)
        if (( $# != 3 )) || [[ ! "$2" =~ $deployment_id_pattern ]] || [[ "$2" != "$3" ]]; then
            echo "$command requires the deployment ID twice as confirmation" >&2
            exit 2
        fi
        portal_request \
            --request DELETE \
            --output /dev/null \
            "https://central.sonatype.com/api/v1/publisher/deployment/$2"
        printf 'drop requested for deployment %s\n' "$2"
        ;;
    *)
        usage
        ;;
esac
