<template>
  <div class="app-container">
    <el-form ref="queryForm" :model="queryParams" :inline="true" size="small" label-width="88px" @submit.native.prevent="handleQuery">
      <el-form-item label="平台" prop="platform"><el-select v-model="queryParams.platform" clearable placeholder="全部平台"><el-option v-for="item in dict.platforms" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
      <el-form-item label="状态" prop="status"><el-select v-model="queryParams.status" clearable placeholder="全部状态"><el-option v-for="item in statuses" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
      <el-form-item><el-button type="primary" icon="el-icon-search" @click="handleQuery">搜索</el-button><el-button icon="el-icon-refresh" @click="resetQuery">重置</el-button></el-form-item>
    </el-form>
    <el-alert type="warning" :closable="false" show-icon title="接口返回的订单号已打码，不能满足后台完整显示要求；接口也未提供商品、付款金额、候选用户、进入时间或该订单的 PID 屏蔽依据。PID 屏蔽状态未由服务端返回时，页面无法判定禁认领规则。" />
    <el-row :gutter="10" class="mb8"><el-col :span="24"><right-toolbar :showSearch.sync="showSearch" @queryTable="getList" /></el-col></el-row>
    <el-table v-loading="loading" :data="pageRows">
      <el-table-column label="订单号（接口脱敏）" prop="maskedOrderNo" min-width="160" show-overflow-tooltip />
      <el-table-column label="平台" prop="platform" width="100"><template slot-scope="scope"><el-tag :type="platformType(scope.row.platform)">{{ platformLabel(scope.row.platform) }}</el-tag></template></el-table-column>
      <el-table-column label="商品标题" min-width="150"><template>接口未提供</template></el-table-column>
      <el-table-column label="付款金额" width="120" align="right"><template>接口未提供</template></el-table-column>
      <el-table-column label="进入原因" prop="reason" min-width="150" show-overflow-tooltip />
      <el-table-column label="候选用户数" width="110"><template>接口未提供</template></el-table-column>
      <el-table-column label="保留到期时间" prop="expiresAt" width="165"><template slot-scope="scope"><span :class="isExpired(scope.row.expiresAt) ? 'expired' : ''">{{ formatTime(scope.row.expiresAt) }}<template v-if="isExpired(scope.row.expiresAt)">（已过期）</template></span></template></el-table-column>
      <el-table-column label="状态" prop="status" width="125"><template slot-scope="scope"><el-tag :type="statusType(scope.row.status)">{{ statusLabel(scope.row.status) }}</el-tag></template></el-table-column>
      <el-table-column label="操作" width="160" fixed="right"><template slot-scope="scope">
        <el-button type="text" size="mini" v-hasPermi="['cps:order:query']" @click="handleDetail(scope.row)">详情</el-button>
        <el-button type="text" size="mini" v-hasPermi="['system:commission:unclaimed:edit']" :disabled="!canAssign(scope.row)" :title="actionTitle(scope.row)" @click="openAssign(scope.row)">指定归属</el-button>
      </template></el-table-column>
      <template slot="empty"><span>暂无数据</span></template>
    </el-table>
    <pagination v-show="total > 0" :total="total" :page.sync="queryParams.pageNum" :limit.sync="queryParams.pageSize" @pagination="updatePage" />

    <el-dialog title="指定订单归属" :visible.sync="assignOpen" width="520px" append-to-body :close-on-click-modal="false">
      <el-form ref="assignForm" :model="assignForm" :rules="rules" label-width="120px">
        <el-form-item label="订单号"><el-input :value="selected ? selected.maskedOrderNo : ''" disabled /></el-form-item>
        <el-form-item label="用户 ID" prop="userId"><el-input v-model.number="assignForm.userId" type="number" min="1" placeholder="请输入目标用户 ID" /></el-form-item>
        <el-form-item label="备注 / 原因" prop="reason"><el-input v-model="assignForm.reason" type="textarea" :rows="3" maxlength="500" show-word-limit placeholder="必填，写明核对依据" /></el-form-item>
      </el-form>
      <div slot="footer"><el-button type="primary" :loading="submitting" @click="submitAssign">确定</el-button><el-button :disabled="submitting" @click="assignOpen = false">取消</el-button></div>
    </el-dialog>
    <el-dialog title="订单详情" :visible.sync="detailOpen" width="900px" append-to-body>
      <div v-loading="detailLoading"><el-alert v-if="detailError" type="error" :closable="false" :title="detailError" /><el-descriptions v-if="detail && detail.order" :column="2" border><el-descriptions-item label="订单号">{{ detail.order.orderNo || '-' }}</el-descriptions-item><el-descriptions-item label="平台">{{ platformLabel(detail.order.platform) }}</el-descriptions-item><el-descriptions-item label="子订单号">{{ detail.order.subOrderNo || '-' }}</el-descriptions-item><el-descriptions-item label="商品标题">{{ detail.order.itemTitle || '-' }}</el-descriptions-item><el-descriptions-item label="付款金额">{{ money(detail.order.orderAmount) }}</el-descriptions-item><el-descriptions-item label="内部状态">{{ detail.order.status || '-' }}</el-descriptions-item></el-descriptions><el-alert v-if="detail && detail.order" class="gap-alert" type="warning" :closable="false" title="该详情响应未提供 displayStatus；请勿从内部状态猜显示状态。" /><el-empty v-if="!detailLoading && !detailError && !detail" description="订单不存在" /></div>
      <div slot="footer"><el-button @click="detailOpen = false">关闭</el-button></div>
    </el-dialog>
  </div>
</template>

<script>
import { getCpsDict } from '@/api/cps/dict'
import { listUnclaimedOrders, assignUnclaimedOrder } from '@/api/cps/attribution'
import { getCpsOrder } from '@/api/cps/orders'

export default {
  name: 'CpsUnclaimedOrders',
  data() {
    return {
      loading: false,
      showSearch: true,
      submitting: false,
      rows: [],
      total: 0,
      dict: { platforms: [] },
      statuses: [
        { value: 'OPEN', label: '待认领' }, { value: 'CLAIMED', label: '已认领' },
        { value: 'MANUAL_REVIEW', label: '已转人工' }, { value: 'PLATFORM', label: '已到期归平台' },
        { value: 'BLOCKED', label: '屏蔽 PID' }
      ],
      queryParams: { platform: undefined, status: undefined, limit: 500, pageNum: 1, pageSize: 10 },
      assignOpen: false,
      selected: null,
      assignForm: { userId: undefined, reason: '' },
      rules: { userId: [{ required: true, message: '请输入用户 ID', trigger: 'blur' }, { type: 'number', min: 1, message: '用户 ID 必须大于 0', trigger: 'blur' }], reason: [{ required: true, message: '请填写备注 / 原因', trigger: 'blur' }, { min: 1, max: 500, message: '原因最多 500 字', trigger: 'blur' }] },
      detailOpen: false,
      detailLoading: false,
      detail: null,
      detailError: ''
    }
  },
  computed: {
    pageRows() { const start = (this.queryParams.pageNum - 1) * this.queryParams.pageSize; return this.rows.slice(start, start + this.queryParams.pageSize) }
  },
  created() { this.loadDict(); this.getList() },
  methods: {
    loadDict() { getCpsDict().then(res => { this.dict = Object.assign(this.dict, res.data || {}) }).catch(() => {}) },
    getList() {
      this.loading = true
      listUnclaimedOrders({ platform: this.queryParams.platform, status: this.queryParams.status, limit: 500 }).then(res => {
        this.rows = Array.isArray(res.data) ? res.data : []
        this.total = this.rows.length
        this.queryParams.pageNum = Math.min(this.queryParams.pageNum, Math.max(1, Math.ceil(this.total / this.queryParams.pageSize)))
      }).catch(() => {}).finally(() => { this.loading = false })
    },
    handleQuery() { this.queryParams.pageNum = 1; this.getList() },
    updatePage() {},
    resetQuery() { this.resetForm('queryForm'); this.queryParams.pageNum = 1; this.getList() },
    canAssign(row) { return row.status === 'OPEN' && !this.isExpired(row.expiresAt) },
    actionTitle(row) { if (row.status === 'PLATFORM' || this.isExpired(row.expiresAt)) return '已到期订单不可认领'; if (row.status === 'BLOCKED') return '屏蔽 PID 下的订单不可认领'; if (row.status !== 'OPEN') return '当前状态不可认领'; return '' },
    openAssign(row) { this.selected = row; this.assignForm = { userId: undefined, reason: '' }; this.assignOpen = true },
    submitAssign() {
      this.$refs.assignForm.validate(valid => {
        if (!valid || this.submitting || !this.selected) return
        const id = this.selected.orderLineId
        const orderNo = this.selected.maskedOrderNo || '(接口返回脱敏订单号)'
        const userId = this.assignForm.userId
        this.submitting = true
        this.$modal.confirm('确认把订单 ' + orderNo + ' 归给用户 ' + userId + '？归属后会生成返利。备注：' + this.assignForm.reason).then(() => {
          return assignUnclaimedOrder(id, { userId: Number(userId), reason: this.assignForm.reason })
        }).then(() => {
          this.$modal.msgSuccess('订单归属处理成功')
          this.assignOpen = false
          this.getList()
        }).catch(() => {}).finally(() => { this.submitting = false })
      })
    },
    handleDetail(row) {
      this.detail = null; this.detailError = ''; this.detailOpen = true; this.detailLoading = true
      getCpsOrder(row.orderLineId).then(res => { const data = res.data || {}; if (data.order) this.detail = data; else this.detailError = res.msg || '订单不存在' }).catch(error => { this.detailError = (error && error.message) || '订单详情加载失败' }).finally(() => { this.detailLoading = false })
    },
    platformLabel(value) { const item = this.dict.platforms.find(x => x.value === String(value || '').toLowerCase()); return item ? item.label : (value || '-') },
    platformType(value) { return ({ TBK: 'warning', JD: 'danger', PDD: 'success' })[String(value || '').toUpperCase()] || 'info' },
    statusLabel(value) { const item = this.statuses.find(x => x.value === value); return item ? item.label : (value || '-') },
    statusType(value) { return ({ OPEN: 'warning', CLAIMED: 'success', MANUAL_REVIEW: 'danger', PLATFORM: 'info', BLOCKED: 'danger' })[value] || 'info' },
    isExpired(value) { return value ? new Date(value).getTime() <= Date.now() : false },
    formatTime(value) { return value ? this.parseTime(value) : '-' },
    money(value) { return value === null || value === undefined || value === '' ? '-' : '¥' + String(value) }
  }
}
</script>

<style scoped>
.expired { color: #f56c6c; }
.gap-alert { margin-top: 12px; }
</style>
