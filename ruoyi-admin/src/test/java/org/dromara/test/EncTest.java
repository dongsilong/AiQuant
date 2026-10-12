package org.dromara.test;

import org.dromara.common.encrypt.utils.EncryptUtils;

import java.util.Map;

public class EncTest {
    /**
     * 响应加密公钥 非对称算法的公私钥 如：SM2，RSA
     */
    public static void main(String[] args) {
        Map<String, String> stringStringMap = EncryptUtils.generateRsaKey();
        for (Map.Entry<String, String> stringStringEntry : stringStringMap.entrySet()) {
            String key = stringStringEntry.getKey();
            String value = stringStringEntry.getValue();
            System.out.println(key + " -> " + value);
        }

    }
}
