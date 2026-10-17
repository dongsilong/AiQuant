package org.dromara.quant.decision;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 提供交易决策日志的管理员查询接口。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/quant/trade-decision-log")
public class TradeDecisionLogController {
    private final TradeDecisionLogRepository repository;

    /**
     * 限制决策日志查询仅由管理员执行。
     */
    static void requireAdmin() {
        if (!LoginHelper.isSuperAdmin(LoginHelper.getUserId())) {
            throw new ServiceException("仅管理员可查询开平仓日志");
        }
    }

    /**
     * 分页查询交易决策日志。
     */
    @SaCheckPermission("quant:tradeDecisionLog:list")
    @GetMapping("/list")
    public R<PageResult<Map<String, Object>>> list(TradeDecisionQuery query) {
        requireAdmin();
        return R.ok(repository.list(query));
    }

    /**
     * 查询单条决策日志及其关联详情。
     */
    @SaCheckPermission("quant:tradeDecisionLog:query")
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        requireAdmin();
        Map<String, Object> row = repository.detail(id);
        if (row == null) {
            throw new ServiceException("日志不存在");
        }
        return R.ok(row);
    }
}
