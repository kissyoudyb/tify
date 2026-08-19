<template>
  <div class="workflow-runs">
    <div class="page-header">
      <div>
        <h2 class="page-title">执行记录</h2>
        <p class="page-desc">{{ workflowName ? `工作流：${workflowName}` : '每次工作流执行的节点明细与可观测性数据' }}</p>
      </div>
      <el-button @click="$router.push('/workflows')">返回列表</el-button>
    </div>

    <el-table :data="runs" v-loading="loading" class="runs-table" stripe>
      <el-table-column prop="id" label="Run ID" width="90" />
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="runStatusType(row.status)" size="small">{{ runStatusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="input" label="输入" min-width="180" show-overflow-tooltip />
      <el-table-column prop="output" label="输出" min-width="200" show-overflow-tooltip />
      <el-table-column label="耗时" width="100">
        <template #default="{ row }">{{ row.elapsedMs != null ? row.elapsedMs + ' ms' : '-' }}</template>
      </el-table-column>
      <el-table-column label="开始时间" width="170">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="viewRun(row)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager-row">
      <el-pagination
        layout="prev, pager, next, total"
        :total="total"
        :page-size="pageSize"
        :current-page="page"
        @current-change="onPageChange"
      />
    </div>

    <!-- 执行详情抽屉：run + 节点明细 -->
    <el-drawer v-model="drawerVisible" :title="`Run #${detail?.run?.id ?? ''} 执行详情`" size="720px" direction="rtl">
      <div v-if="detail" class="detail-panel">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="状态">
            <el-tag :type="runStatusType(detail.run.status)" size="small">{{ runStatusLabel(detail.run.status) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="总耗时">{{ detail.run.elapsedMs ?? '-' }} ms</el-descriptions-item>
          <el-descriptions-item label="输入" :span="2">{{ detail.run.input || '-' }}</el-descriptions-item>
          <el-descriptions-item label="输出" :span="2">{{ detail.run.output || '-' }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.run.error" label="错误" :span="2">
            <span class="error-text">{{ detail.run.error }}</span>
          </el-descriptions-item>
        </el-descriptions>

        <div class="detail-section">
          <h4>节点执行明细 ({{ detail.nodeRuns.length }})</h4>
          <div v-for="n in detail.nodeRuns" :key="n.id" class="node-run-card">
            <div class="node-run-header">
              <el-tag :type="nodeTypeColor(n.nodeType)" size="small" effect="dark">{{ n.nodeType }}</el-tag>
              <span class="node-key">{{ n.nodeKey }}</span>
              <el-tag :type="runStatusType(n.status)" size="small">{{ runStatusLabel(n.status) }}</el-tag>
              <span class="node-elapsed">{{ n.elapsedMs ?? '-' }} ms</span>
            </div>
            <div v-if="n.error" class="node-error">{{ n.error }}</div>
            <div v-if="n.outputs && Object.keys(n.outputs).length" class="node-outputs">
              <pre>{{ JSON.stringify(n.outputs, null, 2) }}</pre>
            </div>
          </div>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { listWorkflowRuns, getWorkflow, getWorkflowRun, type WorkflowRun, type WorkflowRunDetail } from '@/api/workflow'

const route = useRoute()
const workflowId = Number(route.params.id)

const loading = ref(false)
const runs = ref<WorkflowRun[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = 10

const workflowName = ref('')
const drawerVisible = ref(false)
const detail = ref<WorkflowRunDetail | null>(null)

async function fetchRuns() {
  loading.value = true
  try {
    const res = await listWorkflowRuns(workflowId, { page: page.value, pageSize }) as any
    runs.value = res?.list || []
    total.value = res?.total || 0
  } finally {
    loading.value = false
  }
}

function onPageChange(p: number) {
  page.value = p
  fetchRuns()
}

async function viewRun(row: WorkflowRun) {
  detail.value = await getWorkflowRun(workflowId, row.id)
  drawerVisible.value = true
}

function runStatusLabel(s: string) {
  return { RUNNING: '执行中', SUCCESS: '成功', FAILED: '失败' }[s] || s
}

function runStatusType(s: string) {
  return { RUNNING: 'warning', SUCCESS: 'success', FAILED: 'danger' }[s] || 'info'
}

function nodeTypeColor(type: string) {
  const map: Record<string, string> = {
    START: 'success', END: 'danger', LLM: 'primary', CONDITION: 'warning',
    KNOWLEDGE: '', API_CALL: 'info'
  }
  return map[type] || ''
}

function formatTime(t: string) {
  if (!t) return '-'
  return t.replace('T', ' ').substring(0, 19)
}

onMounted(async () => {
  fetchRuns()
  try {
    const wf = await getWorkflow(workflowId) as any
    workflowName.value = wf?.name || ''
  } catch { /* 忽略，名称非关键 */ }
})
</script>

<style scoped>
.workflow-runs { padding: 0; }

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 20px;
}
.page-title { margin: 0 0 4px; font-size: 18px; font-weight: 600; color: var(--el-text-color-primary); }
.page-desc { margin: 0; font-size: 13px; color: var(--el-text-color-secondary); }

.runs-table { width: 100%; }
.pager-row { display: flex; justify-content: flex-end; margin-top: 16px; }

.detail-section { margin-top: 20px; }
.detail-section h4 { margin: 0 0 12px; font-size: 14px; font-weight: 600; color: var(--el-text-color-primary); }

.node-run-card {
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
  padding: 10px 12px;
  margin-bottom: 8px;
  background: var(--el-fill-color-extra-light);
}
.node-run-header { display: flex; align-items: center; gap: 8px; }
.node-key { font-family: monospace; font-size: 12px; color: var(--el-text-color-secondary); }
.node-elapsed { margin-left: auto; font-family: monospace; font-size: 12px; color: var(--el-text-color-secondary); }
.node-error { margin-top: 8px; font-size: 12px; color: var(--el-color-danger); }
.node-outputs {
  margin-top: 8px;
  padding: 8px;
  background: var(--el-fill-color);
  border-radius: 4px;
  overflow: auto;
  max-height: 160px;
}
.node-outputs pre { margin: 0; font-size: 11px; line-height: 1.5; color: var(--el-text-color-regular); }
.error-text { color: var(--el-color-danger); font-size: 12px; }
</style>
