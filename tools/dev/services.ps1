# Windows 本地服务生命周期；不构建、不管理依赖、不修改数据库。
[CmdletBinding()]
param(
    [ValidateSet('status', 'start', 'stop')][string]$Action = 'status',
    [ValidateSet('all', 'institution', 'platform', 'gateway', 'admin', 'platform-web', 'chat')]
    [string[]]$Component = @('all'),
    [string]$EnvFile = '.env',
    [ValidateRange(1, 65535)][int]$Port,
    [ValidateRange(1, 600)][int]$TimeoutSeconds = 90
)
$ErrorActionPreference = 'Stop'

function Get-ServiceProcess($Record, [string]$Workspace) {
    if ($Record.Workspace -ne $Workspace) { return $null }
    $current = Get-CimInstance Win32_Process -Filter "ProcessId=$($Record.ProcessId)"
    if ($current -and $current.CreationDate.ToUniversalTime().Ticks -eq $Record.CreatedTicks -and
        $current.CommandLine -eq $Record.CommandLine -and $current.ExecutablePath -eq $Record.ExecutablePath) {
        return $current
    }
    return $null
}

function Get-ServiceListeners([int]$ListenPort) {
    @(Get-NetTCPConnection -State Listen -LocalPort $ListenPort -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty OwningProcess -Unique)
}

function Test-ServiceReady([string]$Url) {
    try {
        $response = Invoke-WebRequest -Uri $Url -TimeoutSec 2 -UseBasicParsing
        return $response.StatusCode -eq 200
    } catch { return $false }
}

function Stop-OwnedService([string]$RecordPath, [string]$Workspace) {
    if (-not (Test-Path -LiteralPath $RecordPath)) { return }
    $record = Import-Clixml -LiteralPath $RecordPath
    $owned = Get-ServiceProcess $record $Workspace
    if (-not $owned) { throw '进程归属不匹配或已退出；保留记录，不停止任何进程。' }
    # 只停止直接持有的 Java/Node 进程，不使用 taskkill /T 或按名称批量结束。
    $process = Get-Process -Id $record.ProcessId
    Stop-Process -InputObject $process
    if (-not $process.WaitForExit(10000)) { throw '等待进程退出超时；保留记录。' }
    Remove-Item -LiteralPath $RecordPath
}

function Start-OwnedService($FilePath, $Arguments, $WorkingDirectory, $Workspace,
    $RecordPath, $RunDirectory, [int]$ListenPort, [string]$Url, [int]$WaitSeconds) {
    if ($ListenPort -and (Get-ServiceListeners $ListenPort).Count) { throw "端口 $ListenPort 已占用，拒绝启动或接管。" }
    New-Item -ItemType Directory -Path $RunDirectory -Force | Out-Null
    $process = Start-Process -FilePath $FilePath -ArgumentList $Arguments -WorkingDirectory $WorkingDirectory `
        -WindowStyle Hidden -RedirectStandardOutput (Join-Path $RunDirectory 'stdout.log') `
        -RedirectStandardError (Join-Path $RunDirectory 'stderr.log') -PassThru
    $current = Get-CimInstance Win32_Process -Filter "ProcessId=$($process.Id)"
    if (-not $current) { throw '服务立即退出；查看本次日志。' }
    $record = [pscustomobject]@{
        Workspace = $Workspace; ProcessId = $process.Id
        CreatedTicks = $current.CreationDate.ToUniversalTime().Ticks
        ExecutablePath = $current.ExecutablePath; CommandLine = $current.CommandLine
        Port = $ListenPort; Url = $Url; RunDirectory = $RunDirectory
    }
    $record | Export-Clixml -LiteralPath $RecordPath
    $deadline = [DateTime]::UtcNow.AddSeconds($WaitSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        if (-not (Get-ServiceProcess $record $Workspace)) { throw '服务已退出；保留记录和日志供排查。' }
        if ($ListenPort -eq 0) {
            $bound = Get-NetTCPConnection -State Listen -OwningProcess $process.Id -ErrorAction SilentlyContinue | Select-Object -First 1
            if ($bound) {
                $ListenPort = $bound.LocalPort
                $Url = "http://127.0.0.1:$ListenPort/"
                $record.Port = $ListenPort; $record.Url = $Url
                $record | Export-Clixml -LiteralPath $RecordPath
            }
        }
        $listeners = if ($ListenPort) { Get-ServiceListeners $ListenPort } else { @() }
        if ($listeners.Count -and @($listeners | Where-Object { $_ -ne $process.Id }).Count) {
            throw '监听端口归属不匹配；保留记录，请检查。'
        }
        if ($listeners -contains $process.Id -and (Test-ServiceReady $Url)) { return $record }
        Start-Sleep -Milliseconds 250
    }
    throw "服务未在 $WaitSeconds 秒内就绪；保留进程、记录及日志，可通过 stop 停止。"
}

# Dot-source 仅用于隔离的真实子进程生命周期测试。
if ($MyInvocation.InvocationName -eq '.') { return }
if ($env:OS -ne 'Windows_NT') { throw '此入口仅支持 Windows。' }
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..')).TrimEnd('\')
$stateDirectory = Join-Path $workspace '.runtime-logs/local-services'
$names = @('institution', 'platform', 'gateway', 'admin', 'platform-web', 'chat')
$selected = if ($Component -contains 'all') { $names } else { @($Component | Select-Object -Unique) }
if ($Port -and $selected.Count -ne 1) { throw '-Port 只能用于单个组件。' }

$importedKeys = [Collections.Generic.List[string]]::new()
try {
if ($Action -ne 'stop') {
    $environmentPath = if ([IO.Path]::IsPathRooted($EnvFile)) { $EnvFile } else { Join-Path $workspace $EnvFile }
    if ($Action -eq 'start' -and -not (Test-Path -LiteralPath $environmentPath)) { throw '环境文件不存在；请先配置，入口不会复制或覆盖环境。' }
    foreach ($line in $(if (Test-Path -LiteralPath $environmentPath) { Get-Content -LiteralPath $environmentPath })) {
        if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
            $key = $matches[1]; $value = $matches[2].Trim().Trim('"').Trim("'")
            # 显式进程环境优先于文件，且不输出内容。
            if ($null -eq [Environment]::GetEnvironmentVariable($key, 'Process')) {
                $importedKeys.Add($key)
                [Environment]::SetEnvironmentVariable($key, $value, 'Process')
            }
        }
    }
    if ($Action -eq 'start') { New-Item -ItemType Directory -Path $stateDirectory -Force | Out-Null }
}
$definitions = @{
    institution = @('skillforge-institution-service', 'INSTITUTION_SERVICE_PORT', 8000, '/health/ready')
    platform = @('skillforge-platform-service', 'PLATFORM_SERVICE_PORT', 8100, '/actuator/health')
    gateway = @('skillforge-gateway', 'GATEWAY_PORT', 8080, '/health/ready')
    admin = @('frontend-admin', '', 3000, '/')
    'platform-web' = @('frontend-platform', '', 3100, '/')
    chat = @('frontend-chat', '', 3200, '/')
}
foreach ($name in $selected) {
    $definition = $definitions[$name]
    $recordPath = Join-Path $stateDirectory "$name.clixml"
    $record = if (Test-Path -LiteralPath $recordPath) { Import-Clixml -LiteralPath $recordPath } else { $null }
    if ($Action -eq 'stop') {
        Stop-OwnedService $recordPath $workspace
        Write-Output "$name stopped-or-unmanaged"
        continue
    }
    $configuredPort = if ($definition[1]) { [Environment]::GetEnvironmentVariable($definition[1]) } else { $null }
    $listenPort = if ($Port) { $Port } elseif ($record) { $record.Port } elseif ($configuredPort) { [int]$configuredPort } else { $definition[2] }
    $url = "http://127.0.0.1:$listenPort$($definition[3])"
    if ($record) {
        if ($Action -eq 'start' -and $Port -and $Port -ne $record.Port) { throw '服务记录端口与 -Port 不同；先显式停止旧服务。' }
        $owned = Get-ServiceProcess $record $workspace
        $listeners = Get-ServiceListeners $record.Port
        $ready = $owned -and $listeners -contains $record.ProcessId -and
            @($listeners | Where-Object { $_ -ne $record.ProcessId }).Count -eq 0 -and (Test-ServiceReady $record.Url)
        if ($Action -eq 'start' -and -not $ready) { throw "$name 存在未就绪或失效记录；先检查记录与日志，确认退出后手工移除失效记录。" }
        Write-Output "$name managed=$([bool]$owned) ready=$ready port=$($record.Port) pid=$($record.ProcessId) logs=$($record.RunDirectory)"
        continue
    }
    if ($Action -eq 'status') {
        $listeners = Get-ServiceListeners $listenPort
        Write-Output "$name unmanaged port=$listenPort listeners=$($listeners -join ',') http200=$(Test-ServiceReady $url)"
        continue
    }
    $runDirectory = Join-Path $stateDirectory "$name-$([Guid]::NewGuid().ToString('N'))"
    New-Item -ItemType Directory -Path $runDirectory | Out-Null
    if ($name -in @('institution', 'platform', 'gateway')) {
        $module = $definition[0]
        $sourceJar = Join-Path $workspace "backend-java/$module/build/libs/$module.jar"
        if (-not (Test-Path -LiteralPath $sourceJar)) { throw "$name 缺少 JAR；先显式构建对应模块。" }
        $runtimeJar = Join-Path $runDirectory "$module.jar"
        Copy-Item -LiteralPath $sourceJar -Destination $runtimeJar
        $executable = (Get-Command java -CommandType Application | Select-Object -First 1).Source
        $arguments = @('-jar', "`"$runtimeJar`"", '--spring.flyway.enabled=false', "--server.port=$listenPort")
        $workingDirectory = Join-Path $workspace 'backend-java'
    } else {
        $workingDirectory = Join-Path $workspace $definition[0]
        $vite = Join-Path $workingDirectory 'node_modules/vite/bin/vite.js'
        if (-not (Test-Path -LiteralPath $vite)) { throw "$name 缺少 Vite；先在根目录 pnpm install --frozen-lockfile。" }
        $executable = (Get-Command node -CommandType Application | Select-Object -First 1).Source
        $arguments = @("`"$vite`"", '--host', '127.0.0.1', '--port', $listenPort, '--strictPort')
    }
    $record = Start-OwnedService $executable $arguments $workingDirectory $workspace $recordPath $runDirectory $listenPort $url $TimeoutSeconds
    Write-Output "$name ready port=$listenPort pid=$($record.ProcessId) logs=$runDirectory"
}



} finally {
    # 只移除本次从文件导入的键；调用终端原有环境始终保持优先且不改写。
    foreach ($key in $importedKeys) {
        Remove-Item -LiteralPath ('Env:' + $key) -ErrorAction SilentlyContinue
    }
}
