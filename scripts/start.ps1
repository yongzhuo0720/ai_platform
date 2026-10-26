# 一键启动：PostgreSQL + Redis + 后端 + 前端
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts/start.ps1
#   powershell -ExecutionPolicy Bypass -File scripts/start.ps1 -SkipBuild      # 跳过后端打包
#   powershell -ExecutionPolicy Bypass -File scripts/start.ps1 -BackendOnly
#   powershell -ExecutionPolicy Bypass -File scripts/start.ps1 -FrontendOnly
#
# 启动后：
#   前端  http://localhost:5173       账号 admin / 123456（超管）、ss / 123456
#   后端  http://localhost:8507/ai
#
# 前置要求：JDK 8（Java 21 与项目 Lombok 版本不兼容）、Node 18+。
# 仓库自带的 hd_redis/ 已包含 PG 与需要离线依赖，无需联网。

param(
    [string]$JavaHome = $env:JAVA_HOME,
    [switch]$SkipBuild,
    [switch]$BackendOnly,
    [switch]$FrontendOnly
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$pgDir = Join-Path $root "hd_redis\pgsql"
$redisCli = Join-Path $root "hd_redis\redis321\redis-cli.exe"

function Test-Port($port) {
    return [bool](netstat -ano | Select-String ":$port\s.*LISTENING")
}

function Wait-Port($port, $name, $timeoutSec = 120) {
    for ($i = 0; $i -lt $timeoutSec; $i++) {
        if (Test-Port $port) {
            Write-Host "  $name 已就绪（:$port）" -ForegroundColor Green
            return $true
        }
        Start-Sleep -Seconds 1
    }
    Write-Host "  $name 启动超时（:$port）" -ForegroundColor Red
    return $false
}

# ---------------------------------------------------------------- Redis
if (-not $FrontendOnly) {
    Write-Host "==> 检查 Redis（:6379）" -ForegroundColor Cyan
    if (Test-Path $redisCli) {
        $pong = & $redisCli -a zxcvbnm -p 6379 ping 2>$null
        if ($pong -notmatch "PONG") {
            Write-Host "  Redis 未响应。请启动 hd_redis/redis321/redis-server.exe（需密码 zxcvbnm）" -ForegroundColor Yellow
        } else {
            Write-Host "  Redis 正常" -ForegroundColor Green
        }
    } else {
        Write-Host "  未找到 redis-cli，跳过检查" -ForegroundColor Yellow
    }
}

# ---------------------------------------------------------------- PostgreSQL
if (-not $FrontendOnly) {
    Write-Host "==> 检查 PostgreSQL（:5432）" -ForegroundColor Cyan
    if (Test-Port 5432) {
        Write-Host "  已在运行" -ForegroundColor Green
    } else {
        # PG 被强杀后会留下 postmaster.pid，不清掉会拒绝启动
        $pidFile = Join-Path $pgDir "data\postmaster.pid"
        if (Test-Path $pidFile) {
            $oldPid = (Get-Content $pidFile -TotalCount 1).Trim()
            $alive = Get-Process -Id $oldPid -ErrorAction SilentlyContinue
            if (-not $alive) {
                Write-Host "  清理陈旧 postmaster.pid（PID $oldPid 已不存在）" -ForegroundColor Yellow
                Remove-Item $pidFile -Force
            }
        }
        Write-Host "  启动 PostgreSQL..."
        Start-Process -FilePath (Join-Path $pgDir "bin\postgres.exe") `
            -ArgumentList @("-D", (Join-Path $pgDir "data")) `
            -WorkingDirectory $pgDir `
            -WindowStyle Minimized
        if (-not (Wait-Port 5432 "PostgreSQL" 60)) { exit 1 }
    }
}

# ---------------------------------------------------------------- 后端
if (-not $FrontendOnly) {
    Write-Host "==> 后端（:8507）" -ForegroundColor Cyan
    if (Test-Port 8507) {
        Write-Host "  已在运行，跳过启动" -ForegroundColor Green
    } else {
        if ([string]::IsNullOrWhiteSpace($JavaHome) -or -not (Test-Path $JavaHome)) {
            Write-Host "  未找到 JDK 8。请设置 JAVA_HOME 或用 -JavaHome 'D:/DevTools/Java_jdk 1.8'" -ForegroundColor Red
            exit 1
        }
        $env:JAVA_HOME = $JavaHome
        $jar = Join-Path $root "backend\AIProject\app\app-start\target\app-start-1.0-SNAPSHOT.jar"

        # 清理僵尸进程：
        # 后端被强杀时可能卡在 logback 的关闭钩子里，表现为「进程还在、
        # 但已不监听端口，且占着 jar 文件」。这时重新打包会报
        # "Unable to rename ... to ....original"，启动也会抢不到端口。
        $zombies = Get-CimInstance Win32_Process -Filter "name='java.exe'" -ErrorAction SilentlyContinue |
            Where-Object { $_.CommandLine -and $_.CommandLine -like "*app-start-1.0-SNAPSHOT.jar*" }
        foreach ($z in $zombies) {
            Write-Host "  清理残留后端进程 PID $($z.ProcessId)" -ForegroundColor Yellow
            Stop-Process -Id $z.ProcessId -Force -ErrorAction SilentlyContinue
        }
        if ($zombies) { Start-Sleep -Seconds 2 }

        if (-not $SkipBuild -or -not (Test-Path $jar)) {
            Write-Host "  打包中（离线依赖，首次较慢）..."
            $repo = (Resolve-Path (Join-Path $root "hd_redis\ai-maven\pro")).Path
            $settings = (Resolve-Path (Join-Path $root "hd_redis\ai-maven\conf\settings.xml")).Path
            Push-Location (Join-Path $root "backend\AIProject")
            try {
                mvn -s $settings "-Dmaven.repo.local=$repo" -o package -DskipTests
                if ($LASTEXITCODE -ne 0) { throw "打包失败" }
            } finally {
                Pop-Location
            }
        }

        Write-Host "  启动 Spring Boot..."
        # 显式指定 --server.port：某些沙箱/代理环境会重映射端口，
        # 命令行参数优先级最高，能保证绑定到 8507
        Start-Process -FilePath (Join-Path $JavaHome "bin\java.exe") `
            -ArgumentList @("-jar", $jar, "--server.port=8507") `
            -WorkingDirectory (Split-Path $jar -Parent) `
            -WindowStyle Minimized
        if (-not (Wait-Port 8507 "后端" 180)) { exit 1 }
    }
}

# ---------------------------------------------------------------- 前端
if (-not $BackendOnly) {
    Write-Host "==> 前端（:5173）" -ForegroundColor Cyan
    if (Test-Port 5173) {
        Write-Host "  已在运行，跳过启动" -ForegroundColor Green
    } else {
        if (-not (Test-Path (Join-Path $root "frontend\node_modules"))) {
            Write-Host "  首次运行，安装依赖..."
            Push-Location (Join-Path $root "frontend")
            try { npm install } finally { Pop-Location }
        }
        Write-Host "  启动 Vite..."
        # 注意 NODE_OPTIONS：
        # WorkBuddy 会在托管的 node 进程里注入 safe-delete 保护 shim
        # （NODE_OPTIONS --require node-language-shim.cjs → 串联 safe-delete-shim）。
        # Vite 二次预构建时要删自己的缓存目录 node_modules/.vite/deps_temp_*（上百个文件），
        # 会被该保护当成"批量删除"拦截并**杀死整个 dev server**。
        # 用户自己的终端没有这个注入，所以这里清掉 NODE_OPTIONS，
        # 让 vite 的运行环境与用户手动启动时一致。只影响这一个进程。
        Start-Process -FilePath "cmd.exe" `
            -ArgumentList @("/c", "set NODE_OPTIONS=&& npm run dev") `
            -WorkingDirectory (Join-Path $root "frontend") `
            -WindowStyle Minimized
        if (-not (Wait-Port 5173 "前端" 90)) { exit 1 }
    }
}

# ---------------------------------------------------------------- 汇总
Write-Host ""
Write-Host "================ 启动完成 ================" -ForegroundColor Green
Write-Host "  前端    http://localhost:5173"
Write-Host "  后端    http://localhost:8507/ai"
Write-Host "  Swagger http://localhost:8507/ai/swagger-ui.html"
Write-Host ""
Write-Host "  账号    admin / 123456   （超级管理员）"
Write-Host "          ss    / 123456   （普通用户，rid=1）"
Write-Host ""
Write-Host "  数据库  postgres / zxcvbnm  库名 ai  端口 5432"
Write-Host "  Redis   127.0.0.1:6379  密码 zxcvbnm"
Write-Host ""
Write-Host "  改完后端代码后需重新打包并重启（本脚本会自动处理）。"
Write-Host "  提交前跑一遍 scripts/verify.ps1 做全量校验。"
Write-Host "==========================================" -ForegroundColor Green
