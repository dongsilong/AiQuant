package org.dromara.quant.exchange;

import java.util.function.Consumer;

/**
 * 交易所用户数据流端口。
 */
public interface ExchangeUserDataPort {
    ExchangeGateway.UserDataStreamSession subscribeUserData(ExchangeGateway.Credential credential, Consumer<ExchangeGateway.UserDataEvent> listener);

    /**
     * 账户用户数据流订阅绑定。
     *
     * @param credential 当前运行上下文使用的交易凭证
     */
    record UserDataBinding(ExchangeGateway.Credential credential) {
    }
}
