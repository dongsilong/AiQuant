RuoYi-Vue-Plus 的接口加密需要**两对独立的 RSA 密钥**，一对用于请求加密，另一对用于响应加密，互不混用。

---

## 一、两对密钥的对应关系

| 通信方向 | 前端配置项 | 后端配置项 | 用途 |
|----------|-----------|-----------|------|
| **请求**（前端 → 后端） | `VITE_APP_RSA_PUBLIC_KEY` | `api-decrypt.privateKey` | 前端用**公钥**加密请求体中的 AES 密钥，后端用**私钥**解密 |
| **响应**（后端 → 前端） | `VITE_APP_RSA_PRIVATE_KEY` | `api-decrypt.publicKey` | 后端用**公钥**加密响应中的 AES 密钥，前端用**私钥**解密 |

> **核心规则**：同一对密钥中，公钥负责加密，私钥负责解密。请求和响应各用一对，不能互换。

---

## 二、生成两对 RSA 密钥

推荐使用 **1024 位**密钥（与 RuoYi-Vue-Plus 默认配置一致）。

### 第一对：请求加密密钥对

```bash
# 1. 生成原始 RSA 私钥（PKCS#1 格式）
openssl genrsa -out request_private_pkcs1.pem 1024

# 2. 转换为 PKCS#8 格式（Java 后端需要此格式）
openssl pkcs8 -topk8 -inform PEM -in request_private_pkcs1.pem -outform PEM -nocrypt -out request_private_pkcs8.pem

# 3. 从私钥中提取公钥（给前端使用）
openssl rsa -in request_private_pkcs1.pem -pubout -out request_public.pem
```

### 第二对：响应加密密钥对

```bash
# 1. 生成原始 RSA 私钥
openssl genrsa -out response_private_pkcs1.pem 1024

# 2. 转换为 PKCS#8 格式
openssl pkcs8 -topk8 -inform PEM -in response_private_pkcs1.pem -outform PEM -nocrypt -out response_private_pkcs8.pem

# 3. 提取公钥
openssl rsa -in response_private_pkcs1.pem -pubout -out response_public.pem
```

### 查看密钥内容并去掉头尾标记

```bash
# 查看私钥内容
cat request_private_pkcs8.pem
cat response_private_pkcs8.pem

# 查看公钥内容
cat request_public.pem
cat response_public.pem
```

**每个文件的内容需要处理**：只保留中间那一长串 Base64 字符，**去掉** `-----BEGIN XXX-----` 和 `-----END XXX-----` 标记，**去掉所有换行符**，得到一行连续的字符串。

---

## 三、配置填写对照表

假设你生成的两对密钥（示例值，实际以你生成的为准）：

| 密钥 | 示例值（仅示意） |
|------|-----------------|
| **请求-公钥**（给前端） | `MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQD...` |
| **请求-私钥**（给后端） | `MIICdgIBADANBgkqhkiG9w0BAQEFAASCAmAwggJcAgEAAoGBA...` |
| **响应-公钥**（给后端） | `MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQD...` |
| **响应-私钥**（给前端） | `MIICdgIBADANBgkqhkiG9w0BAQEFAASCAmAwggJcAgEAAoGBA...` |

### 后端 `application.yml` 填写

```yaml
api-decrypt:
  enabled: true
  headerFlag: encrypt-key
  # 响应加密公钥（来自第二对密钥的公钥）
  publicKey: MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQD...
  # 请求解密私钥（来自第一对密钥的私钥）
  privateKey: MIICdgIBADANBgkqhkiG9w0BAQEFAASCAmAwggJcAgEAAoGBA...
```

### 前端 `.env` 填写

```env
VITE_APP_ENCRYPT = true

# 请求加密公钥（来自第一对密钥的公钥）
VITE_APP_RSA_PUBLIC_KEY = 'MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQD...'

# 响应解密私钥（来自第二对密钥的私钥）
VITE_APP_RSA_PRIVATE_KEY = 'MIICdgIBADANBgkqhkiG9w0BAQEFAASCAmAwggJcAgEAAoGBA...'
```

---

## 四、完整生成脚本（一键执行）

```bash
#!/bin/bash

# ==== 请求加密密钥对 ====
openssl genrsa -out req_private_pkcs1.pem 1024
openssl pkcs8 -topk8 -inform PEM -in req_private_pkcs1.pem -outform PEM -nocrypt -out req_private_pkcs8.pem
openssl rsa -in req_private_pkcs1.pem -pubout -out req_public.pem

# ==== 响应加密密钥对 ====
openssl genrsa -out res_private_pkcs1.pem 1024
openssl pkcs8 -topk8 -inform PEM -in res_private_pkcs1.pem -outform PEM -nocrypt -out res_private_pkcs8.pem
openssl rsa -in res_private_pkcs1.pem -pubout -out res_public.pem

echo "===== 请求-私钥（给后端 privateKey） ====="
cat req_private_pkcs8.pem | grep -v "BEGIN" | grep -v "END" | tr -d '\n'
echo ""

echo "===== 请求-公钥（给前端 VITE_APP_RSA_PUBLIC_KEY） ====="
cat req_public.pem | grep -v "BEGIN" | grep -v "END" | tr -d '\n'
echo ""

echo "===== 响应-公钥（给后端 publicKey） ====="
cat res_public.pem | grep -v "BEGIN" | grep -v "END" | tr -d '\n'
echo ""

echo "===== 响应-私钥（给前端 VITE_APP_RSA_PRIVATE_KEY） ====="
cat res_private_pkcs8.pem | grep -v "BEGIN" | grep -v "END" | tr -d '\n'
echo ""
```

执行后会直接输出四段 Base64 字符串，分别填入对应位置即可。

---

## 五、注意事项

1. **前后端必须同步更换**：修改任意一对密钥后，前后端相关配置必须一起更新，否则会出现 `BadBlockException: unable to decrypt block` 错误。

2. **私钥格式**：后端 `privateKey` 必须是 **PKCS#8** 格式（以 `MII...` 开头），不能直接用 PKCS#1 格式（以 `MIIC...` 或 `MIIE...` 开头，但 PKCS#1 的私钥头是 `-----BEGIN RSA PRIVATE KEY-----`）。需要用 `openssl pkcs8 -topk8` 转换。

3. **公钥格式**：前端 `jsencrypt` 使用 PKCS#8 格式的公钥（以 `MIGf...` 或 `MIIB...` 开头），直接用 `openssl rsa -pubout` 生成的即可。

4. **如果暂时不需要响应加密**：可以只配置请求加密那一对，`VITE_APP_RSA_PRIVATE_KEY` 和后端 `publicKey` 留空即可。

5. **开关必须一致**：前端的 `VITE_APP_ENCRYPT` 与后端的 `api-decrypt.enabled` 必须同时为 `true` 或同时为 `false`，否则登录接口会报错。