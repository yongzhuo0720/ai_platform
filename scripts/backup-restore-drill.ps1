# 数据库备份恢复演练（真实 PostgreSQL）
#
# 作用：用 pg_dump 做一次一致性备份，恢复到一个临时库，逐表比对行数、
# 架构对象数量与关键表内容哈希，全部一致才算通过。用于验证「备份文件真的能恢复」，
# 而不只是「定时任务跑过了」。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts/backup-restore-drill.ps1
#   powershell -ExecutionPolicy Bypass -File scripts/backup-restore-drill.ps1 -DumpOut .tmp\ai-backup.dump
#
# 默认连接本机 Docker 容器 ai-platform-postgres，账号密码从
# backend/AIProject/app/app-start/src/main/resources/application-dev.properties
# 读取（该文件不入库）。脚本不会打印密码。
#
# 安全边界：
#   - 演练库名必须包含 drill 或 restore，或显式传 -Force，避免误删业务库；
#   - 备份文件默认在用完后删除（-DumpOut 可另存，但其中是真实业务数据，注意保管）；
#   - 全程只读源库；所有写操作都发生在演练库。
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
    [switch]$KeepDrillDatabase
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
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = & docker exec -e PGPASSWORD $Container psql -w -U $script:dbUser -d $db -tAc $sql 2>&1
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

    Write-Host ""
    Write-Host "演练通过：备份文件可以完整恢复" -ForegroundColor Green
} catch {
    $failed = $true
    Write-Host "演练失败：$($_.Exception.Message)" -ForegroundColor Red
} finally {
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