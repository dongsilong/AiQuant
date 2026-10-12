@echo off
chcp 65001 >nul
setlocal

where openssl >nul 2>nul
if %errorlevel% neq 0 (
    echo [错误] 未找到 openssl 命令，请先安装 OpenSSL 并添加到 PATH。
    pause
    exit /b 1
)

:: 创建临时目录
set "TEMPDIR=%TEMP%\rsa_keys_%RANDOM%"
mkdir "%TEMPDIR%" >nul 2>nul

:: 生成两对密钥
cd /d "%TEMPDIR%"

echo [1/4] 生成请求加密私钥...
openssl genrsa -out req_private_pkcs1.pem 1024 2>nul
openssl pkcs8 -topk8 -inform PEM -in req_private_pkcs1.pem -outform PEM -nocrypt -out req_private_pkcs8.pem 2>nul
openssl rsa -in req_private_pkcs1.pem -pubout -out req_public.pem 2>nul

echo [2/4] 生成响应加密密钥对...
openssl genrsa -out res_private_pkcs1.pem 1024 2>nul
openssl pkcs8 -topk8 -inform PEM -in res_private_pkcs1.pem -outform PEM -nocrypt -out res_private_pkcs8.pem 2>nul
openssl rsa -in res_private_pkcs1.pem -pubout -out res_public.pem 2>nul

:: 用 PowerShell 清理密钥并输出
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$files = @{'请求-私钥(后端 privateKey)'='req_private_pkcs8.pem'; '请求-公钥(前端 VITE_APP_RSA_PUBLIC_KEY)'='req_public.pem'; '响应-公钥(后端 publicKey)'='res_public.pem'; '响应-私钥(前端 VITE_APP_RSA_PRIVATE_KEY)'='res_private_pkcs8.pem'}; foreach ($k in $files.Keys) { Write-Host ''; Write-Host ('-------- ' + $k + ' --------') -ForegroundColor Yellow; $c = Get-Content $files[$k] -Raw; $c = $c -replace '-----BEGIN[^-]+-----','' -replace '-----END[^-]+-----','' -replace '\s',''; Write-Host $c }"

echo.
echo 临时文件保存在: %TEMPDIR%
pause >nul

cd /d "%~dp0"
rmdir /s /q "%TEMPDIR%" >nul 2>nul
endlocal
