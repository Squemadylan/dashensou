# install-android-build-install-skill.ps1
# Run this ONCE on this workstation to install the cross-project
# "android-build-install" skill to the user-level Trae skills directory.
# After that, every Android project opened in Trae on this machine can
# invoke the skill automatically.

$ErrorActionPreference = "Stop"

$projectSkill = Join-Path $PSScriptRoot "skills\android-build-install\SKILL.md"
$userSkillDir = Join-Path $env:USERPROFILE ".trae\skills\android-build-install"
$userSkill    = Join-Path $userSkillDir "SKILL.md"

if (-not (Test-Path $projectSkill)) {
    Write-Error "Source skill not found: $projectSkill"
    exit 1
}

New-Item -ItemType Directory -Force -Path $userSkillDir | Out-Null
Copy-Item -Path $projectSkill -Destination $userSkill -Force

Write-Host "Installed to: $userSkill"
Write-Host "Bytes copied: $((Get-Item $userSkill).Length)"
Write-Host ""
Write-Host "You can now invoke this skill from any Android project on this machine."
