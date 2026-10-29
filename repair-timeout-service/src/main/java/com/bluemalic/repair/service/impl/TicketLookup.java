package com.bluemalic.repair.service.impl;

import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.Ticket;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.mapper.TicketMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 工单域最基础的读取：按 ID 取工单 / 取用户，并把“不存在”统一成业务错误。
 *
 * <p>它不做数据权限——可见范围由 MyBatis 拦截器注入，查不到时这里只负责对外说“不存在”，
 * 不泄露“存在但不属于你”的差别。
 */
@Component
@RequiredArgsConstructor
public class TicketLookup {

    private final TicketMapper ticketMapper;
    private final SysUserMapper sysUserMapper;

    Ticket requireTicket(long id) {
        Ticket ticket = ticketMapper.selectById(id);
        if (ticket == null) {
            // 查不到 = 不存在，或不在当前用户的数据范围内——对外统一说"不存在"，不泄露差别
            throw new BizException(ErrorCode.TICKET_NOT_FOUND);
        }
        return ticket;
    }

    SysUser requireUser(long id) {
        SysUser user = sysUserMapper.selectById(id);
        if (user == null || !Integer.valueOf(1).equals(user.getStatus())) {
            throw new BizException(ErrorCode.NOT_LOGIN);
        }
        return user;
    }
}
