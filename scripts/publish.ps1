# publish.ps1 - 把本地仓库发布到 GitHub，并创建 v1.00 发行版
#
# 用法（需要先有 GitHub 个人访问令牌，scope 勾 repo）：
#   $env:GH_TOKEN = "ghp_xxx"
#   .\scripts\publish.ps1 -Owner <你的用户名> -Repo serein-chat
#
# 脚本会：
#   1. 把 README 里的 OWNER/REPO 占位符替换成真实仓库
#   2. 创建公开仓库并推送 main 与 v1.00 标签
#   3. 创建 Release v1.00，上传 out\SereinChat-1.00.apk
param(
    [Parameter(Mandatory = $true)][string]$Owner,
    [Parameter(Mandatory = $true)][string]$Repo,
    [string]$Token = $env:GH_TOKEN,
    [string]$ApkPath = (Join-Path $PSScriptRoot '..\out\SereinChat-1.00.apk'),
    [string]$NotesPath = (Join-Path $PSScriptRoot '..\docs\RELEASE-v1.00.md')
)

$ErrorActionPreference = 'Stop'
$Root = Resolve-Path (Join-Path $PSScriptRoot '..')

if (-not $Token) { throw '缺少 GitHub 令牌：先设置 $env:GH_TOKEN' }
if (-not (Test-Path $ApkPath)) { throw "找不到 APK：$ApkPath（先运行 .\build.ps1）" }

$Headers = @{
    Authorization          = "Bearer $Token"
    Accept                 = 'application/vnd.github+json'
    'X-GitHub-Api-Version' = '2022-11-28'
    'User-Agent'           = 'serein-chat-publish'
}

# 1) README 徽章占位符替换
$readme = Join-Path $Root 'README.md'
$text = [System.IO.File]::ReadAllText($readme)
$text = $text.Replace('OWNER/REPO', "$Owner/$Repo").Replace('OWNER%2FREPO', "$Owner%2F$Repo").Replace('OWNER.REPO', "$Owner.$Repo")
[System.IO.File]::WriteAllText($readme, $text, (New-Object System.Text.UTF8Encoding($false)))

Push-Location $Root
try {
    # 2) 创建仓库（已存在则忽略 422）
    $body = @{ name = $Repo; private = $false; description = 'Serein Chat — 纯原生 Android AI 聊天客户端（双协议直连，无中转）' } | ConvertTo-Json
    try {
        Invoke-RestMethod -Method Post -Uri 'https://api.github.com/user/repos' -Headers $Headers -Body $body -ContentType 'application/json' | Out-Null
        Write-Host "已创建仓库 $Owner/$Repo"
    } catch {
        Write-Host "仓库可能已存在，继续：$($_.Exception.Message)"
    }

    # 3) 推送
    git remote remove origin 2>$null
    git remote add origin "https://github.com/$Owner/$Repo.git"
    git add -A
    git commit -m 'docs: 徽章指向真实仓库' 2>$null | Out-Null
    git -c core.quotepath=false push -u origin main
    git push origin v1.00 --force

    # 4) 发行版
    $tag = 'v1.00'
    $notes = if (Test-Path $NotesPath) { [System.IO.File]::ReadAllText($NotesPath) } else { 'Serein Chat v1.00' }
    $release = @{ tag_name = $tag; name = 'Serein Chat v1.00'; body = $notes; draft = $false; prerelease = $false } | ConvertTo-Json
    $rel = Invoke-RestMethod -Method Post -Uri "https://api.github.com/repos/$Owner/$Repo/releases" -Headers $Headers -Body $release -ContentType 'application/json'
    Write-Host "已创建发行版：$($rel.html_url)"

    # 5) 上传 APK
    $name = Split-Path $ApkPath -Leaf
    $upload = "https://uploads.github.com/repos/$Owner/$Repo/releases/$($rel.id)/assets?name=$name"
    $upHeaders = $Headers.Clone()
    $upHeaders['Content-Type'] = 'application/vnd.android.package-archive'
    $res = Invoke-RestMethod -Method Post -Uri $upload -Headers $upHeaders -InFile $ApkPath
    Write-Host "已上传资产：$($res.browser_download_url)"
} finally {
    Pop-Location
}
