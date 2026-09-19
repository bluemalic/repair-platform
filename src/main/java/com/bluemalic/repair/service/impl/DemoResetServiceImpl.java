package com.bluemalic.repair.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bluemalic.repair.common.TicketAction;
import com.bluemalic.repair.common.TicketStatus;
import com.bluemalic.repair.config.DemoProperties;
import com.bluemalic.repair.entity.Building;
import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.SysUserRole;
import com.bluemalic.repair.entity.Tenant;
import com.bluemalic.repair.entity.Ticket;
import com.bluemalic.repair.entity.TicketCategory;
import com.bluemalic.repair.entity.TicketEvaluation;
import com.bluemalic.repair.entity.TicketLog;
import com.bluemalic.repair.entity.WorkerBuilding;
import com.bluemalic.repair.mapper.BuildingMapper;
import com.bluemalic.repair.mapper.DemoResetMapper;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.mapper.SysUserRoleMapper;
import com.bluemalic.repair.mapper.TenantMapper;
import com.bluemalic.repair.mapper.TicketCategoryMapper;
import com.bluemalic.repair.mapper.TicketEvaluationMapper;
import com.bluemalic.repair.mapper.TicketLogMapper;
import com.bluemalic.repair.mapper.TicketMapper;
import com.bluemalic.repair.mapper.WorkerBuildingMapper;
import com.bluemalic.repair.service.DemoResetService;
import com.bluemalic.repair.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 演示数据重置的实现。要解决的场景与取舍见 {@link DemoResetService} 的类注释。
 *
 * <p>三个实现上的关键点：
 * <ol>
 *   <li><b>业务数据用物理删除</b>（{@code DemoResetMapper} 里的 DELETE），不走 MP 的逻辑删除。
 *       每天逻辑删一遍的话表会一天比一天大，而且旧行仍占着 {@code uk_ticket_no} 唯一索引，
 *       迟早撞键——这种故障会在某天早上突然出现，很难往"演示重置"上想。</li>
 *   <li><b>演示账号每次重置都重设口令。</b>访客是能登录管理端的，他可以在"修改密码"里改掉
 *       演示口令，那之后演示站就废了（登录页上写的口令登不上）。重置把口令改回来。</li>
 *   <li><b>对象存储的清理放在事务提交之后。</b>数据回滚了却已经把图删了，会造成"工单还在、
 *       图片全裂"，比反过来糟得多。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemoResetServiceImpl implements DemoResetService {

    private static final DateTimeFormatter TICKET_NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 演示账号（口令统一取配置里的那个，见 {@link DemoProperties}）。 */
    private static final List<AccountSpec> ACCOUNTS = List.of(
            new AccountSpec("admin", "后勤管理员", 3, 3L),
            new AccountSpec("worker01", "维修工小李", 2, 2L),
            new AccountSpec("worker02", "维修工小张", 2, 2L),
            new AccountSpec("20260001", "学生小王", 1, 1L),
            new AccountSpec("20260002", "学生小李", 1, 1L));

    /** 演示站要保证存在的楼栋与类别（被访客删掉时补回来；已有的不动）。 */
    private static final List<String> DEMO_BUILDINGS = List.of("1号楼", "2号楼", "3号楼");

    /** **必须覆盖 {@link #SPECS} 里用到的每一个类别名**：少一个就会在建工单时取到 null。 */
    private static final List<String> DEMO_CATEGORIES = List.of("水电", "家具", "网络", "门锁", "空调", "其他");

    /**
     * 演示工单的剧本。
     *
     * <p>{@code submitMinutesAgo} 是"提交时间距今多少分钟"，其余节点按固定间隔往后推。
     * **有两种量级，是刻意的**：
     * <ul>
     *   <li>未结束的工单（10~40）都放在最近几小时内——超时阈值是 24h/48h，
     *       时间往前放会立刻触发"接单超时提醒 / 处理超时升级"，把演示站变成一屏通知</li>
     *   <li>已结束的工单（50~80）往前铺到一周，让统计看板的「报修量趋势」有起伏；
     *       这些是终态，不会再被超时任务碰到</li>
     * </ul>
     */
    private static final List<TicketSpec> SPECS = List.of(
            // ---- 活跃工单：全部在最近几小时内，不触发超时 ----
            new TicketSpec(TicketStatus.TO_DISPATCH, 25, "水电", "3-412",
                    "卫生间水管接头一直在滴水，地上已经积了一片", null, null, null),
            new TicketSpec(TicketStatus.TO_ACCEPT, 120, "家具", "1-101",
                    "椅子有一条腿断了，坐上去会塌", null, null, null),
            new TicketSpec(TicketStatus.PROCESSING, 260, "门锁", "2-201",
                    "宿舍门锁转不动，钥匙插进去卡住", null, null, null),
            new TicketSpec(TicketStatus.TO_VERIFY, 200, "网络", "3-412",
                    "墙上网口不通，换了两根网线都不行", "网口模块老化，已更换并测通", null, null),
            new TicketSpec(TicketStatus.FINISHED, 300, "空调", "1-101",
                    "空调只出风不制冷，开一小时还是热的", "冷媒不足，已补充并清洗滤网", 5, "师傅来得很快，修完就凉了"),
            // ---- 终态工单：往前铺开，让趋势图有起伏；评价让满意度有数据 ----
            new TicketSpec(TicketStatus.CLOSED, 24 * 60, "水电", "2-201",
                    "洗手池下面漏水", "更换了密封圈", 5, "处理得干净，地上也擦过了"),
            new TicketSpec(TicketStatus.CLOSED, 2 * 24 * 60, "门锁", "1-101",
                    "门禁刷卡没反应", "读卡器接线松动，已重新压接", 4, "修好了，但等了一天"),
            new TicketSpec(TicketStatus.CLOSED, 3 * 24 * 60, "网络", "3-412",
                    "宿舍网速很慢，晚上几乎打不开网页", "交换机端口限速配置错误，已修正", 5, "网速恢复正常"),
            new TicketSpec(TicketStatus.CLOSED, 5 * 24 * 60, "家具", "2-201",
                    "衣柜门合不上", "合页变形，已更换", 3, "能用，但颜色和原来的不太一样"),
            new TicketSpec(TicketStatus.CLOSED, 6 * 24 * 60, "空调", "3-412",
                    "空调外机噪音很大", "外机支架螺丝松动，已紧固", 4, "噪音小多了"),
            new TicketSpec(TicketStatus.CANCELED, 8 * 60, "其他", "1-101",
                    "窗帘轨道掉了（后来自己装回去了）", null, null, null),
            new TicketSpec(TicketStatus.REJECTED, 7 * 60, "水电", "2-201",
                    "想换一个更大的热水器", null, null, null));

    private final DemoProperties demo;
    private final PasswordEncoder passwordEncoder;
    private final StringRedisTemplate redis;
    private final PlatformTransactionManager transactionManager;
    private final FileStorageService fileStorageService;
    private final DemoResetMapper demoResetMapper;
    private final TenantMapper tenantMapper;
    private final SysUserMapper sysUserMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final WorkerBuildingMapper workerBuildingMapper;
    private final BuildingMapper buildingMapper;
    private final TicketCategoryMapper categoryMapper;
    private final TicketMapper ticketMapper;
    private final TicketLogMapper ticketLogMapper;
    private final TicketEvaluationMapper ticketEvaluationMapper;

    @Override
    public Result reset() {
        long tenantId = demo.getTenantId();
        Tenant tenant = tenantMapper.selectById(tenantId);
        if (tenant == null) {
            throw new IllegalStateException("演示重置的租户不存在：tenantId=" + tenantId
                    + "（repair.demo.tenant-id 配错了？schema.sql 的种子租户是 1）");
        }

        // 数据库部分一个事务；对象存储的清理放到提交之后（见类注释第 3 条）
        Result db = new TransactionTemplate(transactionManager).execute(status -> resetDatabase(tenantId));
        int purged = fileStorageService.purgeTenant(tenantId);

        Result result = new Result(db.deletedTickets(), db.createdTickets(), db.createdAccounts(), purged);
        log.info("演示数据重置完成 tenantId={} 清掉工单={} 重建工单={} 演示账号={} 清理图片={}",
                tenantId, result.deletedTickets(), result.createdTickets(),
                result.createdAccounts(), result.purgedObjects());
        return result;
    }

    private Result resetDatabase(long tenantId) {
        int deletedTickets = demoResetMapper.deleteTickets(tenantId);
        demoResetMapper.deleteTicketLogs(tenantId);
        demoResetMapper.deleteEvaluations(tenantId);
        demoResetMapper.deleteNotifications(tenantId);
        demoResetMapper.deleteWorkerBuildings(tenantId);

        Map<String, Long> categories = ensureCategories(tenantId);
        List<Building> buildings = ensureBuildings(tenantId);
        Map<String, SysUser> users = ensureAccounts(tenantId);
        ensureWorkerBuildings(tenantId, users, buildings);

        int created = seedTickets(tenantId, users, buildings, categories);
        return new Result(deletedTickets, created, ACCOUNTS.size(), 0);
    }

    // ==================== 基础数据：缺了才补，已有的不动 ====================

    private Map<String, Long> ensureCategories(long tenantId) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (String name : DEMO_CATEGORIES) {
            TicketCategory existing = categoryMapper.selectOne(Wrappers.<TicketCategory>lambdaQuery()
                    .eq(TicketCategory::getTenantId, tenantId)
                    .eq(TicketCategory::getName, name)
                    .last("LIMIT 1"));
            if (existing != null) {
                result.put(name, existing.getId());
                continue;
            }
            TicketCategory category = new TicketCategory();
            category.setTenantId(tenantId);
            category.setName(name);
            category.setDefaultUrgency(2);
            category.setSort(result.size() + 1);
            category.setStatus(1);
            categoryMapper.insert(category);
            result.put(name, category.getId());
            log.info("演示重置补建报修类别 tenantId={} name={}", tenantId, name);
        }
        return result;
    }

    private List<Building> ensureBuildings(long tenantId) {
        for (int i = 0; i < DEMO_BUILDINGS.size(); i++) {
            String name = DEMO_BUILDINGS.get(i);
            Long count = buildingMapper.selectCount(Wrappers.<Building>lambdaQuery()
                    .eq(Building::getTenantId, tenantId)
                    .eq(Building::getName, name));
            if (count != null && count > 0) {
                continue;
            }
            Building building = new Building();
            building.setTenantId(tenantId);
            building.setName(name);
            building.setArea("东区");
            building.setSort(i + 1);
            building.setStatus(1);
            buildingMapper.insert(building);
            log.info("演示重置补建楼栋 tenantId={} name={}", tenantId, name);
        }
        return buildingMapper.selectList(Wrappers.<Building>lambdaQuery()
                .eq(Building::getTenantId, tenantId)
                .orderByAsc(Building::getSort)
                .last("LIMIT 3"));
    }

    // ==================== 演示账号 ====================

    private Map<String, SysUser> ensureAccounts(long tenantId) {
        // 五个账号共用同一个口令：**演示站上这是刻意的**——口令要写在登录页给访客抄，
        // 一人一个反而没人记得住。所以这里只编码一次，五个用户共用同一串哈希。
        String encoded = passwordEncoder.encode(demo.getPassword());
        Map<String, SysUser> result = new LinkedHashMap<>();
        for (AccountSpec spec : ACCOUNTS) {
            SysUser user = sysUserMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                    .eq(SysUser::getTenantId, tenantId)
                    .eq(SysUser::getUsername, spec.username()));
            if (user == null) {
                user = new SysUser();
                user.setTenantId(tenantId);
                user.setUsername(spec.username());
                user.setPassword(encoded);
                user.setRealName(spec.realName());
                user.setUserType(spec.userType());
                user.setStatus(1);
                sysUserMapper.insert(user);
            } else {
                // 已存在：把口令改回来（访客可能改过）、并保证是启用状态
                user.setPassword(encoded);
                user.setRealName(spec.realName());
                user.setUserType(spec.userType());
                user.setStatus(1);
                sysUserMapper.updateById(user);
            }

            // 角色关联删了重建：保证只有该有的那一条，不会因为重复重置堆出多条
            sysUserRoleMapper.delete(Wrappers.<SysUserRole>lambdaQuery().eq(SysUserRole::getUserId, user.getId()));
            SysUserRole link = new SysUserRole();
            link.setUserId(user.getId());
            link.setRoleId(spec.roleId());
            sysUserRoleMapper.insert(link);

            result.put(spec.username(), user);
        }
        return result;
    }

    private void ensureWorkerBuildings(long tenantId, Map<String, SysUser> users, List<Building> buildings) {
        assign(tenantId, users.get("worker01"), buildings.subList(0, Math.min(2, buildings.size())));
        assign(tenantId, users.get("worker02"), buildings.subList(Math.max(0, buildings.size() - 1), buildings.size()));
    }

    private void assign(long tenantId, SysUser worker, List<Building> buildings) {
        for (Building building : buildings) {
            WorkerBuilding link = new WorkerBuilding();
            link.setTenantId(tenantId);
            link.setWorkerId(worker.getId());
            link.setBuildingId(building.getId());
            workerBuildingMapper.insert(link);
        }
    }

    // ==================== 演示工单 ====================

    private int seedTickets(long tenantId, Map<String, SysUser> users, List<Building> buildings,
                            Map<String, Long> categories) {
        SysUser worker1 = users.get("worker01");
        SysUser worker2 = users.get("worker02");
        SysUser student1 = users.get("20260001");
        SysUser student2 = users.get("20260002");
        LocalDateTime now = LocalDateTime.now();
        int created = 0;

        for (int i = 0; i < SPECS.size(); i++) {
            TicketSpec spec = SPECS.get(i);
            Building building = buildings.get(i % buildings.size());
            Ticket ticket = new Ticket();
            ticket.setTenantId(tenantId);
            ticket.setTicketNo(nextTicketNo());
            ticket.setStudentId(i % 2 == 0 ? student1.getId() : student2.getId());
            ticket.setBuildingId(building.getId());
            ticket.setRoom(spec.room());
            ticket.setCategoryId(requireCategory(categories, spec.category()));
            ticket.setDescription(spec.description());
            ticket.setUrgency(2);
            ticket.setStatus(spec.status().getCode());
            ticket.setResultDesc(spec.resultDesc());
            // 让两个维修工的活分布不均，看板上的「师傅工作量」才有对比
            ticket.setWorkerId(i % 3 == 0 ? worker2.getId() : worker1.getId());

            LocalDateTime submit = now.minusMinutes(spec.submitMinutesAgo());
            fillTimeline(ticket, submit);
            ticketMapper.insert(ticket);
            created++;

            writeLogs(tenantId, ticket, submit, spec);
            if (spec.score() != null) {
                TicketEvaluation evaluation = new TicketEvaluation();
                evaluation.setTenantId(tenantId);
                evaluation.setTicketId(ticket.getId());
                evaluation.setStudentId(ticket.getStudentId());
                evaluation.setScore(spec.score());
                evaluation.setContent(spec.comment());
                evaluation.setCreateTime(ticket.getFinishTime().plusMinutes(30));
                ticketEvaluationMapper.insert(evaluation);
            }
        }
        return created;
    }

    /**
     * 按状态倒推各节点时间，并算出两个冗余时长字段。
     *
     * <p>节点间隔是固定的（派单 +5min、接单 +15min、到场 +30min、完工 +60min），
     * 这样演示数据看起来像正常流程走出来的，而不是同一秒内批量生成的。
     */
    private void fillTimeline(Ticket ticket, LocalDateTime submit) {
        int status = ticket.getStatus();
        ticket.setSubmitTime(submit);
        if (status == TicketStatus.CANCELED.getCode()) {
            // 10 → 70：只有提交，没有派单
            ticket.setCloseTime(submit.plusMinutes(20));
            return;
        }
        if (status >= TicketStatus.TO_ACCEPT.getCode()) {
            ticket.setDispatchTime(submit.plusMinutes(5));
            ticket.setDispatchType(1);
        }
        if (status == TicketStatus.REJECTED.getCode()) {
            // 20 → 80：驳回到此为止
            ticket.setRejectReason("不属于后勤维修范围，请自行联系商家售后");
            return;
        }
        if (status >= TicketStatus.PROCESSING.getCode()) {
            ticket.setAcceptTime(submit.plusMinutes(15));
            ticket.setArriveTime(submit.plusMinutes(30));
            ticket.setArriveMinutes(minutesBetween(ticket.getDispatchTime(), ticket.getArriveTime()));
        }
        if (status >= TicketStatus.TO_VERIFY.getCode()) {
            ticket.setFinishTime(submit.plusMinutes(60));
            ticket.setHandleMinutes(minutesBetween(ticket.getArriveTime(), ticket.getFinishTime()));
        }
        if (status >= TicketStatus.FINISHED.getCode()) {
            ticket.setCloseTime(submit.plusMinutes(90));
        }
    }

    private void writeLogs(long tenantId, Ticket ticket, LocalDateTime submit, TicketSpec spec) {
        int status = ticket.getStatus();
        long studentId = ticket.getStudentId();
        long workerId = ticket.getWorkerId();

        log(tenantId, ticket.getId(), null, TicketStatus.TO_DISPATCH.getCode(), TicketAction.SUBMIT,
                studentId, submit);
        if (status == TicketStatus.CANCELED.getCode()) {
            log(tenantId, ticket.getId(), TicketStatus.TO_DISPATCH.getCode(), TicketStatus.CANCELED.getCode(),
                    TicketAction.CANCEL, studentId, ticket.getCloseTime());
            return;
        }
        if (status == TicketStatus.REJECTED.getCode()) {
            // 驳回发生在派单之后（20 → 80），此时还没有接单
            log(tenantId, ticket.getId(), TicketStatus.TO_ACCEPT.getCode(), TicketStatus.REJECTED.getCode(),
                    TicketAction.REJECT, workerId, ticket.getDispatchTime().plusMinutes(10));
            return;
        }
        log(tenantId, ticket.getId(), TicketStatus.TO_DISPATCH.getCode(), TicketStatus.TO_ACCEPT.getCode(),
                TicketAction.DISPATCH, null, ticket.getDispatchTime());
        if (status < TicketStatus.PROCESSING.getCode()) {
            return;
        }
        log(tenantId, ticket.getId(), TicketStatus.TO_ACCEPT.getCode(), TicketStatus.PROCESSING.getCode(),
                TicketAction.ACCEPT, workerId, ticket.getAcceptTime());
        log(tenantId, ticket.getId(), TicketStatus.PROCESSING.getCode(), TicketStatus.PROCESSING.getCode(),
                TicketAction.ARRIVE, workerId, ticket.getArriveTime());
        if (status < TicketStatus.TO_VERIFY.getCode()) {
            return;
        }
        log(tenantId, ticket.getId(), TicketStatus.PROCESSING.getCode(), TicketStatus.TO_VERIFY.getCode(),
                TicketAction.FINISH, workerId, ticket.getFinishTime());
        if (status < TicketStatus.FINISHED.getCode()) {
            return;
        }
        log(tenantId, ticket.getId(), TicketStatus.TO_VERIFY.getCode(), TicketStatus.FINISHED.getCode(),
                TicketAction.EVALUATE, studentId, ticket.getFinishTime().plusMinutes(30));
        if (status == TicketStatus.CLOSED.getCode()) {
            log(tenantId, ticket.getId(), TicketStatus.FINISHED.getCode(), TicketStatus.CLOSED.getCode(),
                    TicketAction.CLOSE, studentId, ticket.getCloseTime());
        }
    }

    private void log(long tenantId, long ticketId, Integer from, int to, TicketAction action,
                     Long operatorId, LocalDateTime at) {
        TicketLog entry = new TicketLog();
        entry.setTenantId(tenantId);
        entry.setTicketId(ticketId);
        entry.setFromStatus(from);
        entry.setToStatus(to);
        entry.setAction(action.name());
        // operator_id = 0 表示"系统"（见 docs/02）：派单在演示数据里由系统动作发起，没有操作人
        entry.setOperatorId(operatorId == null ? 0L : operatorId);
        entry.setCreateTime(at);
        ticketLogMapper.insert(entry);
    }

    private static Integer minutesBetween(LocalDateTime from, LocalDateTime to) {
        if (from == null || to == null) {
            return null;
        }
        return (int) ChronoUnit.MINUTES.between(from, to);
    }

    /**
     * 取类别 ID。取不到就抛一句能直接指出问题的错——不这么做的话，工单插库时会报
     * "Field 'category_id' doesn't have a default value"：那是 MySQL 在说"这列不能空"，
     * 但看不出是"剧本里写了个不存在的类别名"。（这个错是测试抓出来的，见过一次就够。）
     */
    private static Long requireCategory(Map<String, Long> categories, String name) {
        Long id = categories.get(name);
        if (id == null) {
            throw new IllegalStateException("演示工单引用了 DEMO_CATEGORIES 里没有的类别：" + name
                    + "（剧本里的类别名和那份清单必须对得上）");
        }
        return id;
    }

    /**
     * 工单号：{@code WX + yyyyMMdd + 6 位当日序号}。
     *
     * <p><b>与 {@code TicketServiceImpl.nextTicketNo} 是同一套格式和同一个 Redis 序列</b>
     * （键 {@code ticket:no:seq:{日期}}），所以演示工单号与真实工单号不会撞、看起来也是一套。
     *
     * <p>这里是**刻意复制的**，没有抽公共类：抽的话要改 {@code TicketServiceImpl}——
     * 那是系统里最核心的写路径，刚上线不久，为几行格式化代码去动它并重跑全部工单测试，
     * 不值得。代价是格式改动时要改两处，所以在两处都写了这条注释。（与 worker 管理里
     * {@code clamp} 的处理同一个判断标准：等第三处出现再抽。）
     */
    private String nextTicketNo() {
        String date = LocalDate.now().format(TICKET_NO_DATE);
        String key = "ticket:no:seq:" + date;
        Long seq = redis.opsForValue().increment(key);
        redis.expire(key, Duration.ofDays(2));
        return "WX" + date + String.format("%06d", seq == null ? 1 : seq);
    }

    private record AccountSpec(String username, String realName, int userType, long roleId) {
    }

    private record TicketSpec(TicketStatus status, int submitMinutesAgo, String category, String room,
                              String description, String resultDesc, Integer score, String comment) {
    }
}
