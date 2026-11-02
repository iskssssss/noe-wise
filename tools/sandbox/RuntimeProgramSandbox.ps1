<#
.SYNOPSIS
准备或管理 typed Program 验收专属的真实 Code Runner。
.DESCRIPTION
Prepare 先只读核验三个显式 IPv4 子网与现有 Docker 网络及本机具体路由均不重叠，
再创建隔离 Compose、私有令牌和归属清单。Start 在创建资源前重新核验。Build 要求中央窗口已生成
skillforge-sandbox-service.jar；不运行 Gradle。Start 只启动真实 CA、代理和执行服务。
Use 将实际 endpoint/token 注入当前 PowerShell 进程，供紧接着启动的定向测试继承。
Stop 仅停止本批服务并删除核验过 owner 的任务容器，保留全部卷和证据；
只有 Stop -RemoveData 才删除核验过 Compose 归属的三个本批卷。运行镜像始终保留。
每次 Prepare 使用新证据目录；不读取仓库 .env，不继承工作区云凭据。
#>
[CmdletBinding()]
param(
    [ValidateSet('Prepare', 'Build', 'Start', 'Use', 'Stop')]
    [string]$Action = 'Prepare',
    [Parameter(Mandatory = $true)]
    [string]$EvidenceDirectory,
    [string]$LocalPythonImage,
    [string]$LocalNodeImage,
    [string]$ControlSubnet,
    [string]$TaskSubnet,
    [string]$UplinkSubnet,
    [switch]$RemoveData
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$evidenceRoot = [IO.Path]::GetFullPath($EvidenceDirectory)
$manifestPath = Join-Path $evidenceRoot 'runner.json'
$utf8 = New-Object System.Text.UTF8Encoding($false)

# 所有 Docker 调用均按参数数组执行；不输出 env_file 或完整 inspect，避免泄露令牌。
function Invoke-RunnerDocker([string[]]$Arguments) {
    $output = @(& docker @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "Docker 操作失败：$($Arguments[0]); $($output -join [Environment]::NewLine)" }
    return ($output -join [Environment]::NewLine)
}

# 清单只保存端点、来源及资源归属，令牌单独保存在 ACL 受限目录。
function Save-RunnerManifest($Manifest) {
    [IO.File]::WriteAllText($manifestPath, ($Manifest | ConvertTo-Json -Depth 20), $utf8)
}

# 以任务卷的 SHA-256 使用生产 Runner 同一 owner 计算，不从任务名称猜测归属。
function Get-RunnerOwner([string]$TaskVolume) {
    $hash = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($hash.ComputeHash([Text.Encoding]::UTF8.GetBytes($TaskVolume)))).Replace('-', '').ToLowerInvariant() }
    finally { $hash.Dispose() }
}

# 同名资源若已被其他任务占用，须在任何创建或停止动作前拒绝；不能仅凭名字接管。
function Assert-RunnerComposeOwner([string]$Kind, [string]$Name, [string]$Project) {
    $template = if ($Kind -eq 'container') { '{{json .Config.Labels}}' } else { '{{json .Labels}}' }
    $observed = @(& docker $Kind inspect '--format' $template $Name 2>$null)
    if ($LASTEXITCODE -ne 0) { return }
    $labels = ($observed -join [Environment]::NewLine) | ConvertFrom-Json
    if ($null -eq $labels -or $labels.'com.docker.compose.project' -ne $Project) { throw "同名 $Kind 的 Compose 归属不匹配，拒绝接管：$Name" }
}

# 用无符号 64 位闭区间比较 IPv4 CIDR，拒绝把非网络地址或短写地址当作子网。
function Convert-RunnerSubnet([string]$Cidr) {
    if ($Cidr -notmatch '^([0-9]{1,3}(?:\.[0-9]{1,3}){3})/([0-9]|[12][0-9]|3[0-2])$') { throw "IPv4 CIDR 非法：$Cidr" }
    $address = $Matches[1]; $prefix = [int]$Matches[2]
    [uint64]$value = 0
    $canonical = @()
    foreach ($octet in $address.Split('.')) {
        $part = [int]$octet
        if ($part -gt 255) { throw "IPv4 CIDR 非法：$Cidr" }
        $canonical += $part.ToString()
        $value = $value * 256 + $part
    }
    [uint64]$width = [uint64]1 -shl (32 - $prefix)
    if (($canonical -join '.') -ne $address -or $value % $width -ne 0) { throw "须使用规范 IPv4 网络地址：$Cidr" }
    return [pscustomobject]@{ cidr = $Cidr; first = $value; last = ($value + $width - 1); prefix = $prefix }
}

# 仅默认路由 0.0.0.0/0 是兜底转发规则而非已占用地址；其他具体路由全部参与检查。
function Get-RunnerLocalRoutes {
    if (Get-Command Get-NetRoute -ErrorAction SilentlyContinue) {
        return @(Get-NetRoute -AddressFamily IPv4 -PolicyStore ActiveStore -ErrorAction Stop |
            ForEach-Object { $_.DestinationPrefix } | Where-Object { $_ -ne '0.0.0.0/0' } | Sort-Object -Unique)
    }
    if (-not (Get-Command ip -ErrorAction SilentlyContinue)) { throw '无法读取本机 IPv4 路由，拒绝跳过子网冲突核验' }
    $output = @(& ip -j -4 route show table all 2>&1)
    if ($LASTEXITCODE -ne 0) { throw '读取本机 IPv4 路由失败' }
    return @(($output -join [Environment]::NewLine) | ConvertFrom-Json | ForEach-Object {
        if ($_.PSObject.Properties['dst'] -and $_.dst -notin @('default', '0.0.0.0/0')) {
            if ($_.dst.Contains('/')) { $_.dst } else { $_.dst + '/32' }
        }
    } | Sort-Object -Unique)
}

# Prepare 与 Start 都读取当前网络事实；已有本批网络只有名称、project 和子网均精确匹配才可复用。
function Assert-RunnerSubnets($Subnets, [string]$Project) {
    $ranges = [ordered]@{}
    foreach ($kind in @('control', 'task', 'uplink')) {
        $range = Convert-RunnerSubnet $Subnets.$kind
        if ($range.prefix -lt 1 -or $range.prefix -gt 30) { throw 'Runner 子网须保留可用容器地址' }
        foreach ($other in $ranges.Values) {
            if ($range.first -le $other.last -and $other.first -le $range.last) { throw "Runner 子网互相重叠：$($range.cidr) / $($other.cidr)" }
        }
        $ranges[$kind] = $range
    }
    $ids = @((Invoke-RunnerDocker @('network', 'ls', '--no-trunc', '--format', '{{.ID}}')) -split '\s+' | Where-Object { $_ })
    $networks = @(if ($ids.Count) { Invoke-RunnerDocker (@('network', 'inspect') + $ids) | ConvertFrom-Json })
    foreach ($network in $networks) {
        foreach ($config in @($network.IPAM.Config)) {
            if ($null -eq $config -or -not $config.PSObject.Properties['Subnet'] -or -not $config.Subnet -or $config.Subnet.Contains(':')) { continue }
            $existing = Convert-RunnerSubnet $config.Subnet
            foreach ($kind in $ranges.Keys) {
                $range = $ranges[$kind]
                if ($range.first -gt $existing.last -or $existing.first -gt $range.last) { continue }
                $projectLabel = if ($null -ne $network.Labels -and $network.Labels.PSObject.Properties['com.docker.compose.project']) { $network.Labels.'com.docker.compose.project' } else { $null }
                if ($Project -and $network.Name -eq "$Project-$kind" -and $projectLabel -eq $Project -and $existing.cidr -eq $range.cidr) { continue }
                throw "Runner 子网 $($range.cidr) 与 Docker 网络 $($network.Name) 的 $($existing.cidr) 重叠"
            }
        }
    }
    $routes = @(Get-RunnerLocalRoutes)
    foreach ($route in $routes) {
        $existing = Convert-RunnerSubnet $route
        foreach ($range in $ranges.Values) {
            if ($range.first -le $existing.last -and $existing.first -le $range.last) { throw "Runner 子网 $($range.cidr) 与本机具体路由 $route 重叠" }
        }
    }
    return [pscustomobject]@{ checkedAt = [DateTime]::UtcNow.ToString('o'); dockerNetworkCount = $networks.Count; localRouteCount = $routes.Count; outcome = 'PASS' }
}

if ($RemoveData -and $Action -ne 'Stop') { throw 'RemoveData 仅适用于 Stop' }
if ($Action -eq 'Prepare') {
    if (Test-Path -LiteralPath $evidenceRoot) { throw 'Prepare 要求全新的证据目录，禁止覆盖既有事实' }
    if (-not $ControlSubnet -or -not $TaskSubnet -or -not $UplinkSubnet) { throw 'Prepare 须显式提供 ControlSubnet、TaskSubnet、UplinkSubnet，禁止使用共享默认地址池' }
    $networkSubnets = [pscustomobject]@{ control = $ControlSubnet; task = $TaskSubnet; uplink = $UplinkSubnet }
    $preparedNetworkCheck = Assert-RunnerSubnets $networkSubnets ''
    [void][IO.Directory]::CreateDirectory($evidenceRoot)
    $privateRoot = Join-Path $evidenceRoot 'private'
    [void][IO.Directory]::CreateDirectory($privateRoot)
    if ($env:OS -eq 'Windows_NT') {
        $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
        $aclOutput = @(& icacls.exe $privateRoot '/inheritance:r' '/grant:r' "*${sid}:(OI)(CI)F" 2>&1)
        if ($LASTEXITCODE -ne 0) { throw "私有目录 ACL 设置失败：$($aclOutput -join ' ')" }
    } else {
        & chmod 700 $privateRoot
        if ($LASTEXITCODE -ne 0) { throw '私有目录权限设置失败' }
    }
    $project = 'sf-program-' + [guid]::NewGuid().ToString('N').Substring(0, 12)
    $taskVolume = "$project-tasks"
    $projectVolume = "$project-projects"
    $caVolume = "$project-ca"
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    $token = ([BitConverter]::ToString($bytes)).Replace('-', '').ToLowerInvariant()
    $tokenFile = Join-Path $privateRoot 'service.env'
    [IO.File]::WriteAllText($tokenFile, "SANDBOX_SERVICE_TOKEN=$token`n", $utf8)
    $token = $null
    $pythonImage = if ($LocalPythonImage) { $LocalPythonImage } else { "${project}-python:runtime" }
    $nodeImage = if ($LocalNodeImage) { $LocalNodeImage } else { "${project}-node:runtime" }
    $runtimeSource = Join-Path $repoRoot 'deploy/sandbox/runtimes'
    $proxySource = Join-Path $repoRoot 'deploy/sandbox/proxy'
    $jarRoot = Join-Path $repoRoot 'backend-java/skillforge-sandbox-service/build/libs'
    $services = [ordered]@{
        'sandbox-ca-init' = @{
            build = @{ context = $proxySource; target = 'ca-init' }; image = "${project}-ca:init"
            volumes = @('sandbox-ca:/ca'); network_mode = 'none'; restart = 'no'
        }
        'sandbox-egress-proxy' = @{
            build = @{ context = $proxySource; target = 'proxy' }; image = "${project}-proxy:11.0.2"
            container_name = "$project-proxy"
            depends_on = @{ 'sandbox-ca-init' = @{ condition = 'service_completed_successfully' } }
            volumes = @('sandbox-ca:/ca:ro')
            networks = @{ 'sandbox-task' = @{ aliases = @('sandbox-egress-proxy') }; 'sandbox-uplink' = @{} }
            read_only = $true; tmpfs = @('/tmp:rw,noexec,nosuid,nodev,size=64m,mode=1777')
            cap_drop = @('ALL'); security_opt = @('no-new-privileges:true')
            pids_limit = 128; mem_limit = '256m'; cpus = 0.5; restart = 'no'
            healthcheck = @{ test = @('CMD', 'python', '-c', "import socket; s=socket.create_connection(('127.0.0.1',8080),2); s.close()"); interval = '3s'; timeout = '3s'; retries = 20 }
        }
        'sandbox-service' = @{
            build = @{ context = $jarRoot; dockerfile = (Join-Path $repoRoot 'backend-java/skillforge-sandbox-service/Dockerfile') }
            image = "${project}-service:candidate"; container_name = "$project-service"; mem_limit = '1g'
            depends_on = @{ 'sandbox-ca-init' = @{ condition = 'service_completed_successfully' }; 'sandbox-egress-proxy' = @{ condition = 'service_healthy' } }
            env_file = @($tokenFile)
            environment = @{
                TZ = 'Asia/Shanghai'; SANDBOX_SERVICE_PORT = '8200'
                SANDBOX_TASK_NETWORK = "$project-task"; SANDBOX_TASK_VOLUME = $taskVolume; SANDBOX_CA_VOLUME = $caVolume
                SANDBOX_PROXY_URL = 'http://sandbox-egress-proxy:8080'; SANDBOX_PROXY_CONTAINER = "$project-proxy"
                SANDBOX_PYTHON_IMAGE = $pythonImage; SANDBOX_NODE_IMAGE = $nodeImage
                SANDBOX_WORKFLOW_PYTHON_IMAGE = $pythonImage; SANDBOX_WORKFLOW_NODE_IMAGE = $nodeImage
                SANDBOX_PROJECT_ROOT = '/var/lib/skillforge-projects'; SANDBOX_PROJECT_VOLUME = $projectVolume
                SANDBOX_WORKSPACE_CLOUD_BUCKET = ''; SANDBOX_WORKSPACE_CLOUD_OPERATOR = ''; SANDBOX_WORKSPACE_CLOUD_PASSWORD = ''
            }
            volumes = @('/var/run/docker.sock:/var/run/docker.sock:ro', 'sandbox-tasks:/var/lib/skillforge-sandbox', 'sandbox-projects:/var/lib/skillforge-projects')
            networks = @('sandbox-control')
            ports = @(@{ target = 8200; published = '0'; host_ip = '127.0.0.1'; protocol = 'tcp' })
            restart = 'no'
        }
    }
    if (-not $LocalPythonImage) { $services['python-runtime-image'] = @{ profiles = @('runtime-images'); build = @{ context = $runtimeSource; target = 'python-runtime' }; image = $pythonImage; network_mode = 'none' } }
    if (-not $LocalNodeImage) { $services['node-runtime-image'] = @{ profiles = @('runtime-images'); build = @{ context = $runtimeSource; target = 'node-runtime' }; image = $nodeImage; network_mode = 'none' } }
    $compose = @{
        name = $project; services = $services
        volumes = @{ 'sandbox-ca' = @{ name = $caVolume }; 'sandbox-tasks' = @{ name = $taskVolume }; 'sandbox-projects' = @{ name = $projectVolume } }
        networks = @{
            'sandbox-control' = @{ name = "$project-control"; ipam = @{ config = @(@{ subnet = $ControlSubnet }) } }
            'sandbox-task' = @{ name = "$project-task"; ipam = @{ config = @(@{ subnet = $TaskSubnet }) }; internal = $true; enable_ipv6 = $false; driver_opts = @{ 'com.docker.network.bridge.gateway_mode_ipv4' = 'isolated' } }
            'sandbox-uplink' = @{ name = "$project-uplink"; ipam = @{ config = @(@{ subnet = $UplinkSubnet }) } }
        }
    }
    $composePath = Join-Path $evidenceRoot 'compose.json'
    [IO.File]::WriteAllText($composePath, ($compose | ConvertTo-Json -Depth 30), $utf8)
    [IO.File]::WriteAllText((Join-Path $privateRoot 'empty.env'), '', $utf8)
    $manifest = [ordered]@{
        phase = 'PREPARED_NOT_RUN'; project = $project; repository = $repoRoot
        compose = $composePath; tokenFile = $tokenFile; emptyEnv = (Join-Path $privateRoot 'empty.env')
        endpoint = $null; serviceContainer = "$project-service"; proxyContainer = "$project-proxy"
        taskVolume = $taskVolume; projectVolume = $projectVolume; caVolume = $caVolume
        owner = (Get-RunnerOwner $taskVolume); pythonImage = $pythonImage; nodeImage = $nodeImage
        imageIds = @{}; jarSha256 = $null; buildOutcome = 'NOT_RUN'; startOutcome = 'NOT_RUN'
        networkSubnets = $networkSubnets; preparedNetworkCheck = $preparedNetworkCheck; startedNetworkCheck = $null
        cleanup = 'NOT_RUN'; createdAt = [DateTime]::UtcNow.ToString('o')
    }
    Save-RunnerManifest $manifest
    Write-Host "PREPARED_NOT_RUN：$manifestPath"
    return
}

$manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
if ($manifest.repository -ne $repoRoot -or $manifest.project -notmatch '^sf-program-[0-9a-f]{12}$' -or
    $manifest.taskVolume -ne "$($manifest.project)-tasks" -or $manifest.projectVolume -ne "$($manifest.project)-projects" -or
    $manifest.caVolume -ne "$($manifest.project)-ca" -or $manifest.serviceContainer -ne "$($manifest.project)-service" -or
    $manifest.proxyContainer -ne "$($manifest.project)-proxy" -or $manifest.owner -ne (Get-RunnerOwner $manifest.taskVolume)) { throw 'Runner 清单来源或 owner 不匹配' }
foreach ($file in @($manifest.compose, $manifest.tokenFile, $manifest.emptyEnv)) {
    $resolvedFile = [IO.Path]::GetFullPath($file)
    if (-not $resolvedFile.StartsWith($evidenceRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Runner 清单文件超出本批证据目录' }
}
$composeArgs = @('compose', '--project-name', $manifest.project, '--env-file', $manifest.emptyEnv, '-f', $manifest.compose)
if ($Action -in @('Start', 'Stop')) {
    foreach ($container in @($manifest.serviceContainer, $manifest.proxyContainer)) { Assert-RunnerComposeOwner 'container' $container $manifest.project }
    foreach ($volume in @($manifest.taskVolume, $manifest.projectVolume, $manifest.caVolume)) { Assert-RunnerComposeOwner 'volume' $volume $manifest.project }
    foreach ($network in @('control', 'task', 'uplink')) { Assert-RunnerComposeOwner 'network' "$($manifest.project)-$network" $manifest.project }
}

if ($Action -eq 'Build') {
    $jar = Join-Path $repoRoot 'backend-java/skillforge-sandbox-service/build/libs/skillforge-sandbox-service.jar'
    if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) { throw '须先在中央构建窗口生成 :skillforge-sandbox-service:bootJar；本脚本不运行 Gradle' }
    $manifest.jarSha256 = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLowerInvariant()
    $manifest.phase = 'BUILDING'; Save-RunnerManifest $manifest
    try {
        $output = Invoke-RunnerDocker ($composeArgs + @('--profile', 'runtime-images', 'build'))
        [IO.File]::WriteAllText((Join-Path $evidenceRoot 'build.log'), $output, $utf8)
        $manifest.phase = 'BUILT_NOT_STARTED'; $manifest.buildOutcome = 'PASS'; Save-RunnerManifest $manifest
    } catch {
        [IO.File]::WriteAllText((Join-Path $evidenceRoot 'build-failure.log'), $_.ToString(), $utf8)
        $manifest.phase = 'BUILD_FAILED'; $manifest.buildOutcome = 'FAILED'; Save-RunnerManifest $manifest
        throw
    }
    Write-Host "BUILT_NOT_STARTED：$manifestPath"
    return
}

if ($Action -eq 'Start') {
    $manifest.phase = 'STARTING'; Save-RunnerManifest $manifest
    try {
        $manifest.startedNetworkCheck = Assert-RunnerSubnets $manifest.networkSubnets $manifest.project
        Save-RunnerManifest $manifest
        # 镜像固定在启动之前；未构建、未检查的运行时不在测试调用期间自动拉取。
        $imageIds = [ordered]@{}
        foreach ($image in @($manifest.pythonImage, $manifest.nodeImage)) {
            $imageId = (Invoke-RunnerDocker @('image', 'inspect', '--format', '{{.Id}}', $image)).Trim()
            if ($imageId -notmatch '^sha256:[0-9a-f]{64}$') { throw '本地运行镜像无法固定摘要' }
            $imageIds[$image] = $imageId
        }
        $manifest.imageIds = $imageIds; Save-RunnerManifest $manifest
        $output = Invoke-RunnerDocker ($composeArgs + @('up', '-d', '--no-build', '--pull', 'never', 'sandbox-service'))
        [IO.File]::WriteAllText((Join-Path $evidenceRoot 'start.log'), $output, $utf8)
        $bound = (Invoke-RunnerDocker ($composeArgs + @('port', 'sandbox-service', '8200'))).Trim()
        if ($bound -notmatch '^127\.0\.0\.1:([0-9]+)$') { throw 'Runner 端口未绑定到唯一 loopback 地址' }
        $manifest.endpoint = "http://$bound"; Save-RunnerManifest $manifest
        $deadline = [DateTime]::UtcNow.AddSeconds(90)
        do {
            try { $health = Invoke-RestMethod -Uri ($manifest.endpoint + '/actuator/health') -TimeoutSec 2; if ($health.status -eq 'UP') { break } } catch { $health = $null }
            if ([DateTime]::UtcNow -ge $deadline) { throw 'Runner 未在 90 秒内达到实际 health UP' }
            Start-Sleep -Milliseconds 500
        } while ($true)
        $manifest.phase = 'READY'; $manifest.startOutcome = 'PASS'; Save-RunnerManifest $manifest
        Write-Host "READY：$($manifest.endpoint)；令牌不输出，测试前执行 Action Use"
    } catch {
        [IO.File]::WriteAllText((Join-Path $evidenceRoot 'start-failure.log'), $_.ToString(), $utf8)
        $manifest.phase = 'START_FAILED'; $manifest.startOutcome = 'FAILED'; Save-RunnerManifest $manifest
        # 保留失败事实；资源在 finally 等价的 Stop 动作中按本批归属回收。
        try { & $PSCommandPath -Action Stop -EvidenceDirectory $evidenceRoot } catch { Write-Warning 'Runner 清理失败，请保留清单并按同一 Stop 入口重试清理' }
        throw
    }
    return
}

if ($Action -eq 'Use') {
    if ($manifest.phase -ne 'READY' -or $manifest.endpoint -notmatch '^http://127\.0\.0\.1:[0-9]+$') { throw 'Runner 未就绪' }
    $tokenLine = [IO.File]::ReadAllText($manifest.tokenFile, $utf8).Trim()
    if ($tokenLine -notmatch '^SANDBOX_SERVICE_TOKEN=([0-9a-f]{64})$') { throw 'Runner 令牌文件非法' }
    $env:CODE_EXECUTION_SMOKE_ENDPOINT = $manifest.endpoint
    $env:CODE_EXECUTION_SMOKE_TOKEN = $Matches[1]
    $env:SKILLFORGE_RUNTIME_PROGRAM_E2E = '1'
    Write-Host '当前进程已设置真实 Code Runner 定向验收环境；未执行测试'
    return
}

if ($Action -eq 'Stop') {
    # 先让本批可信服务静止，再清理该 owner 的遗留任务，避免它继续创建新任务。
    [void](Invoke-RunnerDocker ($composeArgs + @('stop', '--timeout', '30', 'sandbox-service')))
    $owned = (Invoke-RunnerDocker @('ps', '-aq', '--no-trunc', '--filter', 'label=skillforge.sandbox.managed=true', '--filter', "label=skillforge.sandbox.owner=$($manifest.owner)")) -split '\s+'
    foreach ($container in $owned) {
        if (-not $container) { continue }
        if ($container -notmatch '^[0-9a-f]{64}$') { throw '任务容器返回非法 ID' }
        $labels = Invoke-RunnerDocker @('inspect', '--format', '{{json .Config.Labels}}', $container) | ConvertFrom-Json
        if ($labels.'skillforge.sandbox.owner' -ne $manifest.owner -or $labels.'skillforge.sandbox.managed' -ne 'true') { throw '任务容器归属核验失败，拒绝删除' }
        [void](Invoke-RunnerDocker @('rm', '--force', $container))
    }
    [IO.File]::WriteAllText((Join-Path $evidenceRoot 'service.log'), (Invoke-RunnerDocker ($composeArgs + @('logs', '--no-color', 'sandbox-service'))), $utf8)
    [void](Invoke-RunnerDocker ($composeArgs + @('down', '--timeout', '30')))
    $remaining = (Invoke-RunnerDocker @('ps', '-aq', '--filter', "label=com.docker.compose.project=$($manifest.project)")).Trim()
    if ($remaining) { throw 'Compose 容器仍未静止' }
    $remainingTasks = (Invoke-RunnerDocker @('ps', '-aq', '--filter', 'label=skillforge.sandbox.managed=true', '--filter', "label=skillforge.sandbox.owner=$($manifest.owner)")).Trim()
    if ($remainingTasks) { throw '本批任务容器仍未静止' }
    if ($RemoveData) {
        foreach ($volume in @($manifest.taskVolume, $manifest.projectVolume, $manifest.caVolume)) {
            if (-not $volume.StartsWith($manifest.project + '-', [StringComparison]::Ordinal)) { throw '卷名超出本批前缀' }
            $labels = Invoke-RunnerDocker @('volume', 'inspect', '--format', '{{json .Labels}}', $volume) | ConvertFrom-Json
            if ($labels.'com.docker.compose.project' -ne $manifest.project) { throw '卷 Compose 归属不匹配，拒绝删除' }
            [void](Invoke-RunnerDocker @('volume', 'rm', $volume))
        }
    }
    $manifest.phase = if ($RemoveData) { 'STOPPED_DATA_REMOVED' } else { 'STOPPED_DATA_RETAINED' }
    $manifest.cleanup = 'PASS'; Save-RunnerManifest $manifest
    Write-Host "$($manifest.phase)：$manifestPath"
}
