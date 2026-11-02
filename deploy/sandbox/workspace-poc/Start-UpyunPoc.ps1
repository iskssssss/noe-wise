param(
    [string]$Prefix = ('/workspace-poc/' + [guid]::NewGuid().ToString('N'))
)
$ErrorActionPreference = 'Stop'
foreach ($key in @('UPYUN_BUCKET', 'UPYUN_OPERATOR', 'UPYUN_PASSWORD')) {
    if (-not [Environment]::GetEnvironmentVariable($key)) {
        throw "缺少环境变量：$key"
    }
}
if (-not $Prefix.StartsWith('/workspace-poc/') -or $Prefix.Contains('..')) {
    throw '仅允许专用测试目录 /workspace-poc/ 下的路径'
}
Write-Host "测试目录：$Prefix"
# 凭据仅经标准输入传入，不进入镜像、容器环境变量或仓库文件。
$configJson = @{
    bucket = $env:UPYUN_BUCKET
    operator = $env:UPYUN_OPERATOR
    password = $env:UPYUN_PASSWORD
    prefix = $Prefix
} | ConvertTo-Json -Compress
try {
    $configJson | docker run --rm -i --name skillforge-upyun-poc `
        --device /dev/fuse --cap-add SYS_ADMIN --security-opt apparmor=unconfined `
        --memory 256m --cpus 1 --pids-limit 64 skillforge-upyun-workspace-poc:local
    if ($LASTEXITCODE -ne 0) { throw '又拍云挂载验证容器启动或执行失败' }
} finally {
    $configJson = $null
}
