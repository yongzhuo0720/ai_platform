# 本地一键验证脚本
#
# 作用：在提交前把 CI 会查的东西全跑一遍。
# 后端目前进不了 GitHub Actions（私有框架 com.heneng.hnsp / blxt.qjava
# 不在 Maven Central），所以这个脚本是后端质量的唯一门禁，务必每次改完
# 后端代码都跑一次。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts/verify.ps1
#   powershell -ExecutionPolicy Bypass -File scripts/verify.ps1 -SkipBuild   # 跳过前端构建（快很多）
#
# 需要预先装好：
#   - JDK 8（Java 21 与项目 Lombok 版本不兼容，README 已说明）
#   - Node.js 22+（单测使用的 jsdom 30 官方要求 ≥22.22）
#   - 仓库自带的离线 Maven 依赖 hd_redis/ai-maven/pro

param(
    # JDK 8 路径。默认取环境变量，可用 -JavaHome 覆盖
    [string]$JavaHome = $env:JAVA_HOME,
    # 跳过前端构建以加快迭代（typecheck 仍会跑）
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$failed = @()

function Step($name, [scriptblock]$body) {
    Write-Host ""
    Write-Host "=== $name ===" -ForegroundColor Cyan
    try {
        & $body
        Write-Host "通过：$name" -ForegroundColor Green
    } catch {
        Write-Host "失败：$name" -ForegroundColor Red
        Write-Host $_ -ForegroundColor Red
        $script:failed += $name
    }
}

# ---------- 后端 ----------
Step "后端编译 + 单元测试" {
    if ([string]::IsNullOrWhiteSpace($JavaHome)) {
        throw "未指定 JDK 8 路径。请设置 JAVA_HOME 或用 -JavaHome 'D:/DevTools/Java_jdk 1.8' 传入"
    }
    if (-not (Test-Path $JavaHome)) {
        throw "JDK 路径不存在：$JavaHome"
    }
    $env:JAVA_HOME = $JavaHome

    $repo = (Resolve-Path "$root/hd_redis/ai-maven/pro").Path
    $settings = (Resolve-Path "$root/hd_redis/ai-maven/conf/settings.xml").Path

    Push-Location "$root/backend/AIProject"
    try {
        # -o 离线：私有依赖已在本地仓库，不需要联网
        mvn -s $settings "-Dmaven.repo.local=$repo" -o test
        if ($LASTEXITCODE -ne 0) { throw "mvn test 退出码 $LASTEXITCODE" }
    } finally {
        Pop-Location
    }
}

# ---------- 前端 ----------
Step "前端类型检查" {
    Push-Location "$root/frontend"
    try {
        npm run typecheck
        if ($LASTEXITCODE -ne 0) { throw "typecheck 退出码 $LASTEXITCODE" }
    } finally {
        Pop-Location
    }
}

Step "图标注册检查" {
    Push-Location "$root/frontend"
    try {
        npm run check:icons
        if ($LASTEXITCODE -ne 0) { throw "icons 检查退出码 $LASTEXITCODE" }
    } finally {
        Pop-Location
    }
}

Step "前端单元测试（Vitest）" {
    Push-Location "$root/frontend"
    try {
        npm run test
        if ($LASTEXITCODE -ne 0) { throw "前端单测退出码 $LASTEXITCODE" }
    } finally {
        Pop-Location
    }
}

Step "前端组件测试（Vitest + @vue/test-utils）" {
    Push-Location "$root/frontend"
    try {
        npm run test:component
        if ($LASTEXITCODE -ne 0) { throw "组件测试退出码 $LASTEXITCODE" }
    } finally {
        Pop-Location
    }
}

Step "流式回归检查" {
    Push-Location "$root/frontend"
    try {
        npm run check:streaming
        if ($LASTEXITCODE -ne 0) { throw "streaming 检查退出码 $LASTEXITCODE" }
    } finally {
        Pop-Location
    }
}

Step "Markdown / XSS 消毒回归检查" {
    Push-Location "$root/frontend"
    try {
        npm run check:markdown
        if ($LASTEXITCODE -ne 0) { throw "markdown 检查退出码 $LASTEXITCODE" }
    } finally {
        Pop-Location
    }
}

if (-not $SkipBuild) {
    Step "前端生产构建" {
        Push-Location "$root/frontend"
        try {
            npm run build
            if ($LASTEXITCODE -ne 0) { throw "build 退出码 $LASTEXITCODE" }
        } finally {
            Pop-Location
        }
    }
} else {
    Write-Host ""
    Write-Host "（已跳过前端构建）" -ForegroundColor DarkGray
}

# ---------- RAG 评估集自检 ----------
# 离线跑，不需要后端：校验黄金集与语料一致、JS 指标与 Java 实现口径一致
Step "RAG 评估集自检" {
    node "$root/scripts/rag-eval.mjs" --dry-run
    if ($LASTEXITCODE -ne 0) { throw "rag-eval --dry-run 退出码 $LASTEXITCODE" }
    node "$root/scripts/rag-eval.mjs" --self-check
    if ($LASTEXITCODE -ne 0) { throw "rag-eval --self-check 退出码 $LASTEXITCODE" }
}

# ---------- 汇总 ----------
Write-Host ""
if ($failed.Count -eq 0) {
    Write-Host "全部通过" -ForegroundColor Green
    exit 0
} else {
    Write-Host "以下步骤失败：" -ForegroundColor Red
    $failed | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
    exit 1
}
