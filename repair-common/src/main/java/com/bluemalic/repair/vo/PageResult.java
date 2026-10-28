package com.bluemalic.repair.vo;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.bluemalic.repair.common.NumericLongSerializer;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.Data;

import java.util.Collections;
import java.util.List;

/**
 * 分页返回体，契约见 docs/03-接口规范.md 第 2.1 节。
 *
 * <p>四个计数字段显式按**数字**输出：它们是全局「Long → 字符串」规则的唯一例外，
 * 理由是这个契约的不对称是故意的（ID 要字符串保精度，计数要数字给分页组件用）。
 */
@Data
public class PageResult<T> {

    @JsonSerialize(using = NumericLongSerializer.class)
    private long total;

    @JsonSerialize(using = NumericLongSerializer.class)
    private long pageNum;

    @JsonSerialize(using = NumericLongSerializer.class)
    private long pageSize;

    @JsonSerialize(using = NumericLongSerializer.class)
    private long pages;

    /** 恒为数组，不会是 null —— 前端不必写空值判断 */
    private List<T> list = Collections.emptyList();

    /**
     * 由 MyBatis-Plus 的 {@code IPage} 转换而来。
     * Controller 只调用这一个方法，不自己拼字段，避免各处口径不一致。
     */
    public static <T> PageResult<T> of(IPage<T> page) {
        PageResult<T> result = new PageResult<>();
        result.setTotal(page.getTotal());
        result.setPageNum(page.getCurrent());
        result.setPageSize(page.getSize());
        result.setPages(page.getPages());
        result.setList(page.getRecords() == null ? Collections.emptyList() : page.getRecords());
        return result;
    }
}
