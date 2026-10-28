package com.bluemalic.repair.vo;

import lombok.Data;

import java.util.List;

/**
 * 一个楼栋下可报修的位置（docs/01 §4.6）。
 *
 * <p><b>只包含"有报修码"的位置</b>——这不是实现偷懒，而是设计上的不变量：
 * 维修工到场要扫同一张码核验，没有码的位置报了也验不了到场。所以"可选位置清单"
 * 本质就是"报修码清单"的人话版。
 */
@Data
public class LocationVO {

    private Long buildingId;

    private String buildingName;

    private List<LocationItem> locations;

    @Data
    public static class LocationItem {

        /** 房间号（宿舍房间）或区域名（公共区域，如 1F-大厅） */
        private String room;

        /** 1 宿舍房间 / 2 公共区域 */
        private Integer locationType;
    }
}
