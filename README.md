在 PowerShell 配置一次稳定密钥：
$key = [Guid]::NewGuid().ToString("N")
[Environment]::SetEnvironmentVariable("QUANT_CREDENTIAL_ENCRYPTION_KEY", $key, "User")
$env:QUANT_CREDENTIAL_ENCRYPTION_KEY = $key
密钥一旦用于加密已有凭证，后续不要随意更换，否则旧凭证无法解密。