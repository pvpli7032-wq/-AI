<template>
  <div class="app-container">
    <el-form ref="queryForm" :model="queryParams" :inline="true" size="small" label-width="88px" @submit.native.prevent="handleQuery">
      <el-form-item label="平台" prop="platform">
        <el-select v-model="queryParams.platform" clearable placeholder="全部平台">
          <el-option v-for="item in dict.platforms" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="内部状态" prop="status">
        <el-select v-model="queryParams.status" clearable placeholder="全部内部状态">
          <el-option v-for="item in dict.orderStatuses" :key="item.value" :label="item.value + ' · ' + item.label" :value="item.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="绑定方式" prop="method">
        <el-select v-model="queryParams.method" clearable placeholder="全部绑定方式">
          <el-option v-for="item in dict.attributionMethods" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" @click="handleQuery">搜索</el-button>
        <el-button icon="el-icon-refresh" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <el-alert v-if="apiGaps.length" type="warning" :closable="false" show-icon class="gap-alert">
      <template slot="title">当前订单接口只支持平台、内部状态、归因方式筛选；展示状态、金额拆分、可疑标记和原始数据未由接口提供。</template>
    </el-alert>

    <el-row :gutter="10" class="mb8">
      <el-col :span="24"><right-toolbar :showSearch.sync="showSearch" @queryTable="getList" /></el-col>
    </el-row>

    <el-table v-loading="loading" :data="orderList" @row-dblclick="handleView">
      <el-table-column label="订单号" prop="orderNo" min-width="170" show-overflow-tooltip />
      <el-table-column label="子订单号" prop="subOrderNo" min-width="145" show-overflow-tooltip />
      <el-table-column label="平台" prop="platform" width="100" align="center">
        <template slot-scope="scope"><el-tag v-if="scope.row.platform" :type="platformType(scope.row.platform)">{{ platformLabel(scope.row.platform) }}</el-tag><span v-else>-</span></template>
      </el-table-column>
      <el-table-column label="商品标题" prop="itemTitle" min-width="180" show-overflow-tooltip />
      <el-table-column label="付款金额" prop="orderAmount" width="120" align="right"><template slot-scope="scope">{{ money(scope.row.orderAmount) }}</template></el-table-column>
      <el-table-column label="预估佣金" prop="estimatedCommission" width="120" align="right"><template slot-scope="scope">{{ money(scope.row.estimatedCommission) }}</template></el-table-column>
      <el-table-column label="实际佣金" prop="actualCommission" width="120" align="right"><template slot-scope="scope">{{ money(scope.row.actualCommission) }}</template></el-table-column>
      <el-table-column label="展示状态" prop="displayStatus" width="100" align="center"><template slot-scope="scope"><el-tag v-if="scope.row.displayStatus" :type="displayStatusType(scope.row.displayStatus)">{{ scope.row.displayStatus }}</el-tag><span v-else>-</span></template></el-table-column>
      <el-table-column label="联盟原始状态" prop="rawStatus" width="140" show-overflow-tooltip />
      <el-table-column label="绑定方式" prop="attributionMethod" width="130"><template slot-scope="scope">{{ methodLabel(scope.row.attributionMethod) }}</template></el-table-column>
      <el-table-column label="归属用户" prop="attributedUserId" width="100" />
      <el-table-column label="下单时间" prop="paidTime" width="160"><template slot-scope="scope">{{ formatTime(scope.row.paidTime) }}</template></el-table-column>
      <el-table-column label="操作" width="90" fixed="right" align="center"><template slot-scope="scope"><el-button type="text" size="mini" v-hasPermi="['cps:order:query']" @click="handleView(scope.row)">详情</el-button></template></el-table-column>
      <template slot="empty"><span>暂无数据</span></template>
    </el-table>

    <pagination v-show="total > 0" :total="total" :page.sync="queryParams.pageNum" :limit.sync="queryParams.pageSize" @pagination="getList" />

    <el-dialog title="订单详情" :visible.sync="detailOpen" width="900px" append-to-body :close-on-click-modal="false">
      <div v-loading="detailLoading" class="detail-body">
        <el-alert v-if="detailError" type="error" :closable="false" :title="detailError" />
        <template v-if="detail">
          <el-descriptions title="基本信息" :column="2" border>
            <el-descriptions-item label="平台">{{ platformLabel(detail.order.platform) }}</el-descriptions-item>
            <el-descriptions-item label="展示状态">{{ detail.order.displayStatus || '-' }}</el-descriptions-item>
            <el-descriptions-item label="订单号">{{ detail.order.orderNo || '-' }}</el-descriptions-item>
            <el-descriptions-item label="子订单号">{{ detail.order.subOrderNo || '-' }}</el-descriptions-item>
            <el-descriptions-item label="商品 ID">{{ detail.order.itemId || '-' }}</el-descriptions-item>
            <el-descriptions-item label="商品标题" :span="2">{{ detail.order.itemTitle || '-' }}</el-descriptions-item>
            <el-descriptions-item label="付款金额">{{ money(detail.order.orderAmount) }}</el-descriptions-item>
            <el-descriptions-item label="预估佣金">{{ money(detail.order.estimatedCommission) }}</el-descriptions-item>
            <el-descriptions-item label="实际佣金">{{ money(detail.order.actualCommission) }}</el-descriptions-item>
            <el-descriptions-item label="联盟原始状态">{{ detail.order.rawStatus || '-' }}</el-descriptions-item>
            <el-descriptions-item label="付款时间">{{ formatTime(detail.order.paidTime) }}</el-descriptions-item>
            <el-descriptions-item label="收货时间">{{ formatTime(detail.order.receivedTime) }}</el-descriptions-item>
            <el-descriptions-item label="结算时间">{{ formatTime(detail.order.settleTime) }}</el-descriptions-item>
            <el-descriptions-item label="当前归属用户">{{ detail.order.attributedUserId || '-' }}</el-descriptions-item>
            <el-descriptions-item label="绑定方式">{{ methodLabel(detail.order.attributionMethod) }}</el-descriptions-item>
            <el-descriptions-item label="置信度">{{ detail.order.attributionConfidence || '-' }}</el-descriptions-item>
          </el-descriptions>
          <el-alert class="gap-alert" type="info" :closable="false" title="该接口未返回净佣金、用户返利、邀请提成、补贴、平台毛利、决策人/时间、异常记录或原始 JSON。" />
          <h3>归因历史</h3>
          <el-table :data="detail.attributionHistory || []" size="mini" border>
            <el-table-column label="时间" prop="createdAt" min-width="155"><template slot-scope="scope">{{ formatTime(scope.row.createdAt) }}</template></el-table-column>
            <el-table-column label="方式" prop="method"><template slot-scope="scope">{{ methodLabel(scope.row.method) }}</template></el-table-column>
            <el-table-column label="置信度" prop="confidence" />
            <el-table-column label="决策人" prop="decidedBy" />
            <el-table-column label="归属用户" prop="userId" />
            <el-table-column label="当前" prop="isCurrent"><template slot-scope="scope">{{ scope.row.isCurrent ? '是' : '否' }}</template></el-table-column>
          </el-table>
          <h3>状态历史</h3>
          <el-timeline v-if="detail.statusHistory && detail.statusHistory.length">
            <el-timeline-item v-for="(event, index) in detail.statusHistory" :key="index" :timestamp="formatTime(event.createdAt)" :type="event.source === 'MANUAL' ? 'warning' : 'primary'">
              {{ event.fromStatus || event.previousStatus || '—' }} → {{ event.toStatus || '—' }} · {{ event.source || '—' }}
            </el-timeline-item>
          </el-timeline>
          <el-empty v-else description="暂无状态历史" />
        </template>
        <el-empty v-else-if="!detailLoading && !detailError" description="订单不存在" />
      </div>
      <div slot="footer"><el-button @click="detailOpen = false">关闭</el-button></div>
    </el-dialog>
  </div>
</template>

<script>
import { getCpsDict } from '@/api/cps/dict'
import { listCpsOrders, getCpsOrder } from '@/api/cps/orders'

export default {
  name: 'CpsOrders',
  data() {
    return {
      loading: false,
      showSearch: true,
      total: 0,
      orderList: [],
      dict: { platforms: [], orderStatuses: [], attributionMethods: [] },
      queryParams: { pageNum: 1, pageSize: 10, platform: undefined, status: undefined, method: undefined },
      detailOpen: false,
      detailLoading: false,
      detailError: '',
      detail: null
    }
  },
  created() {
    this.loadDict()
    this.getList()
  },
  methods: {
    loadDict() {
      getCpsDict().then(res => { this.dict = Object.assign(this.dict, res.data || {}) }).catch(() => {})
    },
    getList() {
      this.loading = true
      const params = { ...this.queryParams }
      delete params.pageNum
      delete params.pageSize
      listCpsOrders({ ...params, pageNum: this.queryParams.pageNum, pageSize: this.queryParams.pageSize }).then(res => {
        this.orderList = res.rows || []
        this.total = Number(res.total || 0)
      }).catch(() => {
        // Keep the last successful rows visible on failure.
      }).finally(() => { this.loading = false })
    },
    handleQuery() { this.queryParams.pageNum = 1; this.getList() },
    resetQuery() { this.resetForm('queryForm'); this.queryParams.pageNum = 1; this.getList() },
    handleView(row) {
      this.detailOpen = true
      this.detailLoading = true
      this.detailError = ''
      this.detail = null
      getCpsOrder(row.id).then(res => {
        const data = res.data || {}
        if (data.order) this.detail = data
        else this.detailError = res.msg || '订单不存在'
      }).catch(error => {
        this.detailError = (error && error.message) || '订单详情加载失败'
      }).finally(() => { this.detailLoading = false })
    },
    platformLabel(value) { const item = this.dict.platforms.find(x => x.value === String(value || '').toLowerCase()); return item ? item.label : (value || '-') },
    platformType(value) { return ({ tbk: 'warning', jd: 'danger', pdd: 'success' })[String(value || '').toLowerCase()] || 'info' },
    methodLabel(value) { const item = this.dict.attributionMethods.find(x => x.value === value); return item ? item.label : (value || '-') },
    displayStatusType(value) { return ({ '待收货': 'info', '冻结中': 'warning', '已结算': 'success', '已退款': 'danger' })[value] || 'info' },
    money(value) { return value === null || value === undefined || value === '' ? '-' : '¥' + String(value) },
    formatTime(value) { return value ? this.parseTime(value) : '-' }
  }
}
</script>

<style scoped>
.gap-alert { margin: 8px 0; }
.detail-body { min-height: 140px; }
.detail-body h3 { margin: 18px 0 10px; }
</style>
