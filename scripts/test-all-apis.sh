#!/usr/bin/env bash
set -u

APP_BASE_URL="${APP_BASE_URL:-http://127.0.0.1:8765}"
REPORT_USER_ID="${REPORT_USER_ID:-20}"
REPORT_USERNAME="${REPORT_USERNAME:-hhh}"
REPORT_PASSWORD="${REPORT_PASSWORD:-123456}"
INCLUDE_LOAD=false
[[ "${1:-}" == "--include-load" ]] && INCLUDE_LOAD=true

work_dir="$(mktemp -d)"
trap 'rm -rf -- "$work_dir"' EXIT
cookie_jar="$work_dir/cookies.txt"
failed=0
checked=0

result() {
  local name="$1" status="$2" detail="$3"
  checked=$((checked + 1))
  if [[ "$status" =~ ^2[0-9][0-9]$ ]]; then
    printf 'PASS  %-28s HTTP %s  %s\n' "$name" "$status" "$detail"
  else
    printf 'FAIL  %-28s HTTP %s  %s\n' "$name" "$status" "$detail"
    failed=$((failed + 1))
  fi
}

app_api() {
  local name="$1" method="$2" path="$3" body="${4:-}" output="$work_dir/app.json" status
  if [[ -n "$body" ]]; then
    status="$(printf '%s' "$body" | curl -sS -o "$output" -w '%{http_code}' \
      --max-time 90 -b "$cookie_jar" -c "$cookie_jar" \
      -H 'Content-Type: application/json; charset=utf-8' \
      -X "$method" --data-binary @- "${APP_BASE_URL%/}$path" || printf '000')"
  else
    status="$(curl -sS -o "$output" -w '%{http_code}' --max-time 90 \
      -b "$cookie_jar" -c "$cookie_jar" -X "$method" \
      "${APP_BASE_URL%/}$path" || printf '000')"
  fi
  result "$name" "$status" "application API"
}

upstream_csv() {
  local name="$1" url="$2" date_header="$3" output="$work_dir/upstream.csv" status first_header
  if [[ -z "${REPORT_X_TOKEN:-}" ]]; then
    result "$name" "000" 'REPORT_X_TOKEN is not set'
    return
  fi

  # 敏感请求头通过 curl stdin 配置传入，不出现在进程命令行和脚本文件中。
  status="$({
    printf 'header = "Accept: application/json, text/plain, */*"\n'
    printf 'header = "Referer: https://report.rockorca.com/"\n'
    printf 'header = "X-Token: %s"\n' "$REPORT_X_TOKEN"
    printf 'header = "X-User-Id: %s"\n' "$REPORT_USER_ID"
    printf 'header = "Cookie: x-token=%s"\n' "$REPORT_X_TOKEN"
  } | curl -sS -K - -o "$output" -w '%{http_code}' --max-time 90 \
    --max-redirs 0 "$url" || printf '000')"

  if [[ "$status" =~ ^2[0-9][0-9]$ ]]; then
    first_header="$(head -n 1 "$output" | sed $'s/^\xEF\xBB\xBF//' | tr -d '\r')"
    if ! printf '%s' "$first_header" | tr ',' '\n' | sed 's/^"//;s/"$//' \
        | grep -Fxq "$date_header"; then
      result "$name" "422" "CSV missing $date_header"
      return
    fi
    result "$name" "$status" "$(wc -l < "$output" | tr -d ' ') physical lines"
  else
    result "$name" "$status" "upstream export"
  fi
}

upstream_csv "RockOrca DHH export" \
  "https://report.rockorca.com/api/dcMarketingDhhDaily/getDcMarketingDhhDailyExport" "日期"
upstream_csv "RockOrca JD export" \
  "https://report.rockorca.com/api/marketingJdCpaDaily/getMarketingJdCpaDailyExport?dimType=detail" "业务日期"

app_api "POST /api/login" POST "/api/login" \
  "{\"username\":\"$REPORT_USERNAME\",\"password\":\"$REPORT_PASSWORD\"}"
app_api "GET /api/current" GET "/api/current"
app_api "POST /api/analyze" POST "/api/analyze" '{"start":"","end":""}'
app_api "GET /api/jd/current" GET "/api/jd/current"
app_api "POST /api/jd/analyze" POST "/api/jd/analyze" \
  '{"start":"","end":"","excludeUnknownOptimizer":true}'
app_api "POST /api/pet/chat" POST "/api/pet/chat" \
  '{"message":"总结当前报表","context":{"reportType":"大航海日报","range":["",""]}}'

if $INCLUDE_LOAD; then
  if [[ -z "${REPORT_X_TOKEN:-}" ]]; then
    result "POST /api/load" "000" 'REPORT_X_TOKEN is not set'
    result "POST /api/jd/load" "000" 'REPORT_X_TOKEN is not set'
  else
    app_api "POST /api/load" POST "/api/load" \
      "{\"token\":\"$REPORT_X_TOKEN\",\"userId\":\"$REPORT_USER_ID\"}"
    app_api "POST /api/jd/load" POST "/api/jd/load" \
      "{\"token\":\"$REPORT_X_TOKEN\",\"userId\":\"$REPORT_USER_ID\",\"excludeUnknownOptimizer\":true}"
  fi
fi

app_api "POST /api/logout" POST "/api/logout" '{}'

if (( failed > 0 )); then
  printf '%s/%s API checks failed.\n' "$failed" "$checked" >&2
  exit 1
fi
printf 'All %s API checks passed.\n' "$checked"
