package org.dromara.quant.account;

import java.util.List;

/**
 * 将交易所返回的成交批次切分为管理端表格分页。
 */
public record TradePageSlice<T>(List<T> rows, long total) {
    /**
     * 构建分页切片结果。
     */
    public static <T> TradePageSlice<T> of(List<T> source, int pageNum, int pageSize) {
        if (pageNum < 1 || pageSize < 1) {
            throw new IllegalArgumentException("分页参数必须大于零");
        }
        int fromIndex = Math.min((pageNum - 1) * pageSize, source.size());
        int toIndex = Math.min(fromIndex + pageSize, source.size());
        return new TradePageSlice<>(source.subList(fromIndex, toIndex), source.size());
    }
}
