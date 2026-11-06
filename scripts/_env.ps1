# GoShare 脚本公共环境探测（被其他脚本 dot-source 引用，不单独执行）
# 目标：不写死任何绝对路径，换机器/换 JDK 目录都能跑
#   - 仓库根：自动取脚本所在目录的父目录，可用 GOSHARE_HOME 覆盖
#   - 工具：优先 GOSHARE_JAVA / GOSHARE_MVN / GOSHARE_NODE（指向可执行文件）
#           其次 JAVA_HOME / MAVEN_HOME / NODE_HOME（指向安装目录）
#           再其次 PATH
#           最后在常见安装目录里按通配取最新版本

$ErrorActionPreference = 'Stop'

if ($env:GOSHARE_HOME) {
    $repoRoot = $env:GOSHARE_HOME
} else {
    $repoRoot = Split-Path -Parent $PSScriptRoot
}
if (-not (Test-Path -LiteralPath $repoRoot)) {
    throw "仓库根目录不存在：$repoRoot（可用 GOSHARE_HOME 覆盖）"
}

function Resolve-Tool {
    param(
        [Parameter(Mandatory = $true)][string]$Exe,
        [string]$DirEnv,
        [string[]]$Globs = @()
    )

    $key = 'GOSHARE_' + ($Exe -replace '\.(exe|cmd)$', '').ToUpper()
    $override = [Environment]::GetEnvironmentVariable($key)
    if ($override) {
        if (-not (Test-Path -LiteralPath $override)) { throw "$key 指向的文件不存在：$override" }
        return $override
    }

    if ($DirEnv) {
        $dir = [Environment]::GetEnvironmentVariable($DirEnv)
        if ($dir) {
            $p = Join-Path $dir "bin\$Exe"
            if (Test-Path -LiteralPath $p) { return $p }
        }
    }

    $cmd = Get-Command $Exe -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($cmd) { return $cmd.Source }

    foreach ($g in $Globs) {
        $hit = Get-ChildItem -Path $g -ErrorAction SilentlyContinue |
               Sort-Object Name -Descending | Select-Object -First 1
        if ($hit) {
            $p = Join-Path $hit.FullName "bin\$Exe"
            if (Test-Path -LiteralPath $p) { return $p }
        }
    }

    throw "未找到 $Exe。请将其加入 PATH，或设置 $key（可执行文件路径）/ $DirEnv（安装目录）"
}

$mvn = Resolve-Tool -Exe 'mvn.cmd' -DirEnv 'MAVEN_HOME' -Globs @(
    'C:\DevTools\apache-maven-*',
    'C:\Program Files\apache-maven-*',
    'C:\Program Files (x86)\apache-maven-*',
    "$env:USERPROFILE\apache-maven-*"
)

$java = Resolve-Tool -Exe 'java.exe' -DirEnv 'JAVA_HOME' -Globs @(
    'C:\Program Files\Eclipse Adoptium\jdk-*',
    'C:\Program Files\Java\jdk-*',
    'C:\Program Files\Microsoft\jdk-*',
    'C:\Program Files\Amazon Corretto\jdk*'
)

$node = Resolve-Tool -Exe 'node.exe' -DirEnv 'NODE_HOME' -Globs @(
    'C:\Program Files\nodejs',
    "$env:LOCALAPPDATA\Programs\nodejs*",
    "$env:APPDATA\nvm\v*"
)

# Windows 的环境变量本身不区分大小写，但某些环境（CI、代理注入、容器化终端）会同时
# 塞入 HTTPS_PROXY 和 https_proxy 两个键。Start-Process 重建子进程环境字典时撞到
# 「仅大小写不同」的重复键，会直接抛 ArgumentException 导致服务起不来：
#   已添加项。字典中的关键字:“HTTPS_PROXY”所添加的关键字:“https_proxy”
# 删除操作不区分大小写（删一个会连带另一个），所以只能删一次看一次，循环到无重复为止。
function Remove-CaseDuplicateEnvVar {
    for ($i = 0; $i -lt 20; $i++) {
        $dup = [System.Environment]::GetEnvironmentVariables().Keys |
               Group-Object { $_.ToUpperInvariant() } |
               Where-Object { $_.Count -gt 1 } |
               Select-Object -First 1
        if (-not $dup) { return }
        $target = $dup.Group | Where-Object { $_ -cne $_.ToUpperInvariant() } | Select-Object -First 1
        if (-not $target) { $target = $dup.Group[0] }
        [System.Environment]::SetEnvironmentVariable($target, $null)
    }
}
Remove-CaseDuplicateEnvVar

$serverDir = Join-Path $repoRoot 'server'
$webDir = Join-Path $repoRoot 'web'
$logsDir = Join-Path $repoRoot 'logs'
if (-not (Test-Path -LiteralPath $logsDir)) {
    New-Item -ItemType Directory -Path $logsDir -Force | Out-Null
}

# 服务清单：模块名 / 端口 / 启动类（新增服务只改这里）
$services = @(
    @{ Name = 'gateway';   Port = 8000; Main = 'com.goshare.gateway.GatewayServerApplication' },
    @{ Name = 'user';      Port = 8001; Main = 'com.goshare.user.UserServerApplication' },
    @{ Name = 'trip';      Port = 8002; Main = 'com.goshare.trip.TripServerApplication' },
    @{ Name = 'order';     Port = 8003; Main = 'com.goshare.order.OrderServerApplication' },
    @{ Name = 'marketing'; Port = 8004; Main = 'com.goshare.marketing.MarketingServerApplication' },
    @{ Name = 'payment';   Port = 8005; Main = 'com.goshare.payment.PaymentServerApplication' },
    @{ Name = 'notify';    Port = 8006; Main = 'com.goshare.notify.NotifyServerApplication' },
    @{ Name = 'assistant'; Port = 8007; Main = 'com.goshare.assistant.AssistantServerApplication' }
)
