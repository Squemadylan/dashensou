# install-android-build-install-skill.ps1
# 把项目级 .trae/skills/android-build-install/SKILL.md 同步到用户级
# 用途：以后修改了项目级副本后，一键重新发布到本机用户级，让其他 Android 项目也能自动调用。
# 用法：在 dashensou 项目根目录里，PowerShell 执行  .\install-android-build-install-skill.ps1

$ErrorActionPreference = 'Stop'

$projectSkill = Join-Path $PSScriptRoot '.trae\skills\android-build-install\SKILL.md'
$userSkillDir = Join-Path $env:USERPROFILE '.trae\skills\android-build-install'
$userSkill    = Join-Path $userSkillDir 'SKILL.md'

if (-not (Test-Path $projectSkill)) {
    Write-Error "未找到项目级技能文件: $projectSkill`n请确认脚本放在 dashensou 项目根目录下运行。"
    exit 1
}

if (-not (Test-Path $userSkillDir)) {
    New-Item -ItemType Directory -Path $userSkillDir -Force | Out-Null
    Write-Host "已创建用户级目录: $userSkillDir" -ForegroundColor Cyan
}

Copy-Item -Path $projectSkill -Destination $userSkill -Force

Write-Host ""
Write-Host "android-build-install 技能已同步到用户级" -ForegroundColor Green
Write-Host ("  源:   {0}" -f $projectSkill)
Write-Host ("  目标: {0}" -f $userSkill)
Write-Host ""
Write-Host "请重启 Trae SOLO 让技能生效（重启后，本机任何 Android 项目都会自动调用它）。" -ForegroundColor Yellow
