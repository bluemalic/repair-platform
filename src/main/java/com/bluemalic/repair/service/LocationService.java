package com.bluemalic.repair.service;

import com.bluemalic.repair.vo.LocationVO;

import java.util.List;

/**
 * 可报修位置的清单（学生端"手动选位置"的数据源，docs/01 §4.6）。
 *
 * <p>与 `GET /api/tickets/by-code/{code}` 的区别：那个是"拿一张码换一个位置"，
 * 这个是"列出所有位置让人挑"——公共区域没有码可扫时走的就是这条。
 */
public interface LocationService {

    /** 本租户启用中的楼栋，每个楼栋下挂"有报修码"的位置（宿舍房间 + 公共区域）。 */
    List<LocationVO> list();
}
