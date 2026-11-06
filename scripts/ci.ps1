# GoShare 阶段 7 CI 门禁串联 · 本地一键执行
# 顺序：后端 mvn verify（Jacoco 行覆盖 ≥70% 模块级门禁）→ 前端构建（vue-tsc + vite build）→ vitest 单测 → stylelint 裸样式门禁
# 用法：powershell -ExecutionPolicy Bypass -File scripts/ci.ps1
# 说明：不写死路径；仓库根按脚本位置推导（可用 GOSHARE_HOME 覆盖），Java/Maven/Node 见 scripts/_env.ps1
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '_env.ps1')

$npm = (Get-Command 'npm.cmd' -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1)
if (-not $npm) { throw '未找到 npm.cmd，请安装 Node.js 并加入 PATH' }
$npm = $npm.Source

Write-Host '=== [1/4] 后端 mvn clean verify（Jacoco 行覆盖 ≥70%，check-module 逐模块拦截）===' -ForegroundColor Cyan
Push-Location $serverDir
try {
    & $mvn clean verify -q
    if ($LASTEXITCODE -ne 0) { throw '后端门禁失败：覆盖率未达 70% 或测试失败' }
} finally {
    Pop-Location
}
Write-Host '  后端门禁通过（覆盖率 ≥70%）' -ForegroundColor Green

Write-Host '=== [2/4] 前端构建（vue-tsc 类型 + vite build）===' -ForegroundColor Cyan
Push-Location $webDir
try {
    & $npm run build
    if ($LASTEXITCODE -ne 0) { throw '前端类型检查/构建失败' }
} finally {
    Pop-Location
}
Write-Host '  vue-tsc 通过' -ForegroundColor Green

Write-Host '=== [3/4] 前端单测 vitest ===' -ForegroundColor Cyan
Push-Location $webDir
try {
    & $npm test
    if ($LASTEXITCODE -ne 0) { throw '前端单测失败' }
} finally {
    Pop-Location
}
Write-Host '  vitest 通过' -ForegroundColor Green

Write-Host '=== [4/4] Stylelint 裸样式门禁（页面/组件禁裸色值/裸字号/裸间距）===' -ForegroundColor Cyan
Push-Location $webDir
try {
    & $npm run lint:style
    if ($LASTEXITCODE -ne 0) { throw 'Stylelint 裸样式门禁失败' }
} finally {
    Pop-Location
}
Write-Host '  stylelint 通过' -ForegroundColor Green

Write-Host ''
Write-Host 'CI 门禁全部通过（E2E 主链路另见：npm run test:e2e，需 8 后端 + vite 在跑）' -ForegroundColor Green
