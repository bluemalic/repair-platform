<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  markAllNotificationsRead,
  markNotificationRead,
  pageNotifications,
} from '@/api/notification'
import { refreshUnread, unread } from '@/store/notification'
import type { NotificationVO } from '@/types'

const router = useRouter()

const loading = ref(false)
const rows = ref<NotificationVO[]>([])
const total = ref(0)
/** undefined = 全部；0 = 只看未读；1 = 只看已读 */
const query = reactive({ pageNum: 1, pageSize: 10, isRead: undefined as number | undefined })

async function load() {
  loading.value = true
  try {
    const page = await pageNotifications({ ...query })
    rows.value = page.list
    total.value = page.total
  } finally {
    loading.value = false
  }
}

function filter(isRead: number | undefined) {
  query.isRead = isRead
  query.pageNum = 1
  return load()
}

async function readOne(row: NotificationVO) {
  await markNotificationRead(row.id)
  // 本地直接改掉，省一次列表请求；角标仍以服务端为准刷一次
  row.isRead = 1
  await refreshUnread()
}

async function readAll() {
  await markAllNotificationsRead()
  ElMessage.success('已全部标为已读')
  await Promise.all([load(), refreshUnread()])
}

/** 带工单的通知 → 跳到工单列表并自动打开那条详情（列表页读 ?ticketId）。 */
function openTicket(row: NotificationVO) {
  if (row.ticketId) {
    router.push({ path: '/tickets', query: { ticketId: row.ticketId } })
  }
}

onMounted(async () => {
  await load()
  await refreshUnread()
})
</script>

<template>
  <div>
    <div class="toolbar">
      <el-radio-group v-model="query.isRead" @change="filter(query.isRead)">
        <el-radio-button :value="undefined">全部</el-radio-button>
        <el-radio-button :value="0">未读</el-radio-button>
        <el-radio-button :value="1">已读</el-radio-button>
      </el-radio-group>
      <span class="muted">未读 {{ unread }} 条</span>
      <el-button :disabled="unread === 0" @click="readAll">全部标为已读</el-button>
      <el-button @click="load">刷新</el-button>
    </div>

    <el-table v-loading="loading" :data="rows" border>
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag v-if="row.isRead === 0" type="danger" size="small">未读</el-tag>
          <span v-else class="muted">已读</span>
        </template>
      </el-table-column>
      <el-table-column prop="title" label="标题" min-width="180" />
      <el-table-column prop="content" label="内容" min-width="240" show-overflow-tooltip />
      <el-table-column prop="createTime" label="时间" width="170" />
      <el-table-column label="操作" width="170" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.ticketId" link type="primary" @click="openTicket(row)">查看工单</el-button>
          <el-button v-if="row.isRead === 0" link @click="readOne(row)">标记已读</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      class="pager"
      layout="total, prev, pager, next"
      :total="total"
      :page-size="query.pageSize"
      :current-page="query.pageNum"
      @current-change="(p: number) => { query.pageNum = p; load() }"
    />
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}
.muted {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.pager {
  margin-top: 12px;
  justify-content: flex-end;
}
</style>
