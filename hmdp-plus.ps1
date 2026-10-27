$ErrorActionPreference = 'Stop'
$projectDir = $PSScriptRoot
$composeFile = Join-Path $projectDir 'docker/hmdp-plus/compose.yaml'
$envFile = Join-Path $projectDir '.env'
$action = if ($args.Count -gt 0) { $args[0] } else { 'up' }
$extraArgs = if ($args.Count -gt 1) { @($args[1..($args.Count - 1)]) } else { @() }

function New-RandomHex([int]$byteCount) {
    $bytes = New-Object byte[] $byteCount
    $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($bytes) } finally { $random.Dispose() }
    return ([BitConverter]::ToString($bytes) -replace '-', '').ToLowerInvariant()
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    [Console]::Error.WriteLine('启动失败：请先安装并启动 Docker Desktop。')
    exit 1
}
& docker compose version
if ($LASTEXITCODE -ne 0) {
    [Console]::Error.WriteLine('启动失败：需要可用的 Docker Compose。')
    exit 1
}

$composeArgs = @('compose', '--env-file', $envFile, '-f', $composeFile)
switch ($action) {
    'up' {
        if (-not (Test-Path -LiteralPath $envFile)) {
            $createdEnvFile = $false
            try {
                $lines = @(
                    'MYSQL_DATABASE=hm-dp'
                    ('MYSQL_PASSWORD=' + (New-RandomHex 24))
                    ('REDIS_PASSWORD=' + (New-RandomHex 24))
                    ('HMDP_JWT_SECRET=' + (New-RandomHex 32))
                    'HMDP_HTTP_PORT=8080'
                    'HMDP_BIND_ADDRESS=127.0.0.1'
                    'HMDP_SECKILL_ENABLED=false'
                )
                $stream = [System.IO.File]::Open($envFile, [System.IO.FileMode]::CreateNew)
                $createdEnvFile = $true
                try {
                    $writer = New-Object System.IO.StreamWriter($stream, (New-Object System.Text.UTF8Encoding($false)))
                    try { $writer.Write(($lines -join "`n") + "`n") } finally { $writer.Dispose() }
                } finally { $stream.Dispose() }
                Write-Host "已生成 ${envFile}，请妥善保管。"
            } catch {
                if ($createdEnvFile) { Remove-Item -LiteralPath $envFile -Force -ErrorAction SilentlyContinue }
                [Console]::Error.WriteLine("启动失败：无法创建 .env：$($_.Exception.Message)")
                exit 1
            }
        }
        & docker @composeArgs up -d --build --wait
        if ($LASTEXITCODE -ne 0) {
            [Console]::Error.WriteLine('启动失败：请查看上面的错误、容器状态和最近日志。')
            & docker @composeArgs ps -a
            & docker @composeArgs logs --no-color --tail 30
            [Console]::Error.WriteLine('完整日志：.\hmdp-plus.cmd logs')
            exit 1
        }
        $binding = @(& docker @composeArgs port frontend 80)
        if ($LASTEXITCODE -eq 0 -and $binding -and ($binding[0] -match '^(.*):(\d+)$')) {
            $hostAddress = $Matches[1]
            $port = $Matches[2]
            if ($hostAddress -eq '0.0.0.0' -or $hostAddress -eq '[::]' -or $hostAddress -eq '::') { $hostAddress = '127.0.0.1' }
            Write-Host '启动成功：前端、后端、MySQL 和 Redis 均已通过健康检查。'
            Write-Host "前端地址：http://${hostAddress}:${port}/"
            Write-Host "后端 API 地址：http://${hostAddress}:${port}/api/（由前端代理）"
        } else {
            Write-Host '启动成功，但无法读取端口；请运行 .\hmdp-plus.cmd ps。'
        }
        Write-Host '秒杀和图片上传需要额外配置，详见 docs/frontend/DOCKER_DEPLOY.md。'
        exit 0
    }
    { $_ -in 'down', 'stop', 'ps', 'logs' } {
        & docker @composeArgs $action @extraArgs
        exit $LASTEXITCODE
    }
    default {
        [Console]::Error.WriteLine('用法：.\hmdp-plus.cmd [up|down|stop|ps|logs]；down 保留数据卷。')
        exit 2
    }
}
