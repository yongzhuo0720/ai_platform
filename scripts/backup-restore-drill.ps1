# 数据库备份恢复演练（真实 PostgreSQL）
#
# 作用：用 pg_dump 做一次一致性备份，恢复到一个临时库，逐表比对行数、
# 架构对象数量与关键表内容哈希，全部一致才算通过。用于验证「备份文件真的能恢复」，
# 而不只是「定时任务跑过了」。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts/backup-restore-drill.ps1
#   powershell -ExecutionPolicy Bypass -File scripts/backup-restore-drill.ps1 -DumpOut .tmp\ai-backup.dump
#   powershell -ExecutionPolicy Bypass -File scripts/backup-restore-drill.ps1 -BootSmoke
#
# -BootSmoke 会在数据比对通过后，用恢复库启动一个临时后端（独立端口、独立 Redis
# 库），再通过免登录的对话分享接口读一条预置数据，证明「应用真能跑在恢复库上」，
# 随后停掉进程并销毁演练库。需要本机已有打包好的 jar（可先用 scripts/start.ps1 构建）。
#
# 默认连接本机 Docker 容器 ai-platform-postgres，账号密码从
# backend/AIProject/app/app-start/src/main/resources/application-dev.properties
# 读取（该文件不入库）。脚本不会打印密码。
#
# 安全边界：
#   - 演练库名必须包含 drill 或 restore，或显式传 -Force，避免误删业务库；
#   - 备份文件默认在用完后删除（-DumpOut 可另存，但其中是真实业务数据，注意保管）；
#   - 全程只读源库；所有写操作都发生在演练库；
#   - 冒烟后端的 Redis 走独立 db（默认 12），不碰正在运行实例的 db 11。
#
# ⚠️ 本文件必须保存为「UTF-8 with BOM」。Windows PowerShell 5.1 读没有 BOM 的
# UTF-8 .ps1 时按系统代码页（GBK）解码，中文注释里的字节会破坏引号配对。

param(
    [string]$Container = "ai-platform-postgres",
    [string]$Database = "ai",
    [string]$DrillDatabase = "ai_restore_drill",
    [string]$ConfigFile,
    [string]$DumpOut,
    [switch]$Force,
    [switch]$KeepDrillDatabase,
    [switch]$BootSmoke,
    [string]$Jar,
    [string]$JavaHome = $env:JAVA_HOME,
    [int]$SmokePort = 8599,
    [int]$SmokeRedisDb = 12
)

$ErrorActionPreference = "Stop"

# PowerShell 5.1 默认按系统代码页（中文 Windows 为 GBK）写控制台，
# 而 psql 输出是 UTF-8；不统一的话重定向到日志就是乱码。
try {
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
    $OutputEncoding = [System.Text.Encoding]::UTF8
} catch {
    # 没有真实控制台时赋值会抛异常，忽略：脚本继续跑，只是编码没改
}

$root = Split-Path -Parent $PSScriptRoot
if (-not $ConfigFile) {
    $ConfigFile = Join-Path $root "backend\AIProject\app\app-start\src\main\resources\application-dev.properties"
}

function Fail($message) {
    Write-Host "失败：$message" -ForegroundColor Red
    exit 1
}

function Assert-DbIdentifier($name, $label) {
    if ($name -notmatch '^[A-Za-z_][A-Za-z0-9_]*$') {
        Fail "$label 含非法字符：$name"
    }
}

function Invoke-Psql([string]$sql, [string]$db) {
    # psql 的 NOTICE 走 stderr。PowerShell 5.1 在 $ErrorActionPreference=Stop 且
    # 重定向 2>&1 时会把原生命令的 stderr 当成终止错误（NativeCommandError），
    # 于是 "drop database if exists" 这种带 NOTICE 的成功语句反而会把脚本打挂。
    # 这里先降级错误偏好，拿到退出码后再按需抛错；NOTICE 行本身从返回值里剔除。
    #
    # SQL 走标准输入而不是 -c：PowerShell 5.1 给原生程序传参时会丢掉参数内的
    # 双引号，而驼峰列名（"shareToken"）必须带引号，否则会被折叠成小写。
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = $sql | & docker exec -i -e PGPASSWORD $Container psql -w -U $script:dbUser -d $db -tA -v ON_ERROR_STOP=1 2>&1
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    if ($code -ne 0) {
        throw "psql 执行失败（库 $db）：$output"
    }
    return @($output |
        ForEach-Object { "$_".Trim() } |
        Where-Object { $_ -ne "" -and $_ -notmatch '^(NOTICE|WARNING|INFO):' })
}

# ---------- 前置检查 ----------
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Fail "未找到 docker 命令"
}
$running = ""
$previous = $ErrorActionPreference
$ErrorActionPreference = "Continue"
try {
    $running = & docker inspect -f "{{.State.Running}}" $Container 2>$null
} finally {
    $ErrorActionPreference = $previous
}
if ("$running".Trim() -ne "true") {
    Fail "容器 $Container 未运行"
}
if (-not (Test-Path -LiteralPath $ConfigFile)) {
    Fail "配置文件不存在：$ConfigFile"
}

$jarPath = $null
$javaExe = $null
if ($BootSmoke) {
    if (-not $Jar) {
        $Jar = Join-Path $root "backend\AIProject\app\app-start\target\app-start-1.0-SNAPSHOT.jar"
    }
    if (-not (Test-Path -LiteralPath $Jar)) {
        Fail "找不到后端 jar：$Jar（先运行 scripts/start.ps1 构建）"
    }
    $jarPath = (Resolve-Path -LiteralPath $Jar).Path
    if ([string]::IsNullOrWhiteSpace($JavaHome) -or -not (Test-Path (Join-Path $JavaHome "bin\java.exe"))) {
        Fail "未找到 JDK 8，请设置 JAVA_HOME 或传 -JavaHome"
    }
    $javaExe = (Resolve-Path (Join-Path $JavaHome "bin\java.exe")).Path
    if ($SmokePort -lt 1 -or $SmokePort -gt 65535) {
        Fail "SmokePort 非法：$SmokePort"
    }
    if ($SmokeRedisDb -lt 0) {
        Fail "SmokeRedisDb 非法：$SmokeRedisDb"
    }
    $listening = netstat -ano | Select-String ":$SmokePort\s.*LISTENING"
    if ($listening) {
        Fail "冒烟端口 $SmokePort 已被占用，换一个 -SmokePort"
    }
}

$props = @{}
Get-Content -LiteralPath $ConfigFile -Encoding UTF8 | ForEach-Object {
    if ($_ -match '^\s*([A-Za-z0-9_.]+)\s*=\s*(.*)$') {
        $key = $Matches[1]
        $value = $Matches[2].Trim()
        # 支持 ${ENV_VAR:default} 占位符，取默认值
        if ($value -match '^\$\{[^:}]+\:(.*)\}$') {
            $value = $Matches[1]
        }
        $props[$key] = $value
    }
}

$script:dbUser = $props["hnsp.datasource.username"]
$dbPass = $props["hnsp.datasource.password"]
if (-not $script:dbUser -or -not $dbPass) {
    Fail "配置文件中缺少 hnsp.datasource.username / password"
}

Assert-DbIdentifier $Database "源库名"
Assert-DbIdentifier $DrillDatabase "演练库名"
Assert-DbIdentifier $script:dbUser "数据库账号"
if ($DrillDatabase -eq $Database) {
    Fail "演练库不能与源库同名"
}
if ($DrillDatabase -notmatch '(?i)(drill|restore)' -and -not $Force) {
    Fail "演练库名 $DrillDatabase 不含 drill/restore；确认无风险后加 -Force"
}

$dumpInContainer = "/tmp/$DrillDatabase.dump"
$oldPassword = $env:PGPASSWORD
$drillCreated = $false
$failed = $false
$smokeProc = $null
$smokeLogDir = $null
$smokeToken = $null
$smokeTitle = $null
$smokeRowInserted = $false

Write-Host "=== 数据库备份恢复演练 ===" -ForegroundColor Cyan
Write-Host "容器=$Container 源库=$Database 演练库=$DrillDatabase 账号=$script:dbUser"

try {
    $env:PGPASSWORD = $dbPass

    # ---------- 1. 备份 ----------
    & docker exec -e PGPASSWORD $Container sh -c "rm -f $dumpInContainer && pg_dump -w -U $script:dbUser -d $Database -Fc -f $dumpInContainer"
    if ($LASTEXITCODE -ne 0) {
        throw "pg_dump 失败"
    }
    $dumpBytes = "$(& docker exec $Container stat -c %s $dumpInContainer)".Trim()
    Write-Host "备份完成：$dumpBytes 字节" -ForegroundColor Green

    if ($DumpOut) {
        $dumpDir = Split-Path -Parent $DumpOut
        if ($dumpDir -and -not (Test-Path -LiteralPath $dumpDir)) {
            New-Item -ItemType Directory -Path $dumpDir | Out-Null
        }
        & docker cp "${Container}:$dumpInContainer" $DumpOut
        if ($LASTEXITCODE -ne 0) {
            throw "docker cp 备份文件失败"
        }
        Write-Host "备份文件已另存：$DumpOut（含真实业务数据，请妥善保管）" -ForegroundColor Yellow
    }

    # ---------- 2. 重建演练库 ----------
    Invoke-Psql "select pg_terminate_backend(pid) from pg_stat_activity where datname = '$DrillDatabase' and pid <> pg_backend_pid();" "postgres" | Out-Null
    Invoke-Psql "drop database if exists ""$DrillDatabase"";" "postgres" | Out-Null
    Invoke-Psql "create database ""$DrillDatabase"" owner ""$script:dbUser"";" "postgres" | Out-Null
    $drillCreated = $true

    # ---------- 3. 恢复 ----------
    & docker exec -e PGPASSWORD $Container pg_restore -w -U $script:dbUser -d $DrillDatabase --no-owner --exit-on-error $dumpInContainer
    if ($LASTEXITCODE -ne 0) {
        throw "pg_restore 失败"
    }
    Write-Host "恢复完成" -ForegroundColor Green

    # ---------- 4. 逐表行数 ----------
    $countSql = "select relname || '|' || (xpath('/row/c/text()', query_to_xml(format('select count(*) as c from %I', relname), false, true, '')))[1]::text from pg_stat_user_tables order by relname;"
    $sourceCounts = Invoke-Psql $countSql $Database
    $restoreCounts = Invoke-Psql $countSql $DrillDatabase
    $countDiff = Compare-Object $sourceCounts $restoreCounts
    if ($countDiff) {
        $countDiff | ForEach-Object { Write-Host "  差异 $($_.SideIndicator) $($_.InputObject)" }
        throw "逐表行数不一致"
    }
    $tableCount = $sourceCounts.Count
    $rowTotal = 0
    foreach ($line in $sourceCounts) {
        $rowTotal += [long](($line -split '\|')[1])
    }
    Write-Host "逐表行数一致：$tableCount 张表 / $rowTotal 行" -ForegroundColor Green

    # ---------- 5. 架构对象 ----------
    $schemaSql = "select (select count(*) from pg_indexes where schemaname='public') || '|' || (select count(*) from pg_constraint c join pg_namespace n on n.oid = c.connamespace where n.nspname='public') || '|' || (select count(*) from information_schema.sequences where sequence_schema='public');"
    $sourceSchema = (Invoke-Psql $schemaSql $Database) -join ""
    $restoreSchema = (Invoke-Psql $schemaSql $DrillDatabase) -join ""
    if ($sourceSchema -ne $restoreSchema) {
        throw "架构对象数量不一致：源=$sourceSchema 恢复=$restoreSchema"
    }
    Write-Host "架构对象一致（索引|约束|序列）：$sourceSchema" -ForegroundColor Green

    # ---------- 6. 关键表内容哈希 ----------
    $hashTables = @("agentbean", "agentrunbean", "agentstepbean", "aicalllogbean",
                    "kbchunkbean", "kbdocbean", "modelsconfigbean", "notificationbean",
                    "ragevalrunbean")
    $sourceTables = Invoke-Psql "select relname from pg_stat_user_tables where schemaname='public';" $Database
    $hashParts = @()
    foreach ($table in $hashTables) {
        if ($sourceTables -contains $table) {
            $hashParts += "select '$table|' || md5(coalesce(string_agg(t::text, chr(10) order by t::text), '')) from $table t"
        }
    }
    if ($hashParts.Count -gt 0) {
        $hashSql = $hashParts -join " union all "
        $sourceHashes = Invoke-Psql $hashSql $Database
        $restoreHashes = Invoke-Psql $hashSql $DrillDatabase
        $hashDiff = Compare-Object $sourceHashes $restoreHashes
        if ($hashDiff) {
            $hashDiff | ForEach-Object { Write-Host "  差异 $($_.SideIndicator) $($_.InputObject)" }
            throw "关键表内容哈希不一致"
        }
        Write-Host "关键表内容哈希一致：$($hashParts.Count) 张表" -ForegroundColor Green
    } else {
        Write-Host "未找到关键表，跳过内容哈希比对" -ForegroundColor Yellow
    }

    # ---------- 7. 序列与主键对齐 ----------
    # 数据迁移时若显式写入主键而没同步序列，应用新建记录会主键冲突。
    # 这类问题备份/恢复本身发现不了，但一跑写接口就炸，所以在这里显式检查。
    $seqSql = @"
with tables as (
  select c.relname as table_name
  from pg_class c
  join pg_namespace n on n.oid = c.relnamespace
  where c.relkind = 'r' and n.nspname = 'public'
    and exists (select 1 from information_schema.columns col
                where col.table_schema = 'public' and col.table_name = c.relname and col.column_name = 'iid')
)
select t.table_name || '|' || s.sequencename || '|' || s.last_value || '|' ||
       (xpath('/row/max/text()', query_to_xml(format('select coalesce(max(iid),0) as max from %I', t.table_name), false, true, '')))[1]::text
from tables t
join pg_sequences s on s.schemaname = 'public' and s.sequencename = t.table_name || '_iid_seq'
order by t.table_name;
"@
    $seqBehind = @()
    foreach ($line in (Invoke-Psql $seqSql $DrillDatabase)) {
        $parts = "$line" -split '\|'
        if ($parts.Count -lt 4 -or $parts[2] -eq "" -or $parts[3] -eq "") { continue }
        if ([long]$parts[2] -lt [long]$parts[3]) {
            $seqBehind += "$($parts[0])（序列=$($parts[2])，最大iid=$($parts[3])）"
        }
    }
    if ($seqBehind.Count -gt 0) {
        throw "恢复库序列落后于主键，应用将无法新建记录：$($seqBehind -join '；')"
    }
    Write-Host "序列与主键对齐一致" -ForegroundColor Green

    # ---------- 8. 应用启动冒烟（可选） ----------
    if ($BootSmoke) {
        $smokeTitle = "restore-drill-smoke"
        $smokeToken = "drill" + [guid]::NewGuid().ToString("N")
        Invoke-Psql "insert into ""chatsharebean"" (""shareToken"", title, payload, ""userId"", lastuser) values ('$smokeToken', '$smokeTitle', '{""messages"":[]}', '__drill__', '__drill__');" $DrillDatabase | Out-Null
        $smokeRowInserted = $true
        # 写入成功不代表读得到：iid 序列落后于 max(iid) 时这里会主键冲突，
        # 而 psql 默认遇到 SQL 错误仍返回 0。上面已加 ON_ERROR_STOP=1，
        # 这里再确认一次，避免后续把「写入失败」误报成「读接口有问题」。
        $inserted = (Invoke-Psql "select count(*) from ""chatsharebean"" where ""shareToken"" = '$smokeToken';" $DrillDatabase) -join ""
        if ($inserted -ne "1") {
            throw "冒烟预置数据写入失败：查询到 $inserted 行"
        }

        $smokeLogDir = Join-Path ([System.IO.Path]::GetTempPath()) ("ai-restore-smoke-" + [guid]::NewGuid().ToString("N"))
        New-Item -ItemType Directory -Path $smokeLogDir | Out-Null
        $stdoutLog = Join-Path $smokeLogDir "stdout.log"
        $stderrLog = Join-Path $smokeLogDir "stderr.log"
        $dbUrl = "jdbc:postgresql://127.0.0.1:5432/${DrillDatabase}?currentSchema=public"
        Write-Host "启动冒烟后端：port=$SmokePort redisDb=$SmokeRedisDb 库=$DrillDatabase" -ForegroundColor Cyan
        $smokeStart = Get-Date
        $smokeProc = Start-Process -FilePath $javaExe `
            -ArgumentList @("-jar", $jarPath,
                "--server.port=$SmokePort",
                "--spring.datasource.url=$dbUrl",
                "--spring.redis.database=$SmokeRedisDb") `
            -WorkingDirectory $smokeLogDir `
            -RedirectStandardOutput $stdoutLog `
            -RedirectStandardError $stderrLog `
            -WindowStyle Hidden -PassThru

        $health = $null
        $healthLastError = $null
        $deadline = (Get-Date).AddSeconds(180)
        while ((Get-Date) -lt $deadline) {
            if ($smokeProc.HasExited) {
                throw "冒烟后端启动即退出（exit=$($smokeProc.ExitCode)）"
            }
            try {
                # 这里的响应没有 charset，PowerShell 5.1 的 Invoke-WebRequest 会返回
                # byte[]，pip 给 ConvertFrom-Json 解析不出 status；Invoke-RestMethod
                # 直接给对象，绕开这个坑。
                $health = Invoke-RestMethod -Uri "http://127.0.0.1:$SmokePort/ai/actuator/health" `
                    -TimeoutSec 3
                if ($health.status -eq "UP") { break }
            } catch {
                # 端口未监听 / 应用仍在启动 / health 暂时 DOWN：继续等
                $healthLastError = $_.Exception.Message
            }
            Start-Sleep -Seconds 2
        }
        if (-not $health -or $health.status -ne "UP") {
            throw "冒烟后端在 180 秒内未达到 health=UP（最后错误：$healthLastError）"
        }
        $bootSeconds = [math]::Round(((Get-Date) - $smokeStart).TotalSeconds, 1)
        Write-Host "冒烟后端已就绪：health=UP（$bootSeconds 秒）" -ForegroundColor Green

        # 免登录分享接口会走 Controller → Holder → Mapper → 恢复库，
        # 且明确要求抹掉作者字段，顺带验证脱敏没有退化。
        $body = @{ data = @{ shareToken = $smokeToken } } | ConvertTo-Json -Depth 5 -Compress
        $share = Invoke-RestMethod -Uri "http://127.0.0.1:$SmokePort/ai/api/chatShare/get" `
            -Method Post -ContentType "application/json; charset=utf-8" `
            -Body $body -TimeoutSec 15
        if ($share.resp_code -ne 0) {
            throw "分享读接口返回失败：resp_code=$($share.resp_code) resp_msg=$($share.resp_msg)"
        }
        if (-not $share.datas -or $share.datas.title -ne $smokeTitle) {
            throw "分享读接口没有读到恢复库中的预置记录"
        }
        if ($null -ne $share.datas.userId -or $null -ne $share.datas.lastuser) {
            throw "免登录分享接口泄露了作者字段"
        }
        Write-Host "恢复库读链路通过：应用读到了预置记录，且作者字段已抹除" -ForegroundColor Green
    }

    Write-Host ""
    Write-Host "演练通过：备份文件可以完整恢复" -ForegroundColor Green
} catch {
    $failed = $true
    Write-Host "演练失败：$($_.Exception.Message)" -ForegroundColor Red
    if ($smokeLogDir -and (Test-Path -LiteralPath $smokeLogDir)) {
        foreach ($f in @($stdoutLog, $stderrLog)) {
            if ($f -and (Test-Path -LiteralPath $f)) {
                Write-Host "--- $(Split-Path $f -Leaf) 末尾 ---" -ForegroundColor Yellow
                Get-Content -LiteralPath $f -Tail 60
            }
        }
        Write-Host "冒烟日志保留在：$smokeLogDir" -ForegroundColor Yellow
    }
} finally {
    if ($smokeProc -and -not $smokeProc.HasExited) {
        try {
            Stop-Process -Id $smokeProc.Id -Force -ErrorAction Stop
            Wait-Process -Id $smokeProc.Id -Timeout 15 -ErrorAction SilentlyContinue
            Write-Host "冒烟后端已停止" -ForegroundColor Green
        } catch {
            Write-Host "停止冒烟后端失败：$($_.Exception.Message)" -ForegroundColor Yellow
        }
    }
    if ($smokeRowInserted -and $drillCreated) {
        try {
            Invoke-Psql "delete from ""chatsharebean"" where ""shareToken"" = '$smokeToken';" $DrillDatabase | Out-Null
        } catch {
            Write-Host "清理冒烟数据失败：$($_.Exception.Message)" -ForegroundColor Yellow
        }
    }
    if ($smokeLogDir -and (Test-Path -LiteralPath $smokeLogDir) -and -not $failed) {
        try {
            Remove-Item -LiteralPath $smokeLogDir -Recurse -Force
        } catch {
            Write-Host "清理冒烟日志失败（可手动删除 $smokeLogDir）：$($_.Exception.Message)" -ForegroundColor Yellow
        }
    }
    if ($drillCreated -and -not $KeepDrillDatabase) {
        try {
            Invoke-Psql "select pg_terminate_backend(pid) from pg_stat_activity where datname = '$DrillDatabase' and pid <> pg_backend_pid();" "postgres" | Out-Null
            Invoke-Psql "drop database if exists ""$DrillDatabase"";" "postgres" | Out-Null
        } catch {
            Write-Host "清理演练库失败：$($_.Exception.Message)" -ForegroundColor Yellow
        }
    }
    try {
        $previous = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        & docker exec $Container rm -f $dumpInContainer 2>$null | Out-Null
    } catch {
        Write-Host "清理容器内备份失败：$($_.Exception.Message)" -ForegroundColor Yellow
    } finally {
        $ErrorActionPreference = $previous
    }
    if ($null -eq $oldPassword) {
        Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
    } else {
        $env:PGPASSWORD = $oldPassword
    }
}

if ($failed) {
    exit 1
}
exit 0