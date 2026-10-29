# GoShare 清理脚本
# 用法：
#   .\clean.ps1           # 清理构建产物、日志、临时目录
#   .\clean.ps1 -DockerDown  # 同时停掉 docker compose 容器
param(
    [switch]$DockerDown
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

Write-Host "==> 清理 GoShare 临时产物（$root）"

# 1. 清理 Maven 构建产物
Get-ChildItem -LiteralPath $root -Recurse -Directory -Filter 'target' | ForEach-Object {
    Write-Host "  删除 $($_.FullName)"
    Remove-Item -LiteralPath $_.FullName -Recurse -Force
}

# 2. 清理日志
Get-ChildItem -LiteralPath $root -Recurse -Filter '*.log' | ForEach-Object {
    Write-Host "  删除 $($_.FullName)"
    Remove-Item -LiteralPath $_.FullName -Force
}

# 3. 清理临时目录内容
$work = Join-Path $root '_work'
if (Test-Path -LiteralPath $work) {
    Get-ChildItem -LiteralPath $work | ForEach-Object {
        Write-Host "  删除临时文件 $($_.FullName)"
        Remove-Item -LiteralPath $_.FullName -Recurse -Force
    }
}

# 4. 可选：停掉 docker compose
if ($DockerDown) {
    Write-Host "==> 停止 docker compose 容器"
    docker compose -f (Join-Path $root 'docker\docker-compose.yml') down
}

Write-Host '==> 清理完成'
