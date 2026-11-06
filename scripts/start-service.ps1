# GoShare 服务启动脚本：停旧进程 -> 打包 fat jar -> java -jar 后台启动
# 用法: .\scripts\start-service.ps1 -Module go-share-order-server -Port 8003
# 参数 -MainClass 已不再必需（fat jar 由 MANIFEST 决定入口），保留仅为兼容旧命令行
# 说明：不写死路径；Java/Maven 由 scripts/_env.ps1 探测，仓库根按脚本位置推导
param(
    [Parameter(Mandatory = $true)][string]$Module,
    [Parameter(Mandatory = $true)][int]$Port,
    [string]$MainClass
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '_env.ps1')

$moduleDir = Join-Path $serverDir $Module
if (-not (Test-Path -LiteralPath $moduleDir)) {
    throw "模块目录不存在：$moduleDir"
}

# 1. 停掉占用端口的旧进程
$conn = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if ($conn) {
    Write-Host "端口 $Port 被 PID $($conn.OwningProcess) 占用，先停止"
    Stop-Process -Id $conn.OwningProcess -Force -ErrorAction SilentlyContinue
    Start-Sleep 2
}

# 2. 打包可执行 jar（依赖多时 spring-boot:run 命令行超 Windows 长度限制，统一用 fat jar 启动）
& $mvn -f (Join-Path $serverDir 'pom.xml') -pl $Module package -DskipTests -q
if ($LASTEXITCODE -ne 0) { throw "打包失败: $Module" }

# 3. 后台启动（java -jar 命令行很短，无 206 长度限制）
$jar = Join-Path $moduleDir "target\$Module-0.0.1-SNAPSHOT.jar"
if (-not (Test-Path -LiteralPath $jar)) { throw "未找到 jar：$jar" }

$outLog = Join-Path $logsDir "$Module.log"
$errLog = Join-Path $logsDir "$Module.err.log"
Start-Process -FilePath $java -ArgumentList "-jar `"$jar`" --server.port=$Port" -WindowStyle Hidden `
    -RedirectStandardOutput $outLog -RedirectStandardError $errLog
Write-Host "已启动 $Module (port $Port)，日志：$outLog"
