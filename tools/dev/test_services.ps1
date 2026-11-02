# 真实 Windows 子进程/HTTP 验收，不接触项目服务或数据库。
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/services.ps1"
$testRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('services-test-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot -Force | Out-Null
$fixture = Join-Path $testRoot 'server.cjs'
"require('http').createServer((q,s)=>s.end('ready')).listen(0,'127.0.0.1');" | Set-Content $fixture
$recordPath = Join-Path $testRoot 'fixture.clixml'
$record = $null
# 使用调用终端的真实PATH；多命中时保留第一个Application，不能传数组给Start-Process。
$originalPath = $env:PATH
$nodeMatches = @(Get-Command node -CommandType Application)
$nodeExecutable = (Get-Command node -CommandType Application | Select-Object -First 1).Source
$javaMatches = @(Get-Command java -CommandType Application)
$javaExecutable = (Get-Command java -CommandType Application | Select-Object -First 1).Source
if ($nodeExecutable -isnot [string] -or $nodeExecutable -ne $nodeMatches[0].Source -or
    $javaExecutable -isnot [string] -or $javaExecutable -ne $javaMatches[0].Source) {
    throw 'Executable selection did not preserve the first PATH Application as a scalar'
}
try {
    $record = Start-OwnedService $nodeExecutable @("`"$fixture`"") $testRoot $testRoot $recordPath (Join-Path $testRoot 'run') 0 'http://127.0.0.1:0/' 15
    if (-not (Test-ServiceReady $record.Url)) { throw 'HTTP readiness failed' }
    if (-not (Get-ServiceProcess $record $testRoot)) { throw 'Ownership failed' }
    if (Get-ServiceProcess $record "$testRoot-other") { throw 'Cross-workspace accepted' }
    $originalTicks = $record.CreatedTicks
    $record.CreatedTicks = 0
    $record | Export-Clixml $recordPath
    $refused = $false
    try { Stop-OwnedService $recordPath $testRoot } catch { $refused = $true }
    if (-not $refused -or -not (Test-ServiceReady $record.Url)) { throw 'Stale PID guard failed' }
    $record.CreatedTicks = $originalTicks
    $record | Export-Clixml $recordPath
    $conflictRefused = $false
    try {
        Start-OwnedService $nodeExecutable @("`"$fixture`"") $testRoot $testRoot (Join-Path $testRoot 'conflict.clixml') (Join-Path $testRoot 'conflict') $record.Port $record.Url 5
    } catch { $conflictRefused = $true }
    if (-not $conflictRefused -or -not (Test-ServiceReady $record.Url)) { throw 'Occupied port was not protected' }
    Stop-OwnedService $recordPath $testRoot
    if (Get-ServiceProcess $record $testRoot) { throw 'Process still alive after stop' }
    if (Test-Path $recordPath) { throw 'Stopped record retained' }
    if ((Get-ServiceListeners $record.Port).Count) { throw 'Port not released' }
    "require('http').createServer((q,s)=>{s.statusCode=503;s.end('waiting')}).listen(0,'127.0.0.1');" | Set-Content $fixture
    $timedOut = $false
    try {
        Start-OwnedService $nodeExecutable @("`"$fixture`"") $testRoot $testRoot $recordPath (Join-Path $testRoot 'timeout') 0 'http://127.0.0.1:0/' 2
    } catch { $timedOut = $_.Exception.Message -like '*未在*就绪*' }
    if (-not $timedOut -or -not (Test-Path $recordPath)) { throw 'Timeout evidence not retained' }
    $record = Import-Clixml $recordPath
    if (-not (Get-ServiceProcess $record $testRoot)) { throw 'Timeout process not retained' }
    Stop-OwnedService $recordPath $testRoot
    # 直接 & 调用真实入口，验证文件配置不会留在调用终端，后续调用读取更新值。
    $envPath = Join-Path $testRoot 'test.env'
    $priorPort = [Environment]::GetEnvironmentVariable('INSTITUTION_SERVICE_PORT', 'Process')
    $probeKey = 'SKILLFORGE_SERVICES_TEST_' + [Guid]::NewGuid().ToString('N')
    try {
        Remove-Item -LiteralPath Env:INSTITUTION_SERVICE_PORT -ErrorAction SilentlyContinue
        "INSTITUTION_SERVICE_PORT=1`n$probeKey=first" | Set-Content $envPath
        $firstStatus = & "$PSScriptRoot/services.ps1" status -Component institution -EnvFile $envPath
        if ($firstStatus -notmatch 'port=1 ') { throw "First environment configuration not read: $firstStatus" }
        if ($null -ne [Environment]::GetEnvironmentVariable($probeKey, 'Process') -or
            $null -ne [Environment]::GetEnvironmentVariable('INSTITUTION_SERVICE_PORT', 'Process')) {
            throw 'File environment leaked into calling terminal'
        }
        "INSTITUTION_SERVICE_PORT=2`n$probeKey=second" | Set-Content $envPath
        $secondStatus = & "$PSScriptRoot/services.ps1" status -Component institution -EnvFile $envPath
        if ($secondStatus -notmatch 'port=2 ') { throw 'Updated file environment not refreshed' }
        [Environment]::SetEnvironmentVariable('INSTITUTION_SERVICE_PORT', '3', 'Process')
        $overrideStatus = & "$PSScriptRoot/services.ps1" status -Component institution -EnvFile $envPath
        if ($overrideStatus -notmatch 'port=3 ' -or $env:INSTITUTION_SERVICE_PORT -ne '3') {
            throw 'Explicit caller environment not preserved'
        }
        Remove-Item -LiteralPath Env:INSTITUTION_SERVICE_PORT -ErrorAction SilentlyContinue
        "INSTITUTION_SERVICE_PORT=invalid`n$probeKey=failed" | Set-Content $envPath
        $failed = $false
        try { & "$PSScriptRoot/services.ps1" status -Component institution -EnvFile $envPath } catch { $failed = $true }
        if (-not $failed -or $null -ne [Environment]::GetEnvironmentVariable($probeKey, 'Process')) {
            throw 'Failed invocation did not restore caller environment'
        }
    } finally {
        if ($null -eq $priorPort) {
            Remove-Item -LiteralPath Env:INSTITUTION_SERVICE_PORT -ErrorAction SilentlyContinue
        } else {
            [Environment]::SetEnvironmentVariable('INSTITUTION_SERVICE_PORT', $priorPort, 'Process')
        }
        Remove-Item -LiteralPath ('Env:' + $probeKey) -ErrorAction SilentlyContinue
    }
    if ($env:PATH -ne $originalPath) { throw 'Executable selection changed caller PATH' }
    Write-Output "PASS first PATH executable selection (nodeMatches=$($nodeMatches.Count), javaMatches=$($javaMatches.Count)), real lifecycle, readiness, workspace ownership, stale identity refusal, occupied port refusal, exit and port release, readiness timeout retention and explicit cleanup, caller environment restoration/refresh/override/error cleanup; evidence=$testRoot"
} finally {
    if ($record -and (Get-ServiceProcess $record $testRoot)) {
        $record | Export-Clixml $recordPath
        Stop-OwnedService $recordPath $testRoot
    }
}
