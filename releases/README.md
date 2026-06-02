# DaShenSou APK - 分块下载说明

## 下载步骤

1. 在 Releases 页面下载这 4 个 part 文件
2. 在手机 Termux 或者电脑上运行合并脚本

## 合并脚本 (bash / Termux)

```bash
cat releases/apk_base64_part*.txt > /tmp/dashensou_full.b64
base64 -d /tmp/dashensou_full.b64 > dashensou.apk
```

## Windows PowerShell 合并

```powershell
Get-Content releases\apk_base64_part*.txt -Raw | Out-File -FilePath $env:TEMP\dashensou_full.b64 -NoNewline -Encoding UTF8
[Convert]::FromBase64String((Get-Content $env:TEMP\dashensou_full.b64 -Raw)) | Set-Content dashensou.apk -Encoding Byte
```
