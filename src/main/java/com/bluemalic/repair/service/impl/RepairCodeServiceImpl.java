package com.bluemalic.repair.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.Paging;
import com.bluemalic.repair.common.ErrorCode;
import com.bluemalic.repair.converter.RepairCodeConverter;
import com.bluemalic.repair.dto.RepairCodeCreateDTO;
import com.bluemalic.repair.dto.RepairCodeUpdateDTO;
import com.bluemalic.repair.entity.Building;
import com.bluemalic.repair.entity.RepairCode;
import com.bluemalic.repair.mapper.BuildingMapper;
import com.bluemalic.repair.mapper.RepairCodeMapper;
import com.bluemalic.repair.service.CurrentTenantService;
import com.bluemalic.repair.service.RepairCodeService;
import com.bluemalic.repair.vo.PageResult;
import com.bluemalic.repair.vo.RepairCodeDetailVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 报修码管理。三条规则值得写在这里，改代码前先看：
 *
 * <ol>
 *   <li><b>码必须随机</b>（ADR-004）：顺序码能被枚举出全部房间，等于泄露房间清单；
 *       所以没有"指定码"的入参，用 {@link SecureRandom} 而不是 {@code Random}——
 *       码是"能被猜的凭证"（ADR-009 的威胁模型就是枚举），可预测的伪随机序列会让"猜下一个码"变得容易</li>
 *   <li><b>一房一码由应用层保证</b>：数据库故意不给 {@code (building_id, room)} 建唯一索引
 *       （软删后同一房间要能重新生成，唯一索引会和 deleted 冲突），所以这条不变量只能在服务里守</li>
 *   <li><b>租户条件必须显式写</b>：`repair_code` / `building` 都不在数据权限拦截器的范围内（AGENTS §5.6）</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RepairCodeServiceImpl implements RepairCodeService {

    /** 6 位数字（ADR-009）：便于口述和手输。 */
    private static final int CODE_MIN = 100_000;

    /** 上界（不含）：从 100000 起，是为了**避开前导零**——手输时"012345"很容易被漏掉第一位。 */
    private static final int CODE_MAX_EXCLUSIVE = 1_000_000;

    /** 撞码重试次数：码空间 90 万，租户内已用码通常只有几百条，连着撞 10 次几乎不可能。 */
    private static final int MAX_CODE_ATTEMPTS = 10;

    private static final int STATUS_ENABLED = 1;

    private final RepairCodeMapper repairCodeMapper;
    private final BuildingMapper buildingMapper;
    private final CurrentTenantService currentTenantService;

    private final SecureRandom random = new SecureRandom();

    @Override
    public PageResult<RepairCodeDetailVO> page(long pageNum, long pageSize, Long buildingId, Integer status) {
        long tenantId = currentTenantService.requireTenantId();

        Page<RepairCode> page = repairCodeMapper.selectPage(
                new Page<>(Paging.clamp(pageNum), Paging.clamp(pageSize)),
                Wrappers.<RepairCode>lambdaQuery()
                        .eq(RepairCode::getTenantId, tenantId)
                        .eq(buildingId != null, RepairCode::getBuildingId, buildingId)
                        .eq(status != null, RepairCode::getStatus, status)
                        // 新生成的排在前面：管理端刚点完"生成"就想看到它
                        .orderByDesc(RepairCode::getId));

        Map<Long, String> buildings = buildingNames(page.getRecords().stream()
                .map(RepairCode::getBuildingId).distinct().toList());

        Page<RepairCodeDetailVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream()
                .map(code -> RepairCodeConverter.toVO(code, buildings.get(code.getBuildingId())))
                .toList());
        return PageResult.of(voPage);
    }

    @Override
    @Transactional
    public RepairCodeDetailVO create(RepairCodeCreateDTO dto) {
        long tenantId = currentTenantService.requireTenantId();
        Building building = requireBuilding(dto.getBuildingId(), tenantId);
        requireRoomFree(tenantId, dto.getBuildingId(), dto.getRoom(), null);

        RepairCode entity = new RepairCode();
        entity.setTenantId(tenantId);
        entity.setBuildingId(dto.getBuildingId());
        entity.setRoom(dto.getRoom());
        entity.setStatus(STATUS_ENABLED);
        entity.setCode(nextCode(tenantId));
        insertCode(entity);

        // create_time 由数据库的 DEFAULT CURRENT_TIMESTAMP 填充，MyBatis 不会把它读回实体。
        // 接口要返回这个字段就得插完再读一次：否则"生成"响应里是 null、"列表"里却有值，
        // 同一个字段两副面孔（前端要按它显示生成时间）
        RepairCode saved = repairCodeMapper.selectById(entity.getId());

        // 码是贴在门口的位置码，不是口令：记进日志便于排查（区别于手机号 / 密码）
        log.info("生成报修码 codeId={} code={} buildingId={} room={} operator={}",
                entity.getId(), entity.getCode(), dto.getBuildingId(), dto.getRoom(),
                StpUtil.getLoginIdAsLong());
        return RepairCodeConverter.toVO(saved, building.getName());
    }

    @Override
    @Transactional
    public void update(long id, RepairCodeUpdateDTO dto) {
        long tenantId = currentTenantService.requireTenantId();
        RepairCode existing = requireCode(id, tenantId);
        requireRoomFree(tenantId, existing.getBuildingId(), dto.getRoom(), id);

        boolean regenerate = Boolean.TRUE.equals(dto.getRegenerate());
        String newCode = regenerate ? nextCode(tenantId) : existing.getCode();

        int rows;
        try {
            rows = repairCodeMapper.update(null, Wrappers.<RepairCode>lambdaUpdate()
                    .eq(RepairCode::getId, id)
                    .eq(RepairCode::getTenantId, tenantId)
                    .set(RepairCode::getRoom, dto.getRoom())
                    .set(RepairCode::getStatus, dto.getStatus())
                    .set(regenerate, RepairCode::getCode, newCode));
        } catch (DuplicateKeyException e) {
            // uk_code 兜底：nextCode 已经查过一次，这里只可能是并发窗口。明确让调用方重试，而不是 500
            throw new BizException(ErrorCode.SYSTEM_ERROR, "报修码生成冲突，请重试");
        }
        if (rows == 0) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "报修码不存在");
        }

        if (regenerate) {
            log.info("重新生成报修码 codeId={} 新码={} 旧码={} operator={}",
                    id, newCode, existing.getCode(), StpUtil.getLoginIdAsLong());
        } else {
            log.info("修改报修码 codeId={} room={} status={} operator={}",
                    id, dto.getRoom(), dto.getStatus(), StpUtil.getLoginIdAsLong());
        }
    }

    /**
     * 生成一个本租户当前未占用的 6 位数字码。
     *
     * <p>先查一次再落库：查与插之间确实有窗口，但撞上的概率是"已用码数 / 90 万"再乘上并发的巧合；
     * 真正兜底的是 {@code uk_code} 索引（见两个调用点的 catch），所以这里不做"插入失败了再换一个"的循环
     * ——那会让每次生成都要处理异常路径，收益却微乎其微。
     */
    private String nextCode(long tenantId) {
        for (int attempt = 1; attempt <= MAX_CODE_ATTEMPTS; attempt++) {
            String candidate = String.valueOf(random.nextInt(CODE_MIN, CODE_MAX_EXCLUSIVE));
            Long used = repairCodeMapper.selectCount(Wrappers.<RepairCode>lambdaQuery()
                    .eq(RepairCode::getTenantId, tenantId)
                    .eq(RepairCode::getCode, candidate));
            if (used == null || used == 0) {
                return candidate;
            }
            log.warn("报修码撞码，换一个再试 第 {} 次 code={}", attempt, candidate);
        }
        throw new BizException(ErrorCode.SYSTEM_ERROR, "报修码生成失败，请重试");
    }

    private void insertCode(RepairCode entity) {
        try {
            repairCodeMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "报修码生成冲突，请重试");
        }
    }

    /**
     * 一房一码：同一楼栋同一房间不允许存在第二条**未删除**的码。
     *
     * <p>停用的码也算占用——否则同一房间会留下两行，"一房一码"就名存实亡。
     * 想换码请在原码上「重新生成」（`PUT` 带 `regenerate=true`），不要新建一条。
     */
    private void requireRoomFree(long tenantId, Long buildingId, String room, Long excludeId) {
        Long count = repairCodeMapper.selectCount(Wrappers.<RepairCode>lambdaQuery()
                .eq(RepairCode::getTenantId, tenantId)
                .eq(RepairCode::getBuildingId, buildingId)
                .eq(RepairCode::getRoom, room)
                .ne(excludeId != null, RepairCode::getId, excludeId));
        if (count != null && count > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "该房间已有报修码；换码请在原码上重新生成，不要新建");
        }
    }

    /**
     * 楼栋必须属于本租户。**不要求楼栋处于启用状态**：允许"先把码备好、楼栋稍后开放"，
     * 停用楼栋下的码本身是无害的（学生扫码提交时会被 submit 的楼栋校验拦住）。
     */
    private Building requireBuilding(Long buildingId, long tenantId) {
        Building building = buildingMapper.selectOne(Wrappers.<Building>lambdaQuery()
                .eq(Building::getId, buildingId)
                .eq(Building::getTenantId, tenantId));
        if (building == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "楼栋不存在或不属于本租户");
        }
        return building;
    }

    private RepairCode requireCode(long id, long tenantId) {
        RepairCode code = repairCodeMapper.selectOne(Wrappers.<RepairCode>lambdaQuery()
                .eq(RepairCode::getId, id)
                .eq(RepairCode::getTenantId, tenantId));
        if (code == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "报修码不存在");
        }
        return code;
    }

    private Map<Long, String> buildingNames(List<Long> buildingIds) {
        if (buildingIds.isEmpty()) {
            return Map.of();
        }
        return buildingMapper.selectByIds(buildingIds).stream()
                .collect(Collectors.toMap(Building::getId, Building::getName));
    }

}
