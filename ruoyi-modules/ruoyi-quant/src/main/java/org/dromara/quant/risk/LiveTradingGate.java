package org.dromara.quant.risk;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 实盘交易总闸门。所有下单路径都必须经过本校验。
 */
@Component
@Slf4j
public final class LiveTradingGate {

    /**
     * 执行 LIVE 实盘安全闸门校验。
     */
    public void check(Checkpoint checkpoint) {
        if (checkpoint == null) {
            log.warn("LIVE 交易闸门拒绝：安全检查快照为空");
            throw new LiveTradingBlockedException();
        }
        List<String> failures = new ArrayList<>();
        if (!checkpoint.systemEnabled()) {
            failures.add("系统未开启实盘");
        }
        if (!checkpoint.accountEnabled()) {
            failures.add("账户未开启实盘");
        }
        if (!checkpoint.futuresTradePermission()) {
            failures.add("账户缺少合约交易权限");
        }
        if (!checkpoint.connectivityVerified()) {
            failures.add("交易所连接未验证");
        }
        if (!checkpoint.accountAuthorized()) {
            failures.add("账户授权未通过");
        }
        if (!checkpoint.riskConfigured()) {
            failures.add("风险配置不完整");
        }
        if (!checkpoint.symbolVerified()) {
            failures.add("交易对规则未验证");
        }
        if (!checkpoint.approvedAndNotTripped()) {
            failures.add("风控未批准或已触发熔断");
        }
        if (!failures.isEmpty()) {
            log.warn("LIVE 交易闸门拒绝: systemEnabled={}, accountEnabled={}, futuresTradePermission={}, connectivityVerified={}, accountAuthorized={}, " +
                "riskConfigured={}, symbolVerified={}, approvedAndNotTripped={}", checkpoint.systemEnabled(), checkpoint.accountEnabled(), checkpoint.futuresTradePermission(),
                checkpoint.connectivityVerified(), checkpoint.accountAuthorized(), checkpoint.riskConfigured(), checkpoint.symbolVerified(),
                checkpoint.approvedAndNotTripped());
            throw new LiveTradingBlockedException(failures);
        }
    }

    /**
     * Checkpoint，量化交易模块领域/基础设施对象。
     *
     * @param systemEnabled          系统全局是否允许 LIVE
     * @param accountEnabled         当前凭证是否允许 LIVE
     * @param futuresTradePermission 账户是否具备合约交易权限
     * @param withdrawalDisabled     兼容的账户转出能力观测字段，不作为准入条件，也不代表 API Key 提现权限
     * @param connectivityVerified   是否已验证交易所连通性
     * @param accountAuthorized      当前业务主体是否被授权使用凭证
     * @param riskConfigured         风险配置是否完整
     * @param symbolVerified         交易对规则是否已验证
     * @param approvedAndNotTripped  风险决策是否批准且未触发熔断
     */
    public record Checkpoint(boolean systemEnabled, boolean accountEnabled, boolean futuresTradePermission, boolean withdrawalDisabled, boolean connectivityVerified,
                             boolean accountAuthorized, boolean riskConfigured, boolean symbolVerified, boolean approvedAndNotTripped) {
    }

    /**
     * LiveTradingBlockedException，量化交易模块领域/基础设施对象。
     */
    public static final class LiveTradingBlockedException extends RuntimeException {
        /**
         * 创建实盘交易被安全闸门阻止时使用的异常。
         */
        private final List<String> failures;

        public LiveTradingBlockedException() {
            this(List.of("实盘安全检查快照缺失"));
        }

        public LiveTradingBlockedException(List<String> failures) {
            super("实盘交易条件未满足: " + String.join("、", failures));
            this.failures = List.copyOf(failures);
        }

        /**
         * 返回阻止 LIVE 交易的未通过检查项。
         */
        public List<String> getFailures() {
            return failures;
        }
    }
}
