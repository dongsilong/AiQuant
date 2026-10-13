package org.dromara.quant.exchange;

import java.util.List;

/**
 * 交易所账户/持仓查询端口。
 */
public interface ExchangeAccountPort {
    ExchangeGateway.ConnectivityResult testConnectivity();

    ExchangeGateway.ConnectivityResult validateCredential(ExchangeGateway.Credential credential);

    ExchangeGateway.AccountSnapshot queryAccount(ExchangeGateway.Credential credential);

    /**
     * 余额资金覆盖校准必须从本次调用之后发起，不能加入此前的共享请求。
     */
    default ExchangeGateway.AccountSnapshot queryAccountFresh(ExchangeGateway.Credential credential) {
        return queryAccount(credential);
    }

    List<ExchangeGateway.PositionSnapshot> queryPositions(ExchangeGateway.Credential credential, String symbol);

    List<ExchangeGateway.IncomeSnapshot> queryIncomeHistory(ExchangeGateway.Credential credential, String symbol, String incomeType, ExchangeGateway.TimeRange range, Long limit);

    ExchangeGateway.PositionMode queryPositionMode(ExchangeGateway.Credential credential);

    /**
     * 设置账户持仓模式。
     */
    void setPositionMode(ExchangeGateway.Credential credential, boolean hedgeMode);

    /**
     * 设置交易对保证金模式。
     */
    void setMarginType(ExchangeGateway.Credential credential, String symbol, String marginType);

    /**
     * 设置交易对杠杆。
     */
    void setLeverage(ExchangeGateway.Credential credential, String symbol, int leverage);
}
