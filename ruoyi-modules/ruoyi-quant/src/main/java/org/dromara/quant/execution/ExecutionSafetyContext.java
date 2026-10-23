package org.dromara.quant.execution;

/**
 * 执行前安全检查快照，替代命令对象中不断增长的 boolean 参数列表。
 * 这是一次执行的事实快照，不负责自行查询数据库或交易所。
 *
 * @param systemLiveEnabled      系统是否允许 LIVE
 * @param accountLiveEnabled     凭证是否允许 LIVE
 * @param futuresTradePermission 账户是否具备合约交易权限
 * @param withdrawalDisabled     兼容的账户转出能力观测字段，不作为准入条件，也不代表 API Key 提现权限
 * @param connectivityVerified   是否已验证交易所连通性
 * @param accountAuthorized      当前业务主体是否被授权使用凭证
 * @param riskConfigured         风险配置是否完整
 * @param approvedAndNotTripped  风险决策是否批准且未触发熔断
 */
public record ExecutionSafetyContext(boolean systemLiveEnabled, boolean accountLiveEnabled, boolean futuresTradePermission, boolean withdrawalDisabled,
                                     boolean connectivityVerified, boolean accountAuthorized, boolean riskConfigured, boolean approvedAndNotTripped) {
}
