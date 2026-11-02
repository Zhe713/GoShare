# GoShare 阶段 7 CI 门禁串联 · 本地一键执行
# 顺序：后端 mvn verify（Jacoco 行覆盖 ≥70% 模块级门禁）→ 前端 vue-tsc 类型 → vitest 单测 → stylelint 裸样式门禁
# 用法：powershell -ExecutionPolicy Bypass -File scripts/ci.ps1
$ErrorActionPreference = 'Stop'
$root = 'D:\GoShare'

Write-Host '=== [1/4] 后端 mvn clean verify（Jacoco 行覆盖 ≥70%，check-module 逐模块拦截）===' -ForegroundColor Cyan
Push-Location "$root\server"
& 'D:\DevTools\apache-maven-3.9.16\bin\mvn.cmd' clean verify -q
if ($LASTEXITCODE -ne 0) { Pop-Location; throw '后端门禁失败：覆盖率未达 70% 或测试失败' }
Pop-Location
Write-Host '  后端门禁通过（覆盖率 ≥70%）' -ForegroundColor Green

Write-Host '=== [2/4] 前端类型检查 vue-tsc ===' -ForegroundColor Cyan
Push-Location "$root\web"
& npm run build
if ($LASTEXITCODE -ne 0) { Pop-Location; throw '前端类型检查/构建失败' }
Pop-Location
Write-Host '  vue-tsc 通过' -ForegroundColor Green

Write-Host '=== [3/4] 前端单测 vitest ===' -ForegroundColor Cyan
Push-Location "$root\web"
& npm test
if ($LASTEXITCODE -ne 0) { Pop-Location; throw '前端单测失败' }
Pop-Location
Write-Host '  vitest 通过' -ForegroundColor Green

Write-Host '=== [4/4] Stylelint 裸样式门禁（页面/组件禁裸色值/裸字号/裸间距）===' -ForegroundColor Cyan
Push-Location "$root\web"
& npm run lint:style
if ($LASTEXITCODE -ne 0) { Pop-Location; throw 'Stylelint 裸样式门禁失败' }
Pop-Location
Write-Host '  stylelint 通过' -ForegroundColor Green

Write-Host ''
Write-Host 'CI 门禁全部通过 ✅（E2E 主链路另见：npm run test:e2e，需 8 后端 + vite 在跑）' -ForegroundColor Green
