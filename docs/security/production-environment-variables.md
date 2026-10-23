# Production environment variable contract

This document describes the environment variables referenced by the committed Spring configuration. Supply secret values through the deployment secret manager or process environment; do not commit a populated `.env` file or paste secret values into issues or chat.

## Required before starting the production profile

| Variable | Purpose | Deployment requirement |
|---|---|---|
| `AIQUANT_QUANT_SECRET_KEY` | AiQuant trading credential encryption | Required; preserve the existing value if encrypted credentials already exist. |
| `AIQUANT_MYBATIS_ENCRYPTOR_PASSWORD` | MyBatis field encryption | Required; **do not rotate casually**. Existing encrypted database values may become unreadable with a different password. |
| `AIQUANT_DB_PASSWORD` | MySQL password | Required; use a least-privilege DB account. |
| `AIQUANT_REDIS_PASSWORD` | Redis password | Required for production. Do not leave Redis unauthenticated on a reachable network. |
| `AIQUANT_SA_TOKEN_JWT_SECRET_KEY` | Sa-Token JWT signing key | Required and must be a high-entropy secret. Changing it invalidates existing tokens and requires users to log in again. |
| `AIQUANT_API_DECRYPT_PRIVATE_KEY` | API request-decryption private key | Required if API decryption remains enabled. Supply the key that matches the public key used by the deployed client; changing the key pair can break encrypted requests. Store as the single-line Base64/PEM payload format expected by the existing configuration. |

The application now intentionally has no committed fallback for the JWT signing key or API decryption private key. A production deployment missing either variable should be treated as incomplete, not worked around by restoring a source-code default.

## Optional integrations

Set these only when the corresponding integration is configured and intended to be used. Empty defaults do not provide working credentials.

- MQTT: `AIQUANT_MQTT_USERNAME`, `AIQUANT_MQTT_PASSWORD` (MQTT client is disabled in the checked-in base configuration).
- Email: `AIQUANT_MAIL_ENABLED`, `AIQUANT_MAIL_FROM`, `AIQUANT_MAIL_USER`, `AIQUANT_MAIL_PASSWORD`. Keep mail disabled unless configured and tested.
- SMS: `AIQUANT_SMS_ACCESS_KEY_ID`, `AIQUANT_SMS_ACCESS_KEY_SECRET`, `AIQUANT_SMS_SIGNATURE`, `AIQUANT_SMS_SDK_APP_ID`. Provider-specific settings may require separate credentials; do not assume one shared credential works for both configured provider examples.
- SnailJob: `AIQUANT_SNAIL_JOB_TOKEN` (client is disabled by default).
- SnailAI: `AIQUANT_SNAIL_AI_TOKEN` (client is disabled by default).
- JustAuth OAuth client secrets: `AIQUANT_JUSTAUTH_MAXKEY_CLIENT_SECRET`, `AIQUANT_JUSTAUTH_TOPIAM_CLIENT_SECRET`, `AIQUANT_JUSTAUTH_QQ_CLIENT_SECRET`, `AIQUANT_JUSTAUTH_WEIBO_CLIENT_SECRET`, `AIQUANT_JUSTAUTH_GITEE_CLIENT_SECRET`, `AIQUANT_JUSTAUTH_DINGTALK_CLIENT_SECRET`, `AIQUANT_JUSTAUTH_BAIDU_CLIENT_SECRET`, `AIQUANT_JUSTAUTH_CSDN_CLIENT_SECRET`, `AIQUANT_JUSTAUTH_CODING_CLIENT_SECRET`, `AIQUANT_JUSTAUTH_OSCHINA_CLIENT_SECRET`, `AIQUANT_JUSTAUTH_ALIPAY_WALLET_CLIENT_SECRET`, `AIQUANT_JUSTAUTH_WECHAT_OPEN_CLIENT_SECRET`, `AIQUANT_JUSTAUTH_WECHAT_MP_CLIENT_SECRET`, `AIQUANT_JUSTAUTH_WECHAT_ENTERPRISE_CLIENT_SECRET`, `AIQUANT_JUSTAUTH_GITLAB_CLIENT_SECRET`, `AIQUANT_JUSTAUTH_GITEA_CLIENT_SECRET`. Configure only providers actually used, with matching provider-side client IDs and redirect URIs.

## Other runtime controls

- `QUANT_BINANCE_WEBSOCKET_TRANSPORT`: defaults to `BINANCE_SDK`; only change after transport-specific integration validation.
- `QUANT_BINANCE_SDK_BACKLOG_LIMIT`: defaults to `2048`; tune only with measured event throughput and reconnect behavior.

## Deployment verification checklist

1. Provision required secrets in the deployment platform, with access limited to the AiQuant service identity.
2. Confirm the JWT signing key is not the former committed default; plan for existing sessions to be invalidated.
3. Confirm the API private key matches the public key configured in the actual deployed client and test encrypted request/response flows in a non-production environment.
4. Verify the existing MyBatis encryption password and trading secret key before rollout; do not replace either blindly.
5. Confirm MySQL and Redis are reachable only from intended networks, and Redis authentication is enabled.
6. Keep SnailJob, SnailAI, SMS, email, and OAuth providers disabled/unconfigured unless the corresponding credentials and callback URLs are provisioned.
7. Verify the production Actuator surface exposes only `health` and `info`; health details are hidden.
8. Run startup, login, encrypted API, database field decryption, Redis, Binance market-data and testnet order-flow checks before enabling LIVE execution.

This file is a configuration contract, not evidence that a particular server has been provisioned. No production host or LIVE account is changed by committing this document.
