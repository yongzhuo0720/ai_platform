package com.aiproject.ai.service.dto;

import lombok.Data;

/**
 * 操作日志查询条件。
 *
 * <p>不含排序字段：{@code PageRequest.order/desc} 在框架里是直接拼进
 * {@code ORDER BY} 的字符串，属于注入面。日志列表只有「按写入顺序倒看」
 * 这一种合理排序，所以排序由后端固定，不接受前端传值。</p>
 */
@Data
public class ControllerLogQuery {

    private Integer pageNum = 1;

    private Integer pageSize = 20;

    /** yyyy-MM-dd，为空表示不限起始时间 */
    private String startDate;

    /** yyyy-MM-dd，为空表示不限结束时间 */
    private String endDate;
}
