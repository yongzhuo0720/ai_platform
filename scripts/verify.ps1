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
#   powershell -ExecutionPolicy Bypass -File scripts/verify.ps1 -SkipE2E     # 跳过浏览器冒烟
#
# 需要预先装好：
#   - JDK 8（Java 21 与项目 Lombok 版本不兼容，README 已说明）
#   - Node.js 22+（单测使用的 jsdom 30 官方要求 ≥22.22）
#   - Playwright 浏览器：cd frontend; npx playwright install chromium
#   - 仓库自带的离线 Maven 依赖 hd_redis/ai-maven/pro
#     注意 hd_redis/ 整个目录被 .gitignore 排除、不入库，新机器必须先拷过去；
#     后端也因此进不了 GitHub Actions，见 .github/workflows/ci.yml 里的注释。
#
# 输出编码：脚本会把 PowerShell 的输入输出编码切到 UTF-8，并给 Maven 的 JVM
# 传 -Dfile.encoding=UTF-8。不改的话中文 Windows（GBK/936）下重定向出来的日志
# 是 GBK 与 UTF-8 混排的——node 那侧输出 UTF-8、PowerShell 按 GBK 解码再写出去，
# 会出现「Bundle: 浣…」这种乱码，事后根本读不了。
#
# ⚠️ 本文件必须保存为「UTF-8 with BOM」。Windows PowerShell 5.1 读没有 BOM 的
# UTF-8 .ps1 时按系统代码页（GBK）解码，中文注释里的字节会破坏引号配对，
# 脚本直接解析失败（报「表达式缺少右」这类看不懂的错误）。编辑器别把 BOM 去掉。

param(
    # JDK 8 路径。默认取环境变量，可用 -JavaHome 覆盖
    [string]$JavaHome = $env:JAVA_HOME,
    # 跳过前端构建以加快迭代（typecheck 仍会跑）
    [switch]$SkipBuild,
    # 跳过浏览器冒烟（本机没装 Playwright 浏览器时用）
    [switch]$SkipE2E
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$failed = @()

# ---------- 统一输出编码 ----------
# PowerShell 用系统代码页解码子进程 stdout、再按同一代码页写出，
# 而 node / vitest 输出的本来就是 UTF-8，两者混在一份日志里就是乱码。
# 切到 UTF-8 后子进程与脚本自身的中文输出才是同一种编码。
try {
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
    $OutputEncoding = [System.Text.Encoding]::UTF8
} catch {
    # 没有真实控制台时赋值会抛异常，忽略：脚本继续跑，只是编码没改
}

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

    # 这个目录不入库（.gitignore 排掉了 hd_redis/），换机器忘了拷，
    # 报错会是 Resolve-Path 的一串英文异常，看不出真正缺什么，所以先给明确提示。
    if (-not (Test-Path "$root/hd_redis/ai-maven/pro")) {
        throw "离线 Maven 仓库缺失：hd_redis/ai-maven/pro。该目录被 .gitignore 排除、不随仓库分发，" +
              "新机器需要先从开发机拷贝整个 hd_redis/ 目录（私有框架 com.heneng.hnsp / blxt.qjava 不在 Maven Central）。"
    }
    $repo = (Resolve-Path "$root/hd_redis/ai-maven/pro").Path
    $settings = (Resolve-Path "$root/hd_redis/ai-maven/conf/settings.xml").Path

    # JDK 8 的 Maven 进程按平台默认编码（中文 Windows 是 GBK）写 stdout，
    # 与上面的 UTF-8 对不上。只在本脚本的进程树里追加，不动用户全局配置。
    if ($env:MAVEN_OPTS -notmatch 'file\.encoding') {
        $env:MAVEN_OPTS = (("$env:MAVEN_OPTS" + " -Dfile.encoding=UTF-8").Trim())
    }

    Push-Location "$root/backend/AIProject"
    try {
        # -o 离线：私有依赖已在本地仓库，不需要联网
        $mvnStartedAt = Get-Date
        mvn -s $settings "-Dmaven.repo.local=$repo" -o test
        if ($LASTEXITCODE -ne 0) { throw "mvn test 退出码 $LASTEXITCODE" }

        # 闸门必须证明「测试真的执行了」。本项目踩过一次：测试方法名撞了 Surefire
        # 的 <exclude> 规则，整个测试类静默不跑，BUILD SUCCESS 照样是绿的。
        # 所以跑完按新生成的 surefire 报告汇总用例数，一份都没有就判失败。
        $reports = @(Get-ChildItem "$root/backend/AIProject" -Recurse -Filter 'TEST-*.xml' `
            -ErrorAction SilentlyContinue | Where-Object { $_.LastWriteTime -ge $mvnStartedAt })
        if ($reports.Count -eq 0) {
            throw "Surefire 没有产出任何测试报告：测试可能被静默跳过（检查测试类是否被 <exclude> 命中、方法名是否撞规则）"
        }
        $cases = 0
        $badCases = 0
        foreach ($report in $reports) {
            [xml]$xml = Get-Content -Raw -Encoding UTF8 $report.FullName
            $cases += [int]$xml.testsuite.tests
            $badCases += [int]$xml.testsuite.failures + [int]$xml.testsuite.errors + [int]$xml.testsuite.skipped
        }
        Write-Host "后端单测汇总：$cases 项 / $($reports.Count) 份报告，失败+错误+跳过 $badCases" -ForegroundColor DarkGray
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

# ---------- 浏览器冒烟 ----------
# playwright.config.ts 本地默认 channel=msedge，用的是本机已装的 Edge，
# 不下载 ms-playwright 浏览器；没有 Edge 时给明确提示，而不是让 Playwright
# 抛一句 "Failed to launch: Chromium distribution 'msedge' is not found"。
if ($SkipE2E) {
    Write-Host ""
    Write-Host "（已跳过浏览器冒烟）" -ForegroundColor DarkGray
} else {
    Step "浏览器冒烟（Playwright）" {
        $edge = @(
            "$env:ProgramFiles\Microsoft\Edge\Application\msedge.exe",
            "${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe"
        ) | Where-Object { Test-Path $_ } | Select-Object -First 1
        if (-not $edge) {
            throw "本机没找到 Microsoft Edge（Playwright 本地默认用它）。" +
                  "要么装 Edge / 设 PLAYWRIGHT_CHANNEL 指向已装浏览器，要么加 -SkipE2E 跳过这一步。"
        }
        Push-Location "$root/frontend"
        try {
            npm run test:e2e
            if ($LASTEXITCODE -ne 0) { throw "浏览器冒烟退出码 $LASTEXITCODE" }
        } finally {
            Pop-Location
        }
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

    Step "产物体积预算" {
        Push-Location "$root/frontend"
        try {
            npm run check:bundle
            if ($LASTEXITCODE -ne 0) { throw "bundle 检查退出码 $LASTEXITCODE" }
        } finally {
            Pop-Location
        }
    }

    # 源码里没有密钥不代表构建产物干净：Vite 会把 VITE_* 原样内联进 dist。
    Step "产物密钥扫描" {
        Push-Location "$root/frontend"
        try {
            npm run check:secrets
            if ($LASTEXITCODE -ne 0) { throw "密钥扫描退出码 $LASTEXITCODE" }
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
