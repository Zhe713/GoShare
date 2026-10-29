# GoShare 服务启动脚本（@argfile 方式，规避 Windows 命令行长度限制 CreateProcess error=206）
# 用法: .\scripts\start-service.ps1 -Module go-share-order-server -Port 8003 -MainClass com.goshare.order.OrderServerApplication
param(
    [Parameter(Mandatory = $true)][string]$Module,
    [Parameter(Mandatory = $true)][string]$Port,
    [Parameter(Mandatory = $true)][string]$MainClass
)
$ErrorActionPreference = 'Stop'
$mvn = 'D:\DevTools\apache-maven-3.9.16\bin\mvn.cmd'
$java = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot\bin\java.exe'
$moduleDir = "D:\GoShare\server\$Module"
$work = 'D:\GoShare\_work'
$logs = 'D:\GoShare\logs'

# 1. 停掉占用端口的旧进程
$conn = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if ($conn) { Stop-Process -Id $conn.OwningProcess -Force; Start-Sleep 2 }

# 2. 打包可执行 jar（依赖多时 spring-boot:run 命令行超 Windows 长度限制，统一用 fat jar 启动）
& $mvn -f 'D:\GoShare\server\pom.xml' -pl $Module package -DskipTests -q
if ($LASTEXITCODE -ne 0) { throw "打包失败: $Module" }

# 3. 后台启动（java -jar 命令行很短，无 206 长度限制）
$jar = "$moduleDir\target\$Module-0.0.1-SNAPSHOT.jar"
Start-Process -FilePath $java -ArgumentList '-jar', $jar -WindowStyle Hidden `
    -RedirectStandardOutput "$logs\$Module.log" -RedirectStandardError "$logs\$Module.err.log"
Write-Host "已启动 $Module (port $Port)"
