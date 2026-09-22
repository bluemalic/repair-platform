package com.bluemalic.repair.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import com.bluemalic.repair.common.UserType;
import com.bluemalic.repair.converter.StudentConverter;
import com.bluemalic.repair.dto.StudentCreateDTO;
import com.bluemalic.repair.dto.StudentImportDTO;
import com.bluemalic.repair.dto.StudentUpdateDTO;
import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.service.AccountService;
import com.bluemalic.repair.service.CurrentTenantService;
import com.bluemalic.repair.service.StudentService;
import com.bluemalic.repair.vo.PageResult;
import com.bluemalic.repair.vo.StudentImportVO;
import com.bluemalic.repair.vo.StudentVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 学生账号管理的实现。
 *
 * <p>账号本身的写操作全在 {@link AccountService} 里（维修工与学生共用同一套：建号必写角色关联、
 * 停用必踢下线、每条语句都带 tenant_id）。这里只做学生特有的部分：列表、批量导入。
 *
 * <p>与维修工的两处差异，都是有意为之：
 * <ol>
 *   <li><b>建号一律带 {@code must_change_password = 1}</b>——学生这边的初始口令只能统一发放，
 *       而学号在班里是公开的（见 {@link StudentService} 的类注释）</li>
 *   <li><b>有批量导入</b>——一届新生是按名单一次进来的，一个个建不现实</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StudentServiceImpl implements StudentService {

    private static final String ACCOUNT_LABEL = "学生";

    /**
     * 单次导入的行数上限。
     *
     * <p>不是拍脑袋：一所两万人的学校，如果允许一次请求建两万行，那就是一个超长事务 +
     * 一个可能超时的响应，中途失败还要整体回滚。500 条大致是"一个年级的名单粘一次"的量级，
     * 超了让管理员分批——分十几次导入对操作者不构成负担，但事务大小是可控的。
     */
    private static final int IMPORT_MAX_ROWS = 500;

    /** 学号列与姓名列的分隔符：中英文逗号都接受（从表格里复制粘贴时两种都可能出现）。 */
    private static final String COLUMN_SEPARATORS = ",，";

    private final SysUserMapper sysUserMapper;
    private final AccountService accountService;
    private final CurrentTenantService currentTenantService;

    @Override
    public PageResult<StudentVO> page(long pageNum, long pageSize, Integer status, String keyword) {
        long tenantId = currentTenantService.requireTenantId();

        var query = Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getTenantId, tenantId)
                .eq(SysUser::getUserType, UserType.STUDENT.getCode())
                .eq(status != null, SysUser::getStatus, status);
        if (StringUtils.hasText(keyword)) {
            query.and(w -> w.like(SysUser::getUsername, keyword).or().like(SysUser::getRealName, keyword));
        }

        Page<SysUser> page = sysUserMapper.selectPage(
                new Page<>(clamp(pageNum), clamp(pageSize)),
                query.orderByAsc(SysUser::getUsername));

        Page<StudentVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream().map(StudentConverter::toVO).toList());
        return PageResult.of(voPage);
    }

    @Override
    @Transactional
    public StudentVO create(StudentCreateDTO dto) {
        long tenantId = currentTenantService.requireTenantId();
        SysUser student = accountService.create(newAccount(tenantId, dto.getUsername(),
                dto.getRealName(), dto.getPhone(), dto.getPassword()));
        log.info("新增学生 studentId={} username={} operator={}",
                student.getId(), student.getUsername(), StpUtil.getLoginIdAsLong());
        return StudentConverter.toVO(student);
    }

    @Override
    @Transactional
    public void update(long id, StudentUpdateDTO dto) {
        long tenantId = currentTenantService.requireTenantId();
        accountService.require(id, tenantId, UserType.STUDENT.getCode(), ACCOUNT_LABEL);

        accountService.updateProfile(id, tenantId, dto.getRealName(), dto.getPhone());
        // 停用时的踢下线在 changeStatus 里，那条不变量属于账号层
        accountService.changeStatus(id, tenantId, dto.getStatus(), ACCOUNT_LABEL);

        if (dto.getPassword() != null) {
            // 管理员重置口令后，学生下次登录仍要改密：这个口令是"一次性的"这条语义不能破
            accountService.resetPassword(id, tenantId, dto.getPassword(), true);
        }
        log.info("修改学生 studentId={} operator={}", id, StpUtil.getLoginIdAsLong());
    }

    @Override
    @Transactional
    public void resetPassword(long id, String rawPassword) {
        long tenantId = currentTenantService.requireTenantId();
        accountService.require(id, tenantId, UserType.STUDENT.getCode(), ACCOUNT_LABEL);
        accountService.resetPassword(id, tenantId, rawPassword, true);
    }

    @Override
    @Transactional
    public StudentImportVO importStudents(StudentImportDTO dto) {
        long tenantId = currentTenantService.requireTenantId();

        ParsedRows parsed = parse(dto.getRows());
        if (parsed.accounts().isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "名单里没有可用的学号");
        }
        if (parsed.accounts().size() > IMPORT_MAX_ROWS) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "单次最多导入 " + IMPORT_MAX_ROWS + " 条（本次 " + parsed.accounts().size() + " 条），请分批导入");
        }

        // 一次查出已存在的学号，避免逐行回库（500 行就是 500 次查询）
        Set<String> existing = existingUsernames(tenantId, parsed.accounts().keySet());

        List<String> skippedUsernames = new ArrayList<>();
        int created = 0;
        for (Map.Entry<String, String> entry : parsed.accounts().entrySet()) {
            String username = entry.getKey();
            if (existing.contains(username)) {
                // 重复导入不是错误：学校补录时名单里必然带着上次已导的人
                skippedUsernames.add(username);
                continue;
            }
            accountService.create(newAccount(tenantId, username, entry.getValue(), null, dto.getPassword()));
            created++;
        }

        log.info("批量导入学生 新建={} 跳过={} 忽略={} operator={}",
                created, skippedUsernames.size(), parsed.ignored(), StpUtil.getLoginIdAsLong());
        StudentImportVO vo = new StudentImportVO();
        vo.setCreated(created);
        vo.setSkipped(skippedUsernames.size());
        vo.setSkippedUsernames(skippedUsernames);
        vo.setIgnored(parsed.ignored());
        return vo;
    }

    /** 学生账号一律走"首登强制改密"的那套语义，所以建号参数在三个入口里长得一样。 */
    private AccountService.NewAccount newAccount(long tenantId, String username, String realName,
                                                 String phone, String rawPassword) {
        return new AccountService.NewAccount(tenantId, username, rawPassword, realName, phone,
                UserType.STUDENT.getCode(), UserType.STUDENT.getRoleCode(), true, "学号");
    }

    /**
     * 解析粘贴的名单。
     *
     * <p>规则刻意简单：一行一个学号，或「学号,姓名」。空行忽略；同一批里重复的学号只取第一条
     * （第二条也记进"忽略"，因为它没有产生任何效果）。格式不合法的行**直接报错并指出行号**——
     * 静默丢掉一行意味着"某个学生的账号没建出来"，而管理员只会看到"导入成功 199 条"。
     */
    private ParsedRows parse(String text) {
        Map<String, String> accounts = new LinkedHashMap<>();
        int ignored = 0;
        String[] lines = text.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                ignored++;
                continue;
            }
            String[] cells = line.split("[" + COLUMN_SEPARATORS + "]", 2);
            String username = cells[0].trim();
            String realName = cells.length > 1 ? cells[1].trim() : null;
            if (username.isEmpty()) {
                ignored++;
                continue;
            }
            if (username.length() > 32) {
                throw new BizException(ErrorCode.PARAM_INVALID,
                        "第 " + (i + 1) + " 行的学号超过 32 位：" + username.substring(0, 20) + "…");
            }
            if (realName != null && realName.length() > 32) {
                throw new BizException(ErrorCode.PARAM_INVALID,
                        "第 " + (i + 1) + " 行的姓名超过 32 位：" + realName.substring(0, 20) + "…");
            }
            if (accounts.putIfAbsent(username, realName) != null) {
                // 同一批名单里重复出现：第一条已经建了，这一条算忽略
                ignored++;
            }
        }
        return new ParsedRows(accounts, ignored);
    }

    private Set<String> existingUsernames(long tenantId, Set<String> usernames) {
        if (usernames.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(sysUserMapper.selectList(Wrappers.<SysUser>lambdaQuery()
                        .select(SysUser::getUsername)
                        .eq(SysUser::getTenantId, tenantId)
                        .in(SysUser::getUsername, usernames))
                .stream()
                .map(SysUser::getUsername)
                .toList());
    }

    private long clamp(long value) {
        if (value < 1) {
            return 1;
        }
        return Math.min(value, 100);
    }

    /** 解析结果：学号 → 姓名（保持名单里的顺序），以及被忽略的条数。 */
    private record ParsedRows(Map<String, String> accounts, int ignored) {
    }
}
