package org.dromara.quant.security;

/**
 * API Key 展示脱敏。密钥材料不得进入响应对象。
 */
public final class CredentialMasker {

    private CredentialMasker() {
    }

    /**
     * 对 API Key 进行脱敏。
     */
    public static String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.length() < 9) {
            return "****";
        }
        return apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4);
    }
}
