# GoShare 一键启动：中间件(Docker) + 8 后端 jar + vite 前端
# 用法（任意目录均可）:  powershell -ExecutionPolicy Bypass -File scripts/start-all.ps1
# 说明：不写死路径；仓库根按脚本位置推导（可用 GOSHARE_HOME 覆盖），Java/Node 由 scripts/_env.ps1 探测
param(
    [switch]$SkipDocker,   # 中间件已在跑时跳过 docker compose
    [switch]$SkipBackend,  # 只起前端
    [switch]$SkipFrontend  # 只起后端
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '_env.ps1')

Write-Host '[0/4] 清理本项目的旧进程（只杀 go-share-*-server，不碰其它 Java 程序）...'
Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -match 'go-share-[a-z]+-server' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
if (-not $SkipFrontend) {
    Get-Process node -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
}
Start-Sleep 2

Write-Host '[1/4] 启动 Docker 中间件...'
if ($SkipDocker) {
    Write-Host '  已跳过（-SkipDocker）'
} else {
    $composeFile = Join-Path $repoRoot 'docker\docker-compose.yml'
    if (-not (Test-Path -LiteralPath $composeFile)) { throw "未找到 $composeFile" }
    Push-Location (Join-Path $repoRoot 'docker')
    try {
        docker compose up -d --wait
        if ($LASTEXITCODE -ne 0) { throw 'docker compose up 失败（Docker Desktop 是否已启动？）' }
    } finally {
        Pop-Location
    }
    # --wait 只保证容器健康，建表/种子脚本可能还在跑；等到演示账号可查再起服务，
    # 否则第一批请求会因为表不存在直接 500
    $ready = $false
    for ($i = 0; $i -lt 60; $i++) {
        $out = & docker exec goshare-mysql mysql -uroot -proot123 -N -e "SELECT COUNT(*) FROM goshare.user WHERE phone='13900139000'" 2>$null
        if ("$out".Trim() -eq '1') { $ready = $true; break }
        Start-Sleep 2
    }
    if (-not $ready) {
        Write-Warning 'MySQL 建表/种子未就绪。若数据卷是旧的，执行：docker exec -i goshare-mysql mysql -uroot -proot123 goshare < sql/07-demo-seed.sql'
    }
}

if ($SkipBackend) {
    Write-Host '[2/4] 跳过后端启动（-SkipBackend）'
} else {
    Write-Host '[2/4] 启动 8 个后端服务（约 30 秒）...'
    foreach ($s in $services) {
        $jar = Join-Path $serverDir "go-share-$($s.Name)-server\target\go-share-$($s.Name)-server-0.0.1-SNAPSHOT.jar"
        if (-not (Test-Path -LiteralPath $jar)) {
            Write-Warning "  缺少 $($s.Name) 的 jar，先执行：cd server; mvn package -DskipTests"
            continue
        }
        # 显式指定端口：有些环境会注入 SERVER__PORT，Spring 松散绑定会覆盖 server.port
        Start-Process -FilePath $java `
            -ArgumentList "-jar `"$jar`" --server.port=$($s.Port)" -WindowStyle Hidden `
            -RedirectStandardOutput (Join-Path $logsDir "go-share-$($s.Name)-server.log") `
            -RedirectStandardError (Join-Path $logsDir "go-share-$($s.Name)-server.err.log")
        Write-Host "  $($s.Name) -> $($s.Port)"
    }
    Start-Sleep 30
}

if ($SkipFrontend) {
    Write-Host '[3/4] 跳过前端启动（-SkipFrontend）'
} else {
    Write-Host '[3/4] 启动前端 vite...'
    $viteJs = Join-Path $webDir 'node_modules\vite\bin\vite.js'
    if (-not (Test-Path -LiteralPath $viteJs)) { throw "未找到 vite，请先在 web 目录执行 npm install" }
    Start-Process -FilePath $node -ArgumentList "`"$viteJs`" --port 5173" -WorkingDirectory $webDir -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $logsDir 'vite.log') `
        -RedirectStandardError (Join-Path $logsDir 'vite.err.log')
    Start-Sleep 10
}

Write-Host '[4/4] 端口状态：'
$ports = @(5173) + ($services | ForEach-Object { $_.Port })
foreach ($p in $ports) {
    $up = $null -ne (Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue)
    $label = if ($p -eq 5173) { 'vite' } else { ($services | Where-Object { $_.Port -eq $p }).Name }
    Write-Host ("  {0,-5} {1,-10} {2}" -f $p, $label, $(if ($up) { 'UP' } else { 'DOWN' }))
}
Write-Host ''
Write-Host '完成。浏览器打开: http://localhost:5173'
