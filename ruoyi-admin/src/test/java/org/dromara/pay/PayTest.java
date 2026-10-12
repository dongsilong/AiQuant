package org.dromara.pay;

import cn.hutool.crypto.SecureUtil;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.dromara.common.core.utils.DateUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

public class PayTest {

    static String payUrl = "https://ipay.833006.net/tgPosp/services/payApi/allQrcodePay2";
//    static String account = "61605299299092";
//    static String key = "83b47eb40ed67d642a83e7c9825d9933";
//    static String account = "61605607344950";
//    static String key = "49e1e9a0c46968175ffdf59ca7938465";
    static String account = "61605359885322";
    static String key = "74085fea8496ed02f4ec1b0aa1b7451f";
    static long orderId = DateUtils.current();
    static String publicKey = "";
//    static String payUrl = "https://yapi.newict.com.cn/mock/35/tgPosp/services/payApi/wxH5Pay";

    public static void main(String[] args) {
        // 1. 业务参数（不要放 key，也不要放 sign）
        Map<String, Object> paramMap = new HashMap<>();
        paramMap.put("account", account);
        paramMap.put("body", "test");
        paramMap.put("lowOrderId", DateUtils.current());  // 例如 1785244402904
        paramMap.put("payMoney", "0.01");

        // 2. 生成签名
        String sign = getSign(paramMap, key);
        paramMap.put("sign", sign);   // 请求时才把 sign 加进去

        // 3. 发送请求（注意文档要求 Content-Type: application/json）
        String jsonBody = JSONUtil.toJsonStr(paramMap);
        String result = HttpUtil.post(payUrl, jsonBody);
        System.out.println(result);

        // 4. 解析返回结果
        JSONObject resp = JSONUtil.parseObj(result);
        // 真实接口的 status 应该是一个有意义的值，例如 200 表示成功
        Integer status = resp.getInt("status");
        if (Integer.valueOf(100).equals(status)) {
            System.out.println("下单成功，codeUrl: " + resp.getStr("codeUrl"));
        } else {
            System.out.println("下单失败：" + resp.getStr("message"));
        }
    }

    /**
     * 生成签名
     * @param paramMap 业务参数（不含 sign、不含 key）
     * @param key      商户密钥
     * @return 签名字符串（大写 MD5）
     */
    private static String getSign(Map<String, Object> paramMap, String key) {
        // 1. 过滤空值，并按 key 升序排序（TreeMap 自动完成）
        Map<String, String> sortedMap = new TreeMap<>();
        for (Map.Entry<String, Object> entry : paramMap.entrySet()) {
            String k = entry.getKey();
            Object v = entry.getValue();
            // 跳过 sign 字段（本就不应在此传入）以及空值
            if (v == null || v.toString().isEmpty()) {
                continue;
            }
            sortedMap.put(k, v.toString());
        }

        // 2. 拼接成 key1=value1&key2=value2...
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : sortedMap.entrySet()) {
            if (sb.length() > 0) {
                sb.append("&");
            }
            sb.append(entry.getKey()).append("=").append(entry.getValue());
        }

        // 3. 拼接商户密钥
        sb.append("&key=").append(key);

        // 4. MD5 并转大写
        return SecureUtil.md5(sb.toString()).toUpperCase();
    }

}



