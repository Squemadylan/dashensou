---
name: "github-cli"
description: "Uses GitHub CLI (gh) to perform GitHub operations: push code, upload releases, manage assets, etc. Invoke whenever user asks for anything GitHub-related."
---

# GitHub CLI Skill

This skill handles all GitHub operations using the GitHub CLI (`gh`).

## When to Use

**MANDATORY: Invoke this skill FIRST whenever:**
- User says "push to GitHub"
- User says "upload debug APK to GitHub Releases"
- User mentions anything related to GitHub operations
- Any GitHub-related task comes up

## Prerequisites

- GitHub CLI (`gh`) must be installed
- Must be logged in (`gh auth login`)
- Git repository must be initialized and have a remote

## Common Commands

### Push Code
```powershell
git add .
git commit -m "commit message"
git push -u origin main
```

### Create/Update Release
```powershell
# Create new release
gh release create v1.0.0 --title "Release v1.0.0" --notes "Release notes"

# Upload assets
gh release upload v1.0.0 "path/to/file.apk"

# Delete old asset first if needed
gh release delete-asset v1.0.0 "old-file.apk" -y

# Edit existing release
gh release edit v1.0.0 --notes-file "release-notes.md" --title "New Title"
```

### Check Status
```powershell
# Check auth status
gh auth status

# View existing releases
gh release list

# View specific release
gh release view v1.0.0
```

## Workflow for Android APK Upload

1. Check if gh is available: `gh --version`
2. Check auth status: `gh auth status`
3. Commit and push latest changes
4. Delete old APK from release if exists
5. Upload new APK
6. Update release description if needed

## Notes

- Always use the project directory as working directory
- Verify remote is set: `git remote -v`
- Use full paths for files when uploading
