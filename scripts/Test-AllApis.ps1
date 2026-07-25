param(
  [string]$AppBaseUrl = "http://127.0.0.1:8765",
  [switch]$IncludeLoadEndpoints
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

# Token 只从当前进程环境读取，避免出现在脚本、Git 或 PowerShell 历史参数中。
$reportToken = [Environment]::GetEnvironmentVariable("REPORT_X_TOKEN")
$reportUserId = [Environment]::GetEnvironmentVariable("REPORT_USER_ID")
$reportUsername = [Environment]::GetEnvironmentVariable("REPORT_USERNAME")
$reportPassword = [Environment]::GetEnvironmentVariable("REPORT_PASSWORD")
if ([string]::IsNullOrWhiteSpace($reportUserId)) { $reportUserId = "20" }
if ([string]::IsNullOrWhiteSpace($reportUsername)) { $reportUsername = "hhh" }
if ([string]::IsNullOrWhiteSpace($reportPassword)) { $reportPassword = "123456" }

$results = [System.Collections.Generic.List[object]]::new()

function Add-Result {
  param([string]$Name, [bool]$Ok, [int]$Status, [long]$ElapsedMs, [string]$Detail)
  $results.Add([pscustomobject]@{
    Api = $Name
    Ok = $Ok
    Status = $Status
    ElapsedMs = $ElapsedMs
    Detail = $Detail
  })
}

function Invoke-AppApi {
  param(
    [string]$Name,
    [string]$Method,
    [string]$Path,
    [object]$Body,
    [Microsoft.PowerShell.Commands.WebRequestSession]$Session
  )
  $watch = [System.Diagnostics.Stopwatch]::StartNew()
  try {
    $parameters = @{
      Uri = "$($AppBaseUrl.TrimEnd('/'))$Path"
      Method = $Method
      WebSession = $Session
      UseBasicParsing = $true
      TimeoutSec = 90
    }
    if ($null -ne $Body) {
      $parameters.ContentType = "application/json; charset=utf-8"
      $parameters.Body = $Body | ConvertTo-Json -Depth 8 -Compress
    }
    $response = Invoke-WebRequest @parameters
    $watch.Stop()
    $payload = $null
    try { $payload = $response.Content | ConvertFrom-Json } catch {}
    $detail = if ($null -ne $payload -and $null -ne $payload.rows) {
      "rows=$($payload.rows)"
    } else {
      "JSON response"
    }
    Add-Result $Name $true ([int]$response.StatusCode) $watch.ElapsedMilliseconds $detail
  } catch {
    $watch.Stop()
    $status = 0
    if ($null -ne $_.Exception.Response) { $status = [int]$_.Exception.Response.StatusCode }
    Add-Result $Name $false $status $watch.ElapsedMilliseconds $_.Exception.Message
  }
}

function Test-UpstreamCsv {
  param([string]$Name, [string]$Url, [string[]]$RequiredHeaders)
  if ([string]::IsNullOrWhiteSpace($reportToken)) {
    Add-Result $Name $false 0 0 "未设置 REPORT_X_TOKEN"
    return
  }
  $watch = [System.Diagnostics.Stopwatch]::StartNew()
  try {
    $headers = @{
      "Accept" = "application/json, text/plain, */*"
      "Referer" = "https://report.rockorca.com/"
      "X-Token" = $reportToken
      "X-User-Id" = $reportUserId
      "Cookie" = "x-token=$reportToken"
    }
    $response = Invoke-WebRequest -Uri $Url -Method Get -Headers $headers `
      -UseBasicParsing -TimeoutSec 90 -MaximumRedirection 0
    $watch.Stop()
    $content = [string]$response.Content
    $normalized = $content.TrimStart([char]0xFEFF)
    if ([string]::IsNullOrWhiteSpace($normalized) -or $normalized.TrimStart().StartsWith("{") `
        -or $normalized.TrimStart().StartsWith("<")) {
      throw "响应不是 CSV"
    }
    $firstLine = ($normalized -split "\r?\n", 2)[0]
    $headersFound = @($firstLine -split "," | ForEach-Object { $_.Trim().Trim('"') })
    $missing = @($RequiredHeaders | Where-Object { $_ -notin $headersFound })
    if ($missing.Count -gt 0) { throw "缺少列：$($missing -join '、')" }
    $lineCount = [Math]::Max(0, ($normalized -split "\r?\n").Count - 1)
    Add-Result $Name $true ([int]$response.StatusCode) $watch.ElapsedMilliseconds `
      "CSV columns=$($headersFound.Count), physicalRows=$lineCount"
  } catch {
    $watch.Stop()
    $status = 0
    if ($null -ne $_.Exception.Response) { $status = [int]$_.Exception.Response.StatusCode }
    Add-Result $Name $false $status $watch.ElapsedMilliseconds $_.Exception.Message
  }
}

$dhhHeaders = @(
  "日期", "媒体", "账户信息", "优化师", "任务名", "消耗", "现金消耗", "赠款消耗",
  "预估佣金", "结算数", "转化数", "注册数"
)
$jdHeaders = @(
  "业务日期", "推广位ID", "推广位名称", "媒体", "媒体账户ID", "媒体账户名称",
  "推客用户名", "优化师", "转化数", "计费转化数", "去重订单总数",
  "首购订单总数", "回流订单总数", "首购有效订单数", "回流有效订单数",
  "首购无效订单数", "回流无效订单数", "首购已完成订单", "回流已完成订单",
  "消耗", "条件内预估赔付金额(当日)", "首购预估佣金", "回流预估佣金",
  "首购实际佣金", "回流实际佣金"
)

Test-UpstreamCsv "RockOrca DHH export" `
  "https://report.rockorca.com/api/dcMarketingDhhDaily/getDcMarketingDhhDailyExport" `
  $dhhHeaders
Test-UpstreamCsv "RockOrca JD export" `
  "https://report.rockorca.com/api/marketingJdCpaDaily/getMarketingJdCpaDailyExport?dimType=detail" `
  $jdHeaders

$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
Invoke-AppApi "POST /api/login" "POST" "/api/login" `
  @{ username = $reportUsername; password = $reportPassword } $session
Invoke-AppApi "GET /api/current" "GET" "/api/current" $null $session
Invoke-AppApi "POST /api/analyze" "POST" "/api/analyze" @{ start = ""; end = "" } $session
Invoke-AppApi "GET /api/jd/current" "GET" "/api/jd/current" $null $session
Invoke-AppApi "POST /api/jd/analyze" "POST" "/api/jd/analyze" `
  @{ start = ""; end = ""; excludeUnknownOptimizer = $true } $session
Invoke-AppApi "POST /api/pet/chat" "POST" "/api/pet/chat" `
  @{ message = "总结当前报表"; context = @{ reportType = "大航海日报"; range = @("", "") } } $session

if ($IncludeLoadEndpoints) {
  if ([string]::IsNullOrWhiteSpace($reportToken)) {
    Add-Result "POST /api/load" $false 0 0 "未设置 REPORT_X_TOKEN"
    Add-Result "POST /api/jd/load" $false 0 0 "未设置 REPORT_X_TOKEN"
  } else {
    Invoke-AppApi "POST /api/load" "POST" "/api/load" `
      @{ token = $reportToken; userId = $reportUserId } $session
    Invoke-AppApi "POST /api/jd/load" "POST" "/api/jd/load" `
      @{ token = $reportToken; userId = $reportUserId; excludeUnknownOptimizer = $true } $session
  }
}

Invoke-AppApi "POST /api/logout" "POST" "/api/logout" @{} $session

$results | Format-Table -AutoSize
$failed = @($results | Where-Object { -not $_.Ok })
if ($failed.Count -gt 0) {
  Write-Error "$($failed.Count) 个接口未通过。"
  exit 1
}
Write-Host "全部 $($results.Count) 个接口检查通过。" -ForegroundColor Green
