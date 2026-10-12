# ============================================================
# RuoYi-Vue-Plus 接口加密 RSA 密钥生成脚本
# ============================================================

# 检查 openssl
if (-not (Get-Command openssl -ErrorAction SilentlyContinue)) {
    Write-Host ""
    Write-Host "[错误] 未找到 openssl 命令，请先安装 OpenSSL 并添加到系统 PATH。" -ForegroundColor Red
    Write-Host "下载地址：https://slproweb.com/products/Win32OpenSSL.html"
    Write-Host ""
    Read-Host "按回车键退出"
    exit 1
}

# 创建临时目录
$tempDir = Join-Path $env:TEMP ("rsa_keys_" + (Get-Random))
New-Item -ItemType Directory -Path $tempDir -Force | Out-Null
Push-Location $tempDir

Write-Host ""
Write-Host "============================================================"
Write-Host "  正在生成 RuoYi-Vue-Plus 接口加密所需的 RSA 密钥对"
Write-Host "============================================================"
Write-Host ""

# ============ 第一对：请求加密密钥对 ============
Write-Host "[1/4] 生成请求加密私钥（PKCS#1）..."
openssl genrsa -out req_private_pkcs1.pem 1024 2>$null

Write-Host "[2/4] 转换为 PKCS#8 格式..."
openssl pkcs8 -topk8 -inform PEM -in req_private_pkcs1.pem -outform PEM -nocrypt -out req_private_pkcs8.pem 2>$null

Write-Host "[3/4] 提取请求加密公钥..."
openssl rsa -in req_private_pkcs1.pem -pubout -out req_public.pem 2>$null

# ============ 第二对：响应加密密钥对 ============
Write-Host "[4/4] 生成响应加密密钥对..."
openssl genrsa -out res_private_pkcs1.pem 1024 2>$null
openssl pkcs8 -topk8 -inform PEM -in res_private_pkcs1.pem -outform PEM -nocrypt -out res_private_pkcs8.pem 2>$null
openssl rsa -in res_private_pkcs1.pem -pubout -out res_public.pem 2>$null

# 读取并清理 Base64
function Get-CleanBase64($file) {
    $content = Get-Content $file -Raw
    $content = $content -replace '-----BEGIN[^-]+-----', ''
    $content = $content -replace '-----END[^-]+-----', ''
    $content = $content -replace '\s', ''
    return $content
}

$reqPrivate  = Get-CleanBase64 "req_private_pkcs8.pem"
$reqPublic   = Get-CleanBase64 "req_public.pem"
$resPublic   = Get-CleanBase64 "res_public.pem"
$resPrivate  = Get-CleanBase64 "res_private_pkcs8.pem"

# 输出
Write-Host ""
Write-Host "============================================================"
Write-Host "  生成完成！请将以下四段字符串复制到对应配置中"
Write-Host "============================================================"
Write-Host ""

Write-Host "-------- 1. 请求-私钥（后端 application.yml 的 privateKey） --------" -ForegroundColor Yellow
Write-Host $reqPrivate
Write-Host ""

Write-Host "-------- 2. 请求-公钥（前端 .env 的 VITE_APP_RSA_PUBLIC_KEY） --------" -ForegroundColor Yellow
Write-Host $reqPublic
Write-Host ""

Write-Host "-------- 3. 响应-公钥（后端 application.yml 的 publicKey） --------" -ForegroundColor Yellow
Write-Host $resPublic
Write-Host ""

Write-Host "-------- 4. 响应-私钥（前端 .env 的 VITE_APP_RSA_PRIVATE_KEY） --------" -ForegroundColor Yellow
Write-Host $resPrivate
Write-Host ""

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "  配置填写说明" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "【后端 application.yml】"
Write-Host "  api-decrypt:"
Write-Host "    enabled: true"
Write-Host "    headerFlag: encrypt-key"
Write-Host "    publicKey: <上面第 3 段（响应-公钥）>"
Write-Host "    privateKey: <上面第 1 段（请求-私钥）>"
Write-Host ""
Write-Host "【前端 .env】"
Write-Host "  VITE_APP_ENCRYPT = true"
Write-Host "  VITE_APP_RSA_PUBLIC_KEY = '<上面第 2 段（请求-公钥）>'"
Write-Host "  VITE_APP_RSA_PRIVATE_KEY = '<上面第 4 段（响应-私钥）>'"
Write-Host ""

# 清理临时目录
Pop-Location
Remove-Item -Recurse -Force $tempDir -ErrorAction SilentlyContinue

Read-Host "按回车键退出"